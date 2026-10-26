// rt_tonemap.glsl - the tone mapping of the RT passes (no push constants / buffers: included by rt_common.glsl and
// rt_pack_nrd.comp). CPU mirror with the same constants: RtTonemap.java (unit-tested, keep both in sync).
//
// Replaces the per-channel Narkowicz ACES fit, which lifted the dark channels of every colour more than the bright one
// (grass 0.22/0.66/0.11 -> 0.33/0.70/0.14: washed out mid tones, shadows pulled towards the blue sky ambient). This curve works on the
// PEAK channel and scales the colour with it (hue and saturation of the albedo survive, like the exposure curve of Bedrock RTX /
// BetterRTX): scene-linear colour -> mild saturation -> gain -> identity below the knee, smooth C1 shoulder above it.
//
//   m <= KNEE : f(m) = m
//   m  > KNEE : f(m) = 1 - (1 - KNEE)^2 / (m - KNEE + (1 - KNEE))        (value and slope continuous at the knee, limit 1)
//
// Both directions exist: the sky and the emission are built in DISPLAY space (what the player should see) and converted into
// radiance with tonemapInverse(), so tonemapColor(tonemapInverse(y)) == y for display colours below TM_INV_MAX.

const float TM_GAIN = 1.20;         // calibration gain in front of the curve (brightness of the old ACES at exposure 1.1)
const float TM_KNEE = 0.60;         // display value up to which the curve is the identity
const float TM_SAT = 1.00;          // saturation around the luminance in scene space (inverse applied by tonemapInverse)
const float TM_CON = 0.20;          // cinematic contrast: peak m -> P * (m / P)^(1 + CON), anchored at the pivot (mid grey stays put,
                                    // shadows deepen, highlights rise into the shoulder); analytic inverse below
const float TM_PIVOT = 0.25;        // pivot of the contrast, scene value after the gain
const float TM_INV_MAX = 0.985;     // display peak that tonemapInverse() still inverts (the curve reaches 1 only at infinity)
const vec3 TM_LUMA = vec3(0.2126, 0.7152, 0.0722);

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
vec3 tonemapColor(vec3 c) {
    if (isnan(c.r) || isinf(c.r)) c.r = 0.0;
    if (isnan(c.g) || isinf(c.g)) c.g = 0.0;
    if (isnan(c.b) || isinf(c.b)) c.b = 0.0;
    c = clamp(c, vec3(0.0), vec3(65504.0));
    float l = dot(c, TM_LUMA);
    c = max(vec3(l) + (c - vec3(l)) * TM_SAT, vec3(0.0)) * TM_GAIN;
    c = min(c, vec3(65504.0));
    float m = max(c.r, max(c.g, c.b));
    if (isnan(m) || isinf(m) || m <= 1.0e-6) {
        return vec3(0.0);
    }
    float scale = tmCurve(tmContrast(m)) / m;
    vec3 outCol = c * scale;
    if (isnan(outCol.r) || isinf(outCol.r)) outCol.r = 1.0;
    if (isnan(outCol.g) || isinf(outCol.g)) outCol.g = 1.0;
    if (isnan(outCol.b) || isinf(outCol.b)) outCol.b = 1.0;
    return clamp(outCol, 0.0, 1.0);
}

// display colour 0..1 -> scene-linear colour (before the exposure division)
vec3 tonemapInverse(vec3 y) {
    if (isnan(y.r) || isinf(y.r)) y.r = 0.0;
    if (isnan(y.g) || isinf(y.g)) y.g = 0.0;
    if (isnan(y.b) || isinf(y.b)) y.b = 0.0;
    y = clamp(y, vec3(0.0), vec3(1.0));
    float m = max(y.r, max(y.g, y.b));
    if (isnan(m) || isinf(m) || m <= 1.0e-6) {
        return vec3(0.0);
    }
    vec3 c = y * (tmContrastInverse(tmCurveInverse(m)) / m) / TM_GAIN;
    float l = dot(c, TM_LUMA);
    vec3 outCol = max(vec3(l) + (c - vec3(l)) / TM_SAT, vec3(0.0));
    if (isnan(outCol.r) || isinf(outCol.r)) outCol.r = 0.0;
    if (isnan(outCol.g) || isinf(outCol.g)) outCol.g = 0.0;
    if (isnan(outCol.b) || isinf(outCol.b)) outCol.b = 0.0;
    return outCol;
}
