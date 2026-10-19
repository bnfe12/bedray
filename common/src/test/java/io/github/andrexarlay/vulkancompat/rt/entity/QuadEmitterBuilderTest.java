package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtEmitters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuadEmitterBuilderTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void buildsTintedEmitterAndPreservesParticleAlpha() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] argb = {0x80FF8000, 0x80FF8000, 0x80FF8000, 0x80FF8000};
        RtEmitters.TexelEmission texels = solidEmission();
        float[] record = new float[RtEmitters.FLOATS];

        assertTrue(QuadEmitterBuilder.build(record, 3, positions, uvs, argb, texels));
        assertEquals(4f, record[15], EPSILON, "entity atlas slot is stored as slot + 1");
        assertEquals(1f, record[11], EPSILON, "particle quads are two-sided light sources");
        assertEquals(1f, record[RtEmitters.TINT_OFFSET], EPSILON);
        assertEquals(128f / 255f, record[RtEmitters.TINT_OFFSET + 1], EPSILON);
        assertEquals(0f, record[RtEmitters.TINT_OFFSET + 2], EPSILON);
        assertEquals(128f / 255f, record[RtEmitters.TINT_OFFSET + 3], EPSILON,
            "partial particle alpha must scale emitted radiance");
    }

    @Test
    void rejectsFullyTransparentParticleQuad() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] argb = {0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x00FFFFFF};
        float[] record = new float[RtEmitters.FLOATS];

        assertFalse(QuadEmitterBuilder.build(record, 3, positions, uvs, argb, solidEmission()));
    }

    private static RtEmitters.TexelEmission solidEmission() {
        return new RtEmitters.TexelEmission() {
            @Override public int width() { return 4; }
            @Override public int height() { return 4; }
            @Override public float at(int x, int y) { return 1f; }
        };
    }
}
