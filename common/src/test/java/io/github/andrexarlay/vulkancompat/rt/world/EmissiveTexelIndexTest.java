package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The O(1) rejection must not change a single extracted emitter record. */
class EmissiveTexelIndexTest {

    // Material atlas words store the LabPBR source flags inverted in the alpha byte: 0xFF means "no flags".
    private static final int NO_FLAGS = 0xFF000000;

    private static int emissive(int value) {
        return NO_FLAGS | (value & 255) << 16;
    }

    @Test
    void extractionWithTheIndexEqualsTheFullScan() {
        Random random = new Random(42);
        int w = 128, h = 64;
        int[] material = new int[w * h];
        Arrays.fill(material, NO_FLAGS);
        for (int i = 0; i < 40; i++) {
            material[random.nextInt(w * h)] = emissive(2 + random.nextInt(254));
        }
        material[5 * w + 7] = emissive(1); // at the threshold: not an emitter texel (1/255 <= MIN_EMISSION)
        int quads = 3000;
        float[] positions = new float[quads * 12];
        float[] uvs = new float[quads * 8];
        for (int q = 0; q < quads; q++) {
            float x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
            float[] p = {x, y, z, x + 1, y, z, x + 1, y + 1, z, x, y + 1, z};
            System.arraycopy(p, 0, positions, q * 12, 12);
            float u0 = random.nextInt(w - 16) / (float) w, v0 = random.nextInt(h - 16) / (float) h;
            float du = (1 + random.nextInt(16)) / (float) w, dv = (1 + random.nextInt(16)) / (float) h;
            float[] uv = {u0, v0, u0 + du, v0, u0 + du, v0 + dv, u0, v0 + dv};
            System.arraycopy(uv, 0, uvs, q * 8, 8);
        }
        float[] withIndex = RtEmitters.extractQuads(positions, uvs, quads, material, w, h);

        RtEmitters.TexelEmission tex = new RtEmitters.TexelEmission() {
            public int width() { return w; }
            public int height() { return h; }
            public float at(int x, int y) { return ((material[y * w + x] >>> 16) & 255) / 255f; }
        };
        float[] reference = new float[quads * RtEmitters.FLOATS];
        int n = 0;
        for (int q = 0; q < quads; q++) {
            if (RtEmitters.buildQuadRecord(reference, n * RtEmitters.FLOATS, positions, q * 12, uvs, q * 8, tex, false)) n++;
        }
        assertTrue(n > 0, "the test atlas must produce some emitters");
        assertArrayEquals(Arrays.copyOf(reference, n * RtEmitters.FLOATS), withIndex, 0f);
    }

    @Test
    void anAtlasFilledInPlaceIsReindexedWhenItsContentVersionMoves() {
        // 2026-11-08 regression: the live material mirror is filled in place while sprites bake. An index cached by array
        // identity was built over the empty atlas and the game ran with 0 emitters instead of 1313.
        int w = 32, h = 16;
        int[] material = new int[w * h];
        Arrays.fill(material, NO_FLAGS);
        EmissiveTexelIndex empty = EmissiveTexelIndex.of(material, w, h, 1L);
        assertFalse(empty.anyCandidate(0, 0, w, h));
        material[2 * w + 9] = emissive(180);  // a sprite lands in the same array
        assertTrue(EmissiveTexelIndex.of(material, w, h, 2L).anyCandidate(0, 0, w, h),
            "a new content version must rebuild the index");
        assertTrue(EmissiveTexelIndex.of(material, w, h, 2L) == EmissiveTexelIndex.of(material, w, h, 2L),
            "the same version reuses the cached index");
    }

    @Test
    void theLiveExtractionKeysOnTheAtlasContentVersion() throws java.io.IOException {
        String emitters = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtEmitters.java"));
        assertTrue(emitters.contains("RtMaterialAtlas.cpuMaterialVersion();"), "extract() passes the mirror's content version");
        String atlas = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/rt/material/RtMaterialAtlas.java"));
        int bumps = atlas.split("CPU_MATERIAL_VERSION\\.incrementAndGet\\(\\);", -1).length - 1;
        assertTrue(bumps >= 3, "allocation, re-allocation and every mirrored sprite batch bump the version: " + bumps);
    }

    @Test
    void rectangleQueriesCountCandidatesExactly() {
        int w = 16, h = 8;
        int[] material = new int[w * h];
        Arrays.fill(material, NO_FLAGS);
        material[3 * w + 4] = emissive(200);
        EmissiveTexelIndex index = EmissiveTexelIndex.build(material, w, h);
        assertTrue(index.anyCandidate(4, 3, 5, 4));
        assertTrue(index.anyCandidate(0, 0, w, h));
        assertFalse(index.anyCandidate(5, 3, w, h));
        assertFalse(index.anyCandidate(0, 4, w, h));
        assertFalse(EmissiveTexelIndex.isCandidate(emissive(1)), "byte 1 is 0.0039, below MIN_EMISSION 0.005");
        assertTrue(EmissiveTexelIndex.isCandidate(emissive(2)));
    }
}
