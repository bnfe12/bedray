package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrisGeometrySourceTest {

    @Test
    void terrainQuadMapsToIrisAttributes() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] colors = {0x800000FF, 0x800000FF, 0x800000FF, 0x800000FF}; // r255 g0 b0 a128
        int[] light = {10 | (20 << 8), 10 | (20 << 8), 10 | (20 << 8), 10 | (20 << 8)};
        float[] normals = {0, 0, 1};
        IrisGeometrySource.Mesh m = IrisGeometrySource.buildTerrain(positions, uvs, colors, light, normals, 0, 1, 16, 0, 0);
        assertEquals(29, m.strideFloats());
        assertEquals(4 * 29, m.vertices().length);
        assertEquals(6, m.indices().length);
        float[] v = m.vertices();
        assertEquals(16f, v[0]);
        assertEquals(0f, v[1]);
        assertEquals(1f, v[3]);          // r
        assertEquals(0f, v[4]);          // g
        assertEquals(128f / 255f, v[6], 1e-6); // a
        assertEquals(0f, v[7]);          // uv0 u
        assertEquals(10f / 255f, v[9], 1e-6);  // uv1 block
        assertEquals(20f / 255f, v[10], 1e-6); // uv1 sky
        assertEquals(0f, v[11]);         // normal x
        assertEquals(1f, v[13]);         // normal z
        assertEquals(0.5f, v[14], 1e-6); // midBlock x
        assertEquals(0f, v[17]);         // mc_Entity x (block id not captured)
        assertEquals(0f, v[21]);         // mc_midTexCoord u (approximates uv0)
        assertEquals(1f, v[25]);         // at_tangent x (default +X)
        assertEquals(1f, v[28]);         // at_tangent w (sign)
        assertEquals(0, m.indices()[0]);
        assertEquals(1, m.indices()[1]);
        assertEquals(2, m.indices()[2]);
        assertEquals(0, m.indices()[3]);
        assertEquals(2, m.indices()[4]);
        assertEquals(3, m.indices()[5]);
    }

    @Test
    void entityInterleavedMapsToIrisAttributes() {
        int colorBits = 0x800000FF;
        int lightBits = 1000 | (2000 << 16);
        float[] inter = {1, 2, 3, Float.intBitsToFloat(colorBits), 0.5f, 0.5f, Float.intBitsToFloat(lightBits), 0, 1, 0};
        IrisGeometrySource.Mesh m = IrisGeometrySource.buildEntities(inter, new int[] {0});
        assertEquals(29, m.strideFloats());
        assertEquals(29, m.vertices().length);
        float[] v = m.vertices();
        assertEquals(1f, v[0]);
        assertEquals(1f, v[3]);
        assertEquals(128f / 255f, v[6], 1e-6);
        assertEquals(0.5f, v[7]);
        assertEquals(1000f / 65535f, v[9], 1e-6);
        assertEquals(2000f / 65535f, v[10], 1e-6);
        assertEquals(1f, v[12]); // normal y
    }

    @Test
    void emptyQuadRangeYieldsEmptyMesh() {
        IrisGeometrySource.Mesh m = IrisGeometrySource.buildTerrain(new float[0], new float[0], new int[0], new int[0], new float[0], 0, 0, 0, 0, 0);
        assertEquals(0, m.vertices().length);
        assertEquals(0, m.indices().length);
    }
}