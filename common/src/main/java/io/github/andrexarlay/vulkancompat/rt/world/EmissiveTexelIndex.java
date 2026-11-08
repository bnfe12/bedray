package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;

/**
 * Integral image of the material atlas texels that can make a quad an emitter: emission above
 * {@link RtEmitters#MIN_EMISSION} or the vertex-tinted redstone tag (its emission comes from the vertex signal). A rectangle
 * query is O(1), so the emitter extraction rejects a non-glowing quad without scanning its texels.
 * <p>
 * The CPU profile of 2026-11-08 (Java Flight Recorder, testbed, 2560x1440) had {@code RtEmitters.buildQuadRecord} at 34% of
 * the render thread: every rescan scanned up to 32x32 texels and allocated three arrays for every quad of every section, almost
 * none of which glow. The answer is exact: a rectangle with no candidate texel cannot contain the strided samples the scan
 * would take, so {@code buildQuadRecord} would have returned false for it.
 */
final class EmissiveTexelIndex {
    private static final int MIN_EMISSION_BYTE = (int) Math.floor(RtEmitters.MIN_EMISSION * 255f);

    private final int[] material;
    private final int width;
    private final int height;
    /** (width + 1) x (height + 1) prefix sums of candidate texels. */
    private final int[] prefix;

    private EmissiveTexelIndex(int[] material, int width, int height) {
        this.material = material;
        this.width = width;
        this.height = height;
        this.prefix = new int[(width + 1) * (height + 1)];
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            int row = 0;
            for (int x = 0; x < width; x++) {
                row += isCandidate(material[y * width + x]) ? 1 : 0;
                prefix[(y + 1) * stride + x + 1] = prefix[y * stride + x + 1] + row;
            }
        }
    }

    /** A texel the extraction can count as emissive: emission byte above MIN_EMISSION, or a redstone-tagged texel. */
    static boolean isCandidate(int materialTexel) {
        return ((materialTexel >>> 16) & 255) > MIN_EMISSION_BYTE || LabPbr.hasSourceFlag(materialTexel, LabPbr.SOURCE_REDSTONE);
    }

    /** True when texel rectangle [x0, x1) x [y0, y1) holds at least one candidate texel. */
    boolean anyCandidate(int x0, int y0, int x1, int y1) {
        int stride = width + 1;
        int count = prefix[y1 * stride + x1] - prefix[y0 * stride + x1] - prefix[y1 * stride + x0] + prefix[y0 * stride + x0];
        return count > 0;
    }

    /** The texel rectangle buildQuadRecord scans for a quad's uv bounds (same clamping), as {x0, y0, x1, y1}. */
    static void texelRect(float[] uvs, int uo, int w, int h, int[] out) {
        float uMin = Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE;
        float uMax = -Float.MAX_VALUE;
        float vMax = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float u = uvs[uo + v * 2];
            float t = uvs[uo + v * 2 + 1];
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, t);
            vMax = Math.max(vMax, t);
        }
        int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(uMin * w)));
        int y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(vMin * h)));
        out[0] = x0;
        out[1] = y0;
        out[2] = Math.max(x0 + 1, Math.min(w, (int) Math.ceil(uMax * w)));
        out[3] = Math.max(y0 + 1, Math.min(h, (int) Math.ceil(vMax * h)));
    }

    private static volatile EmissiveTexelIndex last;

    /** The index of this material atlas, built once per atlas array (the baker replaces the array on a pack switch). */
    static EmissiveTexelIndex of(int[] material, int width, int height) {
        EmissiveTexelIndex cached = last;
        if (cached != null && cached.material == material && cached.width == width && cached.height == height) {
            return cached;
        }
        EmissiveTexelIndex built = new EmissiveTexelIndex(material, width, height);
        last = built;
        return built;
    }
}
