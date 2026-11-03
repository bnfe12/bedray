package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.CameraMatrixGuard;
import org.joml.Matrix4f;

/** RR's linear depth and reflection reconstruction need the actual bobbed view, plus a pure projection. */
final class RrCameraMatrices {
    private RrCameraMatrices() {}

    static Matrix4f modelView(Matrix4f baseProjection, Matrix4f fullProjection, Matrix4f viewRotation) {
        if (!CameraMatrixGuard.isUsable(baseProjection) || !CameraMatrixGuard.isUsable(fullProjection)
                || !CameraMatrixGuard.isUsable(viewRotation)) return null;
        Matrix4f result = new Matrix4f(baseProjection).invert().mul(fullProjection).mul(viewRotation);
        return CameraMatrixGuard.isUsable(result) ? result : null;
    }
}
