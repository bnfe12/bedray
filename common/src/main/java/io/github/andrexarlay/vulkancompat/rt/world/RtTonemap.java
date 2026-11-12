package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * CPU mirror of {@code shaders/slang/tonemap_curve.slang} (unit-tested, keep the constants in sync): scene-linear HDR to
 * sRGB-encoded display through John Hable's filmic curve per channel, then the sRGB transfer function; every step has an
 * exact inverse so display-authored colours (the sky) map back to radiance.
 */
public final class RtTonemap {
    public static final float GAIN = 4.0f;
    public static final float WHITE = 11.2f;
    public static final float SAT = 1.20f;
    public static final float INV_MAX = 0.985f;
    private static final float LR = 0.2126f;
    private static final float LG = 0.7152f;
    private static final float LB = 0.0722f;
    private static final double A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;

    private RtTonemap() {
    }

    private static double hable(double x) {
        return (x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F) - E / F;
    }

    /** Normalised filmic curve, one channel (scene value times GAIN in, display-linear out). */
    public static float fit(float v) {
        return (float) Math.max(hable(Math.max(v, 0f)) / hable(WHITE), 0.0);
    }

    public static float fitInverse(float y) {
        double r = Math.max(0f, Math.min(y, INV_MAX)) * hable(WHITE) + E / F;
        double qa = A * (1.0 - r);
        double qb = B * (C - r);
        double qc = D * (E - r * F);
        return (float) Math.max((-qb + Math.sqrt(Math.max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa), 0.0);
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
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = linearToSrgb(clean(fit(Math.max(c[i], 0f) * GAIN), 1f));
        return out;
    }

    /** sRGB-encoded display colour 0..1 to scene-linear colour (before the exposure division). */
    public static float[] invert(float r, float g, float b) {
        float[] c = new float[3];
        float[] d = {r, g, b};
        for (int i = 0; i < 3; i++) c[i] = fitInverse(srgbToLinear(clean(d[i], 0f))) / GAIN;
        c = saturate(c, 1f / SAT);
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
