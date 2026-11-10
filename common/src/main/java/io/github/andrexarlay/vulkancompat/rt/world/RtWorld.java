package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.function.Consumer;

/** Adapts game captures and host completion to the C++ acceleration scene. Render thread only. */
public final class RtWorld {
    private static final long CAPTURE_BUDGET_NS = 8_000_000L;
    /**
     * Bit 24 of a vertex light word: the cutout quad belongs to a plant or a leaf (GLSL {@code VERT_FLAG_VEGETATION}).
     * Byte 3 of a Sodium light word holds the region-local section index, which the decoder drops, so the byte is free.
     */
    public static final int VEGETATION_BIT = 1 << 24;
    /**
     * Bit 25: the vegetation quad is a face of a full leaf cube, not a thin card (GLSL {@code VERT_FLAG_LEAF_CUBE}). Only a
     * cube needs the one-block shadow-ray step along its normal; a card, a vine or a pane is cleared by 1 cm.
     */
    public static final int LEAF_CUBE_BIT = 1 << 25;
    /** {@link BlockLookup} answers: not vegetation, a thin plant card, or a face of a full leaf cube. */
    public static final int FOLIAGE_NONE = 0;
    public static final int FOLIAGE_CARD = 1;
    public static final int FOLIAGE_CUBE = 2;
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

    /**
     * Block-state query that tags the cutout quads with {@link #VEGETATION_BIT} (and {@link #LEAF_CUBE_BIT} for a full
     * cube) while a section is staged. Called on the render thread, once per cutout quad of a section build. Returns one of
     * {@link #FOLIAGE_NONE}, {@link #FOLIAGE_CARD}, {@link #FOLIAGE_CUBE}.
     */
    @FunctionalInterface
    public interface BlockLookup {
        int foliageKind(int x, int y, int z);
    }

    private final RtMemory mem;
    private NativeSceneBridge nativeScene;
    private NativeSectionCapture nativeCapture;
    private boolean nativeSceneDiscarded;
    private boolean nativeSceneFrameActive;
    private long nearestBuiltSq = Long.MAX_VALUE;
    private long frameIndex;
    private BlockLookup blockLookup;
    private int[] taggedLightScratch = new int[0];
    private long vegetationQuads;
    private long vegetationSections;

