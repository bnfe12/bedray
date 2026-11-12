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
    public static final float WHITE_START = 11.0f;
    public static final float WHITE_FULL = 60.0f;
    public static final float WHITE_MAX = 0.65f;
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

    /**
     * Player-selectable tone curve (settings: {@code tonemapper}). Mode 0 is the peak-channel curve of {@link #apply};
     * the others are standard scene-linear -> display mappings. The RT display pass writes the result straight to the
     * RGBA8 output with no separate gamma stage, so every mode returns a final display value 0..1: ACES, GT and PBR
     * Neutral are filmic curves that already output display-referred colour, while AgX outputs display-linear and so
     * carries its own sRGB EOTF here. Mirror of {@code tonemap_curve.slang} {@code tonemapColorMode}; keep them in sync.
     */
    public static float[] applyMode(float r, float g, float b, int mode) {
        return switch (mode) {
            case 1 -> aces(r, g, b);
            case 2 -> pbrNeutral(r, g, b);
            case 3 -> granTurismo(r, g, b);
            case 4 -> agx(r, g, b);
            default -> apply(r, g, b);
        };
    }

    private static float sanitize(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.min(Math.max(v, 0f), 65504f);
    }

    // ---- Mode 1: ACES filmic (Narkowicz 2015 per-channel fit) ----
    private static float acesChannel(float x) {
        float num = x * (2.51f * x + 0.03f);
        float den = x * (2.43f * x + 0.59f) + 0.14f;
        return clamp01(num / den);
    }

    private static float[] aces(float r, float g, float b) {
        return new float[] {acesChannel(sanitize(r)), acesChannel(sanitize(g)), acesChannel(sanitize(b))};
    }

    // ---- Mode 2: Khronos PBR Neutral (2024) ----
    private static final float PBR_START_COMPRESSION = 0.8f - 0.04f;
    private static final float PBR_DESATURATION = 0.15f;

    private static float[] pbrNeutral(float r, float g, float b) {
        r = sanitize(r); g = sanitize(g); b = sanitize(b);
        float x = Math.min(r, Math.min(g, b));
        float offset = x < 0.08f ? x - 6.25f * x * x : 0.04f;
        r -= offset; g -= offset; b -= offset;
        float peak = Math.max(r, Math.max(g, b));
        if (peak < PBR_START_COMPRESSION) {
            return new float[] {clamp01(r), clamp01(g), clamp01(b)};
        }
        float d = 1f - PBR_START_COMPRESSION;
        float newPeak = 1f - d * d / (peak + d - PBR_START_COMPRESSION);
        float scale = newPeak / peak;
        r *= scale; g *= scale; b *= scale;
        float gg = 1f - 1f / (PBR_DESATURATION * (peak - newPeak) + 1f);
        return new float[] {clamp01(r + (newPeak - r) * gg), clamp01(g + (newPeak - g) * gg),
            clamp01(b + (newPeak - b) * gg)};
    }

    // ---- Mode 3: Gran Turismo (Uchimura 2017, per-channel) ----
    private static final float GT_P = 1f, GT_A = 1f, GT_M = 0.22f, GT_L = 0.4f, GT_C = 1.33f, GT_B = 0f;
    private static final float GT_L0 = ((GT_P - GT_M) * GT_L) / GT_A;
    private static final float GT_S0 = GT_M + GT_L0;
    private static final float GT_S1 = GT_M + GT_A * GT_L0;
    private static final float GT_C2 = (GT_A * GT_P) / (GT_P - GT_S1);
    private static final float GT_CP = -GT_C2 / GT_P;

    private static float smoothstep01(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static float gtChannel(float x) {
        float w0 = 1f - smoothstep01(x / GT_M);
        float w2 = x >= GT_S0 ? 1f : 0f;
        float w1 = 1f - w0 - w2;
        float toe = GT_M * (float) Math.pow(Math.max(x, 0f) / GT_M, GT_C) + GT_B;
        float shoulder = GT_P - (GT_P - GT_S1) * (float) Math.exp(GT_CP * (x - GT_S0));
        float linear = GT_M + GT_A * (x - GT_M);
        return clamp01(toe * w0 + linear * w1 + shoulder * w2);
    }

    private static float[] granTurismo(float r, float g, float b) {
        return new float[] {gtChannel(sanitize(r)), gtChannel(sanitize(g)), gtChannel(sanitize(b))};
    }

    // ---- Mode 4: AgX (minimal, Sobotka / Wrensch 2024) ----
    private static final float AGX_MIN_EV = -12.47393f;
    private static final float AGX_MAX_EV = 4.026069f;
    // Inset/outset stored row-major so result[i] = dot(row[i], v); matches mul(agxMat, v) of the column-major Slang matrix.
    private static final float[] AGX_INSET = {
        0.842479062253094f, 0.0784335999999992f, 0.0792237451477643f,
        0.0423282422610123f, 0.878468636469772f, 0.0791661274605434f,
        0.0423756549057051f, 0.0784336f, 0.879142973793104f};
    private static final float[] AGX_OUTSET = {
        1.19687900512017f, -0.0980208811401368f, -0.0990297440797205f,
        -0.0528968517574562f, 1.15190312990417f, -0.0989611768448433f,
        -0.0529716355144438f, -0.0980434501171241f, 1.15107367264116f};

    private static float[] matMul(float[] m, float r, float g, float b) {
        return new float[] {
            m[0] * r + m[1] * g + m[2] * b,
            m[3] * r + m[4] * g + m[5] * b,
            m[6] * r + m[7] * g + m[8] * b};
    }

    private static float agxContrast(float x) {
        float x2 = x * x;
        float x4 = x2 * x2;
        return 15.5f * x4 * x2 - 40.14f * x4 * x + 31.96f * x4 - 6.868f * x2 * x
            + 0.4298f * x2 + 0.1191f * x - 0.00232f;
    }

    private static float srgbChannel(float c) {
        c = clamp01(c);
        return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1f / 2.4f) - 0.055f;
    }

    private static float[] agx(float r, float g, float b) {
        float[] v = matMul(AGX_INSET, sanitize(r), sanitize(g), sanitize(b));
        float range = AGX_MAX_EV - AGX_MIN_EV;
        for (int i = 0; i < 3; i++) {
            float ev = (float) (Math.log(Math.max(v[i], 1e-10f)) / Math.log(2.0));
            ev = Math.min(Math.max(ev, AGX_MIN_EV), AGX_MAX_EV);
            v[i] = agxContrast((ev - AGX_MIN_EV) / range);
        }
        v = matMul(AGX_OUTSET, v[0], v[1], v[2]);
        return new float[] {clamp01(srgbChannel(v[0])), clamp01(srgbChannel(v[1])), clamp01(srgbChannel(v[2]))};
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
