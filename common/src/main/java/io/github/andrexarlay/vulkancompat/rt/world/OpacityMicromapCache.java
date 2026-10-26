package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rt.material.RtMaterialAtlas;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTOpacityMicromap;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkMicromapBuildInfoEXT;
import org.lwjgl.vulkan.VkMicromapBuildSizesInfoEXT;
import org.lwjgl.vulkan.VkMicromapCreateInfoEXT;
import org.lwjgl.vulkan.VkMicromapTriangleEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/**
 * Opacity micromaps (VK_EXT_opacity_micromap) for the CUTOUT pass: foliage micro-triangles whose texels are
 * uniformly opaque or transparent become opaque/transparent for ray traversal, so leaves stop costing a shader
 * invocation per candidate.
 *
 * <p>One global level-4 (256 micro-triangles) 4-state micromap object holds every classified UV triangle.
 * Entries are interned by their exact UV corners (float bits) and appended to a host-visible payload; when the
 * payload grows past the object's entry count, a new object is built ("flip") and the old one is retired while
 * BLAS builds still reference it (refcounted, freed after {@link #RETIRED_FRAMES} frames with no references).
 * Because entries only append within an epoch, a BLAS's per-triangle entry indexes stay valid across flips.
 *
 * <p>Per BLAS triangle the index buffer (8 bytes per cutout quad, uint32 per triangle, appended to the section
 * geometry arena after the light data) holds the interned entry index, or the special value 0xFFFFFFFD
 * (fully-unknown-transparent) for triangles that cannot be classified yet. Unknown keeps the current behaviour:
 * the shader's cutout alpha test decides, so a classification mistake can only cost performance, never change
 * the image.
 *
 * <p>Classification is two-tier and image-identical by construction. Tier 1 (opaque): the micro-triangle's exact
 * footprint (no margin) holds only fully-opaque texels (alpha &ge; 253, not ice). A resolved-opaque candidate is still
 * reported to the shader and the shader's own cutout test stays authoritative, so this can only save nothing or skip
 * nothing today - but it is a definitive state the hardware can act on, and it can never change the image at any ray
 * distance. Tier 2 (transparent): the exact footprint is fully transparent AND the whole neighbourhood the far test
 * can average over ({@link #TEXEL_NEIGHBORHOOD} texels, the 3x3 tap spread plus linear-filter rounding) holds no texel
 * above {@link #NEIGHBORHOOD_MAX_ALPHA}: near, the exact texel already fails alpha &ge; 0.5; far, the averaged coverage
 * stays below the 0.04 draw threshold, so the hardware may cull the candidate without losing a hit the shader would
 * have drawn. Everything else stays unknown: the shader's cutout test decides, a classification mistake can only cost
 * performance, never change the image.
 *
 * <p>Why not a plain margin-0 both-state resolution: the far cutout test draws at a coverage of just 0.04, so a
 * margin-0 transparent classification next to any foliage texel removes hits the distant shader would still have
 * drawn - the measured "foliage drops at distance" regression that kept the feature off by default
 * ({@code RtConfig.OPACITY_MICROMAPS}). The alpha-accurate neighbourhood guard is the classification that resolves
 * under the safe margin. Tier 2 additionally waits for {@code onEpochFinished()}: while the atlas bake is still
 * running, a neighbouring sprite could later turn opaque, so mid-bake classifications resolve opaque states only.
 *
 * <p>Epochs: the material atlas re-bakes on a pack switch. {@link #onEpochStart()} clears the interning state and
 * retires the object (its references stay valid until the sections rebuild); {@link #onEpochFinished()} bumps the
 * generation so sections whose triangles are still unclassified rebuild once the bake has settled. Render thread only.
 */
public final class OpacityMicromapCache implements RtMaterialAtlas.MicromapSink {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final int LEVEL = 4;
    static final int MICRO_TRIS = 1 << (2 * LEVEL);
    static final int LATTICE = 1 << LEVEL;
    static final int MAX_ENTRIES = 8192;
    /** 256 micro-triangles x 2 bits (4-state format), packed LSB-first. */
    static final int ENTRY_BYTES = 64;
    /** VkMicromapTriangleEXT: uint32 dataOffset + uint16 subdivisionLevel + uint16 format. */
    static final int TRI_BYTES = 8;
    static final int THROTTLE_FRAMES = 15;
    static final int RETIRED_FRAMES = 3;
    /** Radius of the neighbourhood the far cutout test can average over: 4 for the shader's max 3x3 tap spread, 1 rounding. */
    static final int TEXEL_NEIGHBORHOOD = 5;
    /** A neighbourhood texel above this alpha could push the far test's averaged coverage over its 0.04 draw threshold. */
    static final int NEIGHBORHOOD_MAX_ALPHA = 10;

