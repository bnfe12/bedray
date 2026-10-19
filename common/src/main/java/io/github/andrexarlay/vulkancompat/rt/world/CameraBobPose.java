package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Camera-relative pose encoded by the bob transform appended to Minecraft's projection matrix. */
public final class CameraBobPose {
    private CameraBobPose() {
    }

    /**
     * Returns the effective camera origin in coordinates relative to the unbobbed camera. Minecraft renders with
     * {@code fullProjection = baseProjection * bobView}, followed by {@code viewRotation}; the center of that view is
     * therefore {@code inverse(bobView * viewRotation) * (0, 0, 0, 1)}.
     */
    public static Vector3f originOffset(Matrix4f fullProjection, Matrix4f baseProjection, Matrix4f viewRotation, Vector3f dest) {
        if (fullProjection == null || baseProjection == null || viewRotation == null) {
            return dest.zero();
        }
        Matrix4f worldToView = effectiveWorldToView(fullProjection, baseProjection, viewRotation, new Matrix4f());
        Vector4f origin = worldToView.invert().transform(new Vector4f(0f, 0f, 0f, 1f));
        if (!Float.isFinite(origin.w) || Math.abs(origin.w) < 1.0e-8f) {
            return dest.zero();
        }
        return dest.set(origin.x / origin.w, origin.y / origin.w, origin.z / origin.w);
    }

    /**
     * Returns the full current-camera world-to-view transform while keeping the base projection separate.
     * Minecraft's RT ray projection is {@code fullProjection = baseProjection * bobView}; NRD must receive
     * {@code viewToClip = baseProjection} and {@code worldToView = bobView * rawViewRotation} so their product
     * describes the exact same camera pose as the rays.
     */
    public static Matrix4f effectiveWorldToView(Matrix4f fullProjection, Matrix4f baseProjection,
                                                Matrix4f viewRotation, Matrix4f dest) {
        if (fullProjection == null || baseProjection == null || viewRotation == null) {
            return dest.set(viewRotation == null ? new Matrix4f() : viewRotation);
        }
        return dest.set(baseProjection).invert().mul(fullProjection).mul(viewRotation);
    }

    /** World-space camera-forward axis of the raw (unbobbed) view rotation, used by NRD's matching IN_VIEWZ guide. */
    public static Vector3f rawForward(Matrix4f viewRotation, Vector3f dest) {
        if (viewRotation == null) {
            return dest.set(0f, 0f, -1f);
        }
        Vector4f forward = new Matrix4f(viewRotation).invert().transform(new Vector4f(0f, 0f, -1f, 0f));
        if (!Float.isFinite(forward.x) || !Float.isFinite(forward.y) || !Float.isFinite(forward.z)) {
            return dest.set(0f, 0f, -1f);
        }
        return dest.set(forward.x, forward.y, forward.z).normalize();
    }
}
