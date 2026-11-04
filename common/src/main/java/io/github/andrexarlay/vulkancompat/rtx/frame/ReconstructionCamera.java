package io.github.andrexarlay.vulkancompat.rtx.frame;

import io.github.andrexarlay.vulkancompat.rtx.CameraMatrixGuard;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Immutable SDK camera. The view transforms positions relative to the raw double-precision anchor. */
public final class ReconstructionCamera {
    private final Matrix4f projection, view, inverseView, viewProjection;
    private final double x, y, z;
    private final float jitterX, jitterY;
    private final boolean reset;

    public ReconstructionCamera(Matrix4f projection, Matrix4f view, double x, double y, double z,
                                float jitterX, float jitterY, boolean reset) {
        if (!CameraMatrixGuard.isUsable(projection) || !CameraMatrixGuard.isUsable(view)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(jitterX) || !Float.isFinite(jitterY))
            throw new IllegalArgumentException("invalid reconstruction camera");
        this.projection = new Matrix4f(projection);
        this.view = new Matrix4f(view);
        this.inverseView = new Matrix4f(view).invert();
        this.viewProjection = new Matrix4f(projection).mul(view);
        if (!CameraMatrixGuard.isUsable(viewProjection) || !inverseView.isFinite())
            throw new IllegalArgumentException("invalid reconstruction camera transform");
        this.x = x;
        this.y = y;
        this.z = z;
        this.jitterX = jitterX;
        this.jitterY = jitterY;
        this.reset = reset;
    }

    public Matrix4f projection() { return new Matrix4f(projection); }
    public Matrix4f view() { return new Matrix4f(view); }
    public float jitterX() { return jitterX; }
    public float jitterY() { return jitterY; }
    public boolean reset() { return reset; }

    public ReconstructionCamera flipClipY() {
        return new ReconstructionCamera(new Matrix4f().scaling(1, -1, 1).mul(projection), view,
            x, y, z, jitterX, -jitterY, reset);
    }

    /** Bob/hurt translations move the effective eye relative to the engine's raw anchor. */
    public Vector3d eye() {
        Vector3f offset = inverseView.transformPosition(new Vector3f());
        return new Vector3d(x + offset.x, y + offset.y, z + offset.z);
    }

    public Vector3f right() { return direction(1, 0, 0); }
    public Vector3f up() { return direction(0, 1, 0); }
    public Vector3f forward() { return direction(0, 0, -1); }

    private Vector3f direction(float x, float y, float z) {
        return inverseView.transformDirection(new Vector3f(x, y, z)).normalize();
    }

    public boolean teleportedFrom(ReconstructionCamera previous) {
        double dx = x - previous.x, dy = y - previous.y, dz = z - previous.z;
        return dx * dx + dy * dy + dz * dz > 64.0 * 64.0;
    }

    /** Qprev * T(anchorCurrent - anchorPrevious) * inverse(Qcurrent), including bob exactly once. */
    public Matrix4f clipToPrevious(ReconstructionCamera previous) {
        return new Matrix4f(previous.viewProjection)
            .translate((float) (x - previous.x), (float) (y - previous.y), (float) (z - previous.z))
            .mul(new Matrix4f(viewProjection).invert());
    }

    public boolean sameConstants(ReconstructionCamera other) {
        return other != null && projection.equals(other.projection) && view.equals(other.view)
            && x == other.x && y == other.y && z == other.z
            && jitterX == other.jitterX && jitterY == other.jitterY && reset == other.reset;
    }
}
