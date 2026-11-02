package io.github.andrexarlay.vulkancompat.rtx;

import org.joml.Matrix4f;

/**
 * Validation of the camera matrices the velocity pass and the temporal upscalers consume.
 * <p>
 * The first RT frame can run before Minecraft has written a camera orientation: the view rotation is then built from a
 * zero quaternion, which is {@code diag(0, 0, 0, 1)}. Multiplying it by the projection gives a singular view-projection
 * matrix and {@link Matrix4f#invert(Matrix4f)} fills every element with NaN (probe: {@code invViewProj.m00 = NaN}).
 * The native velocity contract then rejects the frame and the upscaler is disabled for the rest of the session.
 * A singular projection (zero-size viewport, {@code zFar == zNear}) and a non-finite one behave the same way.
 * <p>
 * The guard refuses such a frame before the native call and leaves the previous matrices untouched, so the caller can
 * skip one temporal frame, force a history reset and keep the upscaler alive.
 */
public final class CameraMatrixGuard {
    /** Smallest |det| accepted for a projection, a rotation or their product. */
    public static final float MIN_ABS_DETERMINANT = 1.0e-12f;

    private CameraMatrixGuard() {
    }

    /** True when every element is finite and the matrix is not singular. */
    public static boolean isUsable(Matrix4f matrix) {
        return matrix != null && matrix.isFinite() && Math.abs(matrix.determinant()) > MIN_ABS_DETERMINANT;
    }

    /**
     * Writes {@code dest = projection * viewRotation} and returns true, or leaves {@code dest} untouched and returns
     * false when an input or the product is unusable.
     */
    public static boolean multiply(Matrix4f projection, Matrix4f viewRotation, Matrix4f dest) {
        if (!isUsable(projection) || !isUsable(viewRotation)) {
            return false;
        }
        Matrix4f product = new Matrix4f(projection).mul(viewRotation);
        if (!isUsable(product)) {
            return false;
        }
        dest.set(product);
        return true;
    }

    /** Writes {@code dest = inverse(viewProj)} and returns true, or leaves {@code dest} untouched on failure. */
    public static boolean invert(Matrix4f viewProj, Matrix4f dest) {
        if (!isUsable(viewProj)) {
            return false;
        }
        Matrix4f inverse = new Matrix4f(viewProj).invert();
        if (!inverse.isFinite()) {
            return false;
        }
        dest.set(inverse);
        return true;
    }

    /** One frame's history decision. */
    public record Update(boolean usable, boolean resetHistory) {
    }

    /**
     * Computes this frame's inverse view-projection and the previous frame's view-projection.
     * <p>
     * When the current camera is degenerate the outputs keep the last usable values and {@code usable == false}; the
     * caller must skip the temporal frame and reset history. A degenerate previous matrix also forces
     * {@code resetHistory} in the returned decision.
     */
    public static Update update(Matrix4f currProj, Matrix4f currViewRotation,
                                Matrix4f prevProj, Matrix4f prevViewRotation,
                                boolean resetHistory, Matrix4f invViewProjOut, Matrix4f prevViewProjOut) {
        Matrix4f currViewProj = new Matrix4f();
        if (!multiply(currProj, currViewRotation, currViewProj)) {
            return new Update(false, true);
        }
        if (!invert(currViewProj, invViewProjOut)) {
            return new Update(false, true);
        }
        boolean reset = resetHistory;
        if (!reset && prevProj != null && prevViewRotation != null) {
            Matrix4f prevViewProj = new Matrix4f();
            if (multiply(prevProj, prevViewRotation, prevViewProj)) {
                prevViewProjOut.set(prevViewProj);
            } else {
                prevViewProjOut.set(currViewProj);
                reset = true;
            }
        } else {
            prevViewProjOut.set(currViewProj);
        }
        return new Update(true, reset);
    }

    /** Names the degenerate input in one line for the log. */
    public static String describe(Matrix4f proj, Matrix4f viewRotation, Matrix4f viewProj) {
        return "proj=" + describeOne(proj) + " view_rotation=" + describeOne(viewRotation)
            + " view_proj=" + describeOne(viewProj);
    }

    private static String describeOne(Matrix4f matrix) {
        if (matrix == null) {
            return "missing";
        }
        return "finite=" + matrix.isFinite() + ",det=" + matrix.determinant();
    }
}
