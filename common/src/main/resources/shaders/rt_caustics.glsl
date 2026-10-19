// Water surface model shared by every pass (included by rt_common.glsl): one analytic wave field, dielectric Fresnel with
// total internal reflection, Snell refraction helpers and the analytic sun caustic of that same field.
//
// The wave field is a sum of directional sines (slope amplitude per component), evaluated in world X/Z. waterSlope() is the ONLY
// source of the water normal: the camera sees it as the surface normal (reflection, refraction), the sun caustic reads the same
// slopes, so the light patterns on the floor move exactly with the waves visible on the surface.
//
// Anti-moire: every component is filtered by the pixel footprint on the surface (footprint = hit distance * pixel angle / cos of
// the grazing angle). A wave whose period is not resolved by the footprint fades out (analytic mip), and the slope variance that
// faded out is returned as extra roughness, so far water becomes a soft sheen instead of aliased stripes.

const float WATER_IOR = 1.333;
const float WATER_PIXEL_ANGLE = 0.0016;   // radians per pixel of the 3D view (about 70 deg fov at 750 px, conservative)

const int WATER_WAVES = 6;

// direction (unit xz), wave number k (rad/block), peak slope, phase offset. Wavelengths 6.3, 3.7, 2.2, 1.4, 0.86, 0.55 blocks.
// The slope amplitudes are tuned so the sum stays below the 0.15 clamp of the previous model (ripples, not a storm).
const vec4 WATER_WAVE_A[WATER_WAVES] = vec4[WATER_WAVES](
    vec4(0.800, 0.600, 1.00, 0.024),
    vec4(-0.700, 0.714, 1.70, 0.024),
    vec4(0.312, -0.950, 2.90, 0.022),
    vec4(-0.911, -0.412, 4.60, 0.019),
    vec4(0.550, 0.835, 7.30, 0.014),
    vec4(-0.200, -0.980, 11.5, 0.010));
const float WATER_WAVE_PHASE[WATER_WAVES] = float[WATER_WAVES](0.0, 1.3, 2.9, 4.1, 5.2, 0.7);

// Gradient of the height field (d h/d x, d h/d z) at world xz. footprint: blocks covered by one pixel on the surface (0 = full
// detail, used by the caustic). lostVar receives the slope variance of the filtered-out components (roughness of the sheen).
// hf > 0 boosts the short waves (gain 1 + hf * k): the caustic field, see CAUSTIC_* in the caustic section below.
vec2 waterSlopeHf(vec2 xz, float time, float footprint, float hf, out float lostVar) {
    vec2 slope = vec2(0.0);
    lostVar = 0.0;
    for (int i = 0; i < WATER_WAVES; i++) {
        vec4 w = WATER_WAVE_A[i];
        float k = w.z;
        float omega = 0.9 * sqrt(k);
        float att = 1.0 - smoothstep(0.25, 1.0, k * footprint);
        float ph = k * dot(w.xy, xz) - omega * time + WATER_WAVE_PHASE[i];
        slope += w.xy * (w.w * (1.0 + hf * k) * att * cos(ph));
        lostVar += 0.5 * w.w * w.w * (1.0 - att * att);
    }
    return slope;
}

vec2 waterSlope(vec2 xz, float time, float footprint, out float lostVar) {
    return waterSlopeHf(xz, time, footprint, 0.0, lostVar);
}

vec2 waterSlope(vec2 xz, float time) {
    float lv;
    return waterSlope(xz, time, 0.0, lv);
}

// Upward-facing water normal from a slope.
vec3 waterNormalFromSlope(vec2 slope) {
    return normalize(vec3(-slope.x, 1.0, -slope.y));
}

// Unpolarised dielectric reflectance. cosI: cosine of the incidence angle (>= 0), eta = n_transmitted / n_incident.
// Returns 1.0 for total internal reflection.
float fresnelDielectric(float cosI, float eta) {
    cosI = clamp(cosI, 0.0, 1.0);
    float sinT2 = (1.0 - cosI * cosI) / (eta * eta);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (cosI - eta * cosT) / (cosI + eta * cosT);
    float rp = (eta * cosI - cosT) / (eta * cosI + cosT);
    return clamp(0.5 * (rs * rs + rp * rp), 0.0, 1.0);
}

// ---- analytic sun caustic ---------------------------------------------------------------------------------------------------
// Sunlight travelling along -L (L towards the sun) enters the wavy surface at X, is refracted by the local normal and reaches the
// plane h blocks below the surface at B = X + disp(X). The light density on that plane relative to flat water is 1 / |det dB/dX|
// (flux conservation). X for a given B is found by a fixed point iteration (disp varies slowly); dB/dX by central differences of
// the same analytic field. Near the focal lines det -> 0: the density is capped, which also is the finite size of the sun.

// The caustic reads the same field with its slopes scaled by CAUSTIC_SLOPE_GAIN: real ripples are steeper in curvature than the
// calm swell shown on the surface, and with the unscaled field the light pattern is a faint +-20 % wobble instead of bright lines.
const float CAUSTIC_SLOPE_GAIN = 1.0;
const float CAUSTIC_HF = 0.35;

vec2 waterRefractDisp(vec2 X, vec3 L, float h, float time) {
    float lv;
    vec3 n = waterNormalFromSlope(waterSlopeHf(X, time, 0.0, CAUSTIC_HF, lv) * CAUSTIC_SLOPE_GAIN);
    vec3 rd = refract(-L, n, 1.0 / WATER_IOR);
    return rd.xz * (h / max(-rd.y, 0.3));
}

// Light density factor (1 = flat water) at world xz B, h blocks below the surface, sun direction L (L.y > 0).
float waterCaustic(vec2 B, vec3 L, float h, float time) {
    vec2 X = B - waterRefractDisp(B, L, h, time);
    X = B - waterRefractDisp(X, L, h, time);
    X = B - waterRefractDisp(X, L, h, time);
    const float e = 0.04;
    vec2 dx = (waterRefractDisp(X + vec2(e, 0.0), L, h, time) - waterRefractDisp(X - vec2(e, 0.0), L, h, time)) / (2.0 * e);
    vec2 dz = (waterRefractDisp(X + vec2(0.0, e), L, h, time) - waterRefractDisp(X - vec2(0.0, e), L, h, time)) / (2.0 * e);
    float det = abs((1.0 + dx.x) * (1.0 + dz.y) - dz.x * dx.y);
    float c = clamp(1.0 / max(det, 0.10), 0.35, 3.0);
    // Deep light is scattered and the sun has a finite disk: fade the contrast with depth.
    // Crisp, radiant caustics matching Bedrock RTX / RenderDragon on shallow riverbeds and shores
    float contrast = 0.85 / (1.0 + h * (1.0 / 7.0));
    return mix(1.0, c, contrast);
}
