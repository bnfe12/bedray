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
