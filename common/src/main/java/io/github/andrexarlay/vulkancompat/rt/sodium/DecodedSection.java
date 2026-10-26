package io.github.andrexarlay.vulkancompat.rt.sodium;

/**
 * CPU-side decoded geometry of one Sodium render section (R1a: no GPU objects).
 *
 * <p>Quads of all passes are stored back to back in pass order SOLID (0), CUTOUT (1), TRANSLUCENT (2).
 * {@link #passQuadStart(int)} gives the first quad of a pass. Per quad (4 vertices, Sodium winding order):
 * <ul>
 *   <li>{@code positions}: 12 floats (x,y,z per vertex), section-local blocks, nominal range [0,16];</li>
 *   <li>{@code uvs}: 8 floats (u,v per vertex), block atlas, without Sodium's +/- shrink bias;</li>
 *   <li>{@code colors}: 4 ints, the raw color word as stored in the vertex: R = bits 0-7, G = 8-15, B = 16-23,
 *       A = 24-31 (Sodium writes ABGR, see {@link SodiumMeshDecoder});</li>
 *   <li>{@code light}: 4 ints, {@code blockLight | skyLight << 8 | material << 16} (raw encoded bytes, see decoder);</li>
 *   <li>{@code normals}: 3 floats per quad, unit length, geometric (right-hand winding).</li>
 * </ul>
 * Triangles are derived on demand with {@link SodiumMeshDecoder#fillTriangleIndices}.
 * Instances are immutable after construction (arrays must not be modified by consumers).
 */
public final class DecodedSection {
    public static final int PASS_SOLID = 0;
    public static final int PASS_CUTOUT = 1;
    public static final int PASS_TRANSLUCENT = 2;
    public static final int PASS_COUNT = 3;

    public final long key;
    public final int sectionX;
    public final int sectionY;
    public final int sectionZ;
    /** Quads per pass, length {@link #PASS_COUNT}. */
    private final int[] passQuads;
    public final float[] positions;
    public final float[] uvs;
    public final int[] colors;
    public final int[] light;
    public final float[] normals;
    /** Minimum / maximum decoded position over all vertices (section-local), or zeros when empty. */
    public final float[] posMin;
    public final float[] posMax;
    /**
     * Raw Sodium vertex words as captured, 5 ints per vertex in vertex order (w0..w4), quads back to back. The
     * region-local section index byte of w4 (bits 24-31) is pre-masked to 0, so raw-word equality is exactly
     * equivalent to comparing {@code positions}, {@code uvs}, {@code colors} and {@code light}: the section
     * identity test can use this array alone, and the GPU dequantize dispatch consumes it verbatim.
     */
    public final int[] rawWords;

    DecodedSection(long key, int sectionX, int sectionY, int sectionZ, int[] passQuads,
                   float[] positions, float[] uvs, int[] colors, int[] light, float[] normals,
                   float[] posMin, float[] posMax, int[] rawWords) {
        this.key = key;
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.passQuads = passQuads;
        this.positions = positions;
        this.uvs = uvs;
        this.colors = colors;
        this.light = light;
        this.normals = normals;
        this.posMin = posMin;
        this.posMax = posMax;
        this.rawWords = rawWords;
    }

    public int quadsInPass(int pass) {
        return passQuads[pass];
    }

    public int passQuadStart(int pass) {
        int start = 0;
        for (int i = 0; i < pass; i++) {
            start += passQuads[i];
        }
        return start;
    }

    public int totalQuads() {
        return passQuads[0] + passQuads[1] + passQuads[2];
    }

    public boolean isEmpty() {
        return totalQuads() == 0;
    }
}
