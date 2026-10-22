package io.github.andrexarlay.bedray.api.v1;

/** Affine normalized block-atlas UVs and uniform opaque sRGB tint (0xAARRGGBB).
 * UV = origin + localU * du + localV * dv. Face axes use positive world coordinates:
 * DOWN/UP=(x,z), NORTH/SOUTH=(x,y), WEST/EAST=(z,y), each within the unit cube.
 */
public record CubeFaceMaterial(float u, float v, float duU, float duV, float dvU, float dvV,
                               int tintArgb) {
    public CubeFaceMaterial {
        check(u, v);
        check(u + duU, v + duV);
        check(u + dvU, v + dvV);
        check(u + duU + dvU, v + duV + dvV);
        if ((tintArgb >>> 24) != 255) throw new IllegalArgumentException("Cube tint must be opaque ARGB");
    }

    private static void check(float u, float v) {
        if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
            throw new IllegalArgumentException("Invalid normalized cube UV");
    }
}
