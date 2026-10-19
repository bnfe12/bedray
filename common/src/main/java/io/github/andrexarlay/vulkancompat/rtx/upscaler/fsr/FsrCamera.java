package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * Camera values FSR wants in {@code ffxDispatchDescUpscale} (cameraNear, cameraFar, cameraFovAngleVertical), derived from the
 * unjittered world projection the engine used last ({@link WorldTargetManager#currentProjection()}); nothing is guessed from
 * game options.
 * <p>
 * For a perspective matrix (JOML column-major, clip.w = -z_view): {@code |m11| = 1/tan(fovY/2)} (sign = y flip), depth at view distance d is
 * {@code (m32 - m22*d)/d = m32/d - m22}. Depth 1 is at {@code d1 = m32/(1+m22)}, depth 0 at {@code d0 = m32/m22}
 * ({@code m22 == 0}: depth 0 at infinity). Near = min(d0, d1), far = max(d0, d1); with reverse-Z (Minecraft 26.3 Vulkan,
 * see velocity_reproject.comp) near is d1 and far is d0. When the matrix is missing or not a plausible perspective,
 * {@link #FALLBACK} is used and marked as such in the log.
 */
public record FsrCamera(float near, float far, float fovY, boolean infiniteFar, boolean derived) {
    /** Used only when no projection was captured yet: marked derived=false in every log line. */
    public static final FsrCamera FALLBACK = new FsrCamera(0.05f, 1024.0f, (float) Math.toRadians(70.0), false, false);

    public static FsrCamera fromCurrentProjection() {
        return fromProjection(WorldTargetManager.currentProjection());
    }

    public static FsrCamera fromProjection(Matrix4f p) {
        if (p == null || p.m23() > -0.5f || p.m11() == 0.0f || p.m32() == 0.0f) {
            return FALLBACK;
        }
        float fov = (float) (2.0 * Math.atan(1.0 / Math.abs(p.m11())));
        float m22 = p.m22();
        float m32 = p.m32();
        // A plane "behind the camera" (negative distance, e.g. JOML's epsilon-infinite reverse-Z with m22 = -1e-6) means that
        // depth value is never reached in front of the camera: that plane is at infinity.
        double d1 = Math.abs(1.0 + m22) < 1e-9 ? Double.POSITIVE_INFINITY : m32 / (1.0 + m22);
        double d0 = Math.abs(m22) < 1e-9 ? Double.POSITIVE_INFINITY : m32 / m22;
        if (d1 <= 0.0) {
            d1 = Double.POSITIVE_INFINITY;
        }
        if (d0 <= 0.0) {
            d0 = Double.POSITIVE_INFINITY;
        }
        double near = Math.min(d0, d1);
        double far = Math.max(d0, d1);
        if (!(near > 0.0) || Double.isInfinite(near) || Double.isNaN(far)) {
            return FALLBACK;
        }
        boolean infinite = Double.isInfinite(far);
        // FSR expects a finite float; with an infinite far plane the flag DEPTH_INFINITE tells it to ignore cameraFar.
        return new FsrCamera((float) near, infinite ? Float.MAX_VALUE : (float) far, fov, infinite, true);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "near=%.4f far=%s fovY=%.2fdeg derived=%s", near,
            infiniteFar ? "inf" : String.format(Locale.ROOT, "%.1f", far), Math.toDegrees(fovY), derived);
    }
}
