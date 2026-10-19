package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * CPU mirror of {@code shaders/rt_tonemap.glsl} (unit-tested, keep the constants in sync). Peak-channel filmic curve: the colour
 * is scaled by {@code f(max channel) / max channel}, so hue and saturation of the albedo survive (the previous per-channel
 * Narkowicz ACES lifted the dark channels and washed colours out). Identity up to {@link #KNEE}, C1 shoulder to 1 above it.
 */
public final class RtTonemap {
    public static final float GAIN = 1.20f;
    public static final float KNEE = 0.60f;
    public static final float SAT = 1.00f;
    public static final float CON = 0.20f;
    public static final float PIVOT = 0.25f;
    public static final float INV_MAX = 0.985f;
    private static final float LR = 0.2126f;
    private static final float LG = 0.7152f;
    private static final float LB = 0.0722f;

    private RtTonemap() {
    }

    public static float contrast(float m) {
        return PIVOT * (float) Math.pow(Math.max(m, 0f) / PIVOT, 1.0 + CON);
    }

    public static float contrastInverse(float x) {
        return PIVOT * (float) Math.pow(Math.max(x, 0f) / PIVOT, 1.0 / (1.0 + CON));
    }

    /** Shoulder curve on the peak channel (scene value after the gain, display value out). */
    public static float curve(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m) || m > 65504f) {
            return 1f;
        }
        if (m <= KNEE) {
            return Math.max(0f, m);
        }
        float d = 1f - KNEE;
        float denom = m - KNEE + d;
        if (denom <= 1e-6f) {
            return 1f;
        }
        return clamp01(1f - d * d / denom);
    }

    public static float curveInverse(float y) {
        if (Float.isNaN(y) || Float.isInfinite(y)) {
            return KNEE;
        }
        if (y <= KNEE) {
            return Math.max(0f, y);
        }
        float d = 1f - KNEE;
        float yy = Math.max(0f, Math.min(y, INV_MAX));
        float denom = 1f - yy;
        if (denom <= 1e-5f) {
            denom = 1e-5f;
        }
        return KNEE - d + d * d / denom;
    }

    /** Scene-linear colour (times the exposure) to display colour 0..1. */
    public static float[] apply(float r, float g, float b) {
        if (Float.isNaN(r) || Float.isInfinite(r)) r = 0f;
        if (Float.isNaN(g) || Float.isInfinite(g)) g = 0f;
        if (Float.isNaN(b) || Float.isInfinite(b)) b = 0f;
        r = Math.min(Math.max(r, 0f), 65504f);
        g = Math.min(Math.max(g, 0f), 65504f);
        b = Math.min(Math.max(b, 0f), 65504f);
        float l = LR * r + LG * g + LB * b;
        r = Math.max(l + (r - l) * SAT, 0f) * GAIN;
        g = Math.max(l + (g - l) * SAT, 0f) * GAIN;
        b = Math.max(l + (b - l) * SAT, 0f) * GAIN;
        r = Math.min(r, 65504f);
        g = Math.min(g, 65504f);
        b = Math.min(b, 65504f);
        float m = Math.max(r, Math.max(g, b));
        if (Float.isNaN(m) || Float.isInfinite(m) || m <= 1e-6f) {
            return new float[] {0f, 0f, 0f};
        }
        float k = curve(contrast(m)) / m;
        float outR = clamp01(r * k);
        float outG = clamp01(g * k);
        float outB = clamp01(b * k);
        if (Float.isNaN(outR)) outR = 1f;
        if (Float.isNaN(outG)) outG = 1f;
        if (Float.isNaN(outB)) outB = 1f;
        return new float[] {outR, outG, outB};
    }

    /** Display colour 0..1 to scene-linear colour (before the exposure division). */
    public static float[] invert(float r, float g, float b) {
        if (Float.isNaN(r) || Float.isInfinite(r)) r = 0f;
        if (Float.isNaN(g) || Float.isInfinite(g)) g = 0f;
        if (Float.isNaN(b) || Float.isInfinite(b)) b = 0f;
        r = clamp01(r);
        g = clamp01(g);
        b = clamp01(b);
        float m = Math.max(r, Math.max(g, b));
        if (Float.isNaN(m) || Float.isInfinite(m) || m <= 1e-6f) {
            return new float[] {0f, 0f, 0f};
        }
        float k = contrastInverse(curveInverse(m)) / m / GAIN;
        r *= k;
        g *= k;
        b *= k;
        float l = LR * r + LG * g + LB * b;
        float outR = Math.max(l + (r - l) / SAT, 0f);
        float outG = Math.max(l + (g - l) / SAT, 0f);
        float outB = Math.max(l + (b - l) / SAT, 0f);
        if (Float.isNaN(outR)) outR = 0f;
        if (Float.isNaN(outG)) outG = 0f;
        if (Float.isNaN(outB)) outB = 0f;
        return new float[] {outR, outG, outB};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
