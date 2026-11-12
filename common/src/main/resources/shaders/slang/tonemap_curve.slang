// Bedray tone mapping: scene-linear HDR -> sRGB-encoded display, and back.
//
// The path tracer shades in linear light (decodeSurface converts the sRGB atlas to linear reflectance), so the display
// transform is the one Bedrock RTX ends its post chain with (RTXPostFX.Tonemapping: a filmic tone curve, then
// gPerformSRGBConversion): a filmic curve per channel, then the sRGB transfer function.
//
//   tonemapColor(c) = sRGB( H(TM_GAIN * sat(c)) / H(TM_WHITE) )
//   H(x) = (x (A x + C B) + D E) / (x (A x + B) + D F) - E / F       (John Hable's filmic curve, Uncharted 2, 2010)
//
// Per channel, so a bright saturated highlight bleaches towards white the way film does (a torch pool keeps its amber
// falloff, the flame core goes yellow-white) instead of staying one flat saturated plate.  The ACES fit tried first
// crushed the toe (a display-linear 0.01 came out at 0.0004): night rooms and caves went three times darker than the
// former look (autotest J/N/L, 2026-11-12); this curve leaves the shadows where they were and still rolls the
// highlights off.
//
// Every step has an exact inverse - H is a ratio of quadratics, solved in closed form - because the sky and the vanilla
// colours are authored in display space, and bloom / volumetrics read the displayed image back into scene light
// (tonemapInverse), add to it and map it again.
//
// Mirror of RtTonemap.java: change a constant here and there together (RtWorldChecks round-trips the pair).

public module tonemap_curve;

// Exposure bias: with it scene 0.18 lands near the display value the former gamma-space look gave it (fitted on a grey
// ramp), so the exposure meter and its KEY keep their meaning.  White point: scene TM_WHITE / TM_GAIN = 2.8 maps to 1.
static const float TM_GAIN = 4.0;
static const float TM_WHITE = 11.2;
// Saturation before the curve: a per-channel shoulder desaturates bright colours a little; this gives it back.
static const float TM_SAT = 1.20;   // 1.06 read grey next to Bedrock RTX (owner, 2026-11-12)
// Display value the inverse is solved for at most (normalised curve units), so an 8-bit step at the shoulder cannot
// turn into a huge scene step.
static const float TM_INV_MAX = 0.985;
static const float3 TM_LUMA = float3(0.2126, 0.7152, 0.0722);

static const float TM_A = 0.15;
static const float TM_B = 0.50;
static const float TM_C = 0.10;
static const float TM_D = 0.20;
static const float TM_E = 0.02;
static const float TM_F = 0.30;

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

float tmHable(float x) {
    return (x * (TM_A * x + TM_C * TM_B) + TM_D * TM_E) / (x * (TM_A * x + TM_B) + TM_D * TM_F) - TM_E / TM_F;
}

// Normalised curve on one channel (scene value times TM_GAIN in, display-linear out) and its inverse.
float tmFit(float v) {
    return max(tmHable(max(v, 0.0)) / tmHable(TM_WHITE), 0.0);
}

float tmFitInverse(float y) {
    float r = clamp(y, 0.0, TM_INV_MAX) * tmHable(TM_WHITE) + TM_E / TM_F;
    float qa = TM_A * (1.0 - r);
    float qb = TM_B * (TM_C - r);
    float qc = TM_D * (TM_E - r * TM_F);
    return max((-qb + sqrt(max(qb * qb - 4.0 * qa * qc, 0.0))) / (2.0 * qa), 0.0);
}

float3 tmSaturate(float3 c, float s) {
    float l = dot(c, TM_LUMA);
    return float3(l) + (c - float3(l)) * s;
}

// Scene-linear colour (already multiplied by the exposure) -> sRGB-encoded display colour 0..1.
float3 tonemapColor(float3 c) {
    c = clamp(tmSanitize(c, 0.0), float3(0.0), float3(65504.0));
    c = max(tmSaturate(c, TM_SAT), float3(0.0)) * TM_GAIN;
    float3 display = float3(tmFit(c.r), tmFit(c.g), tmFit(c.b));
    return tmLinearToSrgb(tmSanitize(display, 1.0));
}

// sRGB-encoded display colour 0..1 -> scene-linear colour (before the exposure division).
float3 tonemapInverse(float3 y) {
    float3 display = tmSrgbToLinear(tmSanitize(y, 0.0));
    float3 c = float3(tmFitInverse(display.r), tmFitInverse(display.g), tmFitInverse(display.b)) / TM_GAIN;
    return tmSanitize(max(tmSaturate(c, 1.0 / TM_SAT), float3(0.0)), 0.0);
}
