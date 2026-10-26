// tonemap.slang — Tone mapping and auto-exposure utilities.
// Ported 1:1 from rt_tonemap.glsl.
// Pure math — no scene access.

public module tonemap;

import types;

// ---- Luminance ----

float luminance(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

// ---- ACES filmic tone mapping (Narkowicz 2015) ----

float3 acesFilm(float3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return saturate((x * (a * x + b)) / (x * (c * x + d) + e));
}

// ---- Reinhard (extended) ----

float3 reinhardExtended(float3 c, float maxWhite) {
    float3 num = c * (1.0 + c / (maxWhite * maxWhite));
    return num / (1.0 + c);
}

// ---- Auto-exposure ----

// Encodes luminance for the GPU atomic sum: (log2(lum) + 16) * 64.
// Decoded on the CPU side to compute the geometric mean exposure.
uint encodeLuminance(float lum) {
    if (lum <= 0.0) return 0u;
    float logLum = log2(max(lum, 1e-6)) + 16.0;
    return uint(max(logLum * 64.0, 0.0));
}

// Applies exposure and tone mapping to a linear HDR color.
float3 applyToneMap(float3 hdr, float exposure) {
    float3 exposed = hdr * exposure;
    return acesFilm(exposed);
}

// ---- Linear <-> sRGB ----

float3 linearToSrgb(float3 c) {
    float3 lo = c * 12.92;
    float3 hi = 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055;
    return select(c <= 0.0031308, lo, hi);
}

float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
    return select(c <= 0.04045, lo, hi);
}
