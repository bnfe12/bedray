package io.github.andrexarlay.bedray.api.v1;

/** Double world origin plus column-major inverse matrices. Projection includes view bob, excludes jitter.
 * Previous camera is supplied by the consumer only within the same world session.
 */
public final class CameraSnapshot {
    private final double x, y, z;
    private final float[] inverseProjection, inverseView;
    public CameraSnapshot(double x, double y, double z, float[] inverseProjection, float[] inverseView) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Invalid camera origin");
        this.x = x; this.y = y; this.z = z;
        this.inverseProjection = matrix(inverseProjection);
        this.inverseView = matrix(inverseView);
    }
    private static float[] matrix(float[] values) {
        if (values.length != 16) throw new IllegalArgumentException("Expected a 4x4 matrix");
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid camera matrix");
        return values.clone();
    }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float[] inverseProjection() { return inverseProjection.clone(); }
    public float[] inverseView() { return inverseView.clone(); }
}
