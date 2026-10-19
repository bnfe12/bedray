package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;

/**
 * Pure math of the camera-relative scheme (unit-tested without a GPU).
 *
 * <p>Minecraft renders camera-relative: the view matrix is rotation only and positions are world minus camera. BLAS
 * vertices are section-local ([0,16] blocks); an instance transform moves them into that camera-relative space:
 * {@code translate(sectionOrigin - cameraPos)}. The subtraction is done in double and only then rounded to float.
 */
public final class InstanceMath {
    private InstanceMath() {
    }

    /**
     * Writes the row-major 3x4 transform of VkTransformMatrixKHR (12 floats): identity rotation, translation in
     * elements 3, 7, 11.
     */
    public static void sectionTransform(int sectionX, int sectionY, int sectionZ,
                                        double camX, double camY, double camZ, float[] out12) {
        out12[0] = 1f; out12[1] = 0f; out12[2] = 0f;
        out12[3] = (float) (sectionX * 16.0 - camX);
        out12[4] = 0f; out12[5] = 1f; out12[6] = 0f;
        out12[7] = (float) (sectionY * 16.0 - camY);
        out12[8] = 0f; out12[9] = 0f; out12[10] = 1f;
        out12[11] = (float) (sectionZ * 16.0 - camZ);
    }

    /** invViewProj = (projection * viewRotation)^-1, the matrix the primary-ray shader unprojects with. */
    public static Matrix4f inverseViewProjection(Matrix4f projection, Matrix4f viewRotation, Matrix4f dest) {
        return new Matrix4f(projection).mul(viewRotation).invert(dest);
    }

    /** Chebyshev-style radius test: |dx|,|dz| <= radiusXZ and |dy| <= radiusY (section units). */
    public static boolean inRadius(int sx, int sy, int sz, int camSx, int camSy, int camSz, int radiusXZ, int radiusY) {
        return Math.abs(sx - camSx) <= radiusXZ && Math.abs(sz - camSz) <= radiusXZ && Math.abs(sy - camSy) <= radiusY;
    }

    /** Squared distance between two sections in section units (build queue ordering). */
    public static long distanceSq(int sx, int sy, int sz, int camSx, int camSy, int camSz) {
        long dx = sx - camSx;
        long dy = sy - camSy;
        long dz = sz - camSz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Section coordinate of a world coordinate (floor division by 16). */
    public static int sectionCoord(double world) {
        return (int) Math.floor(world / 16.0);
    }
}
