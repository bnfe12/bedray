// Bedray tone mapping: scene-linear HDR -> sRGB-encoded display, and back.
//
// The path tracer shades in linear light (decodeSurface converts the sRGB atlas to linear reflectance), so the display
// transform ends with the sRGB transfer function, as Bedrock RTX does (RTXPostFX.Tonemapping, gPerformSRGBConversion).
// The curve before it works on the PEAK channel and scales the colour with it, so the hue and the saturation of the
// albedo survive: under a white light of exposed intensity 1 a texture comes out as its own colour.
//
//   m <= KNEE : f(m) = m
//   m  > KNEE : f(m) = 1 - (1 - KNEE)^2 / (m - KNEE + (1 - KNEE))        (value and slope continuous at the knee)
//
// The per-channel filmic curves tried first (ACES fit, Hable) desaturated every mid tone: noon read grey and lamps pale
// next to the former look (autotest A/J/N 2026-11-12: saturation 49 -> 34, lamps 95 -> 55).  A peak that only the
// brightest sources reach (beyond what the inverse can return, TM_WHITE_START) still bleaches towards white, so a
// flame core goes yellow-white instead of staying one flat saturated plate.
//
// Every step below TM_WHITE_START has an exact inverse, because the sky and the vanilla colours are authored in display
// space and bloom / volumetrics read the displayed image back into scene light (tonemapInverse).
//
// Mirror of RtTonemap.java: change a constant here and there together (RtWorldChecks round-trips the pair).

public module tonemap_curve;

static const float TM_GAIN = 1.30;      // fitted on a grey ramp against the former gamma-space look
static const float TM_KNEE = 0.60;
static const float TM_SAT = 1.00;
static const float TM_INV_MAX = 0.985;  // the inverse returns a peak of at most ~10.9
static const float TM_WHITE_START = 11.0;
static const float TM_WHITE_FULL = 60.0;
static const float TM_WHITE_MAX = 0.65;
static const float3 TM_LUMA = float3(0.2126, 0.7152, 0.0722);

float3 tmSanitize(float3 c, float fallback) {
    if (isnan(c.r) || isinf(c.r)) { c.r = fallback; }
    if (isnan(c.g) || isinf(c.g)) { c.g = fallback; }
    if (isnan(c.b) || isinf(c.b)) { c.b = fallback; }
    return c;
}

float3 tmLinearToSrgb(float3 x) {
    x = clamp(x, float3(0.0), float3(1.0));
    float3 lo = x * 12.92;
    float3 hi = 1.055 * pow(x, float3(1.0 / 2.4)) - 0.055;
    return float3(x.r <= 0.0031308 ? lo.r : hi.r, x.g <= 0.0031308 ? lo.g : hi.g, x.b <= 0.0031308 ? lo.b : hi.b);
}

float3 tmSrgbToLinear(float3 x) {
    x = clamp(x, float3(0.0), float3(1.0));
    float3 lo = x / 12.92;
    float3 hi = pow((x + 0.055) / 1.055, float3(2.4));
    return float3(x.r <= 0.04045 ? lo.r : hi.r, x.g <= 0.04045 ? lo.g : hi.g, x.b <= 0.04045 ? lo.b : hi.b);
}

// Shoulder on the peak channel (scene value after the gain in, display-linear out) and its inverse.
float tmFit(float m) {
    if (isnan(m) || isinf(m) || m > 65504.0) return 1.0;
    if (m <= TM_KNEE) return max(m, 0.0);
    float d = 1.0 - TM_KNEE;
    return clamp(1.0 - (d * d) / (m - TM_KNEE + d), 0.0, 1.0);
}

float tmFitInverse(float y) {
    if (isnan(y) || isinf(y)) return TM_KNEE;
    if (y <= TM_KNEE) return max(y, 0.0);
    float d = 1.0 - TM_KNEE;
    y = clamp(y, 0.0, TM_INV_MAX);
    return TM_KNEE - d + (d * d) / max(1.0 - y, 1.0e-5);
}

float3 tmSaturate(float3 c, float s) {
    float l = dot(c, TM_LUMA);
    return float3(l) + (c - float3(l)) * s;
}

float tmWhiteWeight(float m) {
    return TM_WHITE_MAX * smoothstep(TM_WHITE_START, TM_WHITE_FULL, m);
}

// Scene-linear colour (already multiplied by the exposure) -> sRGB-encoded display colour 0..1.
float3 tonemapColor(float3 c) {
    c = clamp(tmSanitize(c, 0.0), float3(0.0), float3(65504.0));
    c = max(tmSaturate(c, TM_SAT), float3(0.0)) * TM_GAIN;
    float m = max(c.r, max(c.g, c.b));
    if (!(m > 1.0e-6)) return float3(0.0);
    float3 display = c * (tmFit(m) / m);
    float peak = max(display.r, max(display.g, display.b));
    display = lerp(display, float3(peak), tmWhiteWeight(m));
    return tmLinearToSrgb(tmSanitize(display, 1.0));
}

// sRGB-encoded display colour 0..1 -> scene-linear colour (before the exposure division).
float3 tonemapInverse(float3 y) {
    float3 display = tmSrgbToLinear(tmSanitize(y, 0.0));
    float m = max(display.r, max(display.g, display.b));
    if (!(m > 1.0e-6)) return float3(0.0);
    float3 c = display * (tmFitInverse(m) / m) / TM_GAIN;
    return tmSanitize(max(tmSaturate(c, 1.0 / TM_SAT), float3(0.0)), 0.0);
}