    public RtWorld(VkDevice device, SlangRuntime runtime) {
        mem = new RtMemory(device);
        try {
            nativeScene = NativeSceneBridge.create(runtime);
            nativeCapture = new NativeSectionCapture(SectionGeometryRegistry.INSTANCE, nativeScene, this::quadFlags);
        } catch (RuntimeException | Error failure) {
            if (nativeScene != null) {
                try { nativeScene.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            try { mem.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Borrowed TLAS of this frame. Zero until the native scene publishes geometry. */
    public long tlasHandle() {
        return nativeSceneFrameActive ? nativeScene.tlas() : 0L;
    }

    /** Borrowed table of the generation named by {@link #tlasHandle()}. */
    public RtMemory.Buf sectionTable() {
        return nativeSceneFrameActive ? nativeScene.sectionTable() : null;
    }

    public RtMemory memory() {
        return mem;
    }

    RtPathTraceSlangPass.Entities entityBindings() { return nativeScene.entityBindings(); }

    public int sectionCount() {
        return nativeSceneFrameActive ? nativeScene.sectionCount() : 0;
    }

    public FrameStats update(VkCommandBuffer cmd, double camX, double camY, double camZ) {
        return update(cmd, camX, camY, camZ, RtConfig.RADIUS_XZ, RtConfig.RADIUS_Y);
    }

    public FrameStats update(VkCommandBuffer cmd, double camX, double camY, double camZ, int radiusXz, int radiusY) {
        long tUpdate = RtTimings.start();
        frameIndex++;
        nativeSceneFrameActive = false;
        nativeScene.updateEntities(frameIndex, camX, camY, camZ);
        long started = System.nanoTime();
        NativeSectionCapture.Result captures = nativeCapture.update(InstanceMath.sectionCoord(camX),
            InstanceMath.sectionCoord(camY), InstanceMath.sectionCoord(camZ), radiusXz, radiusY,
            RtConfig.BLAS_PER_FRAME, CAPTURE_BUDGET_NS);
        if (captures.rescanned()) RtTimings.countRescan();
        nearestBuiltSq = captures.nearestSq();
        long prepared = System.nanoTime();
        nativeScene.prepareFrame(cmd.address(), frameIndex, camX, camY, camZ);
        nativeSceneFrameActive = nativeScene.activeThisFrame();
        int instances = nativeScene.instanceCount();
        lastEntityTelemetry = "[VK-ENTITY-AS] owner=native captured="
            + RtEntityGeometryManager.INSTANCE.getCapturedEntityCount() + " triangles=" + nativeScene.entityTriangles()
            + " instances=" + instances + " " + RtEntityGeometryManager.INSTANCE.formatDiagnostics();
        RtTimings.countBlas(nativeScene.blasBuilds());
        RtTimings.stop(RtTimings.Phase.UPDATE_TOTAL, tUpdate);
        RtTimings.endFrame();
        return new FrameStats(nativeScene.blasBuilds(), nativeScene.sectionCount(), captures.pending(),
            instances, (prepared - started) / 1e6, (System.nanoTime() - prepared) / 1e6,
            mem.reservedBytes() + nativeScene.residentBytes(), SectionGeometryRegistry.INSTANCE.failedCount());
    }

    /** Last [VK-ENTITY-AS] line, read by the game test harness. */
    public static volatile String lastEntityTelemetry = "none";

    public long identicalSkipped() {
        return nativeCapture.identicalSkipped();
    }

    /** Cutout quads tagged as vegetation since start (cumulative; a rebuild tags again). Zero means the classifier is dead. */
    public long vegetationQuads() {
        return vegetationQuads;
    }

    /** Sections whose build tagged at least one vegetation quad (cumulative). */
    public long vegetationSections() {
        return vegetationSections;
    }

    /**
     * Installs the vegetation query used while staging cutout quads. Set it before the first {@link #update}: sections
     * staged without it keep their quads untagged until Sodium re-meshes them.
     */
    public void setBlockLookup(BlockLookup lookup) {
        this.blockLookup = lookup;
    }

    /**
     * Per-quad vegetation bits for the native section upload: {@code out[q] = VEGETATION_BIT (| LEAF_CUBE_BIT)} for the cutout quads whose block is a plant or a
     * leaf, or null when nothing is tagged. Same classification loop as {@link #tagVegetation}, which applies the bits to each vertex light word.
     */
    int[] quadFlags(DecodedSection d) {
        if (blockLookup == null || d.quadsInPass(DecodedSection.PASS_CUTOUT) == 0) {
            return null;
        }
        int quads = d.totalQuads();
        if (taggedLightScratch.length < quads) {
            taggedLightScratch = new int[quads];
        }
        java.util.Arrays.fill(taggedLightScratch, 0, quads, 0);
        int cutout = d.quadsInPass(DecodedSection.PASS_CUTOUT);
        int first = d.passQuadStart(DecodedSection.PASS_CUTOUT);
        int tagged = 0;
        // The faces of one block are usually adjacent in the buffer, and a level lookup is not free: ask once per cell.
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
                kind = blockLookup.foliageKind(bx, by, bz);
                lastX = bx;
                lastY = by;
                lastZ = bz;
            }
            if (kind == FOLIAGE_NONE) {
                continue;
            }
            int bits = VEGETATION_BIT;
            if (kind == FOLIAGE_CUBE && axisAligned(nx, ny, nz)) {
                bits |= LEAF_CUBE_BIT;
            }
            taggedLightScratch[q] = bits;
            tagged++;
        }
        if (tagged > 0) {
            vegetationQuads += tagged;
            vegetationSections++;
        }
        return tagged > 0 ? taggedLightScratch : null;
    }

    /**
     * Copies the light words of the cutout pass into {@code out} and sets {@link #VEGETATION_BIT} on the quads of plants
     * and leaves, adding {@link #LEAF_CUBE_BIT} when the block is a full cube. Returns the number of tagged quads.
     *
     * <p>The block of a quad is the quad centre stepped 1 cm against its geometric normal: a full-block face (leaves)
     * has its centre exactly on a cell boundary, where a plain floor() could resolve to the neighbouring cell.
     */
    static int tagVegetation(DecodedSection d, int[] out, BlockLookup lookup) {
        int words = d.totalQuads() * 4;
        if (out.length < words) {
            throw new IllegalArgumentException("out too small: " + out.length + " < " + words);
        }
        System.arraycopy(d.light, 0, out, 0, words);
        int cutout = d.quadsInPass(DecodedSection.PASS_CUTOUT);
        int first = d.passQuadStart(DecodedSection.PASS_CUTOUT);
        int tagged = 0;
        // The faces of one block are usually adjacent in the buffer, and a level lookup is not free: ask once per cell.
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
            if (kind == FOLIAGE_NONE) {
                continue;
            }
            int bits = VEGETATION_BIT;
            if (kind == FOLIAGE_CUBE && axisAligned(nx, ny, nz)) {
                bits |= LEAF_CUBE_BIT;
            }
            int v = q * 4;
            out[v] |= bits;
            out[v + 1] |= bits;
            out[v + 2] |= bits;
            out[v + 3] |= bits;
            tagged++;
        }
        return tagged;
    }

    /**
     * A full leaf cube always shows an axis-aligned face. A resource pack or a modded {@code LeavesBlock} drawn as a cross
     * is a card: the one-block step along a diagonal normal would leave the caster cell on the wrong side.
     */
    private static boolean axisAligned(float nx, float ny, float nz) {
        return Math.abs(nx) > 0.99f || Math.abs(ny) > 0.99f || Math.abs(nz) > 0.99f;
    }

    io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative.TableView frameTable(
        int kind, long revision, java.nio.ByteBuffer bytes) {
        return nativeScene.frameTable(kind, revision, bytes);
    }

    io.github.andrexarlay.vulkancompat.rt.scene.BedraySceneNative.MaterialViews uploadMaterials(
        int width, int height, int count, java.lang.foreign.MemorySegment regions,
        java.lang.foreign.MemorySegment bytes, long planeBytes) {
        return nativeScene.uploadMaterials(width, height, count, regions, bytes, planeBytes);
    }

    public long structureVersion() {
        return nativeScene.sectionEpoch();
    }

    public void forEachBuiltSection(Consumer<DecodedSection> consumer) {
        nativeCapture.forEachCommitted(consumer);
    }

    public long nearestBuiltSq() {
        return nearestBuiltSq;
    }

    public void releaseRecording(long commandBuffer) {
        nativeScene.releaseRecording(commandBuffer);
    }

    Runnable retainSceneRead(long commandBuffer) {
        return nativeScene.retainRead(commandBuffer);
    }

    public void discardRecording(long commandBuffer) {
        if (nativeScene.discardRecording(commandBuffer)) {
            nativeSceneDiscarded = true;
            nativeSceneFrameActive = false;
        }
    }

    boolean needsNativeSceneRebuild() {
        return nativeSceneDiscarded;
    }

    /** Replays the live Sodium capture into a fresh C++ scene after all consumers have retired. */
    void rebuildNativeSceneAfterHostDrain(SlangRuntime runtime) {
        if (!nativeSceneDiscarded) return;
        nativeScene.close();
        nativeScene = NativeSceneBridge.create(runtime);
        nativeCapture = new NativeSectionCapture(SectionGeometryRegistry.INSTANCE, nativeScene, this::quadFlags);
        RtEntityGeometryManager.INSTANCE.resetHistory();
        nativeSceneDiscarded = false;
    }

    /** Called after the host proves completion of all consumers. */
    public void releaseComputeRecordingsAfterHostDrain() {
        nativeScene.releaseAllFrames();
    }

    public void closeComputeAfterHostDrain() {
        nativeScene.close();
    }

    public void destroy() {
        nativeScene.close();
        nativeSceneFrameActive = false;
        RtEntityGeometryManager.INSTANCE.resetHistory();
        mem.close();
    }

    public int entityPrimitiveCount() {
        return nativeSceneFrameActive ? nativeScene.entityTriangles() : 0;
    }
}
