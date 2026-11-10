package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Consumer;

/** Render-thread coordinator for CPU mesh captures and the native GPU scene. */
public final class RtWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final long CAPACITY_LOG_INTERVAL_NANOS = 5_000_000_000L;
    public static final int FOLIAGE_NONE = 0;
    public static final int FOLIAGE_CARD = 1;
    public static final int FOLIAGE_CUBE = 2;

    private static final long CAPTURE_BUDGET_NANOS = 8_000_000L;
    private static final long TICK_ORIGIN_NS = System.nanoTime();
    /** Tick length of the section birth stamp and fade clock. */
    public static final int TICK_MS = 20;

    public static int nowTick() {
        return (int) (((System.nanoTime() - TICK_ORIGIN_NS) / (TICK_MS * 1_000_000L)) & 0xFFFFFFL);
    }

    public record FrameStats(int builtThisFrame, int totalSections, int pending, int instances,
                             double buildMs, double tlasMs, long vramBytes) {}

    /** Block-state query for foliage tags on captured cutout quads. */
    @FunctionalInterface
    public interface BlockLookup {
        int foliageKind(int x, int y, int z);
    }

    /** Last entity counters read by the autotest. */
    public static volatile String lastEntityTelemetry = "none";

    private final RtMemory mem;
    private final NativeSceneBridge scene;
    private final NativeSectionCapture captures;
    private long frameIndex;
    private long nearestBuiltSq = Long.MAX_VALUE;
    private BlockLookup blockLookup;
    private int[] quadFlagsScratch = new int[0];
    private long vegetationQuads;
    private long vegetationSections;
    private boolean atCapacity;
    private long lastCapacityLogNanos;

    public RtWorld(VkDevice device, SlangRuntime runtime) {
        mem = new RtMemory(device);
        try {
            scene = NativeSceneBridge.create(runtime);
        } catch (RuntimeException | Error failure) {
            try { mem.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        captures = new NativeSectionCapture(SectionGeometryRegistry.INSTANCE, scene, this::quadFlags);
    }

    public long tlasHandle() { return scene.tlas(); }
    public RtFrameBindings.SectionTable sectionTable() { return scene.sectionTable(); }
    public RtMemory memory() { return mem; }
    public int sectionCount() { return scene.sectionCount(); }
    public int entityPrimitiveCount() { return scene.activeThisFrame() ? scene.entityTriangles() : 0; }
    public long identicalSkipped() { return captures.identicalSkipped(); }
    public long structureVersion() { return captures.structureVersion(); }
    public long nearestBuiltSq() { return nearestBuiltSq; }
    public long vegetationQuads() { return vegetationQuads; }
    public long vegetationSections() { return vegetationSections; }

    public void forEachBuiltSection(Consumer<DecodedSection> consumer) {
        captures.forEach(consumer);
    }

    public void setBlockLookup(BlockLookup lookup) {
        blockLookup = lookup;
    }

    /** Records native scene builds into the current host command buffer. */
    public FrameStats update(VkCommandBuffer cmd, double camX, double camY, double camZ, int radiusXz, int radiusY) {
        long tUpdate = RtTimings.start();
        long frame = ++frameIndex;
        var entities = RtEntityGeometryManager.INSTANCE;
        entities.uploadToGpu(mem, camX, camY, camZ, cmd.address());
        scene.updateEntities(frame);
        long started = System.nanoTime();
        NativeSectionCapture.Result capture = captures.update(InstanceMath.sectionCoord(camX),
            InstanceMath.sectionCoord(camY), InstanceMath.sectionCoord(camZ), radiusXz, radiusY,
            RtConfig.BLAS_PER_FRAME, CAPTURE_BUDGET_NANOS);
        if (capture.rescanned()) RtTimings.countRescan();
        logCapacity(capture.omittedByCapacity());
        nearestBuiltSq = capture.nearestSq();
        long prepared = System.nanoTime();
        scene.prepareFrame(cmd.address(), frame, camX, camY, camZ);
        int instances = scene.instanceCount();
        lastEntityTelemetry = "[VK-ENTITY-AS] owner=native captured=" + entities.getCapturedEntityCount()
            + " triangles=" + scene.entityTriangles() + " instances=" + instances + " " + entities.uploadTelemetry();
        RtTimings.countBlas(scene.blasBuilds());
        RtTimings.stop(RtTimings.Phase.UPDATE_TOTAL, tUpdate);
        RtTimings.endFrame();
        return new FrameStats(scene.blasBuilds(), scene.sectionCount(), capture.pending(), instances,
            (prepared - started) / 1e6, (System.nanoTime() - prepared) / 1e6, mem.reservedBytes() + scene.residentBytes());
    }

    private void logCapacity(int omitted) {
        boolean limited = omitted > 0;
        if (!limited && !atCapacity) return;
        long now = System.nanoTime();
        if (limited != atCapacity || now - lastCapacityLogNanos >= CAPACITY_LOG_INTERVAL_NANOS) {
            LOGGER.info("[VKB-RT] scene_capacity sections_max={} omitted_candidates={} reserved_entity_slots={}",
                NativeSceneBridge.MAX_SECTIONS, omitted, RtConfig.MAX_INSTANCES - NativeSceneBridge.MAX_SECTIONS);
            lastCapacityLogNanos = now;
        }
        atCapacity = limited;
    }

    private int[] quadFlags(DecodedSection section) {
        if (blockLookup == null || section.quadsInPass(DecodedSection.PASS_CUTOUT) == 0) return null;
        int quads = section.totalQuads();
        if (quadFlagsScratch.length < quads) quadFlagsScratch = new int[quads];
        int tagged = tagVegetation(section, quadFlagsScratch, blockLookup);
        if (tagged == 0) return null;
        vegetationQuads += tagged;
        vegetationSections++;
        return quadFlagsScratch;
    }

    /**
     * Writes one flag word per quad. Sample a point just inside the face so a
     * cube's face on a cell boundary resolves to the cell that owns it.
     */
    static int tagVegetation(DecodedSection d, int[] flags, BlockLookup lookup) {
        Arrays.fill(flags, 0, d.totalQuads(), 0);
        if (lookup == null) return 0;
        int cutout = d.quadsInPass(DecodedSection.PASS_CUTOUT);
        int first = d.passQuadStart(DecodedSection.PASS_CUTOUT);
        int tagged = 0;
        int lastX = Integer.MIN_VALUE;
        int lastY = 0;
        int lastZ = 0;
        int kind = FOLIAGE_NONE;
        for (int q = first; q < first + cutout; q++) {
            int p = q * 12;
            int n = q * 3;
            float nx = d.normals[n];
            float ny = d.normals[n + 1];
            float nz = d.normals[n + 2];
            float cx = (d.positions[p] + d.positions[p + 3] + d.positions[p + 6] + d.positions[p + 9]) * 0.25f;
            float cy = (d.positions[p + 1] + d.positions[p + 4] + d.positions[p + 7] + d.positions[p + 10]) * 0.25f;
            float cz = (d.positions[p + 2] + d.positions[p + 5] + d.positions[p + 8] + d.positions[p + 11]) * 0.25f;
            int bx = (int) Math.floor(d.sectionX * 16 + cx - nx * 0.01f);
            int by = (int) Math.floor(d.sectionY * 16 + cy - ny * 0.01f);
            int bz = (int) Math.floor(d.sectionZ * 16 + cz - nz * 0.01f);
            if (bx != lastX || by != lastY || bz != lastZ) {
                kind = lookup.foliageKind(bx, by, bz);
                lastX = bx;
                lastY = by;
                lastZ = bz;
            }
            if (kind == FOLIAGE_NONE) continue;
            // A resource pack can draw a leaf block as a diagonal card.
            boolean cube = kind == FOLIAGE_CUBE && (Math.abs(nx) > 0.99f || Math.abs(ny) > 0.99f || Math.abs(nz) > 0.99f);
            flags[q] = BedraySceneGeometry.VERTEX_FLAG_VEGETATION
                | (cube ? BedraySceneGeometry.VERTEX_FLAG_LEAF_CUBE : 0);
            tagged++;
        }
        return tagged;
    }

    /** Called only when the host proves command completion. */
    public void releaseRecording(long commandBuffer) {
        scene.releaseRecording(commandBuffer);
        RtEntityGeometryManager.INSTANCE.releaseRecording(mem, commandBuffer);
    }

    /** Called only when the host abandons an unsubmitted command. */
    public void discardRecording(long commandBuffer) {
        scene.discardRecording(commandBuffer);
        RtEntityGeometryManager.INSTANCE.releaseRecording(mem, commandBuffer);
    }

    /** The caller has drained host submissions and excluded future resubmission. */
    public void releaseComputeRecordingsAfterHostDrain() {
        scene.releaseAllFrames();
    }

    /** Close the scene before the shared native runtime. */
    public void closeComputeAfterHostDrain() {
        scene.close();
    }

    public void destroy() {
        scene.close();
        captures.clear();
        RtEntityGeometryManager.INSTANCE.closeAll(mem);
        mem.close();
    }
}
