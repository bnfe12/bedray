package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Immutable cell-local baked quad. Four vertices contain x,y,z,u,v in source submission order.
 * GPU ABI: 20 little-endian floats followed by tintArgb, flags, emission and reserved zero (96 bytes).
 * Triangles are (0,1,2) and (2,3,0). Later alpha-accepted coplanar quads take precedence.
 */
public final class ModelQuad {
    public static final int CUTOUT = 1, TRANSLUCENT = 2, BYTES = 96;
    private final float[] vertices;
    private final int tintArgb, flags, emission;

    public ModelQuad(float[] vertices, int tintArgb, int flags, int emission) {
        if (vertices.length != 20 || (flags & ~3) != 0 || emission < 0 || emission > 15)
            throw new IllegalArgumentException("Invalid model quad");
        this.vertices = vertices.clone();
        for (int i = 0; i < 20; i++) {
            float value = this.vertices[i];
            if (!Float.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException("Quad positions and atlas UVs must be in [0,1]");
        }
        this.tintArgb = tintArgb;
        this.flags = flags;
        this.emission = emission;
    }
    public float component(int index) { return vertices[index]; }
    public int tintArgb() { return tintArgb; }
    public int flags() { return flags; }
    public int emission() { return emission; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof ModelQuad that && tintArgb == that.tintArgb
            && flags == that.flags && emission == that.emission && Arrays.equals(vertices, that.vertices);
    }
    @Override public int hashCode() { return 31 * (31 * (31 * Arrays.hashCode(vertices) + tintArgb) + flags) + emission; }
}
