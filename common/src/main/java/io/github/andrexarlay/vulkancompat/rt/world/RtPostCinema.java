package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * CPU mirror of the Vibrant Visuals grade in {@code shaders/slang/post_cinema.slang} (unit-tested, keep the constants in
 * sync). Saturation scales the chroma about the luma, then contrast pivots about mid grey; the result is clamped to 0..1.
 * Applied to the tone-mapped display colour only, never to the accumulation mean.
 */
public final class RtPostCinema {
    /** The fixed strengths path_display.slang passes to postVibrant. */
    public static final float VIBRANT_SATURATION = 1.30f;
    public static final float VIBRANT_CONTRAST = 1.12f;
    private static final float LR = 0.2126f;
    private static final float LG = 0.7152f;
    private static final float LB = 0.0722f;

    private RtPostCinema() {
    }

    /** Mirror of {@code postVibrant}: richer saturation and a gentle contrast lift on a display colour 0..1. */
    public static float[] vibrant(float r, float g, float b, float saturation, float contrast) {
        float l = LR * r + LG * g + LB * b;
        float sr = Math.max(l + (r - l) * saturation, 0f);
        float sg = Math.max(l + (g - l) * saturation, 0f);
        float sb = Math.max(l + (b - l) * saturation, 0f);
        return new float[] {
            clamp01((sr - 0.5f) * contrast + 0.5f),
            clamp01((sg - 0.5f) * contrast + 0.5f),
            clamp01((sb - 0.5f) * contrast + 0.5f)
        };
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}