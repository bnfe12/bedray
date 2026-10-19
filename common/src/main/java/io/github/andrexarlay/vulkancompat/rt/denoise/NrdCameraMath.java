package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.joml.Matrix4f;

/**
 * Pure camera math of the NRD frame settings (unit-tested, no GPU).
 *
 * <p>Conventions (from NRD 4.17/4.18 Include/NRDSettings.h CommonSettings and README "Matrices"): column-major, vector is a column,
 * matrices WITHOUT jitter; {@code worldToViewMatrixPrev} must include the camera delta when the coordinate system moves with the
 * camera. Minecraft renders camera-relative (view matrix = rotation only, geometry = world - camera), so
 * {@code worldToViewPrev = prevRotation * translate(cameraNow - cameraPrev)}: a point P (relative to the current camera) is
 * P + (camNow - camPrev) relative to the previous one.
 *
 * <p>Y flip: the RT shader unprojects with ndc.y = -1 on image row 0, i.e. the engine projection is Vulkan style (y down).
 * NRD derives pixel positions as uv.y = 0.5 - 0.5 * ndc.y (D3D style, same as its sample's "unflippedProj"), so the projection
 * handed to NRD gets its clip-space Y negated (row 1 of the matrix). If the picture shows ghosting that depends on the vertical
 * motion, check this assumption first ({@code -Dvkrtx.nrd.projFlipY=false} switches it off for the experiment).
 */
public final class NrdCameraMath {
    private NrdCameraMath() {
    }

    /** {@code dest = diag(1,-1,1,1) * proj}: negates the clip-space Y row. {@code dest} may be {@code proj}. */
    public static Matrix4f negateClipY(Matrix4f proj, Matrix4f dest) {
        return dest.set(proj)
            .m01(-proj.m01()).m11(-proj.m11()).m21(-proj.m21()).m31(-proj.m31());
    }

    /**
     * World-to-view of the PREVIOUS frame in the CURRENT camera-relative coordinates:
     * {@code prevRotation * translate(cameraNow - cameraPrev)}, the subtraction in double.
     */
    public static Matrix4f previousWorldToView(Matrix4f prevRotation, double prevX, double prevY, double prevZ,
                                               double nowX, double nowY, double nowZ, Matrix4f dest) {
        return dest.set(prevRotation).translate((float) (nowX - prevX), (float) (nowY - prevY), (float) (nowZ - prevZ));
    }

    /** True when the camera moved farther than {@code thresholdBlocks} between two frames (teleport, respawn: history is useless). */
    public static boolean isTeleport(double prevX, double prevY, double prevZ, double nowX, double nowY, double nowZ, double thresholdBlocks) {
        double dx = nowX - prevX;
        double dy = nowY - prevY;
        double dz = nowZ - prevZ;
        return dx * dx + dy * dy + dz * dz > thresholdBlocks * thresholdBlocks;
    }

    /** Projects a view-space point to pixel coordinates (top-left origin, pixel centres at +0.5) with an NRD-style (y up) projection. */
    public static float[] projectToPixel(Matrix4f nrdProjection, float vx, float vy, float vz, int width, int height) {
        float cx = nrdProjection.m00() * vx + nrdProjection.m10() * vy + nrdProjection.m20() * vz + nrdProjection.m30();
        float cy = nrdProjection.m01() * vx + nrdProjection.m11() * vy + nrdProjection.m21() * vz + nrdProjection.m31();
        float cw = nrdProjection.m03() * vx + nrdProjection.m13() * vy + nrdProjection.m23() * vz + nrdProjection.m33();
        float ndcX = cx / cw;
        float ndcY = cy / cw;
        return new float[] {(ndcX * 0.5f + 0.5f) * width, (0.5f - ndcY * 0.5f) * height};
    }
}
