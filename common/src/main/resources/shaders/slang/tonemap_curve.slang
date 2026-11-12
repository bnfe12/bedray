// Bedray 2.0 — peak-channel tone curve.
//
// Scene-linear colour -> display colour and back. The curve works on the PEAK channel and scales the colour with it,
// so hue and saturation of the albedo survive (the per-channel ACES fit washed out mid tones). Both directions exist
// because the sky and the emission are authored in display space and converted into radiance on the way in.
//
//   m <= KNEE : f(m) = m
//   m  > KNEE : f(m) = 1 - (1 - KNEE)^2 / (m - KNEE + (1 - KNEE))        (value and slope continuous at the knee)
//
// Mirror of shaders/glsl/rt_tonemap.glsl and of RtTonemap.java. The constants are identical in all three; change one
// and change the other two.

public module tonemap_curve;

static const float TM_GAIN = 1.20;
static const float TM_KNEE = 0.60;
static const float TM_SAT = 1.00;
static const float TM_CON = 0.20;
static const float TM_PIVOT = 0.25;
static const float TM_INV_MAX = 0.985;
static const float3 TM_LUMA = float3(0.2126, 0.7152, 0.0722);

float tmContrast(float m) {
    return TM_PIVOT * pow(max(m, 0.0) / TM_PIVOT, 1.0 + TM_CON);
}

float tmContrastInverse(float x) {
    return TM_PIVOT * pow(max(x, 0.0) / TM_PIVOT, 1.0 / (1.0 + TM_CON));
}

float tmCurve(float m) {
    if (isnan(m) || isinf(m) || m > 65504.0) {
        return 1.0;
    }
    if (m <= TM_KNEE) {
        return max(0.0, m);
    }
    float d = 1.0 - TM_KNEE;
    float denom = m - TM_KNEE + d;
    if (denom <= 1.0e-6) {
        return 1.0;
    }
    return clamp(1.0 - (d * d) / denom, 0.0, 1.0);
}

float tmCurveInverse(float y) {
    if (isnan(y) || isinf(y)) {
        return TM_KNEE;
    }
    if (y <= TM_KNEE) {
        return max(0.0, y);
    }
    float d = 1.0 - TM_KNEE;
    y = clamp(y, 0.0, TM_INV_MAX);
    float denom = 1.0 - y;
    if (denom <= 1.0e-5) {
        denom = 1.0e-5;
    }
    return TM_KNEE - d + (d * d) / denom;
}

// Highlights go to white. The peak-channel curve keeps the hue at any brightness, so everything past its shoulder
// (scene peak ~5, where the display is already 0.985+) was one flat saturated plate: the floor next to a torch read as
// an orange fill that hid its texture ("a colour fill, not a torch", owner 2026-11-12). Film and the ACES curve of RTX
// Remix bleach such a highlight towards white and keep the colour in the falloff. Only scene values the inverse can
// never produce (it tops out at ~4.8, TM_INV_MAX) are touched, so display -> scene -> display stays exact (sky, bloom).
static const float TM_WHITE_START = 5.0;
static const float TM_WHITE_FULL = 40.0;
static const float TM_WHITE_MAX = 0.75;

float tmWhiteWeight(float m) {
    return TM_WHITE_MAX * smoothstep(TM_WHITE_START, TM_WHITE_FULL, m);
}

// scene-linear colour (already multiplied by the exposure) -> display colour 0..1
float3 tonemapColor(float3 c) {
    if (isnan(c.r) || isinf(c.r)) { c.r = 0.0; }
    if (isnan(c.g) || isinf(c.g)) { c.g = 0.0; }
    if (isnan(c.b) || isinf(c.b)) { c.b = 0.0; }
    c = clamp(c, float3(0.0), float3(65504.0));
    float l = dot(c, TM_LUMA);
    c = max(float3(l) + (c - float3(l)) * TM_SAT, float3(0.0)) * TM_GAIN;
    c = min(c, float3(65504.0));
    float m = max(c.r, max(c.g, c.b));
    if (isnan(m) || isinf(m) || m <= 1.0e-6) {
        return float3(0.0);
    }
    float scale = tmCurve(tmContrast(m)) / m;
    float3 outCol = c * scale;
    float peak = max(outCol.r, max(outCol.g, outCol.b));
    outCol = lerp(outCol, float3(peak), tmWhiteWeight(m));
    if (isnan(outCol.r) || isinf(outCol.r)) { outCol.r = 1.0; }
    if (isnan(outCol.g) || isinf(outCol.g)) { outCol.g = 1.0; }
    if (isnan(outCol.b) || isinf(outCol.b)) { outCol.b = 1.0; }
    return clamp(outCol, float3(0.0), float3(1.0));
}

// display colour 0..1 -> scene-linear colour (before the exposure division)
float3 tonemapInverse(float3 y) {
    if (isnan(y.r) || isinf(y.r)) { y.r = 0.0; }
    if (isnan(y.g) || isinf(y.g)) { y.g = 0.0; }
    if (isnan(y.b) || isinf(y.b)) { y.b = 0.0; }
    y = clamp(y, float3(0.0), float3(1.0));
    float m = max(y.r, max(y.g, y.b));
    if (isnan(m) || isinf(m) || m <= 1.0e-6) {
        return float3(0.0);
    }
    float3 c = y * (tmContrastInverse(tmCurveInverse(m)) / m) / TM_GAIN;
    float l = dot(c, TM_LUMA);
    float3 outCol = max(float3(l) + (c - float3(l)) / TM_SAT, float3(0.0));
    if (isnan(outCol.r) || isinf(outCol.r)) { outCol.r = 0.0; }
    if (isnan(outCol.g) || isinf(outCol.g)) { outCol.g = 0.0; }
    if (isnan(outCol.b) || isinf(outCol.b)) { outCol.b = 0.0; }
    return outCol;
}

