package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * GPU side of the world ray tracing (step R1.2): own copies of the section geometry decoded by
 * {@link SectionGeometryRegistry}, one BLAS per section, and a TLAS rebuilt every frame.
 *
 * <p>Everything is recorded into the command buffer passed to {@link #update}; nothing is submitted here. Resources of
 * removed/replaced sections are released {@link #DEFER_FRAMES} frames later (the engine keeps up to 2 submissions in
 * flight). Render thread only.
 *
 * <p>Per section a block of the geometry arena holds {@code [positions float3 x 4Q][uv float2 x 4Q]}; the BLAS has one
 * triangle geometry per non-empty pass (SOLID: OPAQUE, CUTOUT/TRANSLUCENT: non-opaque) reading a slice of the
 * positions with the shared quad index buffer. The shader finds the section through
 * {@code instanceCustomIndex = slot} in the section table (48 bytes per slot).
 */
public final class RtWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final int MAX_QUADS_PER_SECTION = 131072;
    static final long STAGING_SEGMENT_BYTES = 16L << 20;
    static final int STAGING_SEGMENTS = 3;
    static final long SCRATCH_BYTES = 64L << 20;
    static final int TABLE_ENTRY_BYTES = 64;
    /** Bytes of geometry per quad: positions 48, uvs 32, colours 16, light 16. */
    static final long GEOM_BYTES_PER_QUAD = 112L;
    static final int MAX_SLOTS = 65536;
    static final int INSTANCE_BYTES = RtInstanceWriter.SIZEOF;
    static final int DEFER_FRAMES = 3;
    static final long BLOCK_ALIGN = 256L;
    private static final double CPU_BUDGET_MS = 8.0;

    private static final long TICK_ORIGIN_NS = System.nanoTime();
    /** Tick length of the section birth stamp and of the fade clock (ms): 24 bits = 93 hours before the stamp wraps. */
    public static final int TICK_MS = 20;

    /** Clock of the section fade-in: 20 ms ticks since start, 24 bits (rt_common.glsl sectionFade compares the same units). */
    public static int nowTick() {
        return (int) (((System.nanoTime() - TICK_ORIGIN_NS) / (TICK_MS * 1_000_000L)) & 0xFFFFFFL);
    }

    /** Per-frame result for the log. */
    public record FrameStats(int builtThisFrame, int totalSections, int pending, int instances,
                             double buildMs, double tlasMs, long vramBytes, long failedSections) {
    }

    private static final class SectionGpu {
        final long key;
        final DecodedSection src;
        final RtArena.Block geom;
        final RtArena.Block blasBlock;
        final long blasHandle;
        final long blasAddress;
        final int slot;
        /** {@link #nowTick()} when this section first appeared (kept over rebuilds): drives the fade-in from the haze. */
        int birthTick;

        SectionGpu(long key, DecodedSection src, RtArena.Block geom, RtArena.Block blasBlock, long blasHandle,
                   long blasAddress, int slot) {
            this.key = key;
            this.src = src;
            this.geom = geom;
            this.blasBlock = blasBlock;
            this.blasHandle = blasHandle;
            this.blasAddress = blasAddress;
            this.slot = slot;
        }
    }

    private record Deferred(long frame, SectionGpu section) {
    }

    private final VkDevice device;
    private final RtMemory mem;
    private final RtArena geomArena;
    private final RtArena blasArena;
    private final RtMemory.Buf indexBuf;
    private final RtMemory.Buf staging;
    private final RtMemory.Buf scratch;
    private final RtMemory.Buf table;
    private final RtMemory.Buf instances;
    private final RtMemory.Buf tlasBuf;
    private final long tlasHandle;
    private final long tlasAddress;
    private final long scratchBase;
    private final RtEntityBlas entityBlas;

    private final Map<Long, SectionGpu> gpu = new HashMap<>();
    /** Incremented whenever a section enters, leaves or is replaced in {@link #gpu} (emitter list rebuilds key off it). */
    private long structureVersion;
    private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int nextSlot = 0;
    private List<DecodedSection> pending = new ArrayList<>();
    private int pendingIdx = 0;
    private long lastVersion = -1;
    private int lastCamSx = Integer.MIN_VALUE;
    private int radiusXz = RtConfig.RADIUS_XZ;
    private int radiusY = RtConfig.RADIUS_Y;
    /** Smallest squared section distance (camera section to built section) of the BLAS built this frame, MAX_VALUE if none. */
    private long nearestBuiltSq = Long.MAX_VALUE;
    private int lastCamSy = Integer.MIN_VALUE;
    private int lastCamSz = Integer.MIN_VALUE;
    private long frameIndex = 0;
    private long stagingCursor;
    private long scratchCursor;
    private boolean indexUploaded = false;
    private long failedSections = 0;
    private boolean vramBudgetLogged = false;
    private final float[] tmpTransform = new float[12];

    public RtWorld(VkDevice device) {
        this.device = device;
        this.mem = new RtMemory(device);
        this.geomArena = new RtArena(mem, "geom",
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | RtMemory.USAGE_AS_INPUT,
            64L << 20);
        this.blasArena = new RtArena(mem, "blas",
            KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
            64L << 20);
        this.indexBuf = mem.createBuffer("quad_index", 6L * 4L * MAX_QUADS_PER_SECTION,
            VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | RtMemory.USAGE_AS_INPUT, false);
        this.staging = mem.createBuffer("staging", STAGING_SEGMENT_BYTES * STAGING_SEGMENTS,
            VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        this.table = mem.createBuffer("section_table", (long) MAX_SLOTS * TABLE_ENTRY_BYTES,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        this.instances = mem.createBuffer("instances", (long) INSTANCE_BYTES * RtConfig.MAX_INSTANCES * STAGING_SEGMENTS,
            RtMemory.USAGE_AS_INPUT, true);
        this.entityBlas = new RtEntityBlas(device, mem);

        long tlasSize;
        long tlasScratch;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            g.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR);
            g.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            VkAccelerationStructureBuildGeometryInfoKHR info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1).pGeometries(g);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info,
                new int[] {RtConfig.MAX_INSTANCES}, sizes);
            tlasSize = sizes.accelerationStructureSize();
            tlasScratch = sizes.buildScratchSize();
        }
        this.tlasBuf = mem.createBuffer("tlas", tlasSize,
            KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        long scratchBytes = Math.max(SCRATCH_BYTES, FreeListAllocator.roundUp(tlasScratch, mem.scratchAlignment) + mem.scratchAlignment);
        this.scratch = mem.createBuffer("scratch", scratchBytes,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        this.scratchBase = FreeListAllocator.roundUp(scratch.address, mem.scratchAlignment) - scratch.address;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(tlasBuf.handle).offset(0).size(tlasSize)
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, ci, null, p), "create_tlas");
            this.tlasHandle = p.get(0);
            this.tlasAddress = asAddress(stack, tlasHandle);
        }
        LOGGER.info("[VKB-RT] world_init tlas_bytes={} tlas_scratch_bytes={} scratch_bytes={} scratch_align={} max_instances={} radius_xz={} radius_y={}",
            tlasSize, tlasScratch, scratchBytes, mem.scratchAlignment, RtConfig.MAX_INSTANCES, "dynamic", "dynamic");
    }

    private long asAddress(MemoryStack stack, long handle) {
        VkAccelerationStructureDeviceAddressInfoKHR info = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
            .sType$Default().accelerationStructure(handle);
        long ta = RtTimings.start();
        long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device, info);
        RtTimings.stop(RtTimings.Phase.VK_ADDR, ta);
        if (address == 0L) {
            throw new RtFailure("as_device_address", "address=0");
        }
        return address;
    }

    public long tlasHandle() {
        return tlasHandle;
    }

    public RtMemory.Buf sectionTable() {
        return table;
    }

    public RtMemory memory() {
        return mem;
    }

    public int sectionCount() {
        return gpu.size();
    }

    // ------------------------------------------------------------------------------------------------------ frame

    /** Records uploads, BLAS builds and the TLAS build for this frame. */
    public FrameStats update(VkCommandBuffer cmd, double camX, double camY, double camZ) {
        return update(cmd, camX, camY, camZ, RtConfig.RADIUS_XZ, RtConfig.RADIUS_Y);
    }

    /** As above with the RT radius of this frame in sections (RtConfig.radiusXz / radiusY); a change triggers a rescan. */
    public FrameStats update(VkCommandBuffer cmd, double camX, double camY, double camZ, int radiusXz, int radiusY) {
        boolean radiusChanged = radiusXz != this.radiusXz || radiusY != this.radiusY;
        this.radiusXz = radiusXz;
        this.radiusY = radiusY;
        long tUpdate = RtTimings.start();
        frameIndex++;
        releaseDeferred(false);
        stagingCursor = (frameIndex % STAGING_SEGMENTS) * STAGING_SEGMENT_BYTES;
        scratchCursor = scratchBase;

        // Build dynamic entity BLAS at the beginning of the frame update
        RtEntityGeometryManager.INSTANCE.uploadToGpu(mem, camX, camY, camZ);
        entityBlas.build(cmd, RtEntityGeometryManager.INSTANCE, scratch, scratchCursor);

        long t0 = System.nanoTime();
        int camSx = InstanceMath.sectionCoord(camX);
        int camSy = InstanceMath.sectionCoord(camY);
        int camSz = InstanceMath.sectionCoord(camZ);
        SectionGeometryRegistry reg = SectionGeometryRegistry.INSTANCE;
        long version = reg.version();
        if (radiusChanged || version != lastVersion || camSx != lastCamSx || camSy != lastCamSy || camSz != lastCamSz) {
            lastVersion = version;
            lastCamSx = camSx;
            lastCamSy = camSy;
            lastCamSz = camSz;
            long tr = RtTimings.start();
            rescan(reg, camSx, camSy, camSz);
            RtTimings.stop(RtTimings.Phase.RESCAN, tr);
            RtTimings.countRescan();
        }

        nearestBuiltSq = Long.MAX_VALUE;
        boolean copied = uploadIndexBufferIfNeeded(cmd);
        int built = buildPending(cmd, reg, copied);
        long t1 = System.nanoTime();
        int instanceCount = buildTlas(cmd, camX, camY, camZ);
        long t2 = System.nanoTime();
        RtTimings.countBlas(built);
        RtTimings.stop(RtTimings.Phase.UPDATE_TOTAL, tUpdate);
        RtTimings.endFrame();

        String entityTelemetry = RtEntityBlas.formatTelemetry(
            RtEntityGeometryManager.INSTANCE.getCapturedEntityCount(),
            entityBlas.getBlasAddress() != 0L,
            entityBlas.getPrimitiveCount(),
            instanceCount,
            entityBlas.getBlasAddress()
        ) + " entityInstanceWritten=" + lastEntityInstanceWritten + " " + RtEntityGeometryManager.INSTANCE.uploadTelemetry();
        lastEntityTelemetry = entityTelemetry;
        // logged on change only (it was an INFO line every frame)
        int capturedNow = RtEntityGeometryManager.INSTANCE.getCapturedEntityCount();
        if (capturedNow != lastLoggedCaptured || (entityBlas.getBlasAddress() != 0L) != lastLoggedBlas) {
            lastLoggedCaptured = capturedNow;
            lastLoggedBlas = entityBlas.getBlasAddress() != 0L;
            LOGGER.info(entityTelemetry);
        }

        return new FrameStats(built, gpu.size(), pending.size() - pendingIdx, instanceCount,
            (t1 - t0) / 1.0e6, (t2 - t1) / 1.0e6, mem.reservedBytes(), failedSections);
    }

    /** Last [VK-ENTITY-AS] line (production counters of this frame), read by the autotest. */
    public static volatile String lastEntityTelemetry = "none";
    private static volatile boolean lastEntityInstanceWritten;
    private int lastLoggedCaptured = -1;
    private boolean lastLoggedBlas;

    /** Re-meshed sections whose geometry, UVs, colours and light were identical (BLAS kept). */
    private long identicalSkipped;

    public long identicalSkipped() {
        return identicalSkipped;
    }

    static boolean sameGeometry(DecodedSection a, DecodedSection b) {
        if (a.totalQuads() != b.totalQuads()) {
            return false;
        }
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            if (a.quadsInPass(p) != b.quadsInPass(p)) {
                return false;
            }
        }
        return java.util.Arrays.equals(a.positions, b.positions) && java.util.Arrays.equals(a.uvs, b.uvs)
            && java.util.Arrays.equals(a.colors, b.colors) && java.util.Arrays.equals(a.light, b.light);
    }

    /** Version of the set of sections that have a BLAS (changes on build, replace and evict). */
    public long structureVersion() {
        return structureVersion;
    }

    /** Visits the decoded geometry of every section that currently has a BLAS (render thread). */
    public void forEachBuiltSection(java.util.function.Consumer<DecodedSection> consumer) {
        for (SectionGpu g : gpu.values()) {
            consumer.accept(g.src);
        }
    }

    /** Squared section distance of the nearest section whose BLAS was built by the last update, Long.MAX_VALUE if none. */
    public long nearestBuiltSq() {
        return nearestBuiltSq;
    }

    /** Pure recomputation of what to evict and what to build; no GPU work. */
    private void rescan(SectionGeometryRegistry reg, int camSx, int camSy, int camSz) {
        int evictXz = radiusXz + RtConfig.EVICT_MARGIN;
        int evictY = radiusY + RtConfig.EVICT_MARGIN;
        for (Iterator<SectionGpu> it = gpu.values().iterator(); it.hasNext(); ) {
            SectionGpu g = it.next();
            DecodedSection current = reg.get(g.key);
            boolean gone = current == null;
            boolean far = !gone && !InstanceMath.inRadius(g.src.sectionX, g.src.sectionY, g.src.sectionZ,
                camSx, camSy, camSz, evictXz, evictY);
            if (gone || far) {
                it.remove();
                structureVersion++;
                deferred.add(new Deferred(frameIndex, g));
            }
        }
        ArrayList<DecodedSection> list = new ArrayList<>();
        reg.forEachSection(d -> {
            if (InstanceMath.inRadius(d.sectionX, d.sectionY, d.sectionZ, camSx, camSy, camSz,
                radiusXz, radiusY)) {
                SectionGpu g = gpu.get(d.key);
                if (g == null || g.src != d) {
                    list.add(d);
                }
            }
        });
        // nearest first, vertical distance weighted x2: with a limited memory budget the far horizon is kept before deep caves
        list.sort(Comparator.comparingLong(d -> {
            long dx = d.sectionX - camSx;
            long dy = (long) (d.sectionY - camSy) * 2L;
            long dz = d.sectionZ - camSz;
            return dx * dx + dy * dy + dz * dz;
        }));
        pending = list;
        pendingIdx = 0;
    }

    private long stage(long bytes) {
        long start = FreeListAllocator.roundUp(stagingCursor, 16);
        long segmentEnd = (frameIndex % STAGING_SEGMENTS + 1) * STAGING_SEGMENT_BYTES;
        if (start + bytes > segmentEnd) {
            return -1;
        }
        stagingCursor = start + bytes;
        return start;
    }

    /** The shared quad index template (0,1,2, 2,3,0) + 4q, uploaded once through the staging buffer. */
    private boolean uploadIndexBufferIfNeeded(VkCommandBuffer cmd) {
        if (indexUploaded) {
            return false;
        }
        long bytes = 6L * 4L * MAX_QUADS_PER_SECTION;
        long off = stage(bytes);
        if (off < 0) {
            throw new RtFailure("index_upload", "staging_segment_too_small");
        }
        java.nio.IntBuffer ib = MemoryUtil.memIntBuffer(staging.mapped + off, 6 * MAX_QUADS_PER_SECTION);
        for (int q = 0; q < MAX_QUADS_PER_SECTION; q++) {
            int v = q * 4;
            ib.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
        }
        copyBuffer(cmd, staging.handle, off, indexBuf.handle, 0, bytes);
        indexUploaded = true;
        return true;
    }

    private static void copyBuffer(VkCommandBuffer cmd, long src, long srcOffset, long dst, long dstOffset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
            VK10.vkCmdCopyBuffer(cmd, src, dst, region);
        }
    }

    /** Returned by tryBuildSection for a section that can never be built: the caller skips it and continues. */
    private static final BuildItem SKIP = new BuildItem();

    private int allocSlot() {
        Integer reused = freeSlots.pollFirst();
        if (reused != null) {
            return reused;
        }
        if (nextSlot >= MAX_SLOTS) {
            throw new RtFailure("section_slots", "exhausted max=" + MAX_SLOTS);
        }
        return nextSlot++;
    }

    /** Heap-allocated per-section build data, freed after the build call was recorded. */
    private static final class BuildItem {
        VkAccelerationStructureGeometryKHR.Buffer geometries;
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges;
        long dstHandle;
        long scratchAddress;

        void free() {
            if (geometries != null) {
                geometries.free();
            }
            if (ranges != null) {
                ranges.free();
            }
        }
    }

    private int buildPending(VkCommandBuffer cmd, SectionGeometryRegistry reg, boolean copiedSomething) {
        ArrayList<BuildItem> batch = new ArrayList<>();
        long deadline = System.nanoTime() + (long) (CPU_BUDGET_MS * 1.0e6);
        try {
            while (pendingIdx < pending.size() && batch.size() < RtConfig.BLAS_PER_FRAME) {
                if (!batch.isEmpty() && System.nanoTime() > deadline) {
                    break;
                }
                DecodedSection d = pending.get(pendingIdx);
                if (reg.get(d.key) != d) {
                    pendingIdx++; // replaced or removed after the scan: a newer version is queued by the next rescan
                    continue;
                }
                SectionGpu prev = gpu.get(d.key);
                if (prev != null && sameGeometry(prev.src, d)) {
                    // Sodium re-meshed the section with identical output (block/light updates elsewhere in it): keep the BLAS,
                    // swap the source only. No rebuild, and no reason to reset the temporal accumulation.
                    gpu.put(d.key, new SectionGpu(d.key, d, prev.geom, prev.blasBlock, prev.blasHandle, prev.blasAddress, prev.slot));
                    identicalSkipped++;
                    pendingIdx++;
                    continue;
                }
                int quads = d.totalQuads();
                if (quads > MAX_QUADS_PER_SECTION) {
                    failedSections++;
                    LOGGER.error("[VKB-RT] FAILED stage=section_too_large result=quads={} max={} section=({},{},{}) (section skipped)",
                        quads, MAX_QUADS_PER_SECTION, d.sectionX, d.sectionY, d.sectionZ);
                    pendingIdx++;
                    continue;
                }
                if (mem.reservedBytes() > RtConfig.vramBudgetBytes()) {
                    if (!vramBudgetLogged) {
                        vramBudgetLogged = true;
                        LOGGER.warn("[VKB-RT] vram_budget_reached reserved_mb={} budget_mb={} (no more BLAS are created; raise -Dvkbridge.rt.vramBudgetMb or lower radiusSections)",
                            mem.reservedBytes() >> 20, RtConfig.vramBudgetBytes() >> 20);
                    }
                    break;
                }
                BuildItem item = tryBuildSection(cmd, d, quads);
                if (item == null) {
                    break; // staging segment or scratch is full: continue next frame
                }
                pendingIdx++;
                if (item != SKIP) {
                    batch.add(item);
                    nearestBuiltSq = Math.min(nearestBuiltSq,
                        InstanceMath.distanceSq(d.sectionX, d.sectionY, d.sectionZ, lastCamSx, lastCamSy, lastCamSz));
                }
            }
            if (batch.isEmpty()) {
                if (copiedSomething) {
                    barrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                }
                return 0;
            }
            // Copies -> BLAS build input reads.
            barrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);
            long tb = RtTimings.start();
            recordBlasBuilds(cmd, batch);
            RtTimings.stop(RtTimings.Phase.RECORD_BLAS, tb);
            // BLAS writes -> TLAS build reads (and scratch reuse).
            barrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                    | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            return batch.size();
        } finally {
            for (BuildItem b : batch) {
                b.free();
            }
        }
    }

    /**
     * Allocates GPU memory, stages the section data, creates the BLAS object and fills the section table.
     * Returns the build item, or null when staging/scratch capacity of this frame is exhausted (nothing was allocated).
     */
    private BuildItem tryBuildSection(VkCommandBuffer cmd, DecodedSection d, int quads) {
        // Non-empty passes in order SOLID, CUTOUT, TRANSLUCENT.
        int[] passStart = new int[DecodedSection.PASS_COUNT];
        int[] passCount = new int[DecodedSection.PASS_COUNT];
        int[] passId = new int[DecodedSection.PASS_COUNT];
        int geomCount = 0;
        for (int p = 0; p < DecodedSection.PASS_COUNT; p++) {
            int n = d.quadsInPass(p);
            if (n > 0) {
                passStart[geomCount] = d.passQuadStart(p);
                passCount[geomCount] = n;
                passId[geomCount] = p;
                geomCount++;
            }
        }
        if (geomCount == 0) {
            return null;
        }

        long geomBytes = GEOM_BYTES_PER_QUAD * quads; // [positions][uvs][colours][light]
        long blasSize;
        long buildScratch;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(geomCount, stack);
            int[] primCounts = new int[geomCount];
            for (int i = 0; i < geomCount; i++) {
                fillTriangleGeometry(g.get(i), passId[i], passCount[i], 0L, 0L);
                primCounts[i] = passCount[i] * 2;
            }
            VkAccelerationStructureBuildGeometryInfoKHR info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(geomCount).pGeometries(g);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            long ts = RtTimings.start();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, primCounts, sizes);
            RtTimings.stop(RtTimings.Phase.VK_SIZES, ts);
            blasSize = sizes.accelerationStructureSize();
            buildScratch = sizes.buildScratchSize();
        }

        long scratchNeed = FreeListAllocator.roundUp(buildScratch, mem.scratchAlignment);
        if (scratchNeed > scratch.size - scratchBase) {
            failedSections++;
            LOGGER.error("[VKB-RT] FAILED stage=section_scratch result=need={} capacity={} section=({},{},{}) (section skipped)",
                scratchNeed, scratch.size - scratchBase, d.sectionX, d.sectionY, d.sectionZ);
            return SKIP;
        }
        if (scratchCursor + scratchNeed > scratch.size) {
            return null;
        }
        long stagingOffset = stage(geomBytes);
        if (stagingOffset < 0) {
            return null;
        }

        RtArena.Block geomBlock = geomArena.alloc(geomBytes, BLOCK_ALIGN);
        RtArena.Block blasBlock = blasArena.alloc(blasSize, BLOCK_ALIGN);
        int slot = allocSlot();
        long blasHandle = 0L;
        BuildItem item = new BuildItem();
        try {
            // Stage [positions][uvs] and copy to the arena block.
            long tc = RtTimings.start();
            FloatBuffer fb = MemoryUtil.memFloatBuffer(staging.mapped + stagingOffset, quads * 20);
            fb.put(d.positions, 0, quads * 12);
            fb.put(d.uvs, 0, quads * 8);
            java.nio.IntBuffer ib = MemoryUtil.memIntBuffer(staging.mapped + stagingOffset + 80L * quads, quads * 8);
            ib.put(d.colors, 0, quads * 4);
            ib.put(d.light, 0, quads * 4);
            copyBuffer(cmd, staging.handle, stagingOffset, geomBlock.buffer(), geomBlock.offset, geomBytes);
            RtTimings.stop(RtTimings.Phase.STAGE_COPY, tc);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                    .buffer(blasBlock.buffer()).offset(blasBlock.offset).size(blasSize)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                LongBuffer p = stack.mallocLong(1);
                long tk = RtTimings.start();
                RtFailure.check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device, ci, null, p),
                    "create_blas");
                RtTimings.stop(RtTimings.Phase.VK_CREATE, tk);
                blasHandle = p.get(0);
                long blasAddress = asAddress(stack, blasHandle);

                item.geometries = VkAccelerationStructureGeometryKHR.calloc(geomCount);
                item.ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(geomCount);
                for (int i = 0; i < geomCount; i++) {
                    fillTriangleGeometry(item.geometries.get(i), passId[i], passCount[i],
                        geomBlock.address() + (long) passStart[i] * 48L, indexBuf.address);
                    item.ranges.get(i).primitiveCount(passCount[i] * 2).primitiveOffset(0).firstVertex(0).transformOffset(0);
                }
                item.dstHandle = blasHandle;
                item.scratchAddress = scratch.address + scratchCursor;
                scratchCursor += scratchNeed;

                int passBits = 0;
                for (int i = 0; i < geomCount; i++) {
                    passBits |= passId[i] << (2 * i);
                }
                SectionGpu previous = gpu.get(d.key);
                int birth = previous != null ? previous.birthTick : nowTick();   // a rebuild (block change) does not fade again
                writeTableEntry(slot, geomBlock.address(), quads, passStart, geomCount, passBits | (birth << 8),
                    d.sectionX, d.sectionY, d.sectionZ);
                SectionGpu entry = new SectionGpu(d.key, d, geomBlock, blasBlock, blasHandle, blasAddress, slot);
                entry.birthTick = birth;
                SectionGpu old = gpu.put(d.key, entry);
                structureVersion++;
                if (old != null) {
                    deferred.add(new Deferred(frameIndex, old));
                }
            }
            return item;
        } catch (RuntimeException e) {
            item.free();
            if (blasHandle != 0L) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, blasHandle, null);
            }
            blasArena.free(blasBlock);
            geomArena.free(geomBlock);
            freeSlots.push(slot);
            throw e;
        }
    }

    private static void fillTriangleGeometry(VkAccelerationStructureGeometryKHR geom, int pass, int quadCount,
                                             long vertexAddress, long indexAddress) {
        geom.sType$Default()
            .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
            .flags(pass == DecodedSection.PASS_SOLID ? KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
        geom.geometry().triangles().sType$Default()
            .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
            .vertexStride(12)
            .maxVertex(quadCount * 4 - 1)
            .indexType(VK10.VK_INDEX_TYPE_UINT32);
        geom.geometry().triangles().vertexData().deviceAddress(vertexAddress);
        geom.geometry().triangles().indexData().deviceAddress(indexAddress);
    }

    /**
     * Section table entry (std430, 64 B): posAddr, uvAddr, colAddr, lightAddr (4 x uvec2), quadStart[3] + geometry
     * count (uvec4), origin xyz + pass bits (ivec4). The block layout is {@code [pos 48Q][uv 32Q][colour 16Q][light 16Q]}.
     * Pass bits: 2 bits per geometry, the pass id (0 solid, 1 cutout, 2 translucent) of geometry g at bit 2g; bits 8..31 the birth
     * tick of the section ({@link #nowTick()}), read by rt_common.glsl sectionFade.
     */
    private void writeTableEntry(int slot, long blockAddress, int quads, int[] quadStart, int geomCount, int passBits,
                                 int sx, int sy, int sz) {
        long p = table.mapped + (long) slot * TABLE_ENTRY_BYTES;
        MemoryUtil.memPutLong(p, blockAddress);
        MemoryUtil.memPutLong(p + 8, blockAddress + 48L * quads);
        MemoryUtil.memPutLong(p + 16, blockAddress + 80L * quads);
        MemoryUtil.memPutLong(p + 24, blockAddress + 96L * quads);
        for (int i = 0; i < 3; i++) {
            MemoryUtil.memPutInt(p + 32 + 4L * i, i < geomCount ? quadStart[i] : 0);
        }
        MemoryUtil.memPutInt(p + 44, geomCount);
        MemoryUtil.memPutInt(p + 48, sx * 16);
        MemoryUtil.memPutInt(p + 52, sy * 16);
        MemoryUtil.memPutInt(p + 56, sz * 16);
        MemoryUtil.memPutInt(p + 60, passBits);
    }

    private void recordBlasBuilds(VkCommandBuffer cmd, List<BuildItem> batch) {
        ArrayList<BuildItem> real = new ArrayList<>();
        for (BuildItem b : batch) {
            if (b.geometries != null) {
                real.add(b);
            }
        }
        if (real.isEmpty()) {
            return;
        }
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(real.size());
        PointerBuffer ranges = MemoryUtil.memAllocPointer(real.size());
        try {
            for (int i = 0; i < real.size(); i++) {
                BuildItem b = real.get(i);
                infos.get(i).sType$Default()
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .dstAccelerationStructure(b.dstHandle)
                    .geometryCount(b.geometries.remaining())
                    .pGeometries(b.geometries);
                infos.get(i).scratchData().deviceAddress(b.scratchAddress);
                ranges.put(i, b.ranges.address());
            }
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, infos, ranges);
        } finally {
            infos.free();
            MemoryUtil.memFree(ranges);
        }
    }

    private int buildTlas(VkCommandBuffer cmd, double camX, double camY, double camZ) {
        int n = Math.min(gpu.size(), RtConfig.MAX_INSTANCES);
        boolean hasEntityBlas = entityBlas.getBlasAddress() != 0L && entityBlas.getPrimitiveCount() > 0;
        if (n == 0 && !hasEntityBlas) {
            return 0;
        }
        long segmentOffset = (frameIndex % STAGING_SEGMENTS) * (long) RtConfig.MAX_INSTANCES * INSTANCE_BYTES;
        long tp = RtTimings.start();
        int i = 0;
        for (SectionGpu g : gpu.values()) {
            if (i >= n) {
                break;
            }
            InstanceMath.sectionTransform(g.src.sectionX, g.src.sectionY, g.src.sectionZ, camX, camY, camZ, tmpTransform);
            RtInstanceWriter.write(instances.mapped + segmentOffset + (long) i * INSTANCE_BYTES,
                g.slot, g.blasAddress, tmpTransform);
            i++;
        }
        if (hasEntityBlas && i < RtConfig.MAX_INSTANCES) {
            float[] entityTransform = new float[] {
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f
            };
            int customIndex = io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances.encodeEntityCustomIndex(0);
            RtInstanceWriter.write(instances.mapped + segmentOffset + (long) i * INSTANCE_BYTES,
                customIndex, 0x02, entityBlas.getBlasAddress(), entityTransform);
            i++;
            lastEntityInstanceWritten = true;
        } else {
            lastEntityInstanceWritten = false;
        }
        RtTimings.stop(RtTimings.Phase.TLAS_PACK, tp);
        long tt = RtTimings.start();
        if (gpu.size() > RtConfig.MAX_INSTANCES) {
            LOGGER.warn("[VKB-RT] tlas_truncated sections={} max_instances={}", gpu.size(), RtConfig.MAX_INSTANCES);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            g.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR);
            g.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
            g.get(0).geometry().instances().data().deviceAddress(instances.address + segmentOffset);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default()
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(tlasHandle)
                .geometryCount(1).pGeometries(g);
            info.get(0).scratchData().deviceAddress(scratch.address + scratchBase);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(i).primitiveOffset(0).firstVertex(0).transformOffset(0);
            PointerBuffer pp = stack.pointers(range.address());
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, info, pp);
        }
        RtTimings.stop(RtTimings.Phase.TLAS_RECORD, tt);
        // TLAS (and BLAS/transfer writes) -> ray queries in the compute shader.
        barrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                | VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
            VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);
        return n;
    }

    private static void barrier(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
            b.get(0).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            VK10.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }

    // --------------------------------------------------------------------------------------------- release

    private void releaseDeferred(boolean all) {
        while (!deferred.isEmpty() && (all || frameIndex - deferred.peekFirst().frame() >= DEFER_FRAMES)) {
            SectionGpu g = deferred.pollFirst().section();
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, g.blasHandle, null);
            blasArena.free(g.blasBlock);
            geomArena.free(g.geom);
            freeSlots.push(g.slot);
        }
    }

    /** Waits for the GPU and releases everything. Safe to call once; the object is unusable afterwards. */
    public void destroy() {
        VK10.vkDeviceWaitIdle(device);
        for (SectionGpu g : gpu.values()) {
            deferred.add(new Deferred(frameIndex, g));
        }
        gpu.clear();
        releaseDeferred(true);
        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
        mem.destroy(tlasBuf);
        mem.destroy(scratch);
        mem.destroy(instances);
        mem.destroy(table);
        mem.destroy(staging);
        mem.destroy(indexBuf);
        geomArena.destroy();
        blasArena.destroy();
        entityBlas.close();
        RtEntityGeometryManager.INSTANCE.closeAll(mem);
        mem.close();
    }

    public RtEntityBlas entityBlas() {
        return entityBlas;
    }
}