    /** Payload opacity values (4-state): fully transparent, fully opaque, unknown-transparent. */
    static final byte STATE_TRANSPARENT = 0;
    static final byte STATE_OPAQUE = 1;
    static final byte STATE_UNKNOWN = 2;

    /** Per-triangle micromap index for unclassified triangles (uint32 bits of -3). */
    static final int SPECIAL_UNKNOWN = 0xFFFFFFFD;

    /** intern() result: status in bits 0-1, entry index in the high bits. */
    static final int ST_OK = 0;
    static final int ST_RETRY = 1;
    static final int ST_PERMANENT = 2;

    /** Texel classification (one byte per atlas texel): 0 not baked, 1 opaque, 2 transparent, 3 unknown. */
    static final byte TEXEL_UNKNOWN = 0;
    static final byte TEXEL_OPAQUE = 1;
    static final byte TEXEL_TRANSPARENT = 2;
    static final byte TEXEL_MIXED = 3;

    // --------------------------------------------------------------------------------------------- curve

    /**
     * Verbatim port of the spec appendix reference {@code BarycentricsToSpaceFillingCurveIndex}: bijective over
     * the triangle (validated on a 1/1024 grid, 525k samples, 0 missing regions), index 0 at the vertex-0 corner,
     * 85 at the centroid. Quantization is floor-based: micro-triangle r covers the lattice cell (r is a Morton
     * order of the 16x16 cell coordinates).
     */
    static int curveIndex(float u, float v, int level) {
        u = Math.max(0f, Math.min(1f, u));
        v = Math.max(0f, Math.min(1f, v));
        int iu, iv, iw;
        float fu = u * (1 << level);
        float fv = v * (1 << level);
        iu = (int) fu;
        iv = (int) fv;
        float uf = fu - iu;
        float vf = fv - iv;
        if (iu >= (1 << level)) iu = (1 << level) - 1;
        if (iv >= (1 << level)) iv = (1 << level) - 1;
        int iuv = iu + iv;
        if (iuv >= (1 << level)) iu -= iuv - (1 << level) + 1;
        iw = ~(iu + iv);
        if (uf + vf >= 1.0f && iuv < (1 << level) - 1) --iw;
        int b0 = ~(iu ^ iw);
        b0 &= ((1 << level) - 1);
        int t = (iu ^ iv) & b0;
        int f = t;
        f ^= f >> 1;
        f ^= f >> 2;
        f ^= f >> 4;
        f ^= f >> 8;
        int b1 = ((f ^ iu) & ~b0) | t;
        b0 = (b0 | (b0 << 8)) & 0x00ff00ff;
        b0 = (b0 | (b0 << 4)) & 0x0f0f0f0f;
        b0 = (b0 | (b0 << 2)) & 0x33333333;
        b0 = (b0 | (b0 << 1)) & 0x55555555;
        b1 = (b1 | (b1 << 8)) & 0x00ff00ff;
        b1 = (b1 | (b1 << 4)) & 0x0f0f0f0f;
        b1 = (b1 | (b1 << 2)) & 0x33333333;
        b1 = (b1 | (b1 << 1)) & 0x55555555;
        return b0 | (b1 << 1);
    }

    /** Per micro-triangle inclusive lattice bbox: {minU, maxU, minV, maxV} in 1/16 lattice coordinates. */
    private static int[][] regionBboxTable;

    /**
     * Bounding box of every micro-triangle in curve space, derived once from a 1/1024 grid over the triangle and
     * rounded outward to the 1/16 lattice (region extents are lattice points, so the rounding is exact except at
     * the hypotenuse, where it errs on the large side).
     */
    static int[][] regionBbox() {
        int[][] t = regionBboxTable;
        if (t != null) {
            return t;
        }
        int g = 1024;
        float[] minU = new float[MICRO_TRIS];
        float[] maxU = new float[MICRO_TRIS];
        float[] minV = new float[MICRO_TRIS];
        float[] maxV = new float[MICRO_TRIS];
        Arrays.fill(minU, 2f);
        Arrays.fill(maxV, -2f);
        Arrays.fill(minV, 2f);
        Arrays.fill(maxU, -2f);
        for (int iv = 0; iv <= g; iv++) {
            for (int iu = 0; iu + iv <= g; iu++) {
                float u = iu / (float) g;
                float v = iv / (float) g;
                int idx = curveIndex(u, v, LEVEL);
                if (u < minU[idx]) minU[idx] = u;
                if (u > maxU[idx]) maxU[idx] = u;
                if (v < minV[idx]) minV[idx] = v;
                if (v > maxV[idx]) maxV[idx] = v;
            }
        }
        t = new int[MICRO_TRIS][4];
        for (int r = 0; r < MICRO_TRIS; r++) {
            t[r][0] = Math.max(0, Math.min(LATTICE, (int) Math.floor(minU[r] * LATTICE + 1.0e-6f)));
            t[r][1] = Math.max(0, Math.min(LATTICE, (int) Math.ceil(maxU[r] * LATTICE - 1.0e-6f)));
            t[r][2] = Math.max(0, Math.min(LATTICE, (int) Math.floor(minV[r] * LATTICE + 1.0e-6f)));
            t[r][3] = Math.max(0, Math.min(LATTICE, (int) Math.ceil(maxV[r] * LATTICE - 1.0e-6f)));
        }
        regionBboxTable = t;
        return t;
    }

