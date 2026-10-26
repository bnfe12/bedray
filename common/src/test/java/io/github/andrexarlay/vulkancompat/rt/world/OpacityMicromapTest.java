package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests for the pure parts of {@link OpacityMicromapCache}: the spec space-filling curve
 * {@code BarycentricsToSpaceFillingCurveIndex}, the per-region lattice bounding boxes and the texel
 * classification of a UV triangle into the 4-state payload.
 * <p>
 * The invariants that keep the classification sound (a wrong payload can only lose culling, never change the
 * image, because unknown = the shader cutout test):
 * <ul>
 *   <li>curveIndex maps the triangle bijectively onto the 256 micro-triangles (every index reachable, values
 *       in range for in-domain and clamped out-of-domain inputs);</li>
 *   <li>the lattice cell a sample quantizes to lies inside region r's bbox (the bbox never clips a
 *       micro-triangle's footprint inward);</li>
 *   <li>classifyEntry scans every texel of each footprint grown by the margin and folds uniform footprints
 *       into opaque/transparent, everything else into unknown; any not-yet-baked texel inside a footprint
 *       forces a retry, unknown texels outside every footprint do not.</li>
 * </ul>
 */
public class OpacityMicromapTest {
    private static final int GRID = 1024;

    @Test
    public void testCurveIndexSpotChecks() {
        // spec reference values from the probe: index 0 at the vertex-0 corner, 85 at the centroid
        assertEquals(0, OpacityMicromapCache.curveIndex(0f, 0f, 4));
        assertEquals(0, OpacityMicromapCache.curveIndex(1f / 64f, 1f / 64f, 4));
        assertEquals(85, OpacityMicromapCache.curveIndex(1f / 3f, 1f / 3f, 4));
    }

    @Test
    public void testCurveIndexRangeAndClamping() {
        for (int iv = 0; iv <= GRID; iv++) {
            for (int iu = 0; iu + iv <= GRID; iu++) {
                int idx = OpacityMicromapCache.curveIndex(iu / (float) GRID, iv / (float) GRID, 4);
                assertTrue(idx >= 0 && idx < 256, "in-domain index out of range: " + idx);
            }
        }
        // clamped out-of-domain inputs must stay in range (hostile floats from section UVs)
        float[] probes = {0, 0, 1, 1, 0.9f, 0.9f, 1f, 1f, -0.5f, 0.2f, 0.3f, -1f, 2f, 2f, 0.5f, 1e9f};
        for (int i = 0; i < probes.length; i += 2) {
            int idx = OpacityMicromapCache.curveIndex(probes[i], probes[i + 1], 4);
            assertTrue(idx >= 0 && idx < 256, "clamped index out of range for (" + probes[i] + "," + probes[i + 1] + ")");
        }
    }

    @Test
    public void testCurveSurjectiveAndBboxContainsSampleCell() {
        int[][] bbox = OpacityMicromapCache.regionBbox();
        assertEquals(256, bbox.length);
        boolean[] seen = new boolean[256];
        int seenCount = 0;
        for (int iv = 0; iv <= GRID; iv++) {
            for (int iu = 0; iu + iv <= GRID; iu++) {
                float u = iu / (float) GRID;
                float v = iv / (float) GRID;
                int r = OpacityMicromapCache.curveIndex(u, v, 4);
                if (!seen[r]) {
                    seen[r] = true;
                    seenCount++;
                }
                // the sample's quantized lattice cell must be inside region r's bbox (never clipped inward)
                int cellU = Math.min(iu * 16 / GRID, 16);
                int cellV = Math.min(iv * 16 / GRID, 16);
                assertTrue(cellU >= bbox[r][0] && cellU <= bbox[r][1],
                    "cell u " + cellU + " outside region " + r + " bbox [" + bbox[r][0] + "," + bbox[r][1] + "]");
                assertTrue(cellV >= bbox[r][2] && cellV <= bbox[r][3],
                    "cell v " + cellV + " outside region " + r + " bbox [" + bbox[r][2] + "," + bbox[r][3] + "]");
            }
        }
        assertEquals(256, seenCount, "curve must reach all 256 micro-triangles on the 1/1024 grid");
        for (int r = 0; r < 256; r++) {
            assertTrue(bbox[r][0] >= 0 && bbox[r][0] <= bbox[r][1] && bbox[r][1] <= 16, "u bbox of region " + r);
            assertTrue(bbox[r][2] >= 0 && bbox[r][2] <= bbox[r][3] && bbox[r][3] <= 16, "v bbox of region " + r);
        }
    }

