package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtEmitters;

/** Builds a tinted entity-atlas quad emitter from the four finalized vertices of a particle/text quad. */
public final class QuadEmitterBuilder {
    private QuadEmitterBuilder() {}

    /**
     * Writes one two-sided NEE record. Vertex colors are packed ARGB and averaged because vanilla particle quads use a
     * constant tint per quad; alpha remains a radiance multiplier so translucent particles emit proportionally less.
     */
    public static boolean build(float[] record, int atlasSlot, float[] positions, float[] uvs, int[] argb,
                                RtEmitters.TexelEmission texels) {
        if (record == null || record.length < RtEmitters.FLOATS || atlasSlot <= 0 || positions == null || positions.length < 12
            || uvs == null || uvs.length < 8 || argb == null || argb.length < 4 || texels == null) {
            return false;
        }
        int a = 0, r = 0, g = 0, b = 0;
        for (int color : argb) {
            a += color >>> 24;
            r += (color >>> 16) & 255;
            g += (color >>> 8) & 255;
            b += color & 255;
        }
        a = (a + 2) / 4;
        if (a == 0 || !RtEmitters.buildQuadRecord(record, 0, positions, 0, uvs, 0, texels, true)) {
            return false;
        }
        record[7] *= 2.4f;
        record[11] = 1.0f;
        record[15] = atlasSlot + 1;
        int tint = RtEmitters.TINT_OFFSET;
        record[tint] = ((r + 2) / 4) / 255f;
        record[tint + 1] = ((g + 2) / 4) / 255f;
        record[tint + 2] = ((b + 2) / 4) / 255f;
        record[tint + 3] = a / 255f;
        return true;
    }
}