    // --------------------------------------------------------------------------------- classification (pure)

    /**
     * Classifies one UV triangle into {@code outPayload} (64 bytes, 4 states per byte, LSB-first). Returns
     * {@link #ST_OK}, or {@link #ST_RETRY} when some footprint texel is not baked yet (no payload written means
     * nothing was committed; the caller must not allocate an entry).
     */
    static int classifyEntry(float u0, float v0, float u1, float v1, float u2, float v2,
                             byte[] texelState, byte[] texelAlpha, boolean transparentResolutionAllowed,
                             int atlasW, int atlasH, byte[] outPayload) {
        int[][] bbox = regionBbox();
        Arrays.fill(outPayload, (byte) 0);
        for (int r = 0; r < MICRO_TRIS; r++) {
            int[] b = bbox[r];
            // UV-space bounding box of the lattice cell corners mapped through (1-u-v)*UV0 + u*UV1 + v*UV2
            float uMin = Float.MAX_VALUE;
            float uMax = -Float.MAX_VALUE;
            float vMin = Float.MAX_VALUE;
            float vMax = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                float lu = (c & 1) != 0 ? b[1] : b[0];
                float lv = (c & 2) != 0 ? b[3] : b[2];
                float w = 1.0f - lu / LATTICE - lv / LATTICE;
                float x = w * u0 + (lu / LATTICE) * u1 + (lv / LATTICE) * u2;
                float y = w * v0 + (lu / LATTICE) * v1 + (lv / LATTICE) * v2;
                if (x < uMin) uMin = x;
                if (x > uMax) uMax = x;
                if (y < vMin) vMin = y;
                if (y > vMax) vMax = y;
            }
            int x0 = Math.max(0, floorTexel(uMin, atlasW));
            int x1 = Math.min(atlasW - 1, ceilTexel(uMax, atlasW));
            int y0 = Math.max(0, floorTexel(vMin, atlasH));
            int y1 = Math.min(atlasH - 1, ceilTexel(vMax, atlasH));
            boolean allOpaque = true;
            boolean allTransparent = true;
            for (int y = y0; y <= y1; y++) {
                int row = y * atlasW;
                for (int x = x0; x <= x1; x++) {
                    byte st = texelState[row + x];
                    if (st == TEXEL_UNKNOWN) {
                        return ST_RETRY;
                    }
                    if (st != TEXEL_OPAQUE) {
                        allOpaque = false;
                    }
                    if (st != TEXEL_TRANSPARENT) {
                        allTransparent = false;
                    }
                }
            }
            byte state;
            if (allOpaque) {
                // Tier 1, margin 0: the exact footprint is fully opaque. The candidate is still reported to the shader,
                // whose cutout test stays authoritative (close up the exact texel passes a >= 0.5; far away the averaged
                // coverage still sees the hit texel), so this can never change the image at any ray distance.
                state = STATE_OPAQUE;
            } else if (allTransparent && transparentResolutionAllowed && neighborhoodEmpty(texelState, texelAlpha, x0, y0, x1, y1, atlasW, atlasH)) {
                // Tier 2: the footprint is transparent AND every texel the far test can average over is draw-able-free,
                // so the hardware may cull the candidate: near, the exact texel fails alpha >= 0.5 anyway; far, the
                // averaged coverage stays under the 0.04 threshold and the shader would reject the hit too.
                state = STATE_TRANSPARENT;
            } else {
                state = STATE_UNKNOWN;
            }
            outPayload[r >> 2] |= state << (2 * (r & 3));
        }
        return ST_OK;
    }

    /**
     * Tier 2 guard: the far cutout test (dustCoverage) spreads 3x3 taps up to &plusmn;{@link #TEXEL_NEIGHBORHOOD} texels
     * around the hit and draws when the averaged coverage reaches 0.04. The candidate may only leave the traversal if no
     * texel the taps can touch holds a draw-able alpha (or is opaque / mixed - an animated neighbour can turn opaque
     * later, a partial texel already draws). Unknown texels count as empty: with the bake settled they are atlas padding.
     */
    private static boolean neighborhoodEmpty(byte[] texelState, byte[] texelAlpha, int x0, int y0, int x1, int y1,
                                             int atlasW, int atlasH) {
        int nx0 = Math.max(0, x0 - TEXEL_NEIGHBORHOOD);
        int nx1 = Math.min(atlasW - 1, x1 + TEXEL_NEIGHBORHOOD);
        int ny0 = Math.max(0, y0 - TEXEL_NEIGHBORHOOD);
        int ny1 = Math.min(atlasH - 1, y1 + TEXEL_NEIGHBORHOOD);
        for (int y = ny0; y <= ny1; y++) {
            int row = y * atlasW;
            for (int x = nx0; x <= nx1; x++) {
                byte st = texelState[row + x];
                if (st == TEXEL_OPAQUE || st == TEXEL_MIXED) {
                    return false;
                }
                if ((texelAlpha[row + x] & 0xFF) > NEIGHBORHOOD_MAX_ALPHA) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int floorTexel(float uv, int size) {
        int t = (int) Math.floor(uv * size);
        return t < 0 ? 0 : Math.min(t, size - 1);
    }

    private static int ceilTexel(float uv, int size) {
        int t = (int) Math.ceil(uv * size);
        return t < 0 ? 0 : Math.min(t, size - 1);
    }

    /**
     * Adds the 2-bit states of one payload to {@code out} ([0] opaque, [1] transparent, [2] unknown), so a run can
     * show that micromaps resolve leaf texels instead of leaving every micro-triangle unknown. The reserved fourth
     * state (unknown-opaque) counts as unknown: {@link #classifyEntry} never writes it.
     */
    static void countStates(byte[] payload, long[] out) {
        for (int i = 0; i < ENTRY_BYTES; i++) {
            int bits = payload[i] & 0xFF;
            for (int q = 0; q < 4; q++) {
                byte st = (byte) ((bits >>> (2 * q)) & 3);
                out[st == STATE_OPAQUE ? 0 : st == STATE_TRANSPARENT ? 1 : 2]++;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- state

    private final RtMemory mem;
    private RtMemory.Buf buildBuf;
    private long payloadOff;
    private long triOff;
    private RtMemory.Buf storage;
    private RtMemory.Buf mmScratch;
    private long mmScratchBytes;
    private long objectHandle;
    private int objectEntries;
    private int entries;
    private long objectGen;
    private long epoch;
    private long frame;
    private long lastFlushFrame = -THROTTLE_FRAMES;
    private boolean disabled;

    private byte[] texelState;
    /** Raw alpha per atlas texel (0 = empty; unbaked regions read 0, gated by {@link #bakeComplete} for tier 2). */
    private byte[] texelAlpha;
    /** True only between {@link #onEpochFinished()} and the next {@link #onEpochStart()}: tier 2 needs a settled bake. */
    private boolean bakeComplete;
    private int atlasW;
    private int atlasH;

    /** Micro-triangles of the entries interned in the current epoch: [0] opaque, [1] transparent, [2] unknown. */
    private final long[] microTris = new long[3];
    /** Cumulative intern() results that resolved to a real entry; a section rebuild counts its triangles again. */
    private long mappedTris;
    private long flips;
    private long lastStatsNs = -1L;
    private int lastLoggedEntries = -1;
    private long lastLoggedFlips = -1L;
    private long lastLoggedMapped = -1L;

    private static final class UvTri {
        float u0, v0, u1, v1, u2, v2;

        UvTri set(float u0, float v0, float u1, float v1, float u2, float v2) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
            return this;
        }

        UvTri copy() {
            return new UvTri().set(u0, v0, u1, v1, u2, v2);
        }

        private static int bits(float f) {
            return Float.floatToRawIntBits(f);
        }

        @Override
        public boolean equals(Object o) {
            UvTri t = (UvTri) o;
            return bits(u0) == bits(t.u0) && bits(v0) == bits(t.v0)
                && bits(u1) == bits(t.u1) && bits(v1) == bits(t.v1)
                && bits(u2) == bits(t.u2) && bits(v2) == bits(t.v2);
        }

        @Override
        public int hashCode() {
            int h = bits(u0);
            h = h * 961 + bits(v0);
            h = h * 961 + bits(u1);
            h = h * 961 + bits(v1);
            h = h * 961 + bits(u2);
            h = h * 961 + bits(v2);
            return h;
        }
    }

    private final UvTri lookupKey = new UvTri();
    private final HashMap<UvTri, Integer> entryOf = new HashMap<>();
    private final HashSet<UvTri> permanentUnknown = new HashSet<>();
    private final HashMap<Long, Integer> refs = new HashMap<>();

    private static final class Retired {
        final long handle;
        final RtMemory.Buf buf;
        final long frame;

        Retired(long handle, RtMemory.Buf buf, long frame) {
            this.handle = handle;
            this.buf = buf;
            this.frame = frame;
        }
    }

    private final ArrayList<Retired> retired = new ArrayList<>();

    private static final class RetiredBuf {
        final RtMemory.Buf buf;
        final long frame;

        RetiredBuf(RtMemory.Buf buf, long frame) {
            this.buf = buf;
            this.frame = frame;
        }
    }

    /** Build-input buffers of finished epochs: a recorded micromap build may still be reading them. */
    private final ArrayList<RetiredBuf> retiredBufs = new ArrayList<>();

    private OpacityMicromapCache(RtMemory mem) {
        this.mem = mem;
    }

    /** Session gate: config toggle, device capability and a shaderc compile probe for the GLSL extension. */
    public static boolean supported() {
        if (!RtConfig.OPACITY_MICROMAPS) {
            return false;
        }
        if (!VulkanCapabilityChecker.isOpacityMicromapSupported()) {
            return false;
        }
        return RtShaderCompiler.probeOpacityMicromapExtension();
    }

    /** Returns the cache, or null when the session gate fails (logged once). */
    public static OpacityMicromapCache create(RtMemory mem) {
        if (!supported()) {
            LOGGER.info("[VKB-RT] opacity micromaps off (toggle, device capability or shader probe failed)");
            return null;
        }
        try {
            OpacityMicromapCache c = new OpacityMicromapCache(mem);
            LOGGER.info("[VKB-RT] opacity micromaps enabled level={} entries_max={}", LEVEL, MAX_ENTRIES);
            return c;
        } catch (Throwable t) {
            LOGGER.error("[VKB-RT] opacity micromaps unavailable\n{}", LogPaths.stack(t));
            return null;
        }
    }

    public boolean enabled() {
        return !disabled;
    }

    public long currentObjectHandle() {
        return objectHandle;
    }

    public long currentGen() {
        return objectGen;
    }

    public long currentEpoch() {
        return epoch;
    }

    /** True when the section must be rebuilt to pick up micromap entries (new epoch, or retried entries appeared). */
    boolean needsRebuild(long ommStamp, long ommGen, boolean ommRetry) {
        return !disabled && ommStamp >= 0
            && (ommStamp != epoch || (ommRetry && ommGen != objectGen));
    }

    void addRef(long handle) {
        if (handle != 0L) {
            refs.merge(handle, 1, Integer::sum);
        }
    }

    void releaseRef(long handle) {
        if (handle != 0L) {
            refs.computeIfPresent(handle, (k, v) -> v == 1 ? null : v - 1);
        }
    }

    // ------------------------------------------------------------------------------------------------ stats

    /**
     * Runtime proof that micromaps do something: how many UV triangles the object holds, how their micro-triangles
     * split, and whether any BLAS triangle has already been built against a real entry. Logged on change at most
     * every 10 s, so a session that classifies nothing leaves a line saying so instead of looking like a working one.
     */
    void logStatsIfDue(long nowNs) {
        if (disabled) {
            return;
        }
        if (entries == lastLoggedEntries && flips == lastLoggedFlips && mappedTris == lastLoggedMapped) {
            return;
        }
        if (lastStatsNs >= 0L && nowNs - lastStatsNs < 10_000_000_000L) {
            return;
        }
        lastStatsNs = nowNs;
        lastLoggedEntries = entries;
        lastLoggedFlips = flips;
        lastLoggedMapped = mappedTris;
        long total = microTris[0] + microTris[1] + microTris[2];
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] omm epoch=%d entries=%d/%d in_object=%d flips=%d mapped_tris=%d micro_tris=%d "
                + "opaque=%s transparent=%s unknown=%s",
            epoch, entries, MAX_ENTRIES, objectEntries, flips, mappedTris, total,
            pct(microTris[0], total), pct(microTris[1], total), pct(microTris[2], total)));
    }

    private static String pct(long part, long total) {
        return total == 0L ? "n/a" : String.format(Locale.ROOT, "%.1f", 100.0 * part / total);
    }

    // -------------------------------------------------------------------------------------------- interning

    private void ensureBuildBuffer() {
        if (buildBuf != null) {
            return;
        }
        long triBytes = FreeListAllocator.roundUp((long) MAX_ENTRIES * TRI_BYTES, 256);
        long size = 512 + triBytes + (long) MAX_ENTRIES * ENTRY_BYTES;
        buildBuf = mem.createBuffer("omm_build", size,
            EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        // data and triangleArray must be 256-byte aligned (the buffer base address is not guaranteed to be)
        triOff = FreeListAllocator.roundUp(buildBuf.address, 256) - buildBuf.address;
        payloadOff = triOff + triBytes;
    }

    /**
     * Interns one UV triangle (vertex order of the BLAS triangle). Returns {@link #ST_OK} with the entry index in
     * the high bits when the entry is in the current object (usable as a BLAS index now), {@link #ST_RETRY} when
     * the index must stay {@link #SPECIAL_UNKNOWN} this build (texels unknown, or the entry is not in the object
     * yet), {@link #ST_PERMANENT} for triangles that will never classify (budget or broken texture).
     */
    int intern(float u0, float v0, float u1, float v1, float u2, float v2) {
        if (disabled || texelState == null) {
            return ST_RETRY;
        }
        UvTri key = lookupKey.set(u0, v0, u1, v1, u2, v2);
        Integer existing = entryOf.get(key);
        if (existing != null) {
            int idx = existing;
            if (idx < objectEntries) {
                mappedTris++;
                return ST_OK | (idx << 2);
            }
            return ST_RETRY;
        }
        if (permanentUnknown.contains(key)) {
            return ST_PERMANENT;
        }
        if (entries >= MAX_ENTRIES) {
            permanentUnknown.add(key.copy());
            return ST_PERMANENT;
        }
        ensureBuildBuffer();
        byte[] payload = new byte[ENTRY_BYTES];
        int st = classifyEntry(u0, v0, u1, v1, u2, v2, texelState, texelAlpha, bakeComplete, atlasW, atlasH, payload);
        if (st != ST_OK) {
            return ST_RETRY;
        }
        countStates(payload, microTris);
        int idx = entries;
        long p = buildBuf.mapped + payloadOff + (long) idx * ENTRY_BYTES;
        for (int i = 0; i < ENTRY_BYTES; i++) {
            MemoryUtil.memPutByte(p + i, payload[i]);
        }
        long t = buildBuf.mapped + triOff + (long) idx * TRI_BYTES;
        MemoryUtil.memPutInt(t, idx * ENTRY_BYTES);
        MemoryUtil.memPutShort(t + 4, (short) LEVEL);
        MemoryUtil.memPutShort(t + 6, (short) EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
        entryOf.put(key.copy(), idx);
        entries++;
        // the entry is in the payload but not in the object until the next flip
        return ST_RETRY;
    }

    // -------------------------------------------------------------------------------------------------- flush

    /** Records the micromap build (throttled) and frees long-retired objects; called once per frame before the BLAS builds. */
    void beforeBuild(VkCommandBuffer cmd, long frameIndex) {
        frame = frameIndex;
        if (disabled) {
            return;
        }
        destroyRetired();
        if (entries > objectEntries && frameIndex - lastFlushFrame >= THROTTLE_FRAMES) {
            flush(cmd);
        }
    }

    private void destroyRetired() {
        for (Iterator<Retired> it = retired.iterator(); it.hasNext(); ) {
            Retired r = it.next();
            if (frame - r.frame >= RETIRED_FRAMES && !refs.containsKey(r.handle)) {
                EXTOpacityMicromap.vkDestroyMicromapEXT(mem.device, r.handle, null);
                mem.destroy(r.buf);
                it.remove();
            }
        }
        for (Iterator<RetiredBuf> it = retiredBufs.iterator(); it.hasNext(); ) {
            RetiredBuf r = it.next();
            if (frame - r.frame >= RETIRED_FRAMES) {
                mem.destroy(r.buf);
                it.remove();
            }
        }
    }

    private void flush(VkCommandBuffer cmd) {
        ensureBuildBuffer();
        int count = entries;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMicromapUsageEXT.Buffer usage = VkMicromapUsageEXT.calloc(1, stack)
                .count(count).subdivisionLevel(LEVEL).format(EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
            VkMicromapBuildInfoEXT.Buffer info = VkMicromapBuildInfoEXT.calloc(1, stack);
            info.get(0).sType$Default()
                .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
                .flags(EXTOpacityMicromap.VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT)
                .mode(EXTOpacityMicromap.VK_BUILD_MICROMAP_MODE_BUILD_EXT)
                .usageCountsCount(1).pUsageCounts(usage)
                .triangleArrayStride(TRI_BYTES);
            info.get(0).data(a -> a.deviceAddress(buildBuf.address + payloadOff));
            info.get(0).triangleArray(a -> a.deviceAddress(buildBuf.address + triOff));
            VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(stack).sType$Default();
            EXTOpacityMicromap.vkGetMicromapBuildSizesEXT(mem.device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), sizes);

            long scratchNeed = FreeListAllocator.roundUp(sizes.buildScratchSize(), mem.scratchAlignment);
            ensureScratch(scratchNeed);

            RtMemory.Buf newStorage = mem.createBuffer("omm_storage", sizes.micromapSize(),
                EXTOpacityMicromap.VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false);
            long newHandle;
            try {
                VkMicromapCreateInfoEXT ci = VkMicromapCreateInfoEXT.calloc(stack).sType$Default()
                    .buffer(newStorage.handle).size(sizes.micromapSize())
                    .type(EXTOpacityMicromap.VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT);
                java.nio.LongBuffer p = stack.mallocLong(1);
                RtFailure.check(EXTOpacityMicromap.vkCreateMicromapEXT(mem.device, ci, null, p), "create_micromap");
                newHandle = p.get(0);
                info.get(0).dstMicromap(newHandle);
                info.get(0).scratchData(a -> a.deviceAddress(mmScratch.address));
                EXTOpacityMicromap.vkCmdBuildMicromapsEXT(cmd, info);
                micromapBarrier(cmd);
            } catch (RuntimeException e) {
                mem.destroy(newStorage);
                throw e;
            }

            if (objectHandle != 0L) {
                retired.add(new Retired(objectHandle, storage, frame));
            }
            objectHandle = newHandle;
            storage = newStorage;
            objectEntries = count;
            objectGen++;
            flips++;
            lastFlushFrame = frame;
        } catch (RuntimeException e) {
            if (objectEntries == 0 && retired.isEmpty()) {
                disable("micromap build failed before any object was referenced: " + e.getMessage());
            } else {
                // keep the old object and the pending payload; the throttle retries the flip later
                if (frame - lastLoggedFlushFailure > 600) {
                    lastLoggedFlushFailure = frame;
                    LOGGER.warn("[VKB-RT] opacity micromap flip failed (kept previous object): {}", LogPaths.show(e.getMessage()));
                }
            }
        }
    }

    private long lastLoggedFlushFailure = -1000;

    private void ensureScratch(long need) {
        if (mmScratch != null && mmScratchBytes >= need) {
            return;
        }
        long bytes = Math.max(8L << 20, need);
        if (mmScratch != null) {
            VK10.vkDeviceWaitIdle(mem.device); // a recorded build may still be executing
            mem.destroy(mmScratch);
        }
        mmScratch = mem.createBuffer("omm_scratch", bytes,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        mmScratchBytes = bytes;
    }

    /**
     * Micromap build writes must be visible to the acceleration structure builds that reference the object and to
     * ray traversal. The micromap stage/access bits only exist as 64-bit sync2 values, so this uses
     * vkCmdPipelineBarrier2 (the OMM device requirement group enables VK_KHR_synchronization2).
     */
    private static void micromapBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer b = VkMemoryBarrier2.calloc(1, stack);
            b.get(0).sType$Default()
                .srcStageMask(EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT)
                .srcAccessMask(EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_WRITE_BIT_EXT)
                .dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                    | EXTOpacityMicromap.VK_PIPELINE_STAGE_2_MICROMAP_BUILD_BIT_EXT
                    | KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
                .dstAccessMask(KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
                    | KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
                    | EXTOpacityMicromap.VK_ACCESS_2_MICROMAP_READ_BIT_EXT
                    | KHRSynchronization2.VK_ACCESS_2_SHADER_READ_BIT_KHR);
            VkDependencyInfo dep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep);
        }
    }

    private void disable(String why) {
        disabled = true;
        entryOf.clear();
        permanentUnknown.clear();
        entries = 0;
        objectEntries = 0;
        Arrays.fill(microTris, 0L);
        LOGGER.error("[VKB-RT] opacity micromaps DISABLED: {} (sections fall back to the shader cutout test)", LogPaths.show(why));
    }

    // ---------------------------------------------------------------------------------------------- atlas sink

    @Override
    public void onEpochStart() {
        if (disabled) {
            return;
        }
        epoch++;
        entryOf.clear();
        permanentUnknown.clear();
        if (objectHandle != 0L) {
            retired.add(new Retired(objectHandle, storage, frame));
            objectHandle = 0L;
            storage = null;
        }
        if (buildBuf != null) {
            // entries are rewritten from index 0 in the new epoch, and the build recorded for the retired object may
            // still be executing: the buffer it reads must outlive the frame, like the object itself
            retiredBufs.add(new RetiredBuf(buildBuf, frame));
            buildBuf = null;
        }
        objectEntries = 0;
        entries = 0;
        Arrays.fill(microTris, 0L);
        objectGen++;
        if (texelState != null) {
            Arrays.fill(texelState, TEXEL_UNKNOWN);
        }
        if (texelAlpha != null) {
            // unbaked reads as empty; tier 2 is gated by bakeComplete until the settled re-bake has rewritten these
            Arrays.fill(texelAlpha, (byte) 0);
        }
        bakeComplete = false;
    }

    @Override
    public void onSpriteBaked(int x0, int y0, int w, int h, int[] material, byte[] alpha, boolean animated,
                              int atlasWidth, int atlasHeight) {
        if (disabled) {
            return;
        }
        ensureAtlasArrays(atlasWidth, atlasHeight);
        if (alpha == null) {
            fillRect(x0, y0, w, h, TEXEL_MIXED);
            fillAlphaRect(x0, y0, w, h, (byte) 0xFF);   // no data: block tier-2 resolutions nearby
            return;
        }
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * atlasW + x0;
            for (int x = 0; x < w; x++) {
                byte st;
                int a = alpha[y * w + x] & 0xFF;
                boolean optical = io.github.andrexarlay.vulkancompat.rt.material.OpticalMaterials.profileId(material[y * w + x]) != 0;
                texelAlpha[row + x] = optical ? (byte) 0xFF : (byte) a; // tier-2 must not cull a clear dielectric centre
                if (animated || optical) {
                    st = TEXEL_MIXED;
                } else {
                    if (a <= 127) {
                        st = TEXEL_TRANSPARENT;
                    } else {
                        int m = material[y * w + x];
                        int g = (m >> 8) & 0xFF;
                        int r = m & 0xFF;
                        // ice dielectric (shader: m.g in [2..6] of 255, m.r <= 20): never opaque, the shader filters it
                        boolean ice = g >= 2 && g <= 6 && r <= 20;
                        st = a >= 253 && !ice ? TEXEL_OPAQUE : TEXEL_MIXED;
                    }
                }
                texelState[row + x] = st;
            }
        }
    }

    @Override
    public void onEpochFinished() {
        if (disabled) {
            return;
        }
        // sections whose triangles were retried during the bake get one more pass now that every sprite is final
        objectGen++;
        bakeComplete = true;
    }

    private void ensureAtlasArrays(int width, int height) {
        // The dimensions, not just the length: a pack switch to a smaller atlas keeps the array long enough but every
        // row stride below (and the classification grid) would still use the old width and read unrelated sprites.
        if (texelState != null && atlasW == width && atlasH == height) {
            return;
        }
        texelState = new byte[width * height];
        texelAlpha = new byte[width * height];
        atlasW = width;
        atlasH = height;
    }

    private void fillRect(int x0, int y0, int w, int h, byte st) {
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * atlasW + x0;
            Arrays.fill(texelState, row, row + w, st);
        }
    }

    private void fillAlphaRect(int x0, int y0, int w, int h, byte a) {
        for (int y = 0; y < h; y++) {
            int row = (y0 + y) * atlasW + x0;
            Arrays.fill(texelAlpha, row, row + w, a);
        }
    }

    // ------------------------------------------------------------------------------------------------ release

    /** Waits for the GPU and releases everything. Safe to call once; called from {@link RtWorld#destroy()}. */
    public void destroy() {
        disabled = true;
        if (objectHandle != 0L) {
            EXTOpacityMicromap.vkDestroyMicromapEXT(mem.device, objectHandle, null);
            objectHandle = 0L;
        }
        if (storage != null) {
            mem.destroy(storage);
            storage = null;
        }
        for (Retired r : retired) {
            EXTOpacityMicromap.vkDestroyMicromapEXT(mem.device, r.handle, null);
            mem.destroy(r.buf);
        }
        retired.clear();
        for (RetiredBuf r : retiredBufs) {
            mem.destroy(r.buf);
        }
        retiredBufs.clear();
        if (buildBuf != null) {
            mem.destroy(buildBuf);
            buildBuf = null;
        }
        if (mmScratch != null) {
            mem.destroy(mmScratch);
            mmScratch = null;
        }
        entryOf.clear();
        permanentUnknown.clear();
    }
}
