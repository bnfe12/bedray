package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * CPU mirror of {@code shaders/slang/tonemap_curve.slang} (unit-tested, keep the constants in sync): scene-linear HDR to
 * sRGB-encoded display through a peak-channel shoulder (hue and saturation kept, identity below the knee), a white
 * bleach of peaks beyond the inverse's reach, then the sRGB transfer function.
 */
public final class RtTonemap {
    public static final float GAIN = 1.30f;
    public static final float KNEE = 0.60f;
    public static final float SAT = 1.00f;
    public static final float INV_MAX = 0.985f;
    public static final float WHITE_START = 11.0f;
    public static final float WHITE_FULL = 60.0f;
    public static final float WHITE_MAX = 0.65f;
    private static final float LR = 0.2126f;
    private static final float LG = 0.7152f;
    private static final float LB = 0.0722f;

    private RtTonemap() {
    }

    /** Shoulder on the peak channel (scene value after the gain in, display-linear out). */
    public static float fit(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m) || m > 65504f) return 1f;
        if (m <= KNEE) return Math.max(m, 0f);
        float d = 1f - KNEE;
        return clamp01(1f - d * d / (m - KNEE + d));
    }

    public static float fitInverse(float y) {
        if (Float.isNaN(y) || Float.isInfinite(y)) return KNEE;
        if (y <= KNEE) return Math.max(y, 0f);
        float d = 1f - KNEE;
        float yy = Math.max(0f, Math.min(y, INV_MAX));
        return KNEE - d + d * d / Math.max(1f - yy, 1.0e-5f);
    }

    public static float whiteWeight(float m) {
        float t = clamp01((m - WHITE_START) / (WHITE_FULL - WHITE_START));
        return WHITE_MAX * t * t * (3f - 2f * t);
    }

    public static float linearToSrgb(float x) {
        x = clamp01(x);
        return x <= 0.0031308f ? x * 12.92f : (float) (1.055 * Math.pow(x, 1.0 / 2.4) - 0.055);
    }

    public static float srgbToLinear(float x) {
        x = clamp01(x);
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055) / 1.055, 2.4);
    }

    /** Scene-linear colour (times the exposure) to sRGB-encoded display colour 0..1. */
    public static float[] apply(float r, float g, float b) {
        float[] c = {clean(r, 0f), clean(g, 0f), clean(b, 0f)};
        for (int i = 0; i < 3; i++) c[i] = Math.min(Math.max(c[i], 0f), 65504f);
        c = saturate(c, SAT);
        for (int i = 0; i < 3; i++) c[i] = Math.max(c[i], 0f) * GAIN;
        float m = Math.max(c[0], Math.max(c[1], c[2]));
        if (!(m > 1.0e-6f)) return new float[] {0f, 0f, 0f};
        float k = fit(m) / m;
        float[] d = {c[0] * k, c[1] * k, c[2] * k};
        float peak = Math.max(d[0], Math.max(d[1], d[2]));
        float w = whiteWeight(m);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = linearToSrgb(clean(d[i] + (peak - d[i]) * w, 1f));
        return out;
    }

    /** sRGB-encoded display colour 0..1 to scene-linear colour (before the exposure division). */
    public static float[] invert(float r, float g, float b) {
        float[] d = {srgbToLinear(clean(r, 0f)), srgbToLinear(clean(g, 0f)), srgbToLinear(clean(b, 0f))};
        float m = Math.max(d[0], Math.max(d[1], d[2]));
        if (!(m > 1.0e-6f)) return new float[] {0f, 0f, 0f};
        float k = fitInverse(m) / m / GAIN;
        float[] c = saturate(new float[] {d[0] * k, d[1] * k, d[2] * k}, 1f / SAT);
        for (int i = 0; i < 3; i++) c[i] = clean(Math.max(c[i], 0f), 0f);
        return c;
    }

    /** A display-authored light colour as linear light of the same hue: chromaticity decoded, the peak kept. */
    public static float[] linearHue(float r, float g, float b) {
        float m = Math.max(r, Math.max(g, b));
        if (!(m > 1.0e-6f) || Float.isInfinite(m)) {
            return new float[] {0f, 0f, 0f};
        }
        return new float[] {srgbToLinear(r / m) * m, srgbToLinear(g / m) * m, srgbToLinear(b / m) * m};
    }

    private static float[] saturate(float[] c, float s) {
        float l = LR * c[0] + LG * c[1] + LB * c[2];
        return new float[] {l + (c[0] - l) * s, l + (c[1] - l) * s, l + (c[2] - l) * s};
    }

    private static float clean(float v, float fallback) {
        return Float.isNaN(v) || Float.isInfinite(v) ? fallback : v;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