    @Test
    public void testClassifyUniformOpaque() {
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_OPAQUE);
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        int st = classifyIdentity(texelState, payload);
        assertEquals(OpacityMicromapCache.ST_OK, st);
        for (int i = 0; i < payload.length; i++) {
            assertEquals((byte) 0x55, payload[i], "byte " + i + " (state 1 in both 2-bit slots)");
        }
    }

    @Test
    public void testClassifyUniformTransparent() {
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_TRANSPARENT);
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_OK, classifyIdentity(texelState, payload));
        for (int i = 0; i < payload.length; i++) {
            assertEquals((byte) 0x00, payload[i], "byte " + i + " (state 0 = transparent)");
        }
    }

    @Test
    public void testClassifyUniformMixed() {
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_MIXED);
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_OK, classifyIdentity(texelState, payload));
        for (int i = 0; i < payload.length; i++) {
            assertEquals((byte) 0xAA, payload[i], "byte " + i + " (state 2 = unknown in both slots)");
        }
    }

    @Test
    public void testClassifySplitHalf() {
        // texture x < 32 transparent, x >= 32 opaque; identity map so the parameter u is the texture u
        byte[] texelState = new byte[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                texelState[y * 64 + x] = x < 32 ? OpacityMicromapCache.TEXEL_TRANSPARENT : OpacityMicromapCache.TEXEL_OPAQUE;
            }
        }
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_OK, classifyIdentity(texelState, payload));
        int transparent = 0;
        int opaque = 0;
        int unknown = 0;
        for (int r = 0; r < 256; r++) {
            int state = (payload[r >> 2] >> (2 * (r & 3))) & 3;
            if (state == OpacityMicromapCache.STATE_TRANSPARENT) {
                transparent++;
            } else if (state == OpacityMicromapCache.STATE_OPAQUE) {
                opaque++;
            } else {
                unknown++;
            }
        }
        assertTrue(transparent > 0, "u≈0 footprints must classify transparent");
        assertTrue(opaque > 0, "u≈1 footprints must classify opaque");
        assertTrue(unknown > 0, "the x≈32 boundary must stay unknown");
        // region 0 covers the vertex-0 corner (u,v≈0) on the transparent side
        assertEquals(OpacityMicromapCache.STATE_TRANSPARENT, payload[0] & 3);
    }

    @Test
    public void testClassifyRetryOnUnknownTexel() {
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_OPAQUE);
        // inside region 0's footprint (the vertex-0 corner)
        texelState[2 * 64 + 2] = OpacityMicromapCache.TEXEL_UNKNOWN;
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_RETRY, classifyIdentity(texelState, payload), "unknown texel near the corner");
        // mid-atlas: inside footprints of the boundary regions
        texelState[2 * 64 + 2] = OpacityMicromapCache.TEXEL_OPAQUE;
        texelState[32 * 64 + 32] = OpacityMicromapCache.TEXEL_UNKNOWN;
        assertEquals(OpacityMicromapCache.ST_RETRY, classifyIdentity(texelState, payload), "unknown texel on the hypotenuse");
    }

    @Test
    public void testUnknownTexelOutsideAllFootprintsIsIgnored() {
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_OPAQUE);
        // the identity triangle fills u+v<=1; its footprints (with the 5-texel margin) never reach (63,63)
        texelState[63 * 64 + 63] = OpacityMicromapCache.TEXEL_UNKNOWN;
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_OK, classifyIdentity(texelState, payload));
    }

    @Test
    public void testClassifyOffsetTriangle() {
        // a UV triangle that does not start at the atlas origin (exercises the affine path)
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_OPAQUE);
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        int st = OpacityMicromapCache.classifyEntry(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f,
            texelState, new byte[64 * 64], true, 64, 64, payload);
        assertEquals(OpacityMicromapCache.ST_OK, st);
        for (int i = 0; i < payload.length; i++) {
            assertEquals((byte) 0x55, payload[i], "byte " + i);
        }
    }

    @Test
    public void testCountStatesSplitsPayload() {
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        // 4 states per byte, LSB-first: 10 opaque bytes (40), 20 transparent (80), 34 unknown (136) = 256
        java.util.Arrays.fill(payload, 0, 10, (byte) 0x55);
        java.util.Arrays.fill(payload, 10, 30, (byte) 0x00);
        java.util.Arrays.fill(payload, 30, 64, (byte) 0xAA);
        long[] out = new long[3];
        OpacityMicromapCache.countStates(payload, out);
        assertArrayEquals(new long[] {40, 80, 136}, out);
    }

    @Test
    public void testCountStatesMatchesClassifiedPayload() {
        // the split texture classifies into all three states; the counter must account for exactly 256 micro-triangles
        byte[] texelState = new byte[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                texelState[y * 64 + x] = x < 32 ? OpacityMicromapCache.TEXEL_TRANSPARENT : OpacityMicromapCache.TEXEL_OPAQUE;
            }
        }
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        assertEquals(OpacityMicromapCache.ST_OK, classifyIdentity(texelState, payload));
        long[] out = new long[3];
        OpacityMicromapCache.countStates(payload, out);
        assertEquals(OpacityMicromapCache.MICRO_TRIS, out[0] + out[1] + out[2], "each micro-triangle counted once");
        assertTrue(out[0] > 0 && out[1] > 0 && out[2] > 0, "the split texture must produce all three states");
    }

    private static byte[] filled(byte state) {
        byte[] t = new byte[64 * 64];
        java.util.Arrays.fill(t, state);
        return t;
    }

    /** classifyEntry for the identity UV triangle (0,0),(1,0),(0,1) in a 64x64 atlas. */
    @Test
    public void testTransparentNeedsEmptyNeighbourhood() {
        // fully transparent texture; one 100-alpha texel inside region 0's 5-texel neighbourhood must keep that
        // micro-triangle unknown (the far test would still average over it), distant regions stay transparent
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_TRANSPARENT);
        byte[] texelAlpha = new byte[64 * 64];
        texelAlpha[4 * 64 + 4] = 100;
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        int st = OpacityMicromapCache.classifyEntry(0f, 0f, 1f, 0f, 0f, 1f, texelState, texelAlpha, true, 64, 64, payload);
        assertEquals(OpacityMicromapCache.ST_OK, st);
        assertEquals(OpacityMicromapCache.STATE_UNKNOWN, (payload[0] >> 0) & 3, "region 0 sits next to the 100-alpha texel");
        boolean anyTransparent = false;
        for (int r = 0; r < 256; r++) {
            anyTransparent |= ((payload[r >> 2] >> (2 * (r & 3))) & 3) == OpacityMicromapCache.STATE_TRANSPARENT;
        }
        assertTrue(anyTransparent, "micro-triangles outside the touched neighbourhood still resolve transparent");
    }

    @Test
    public void testTransparentWaitsForBakeComplete() {
        // identical texture, but the bake has not settled: tier 2 must not resolve, tier 1 (opaque) still may
        byte[] texelState = filled(OpacityMicromapCache.TEXEL_TRANSPARENT);
        byte[] payload = new byte[OpacityMicromapCache.ENTRY_BYTES];
        int st = OpacityMicromapCache.classifyEntry(0f, 0f, 1f, 0f, 0f, 1f, texelState, new byte[64 * 64], false, 64, 64, payload);
        assertEquals(OpacityMicromapCache.ST_OK, st);
        for (int i = 0; i < payload.length; i++) {
            assertEquals((byte) 0xAA, payload[i], "byte " + i + " (state 2 = unknown while the bake runs)");
        }
    }

    private static int classifyIdentity(byte[] texelState, byte[] payload) {
        // zero alphas = an empty neighbourhood, bake settled: tier 2 (transparent) may resolve
        return OpacityMicromapCache.classifyEntry(0f, 0f, 1f, 0f, 0f, 1f, texelState, new byte[64 * 64], true, 64, 64, payload);
    }
}
