package io.github.andrexarlay.bedray.api.v1.light;

/**
 * One extra light an add-on asks the host to consider this frame. Position is in world coordinates (the same space as
 * {@code CameraSnapshot}), colour is a linear RGB radiance scale, and {@code radiusBlocks} is the distance at which the host
 * stops considering it. Values are validated on construction; the host additionally caps what it accepts.
 */
public record PointLight(double x, double y, double z, float red, float green, float blue, float radiusBlocks) {
    public static final float MIN_RADIUS = 0.5f;
    public static final float MAX_RADIUS = 64.0f;
    public static final float MAX_COMPONENT = 1000.0f;

    public PointLight {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Point light position must be finite");
        }
        if (!inRange(red) || !inRange(green) || !inRange(blue)) {
            throw new IllegalArgumentException("Point light colour must be finite and within [0, " + MAX_COMPONENT + "]");
        }
        if (!(radiusBlocks >= MIN_RADIUS && radiusBlocks <= MAX_RADIUS)) {
            throw new IllegalArgumentException("Point light radius must be within [" + MIN_RADIUS + ", " + MAX_RADIUS + "] blocks");
        }
    }

    private static boolean inRange(float v) {
        return Float.isFinite(v) && v >= 0.0f && v <= MAX_COMPONENT;
    }
}