// ---- Player-selectable tone curves (settings: tonemapper). Mirror of RtTonemap.applyMode; keep the constants in sync. ----
// Mode 0 is the peak-channel curve above. The RT display pass writes the result straight to the RGBA8 output with no
// separate gamma stage, so every mode returns a final display value 0..1: ACES, GT and PBR Neutral are filmic curves
// that already output display-referred colour, while AgX outputs display-linear and carries its own sRGB EOTF here.

static const uint TM_MODE_PEAK = 0u;
static const uint TM_MODE_ACES = 1u;
static const uint TM_MODE_PBR_NEUTRAL = 2u;
static const uint TM_MODE_GT = 3u;
static const uint TM_MODE_AGX = 4u;

float tmSanitizeChannel(float v) {
    if (isnan(v) || isinf(v)) { return 0.0; }
    return clamp(v, 0.0, 65504.0);
}

float3 tmSanitize(float3 c) {
    return float3(tmSanitizeChannel(c.r), tmSanitizeChannel(c.g), tmSanitizeChannel(c.b));
}

// Mode 1: ACES filmic (Narkowicz 2015 per-channel fit).
float tmAcesChannel(float x) {
    float num = x * (2.51 * x + 0.03);
    float den = x * (2.43 * x + 0.59) + 0.14;
    return saturate(num / den);
}

float3 tmAces(float3 c) {
    c = tmSanitize(c);
    return float3(tmAcesChannel(c.r), tmAcesChannel(c.g), tmAcesChannel(c.b));
}

// Mode 2: Khronos PBR Neutral (2024).
float3 tmPbrNeutral(float3 c) {
    c = tmSanitize(c);
    float startCompression = 0.8 - 0.04;
    float desaturation = 0.15;
    float x = min(c.r, min(c.g, c.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    c -= offset;
    float peak = max(c.r, max(c.g, c.b));
    if (peak < startCompression) { return saturate(c); }
    float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    c *= newPeak / peak;
    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return saturate(lerp(c, float3(newPeak), g));
}

// Mode 3: Gran Turismo (Uchimura 2017, per-channel).
float tmGtChannel(float x) {
    float P = 1.0, a = 1.0, m = 0.22, l = 0.4, cc = 1.33, b = 0.0;
    float l0 = ((P - m) * l) / a;
    float S0 = m + l0;
    float S1 = m + a * l0;
    float C2 = (a * P) / (P - S1);
    float CP = -C2 / P;
    float w0 = 1.0 - smoothstep(0.0, m, x);
    float w2 = step(S0, x);
    float w1 = 1.0 - w0 - w2;
    float T = m * pow(max(x, 0.0) / m, cc) + b;
    float S = P - (P - S1) * exp(CP * (x - S0));
    float L = m + a * (x - m);
    return saturate(T * w0 + L * w1 + S * w2);
}

float3 tmGranTurismo(float3 c) {
    c = tmSanitize(c);
    return float3(tmGtChannel(c.r), tmGtChannel(c.g), tmGtChannel(c.b));
}

// Mode 4: AgX (minimal, Sobotka / Wrensch 2024). Rows below are the transpose of the column-major GLSL inset/outset,
// so mul(inset, c) here equals agx_mat * c there and RtTonemap.matMul(AGX_INSET, ...) on the CPU.
float3 tmAgxContrast(float3 x) {
    float3 x2 = x * x;
    float3 x4 = x2 * x2;
    return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
}

float tmSrgbChannel(float c) {
    c = saturate(c);
    return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
}

float3 tmAgx(float3 c) {
    c = tmSanitize(c);
    float minEv = -12.47393;
    float maxEv = 4.026069;
    float3x3 inset = float3x3(
        float3(0.842479062253094, 0.0784335999999992, 0.0792237451477643),
        float3(0.0423282422610123, 0.878468636469772, 0.0791661274605434),
        float3(0.0423756549057051, 0.0784336, 0.879142973793104));
    float3x3 outset = float3x3(
        float3(1.19687900512017, -0.0980208811401368, -0.0990297440797205),
        float3(-0.0528968517574562, 1.15190312990417, -0.0989611768448433),
        float3(-0.0529716355144438, -0.0980434501171241, 1.15107367264116));
    float3 v = mul(inset, c);
    v = clamp(log2(max(v, float3(1e-10))), float3(minEv), float3(maxEv));
    v = (v - minEv) / (maxEv - minEv);
    v = tmAgxContrast(v);
    v = mul(outset, v);
    return saturate(float3(tmSrgbChannel(v.r), tmSrgbChannel(v.g), tmSrgbChannel(v.b)));
}

// Scene-linear colour -> display colour 0..1 with the player's chosen curve (mode 0 = the peak-channel curve).
float3 tonemapColorMode(float3 c, uint mode) {
    if (mode == TM_MODE_ACES) { return tmAces(c); }
    if (mode == TM_MODE_PBR_NEUTRAL) { return tmPbrNeutral(c); }
    if (mode == TM_MODE_GT) { return tmGranTurismo(c); }
    if (mode == TM_MODE_AGX) { return tmAgx(c); }
    return tonemapColor(c);
}
