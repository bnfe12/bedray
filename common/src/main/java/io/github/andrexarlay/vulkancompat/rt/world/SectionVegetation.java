package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;

/**
 * Per-quad foliage classification for the cutout pass of a decoded section. The surface layer gates leaf transmission on
 * {@link #VEGETATION_BIT} and the one-block shadow-caster step on {@link #LEAF_CUBE_BIT}; both are derived here from the
 * block state, never from the cutout pass alone (bars, fences, panes, ladders, rails and doors are cutout but not foliage).
 *
 * <p>{@link #classify} is the single classification loop: the staging path ({@code RtWorld.quadFlags}) writes its bits
 * straight into the reused GPU scratch, and {@link #tag} ORs them into a copy of the light words for the word-based
 * contract the tests pin. The bits live in byte 3 of a Sodium light word, which the decoder drops, so the vanilla light
 * is never touched.
 */
final class SectionVegetation {
    /**
     * Bit 24 of a vertex light word: the cutout quad belongs to a plant or a leaf (GLSL {@code VERT_FLAG_VEGETATION}).
     * Byte 3 of a Sodium light word holds the region-local section index, which the decoder drops, so the byte is free.
     */
    static final int VEGETATION_BIT = 1 << 24;
    /**
     * Bit 25: the vegetation quad is a face of a full leaf cube, not a thin card (GLSL {@code VERT_FLAG_LEAF_CUBE}). Only a
     * cube needs the one-block shadow-ray step along its normal; a card, a vine or a pane is cleared by 1 cm.
     */
    static final int LEAF_CUBE_BIT = 1 << 25;
    /** {@link RtWorld.BlockLookup} answers: not vegetation, a thin plant card, or a face of a full leaf cube. */
    static final int FOLIAGE_NONE = 0;
    static final int FOLIAGE_CARD = 1;
    static final int FOLIAGE_CUBE = 2;

    private SectionVegetation() {}

    /**
     * Writes {@code bitsOut[q] = VEGETATION_BIT (| LEAF_CUBE_BIT)} for every cutout quad whose block is a plant or a leaf,
     * and returns the number of tagged quads. {@code bitsOut} must be zeroed over {@code [0, totalQuads())} by the caller:
     * only the cutout range is visited, so the solid-pass entries keep the zero the staging path filled.
     *
     * <p>The block of a quad is the quad centre stepped 1 cm against its geometric normal: a full-block face (leaves) has
     * its centre exactly on a cell boundary, where a plain floor() could resolve to the neighbouring cell.
     */
    static int classify(DecodedSection d, RtWorld.BlockLookup lookup, int[] bitsOut) {
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
            bitsOut[q] = bits;
            tagged++;
        }
        return tagged;
    }

    /**
     * Copies the light words of the section into {@code out} and ORs the foliage bits onto the four vertices of each tagged
     * cutout quad. Returns the number of tagged quads. The decoded section is shared with the registry and is never modified.
     */
    static int tag(DecodedSection d, int[] out, RtWorld.BlockLookup lookup) {
        int words = d.totalQuads() * 4;
        if (out.length < words) {
            throw new IllegalArgumentException("out too small: " + out.length + " < " + words);
        }
        System.arraycopy(d.light, 0, out, 0, words);
        int[] bits = new int[d.totalQuads()];
        int tagged = classify(d, lookup, bits);
        int cutout = d.quadsInPass(DecodedSection.PASS_CUTOUT);
        int first = d.passQuadStart(DecodedSection.PASS_CUTOUT);
        for (int q = first; q < first + cutout; q++) {
            int b = bits[q];
            if (b == 0) {
                continue;
            }
            int v = q * 4;
            out[v] |= b;
            out[v + 1] |= b;
            out[v + 2] |= b;
            out[v + 3] |= b;
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
}