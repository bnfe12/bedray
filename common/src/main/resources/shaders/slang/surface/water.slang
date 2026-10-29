// Water surface model for the typed surface decoder.
//
// This is a port of the legacy GLSL wave field (rt_caustics.glsl) and the
// classification / tint rules of rt_common_water.glsl.  It owns three things
// and nothing else:
//
//   * the analytic wave field: one slope function in world X/Z that is the only
//     source of the water shading normal, filtered by the pixel footprint so
//     far water fades to a soft sheen instead of aliasing;
//   * the water test: which translucent surface is a fluid rather than glass;
//   * the biome tint: the vertex colour with the baked vanilla face shade
//     divided out.
//
// What is *not* here: the volumetric medium (depth absorption, in-scattering,
// Snell refraction of the camera ray, caustics) that the legacy realistic style
// runs through traceThroughInterfacesM.  The typed path tracer has no medium
// state yet, so water is shaded as a Fresnel interface with a biome-tinted
// transmission; the medium is a separate follow-up.

public module water;

import "contracts/math.slang";

// Index of refraction of clear water.  Ice and glass keep the option default.
static const float WATER_IOR = 1.333;

// Radians per pixel of the 3D view (about 70 deg fov at 750 px, conservative),
// used to turn the hit distance into a surface footprint for the wave filter.
static const float WATER_PIXEL_ANGLE = 0.0016;

// A calm lake reads as flat glass from a player's viewing angle, so the long
// swells carry most of the slope and the short ripples are small enough not to
// shimmer.  Directions are unit XZ, k is rad/block and w is the peak slope.
static const int WATER_WAVES = 6;
static const float4 WATER_WAVE_A[WATER_WAVES] = {
    float4(0.800, 0.600, 1.00, 0.050),
    float4(-0.700, 0.714, 1.70, 0.048),
    float4(0.312, -0.950, 2.90, 0.036),
    float4(-0.911, -0.412, 4.60, 0.030),
    float4(0.550, 0.835, 7.30, 0.018),
    float4(-0.200, -0.980, 11.5, 0.012)
};
static const float WATER_WAVE_PHASE[WATER_WAVES] = { 0.0, 1.3, 2.9, 4.1, 5.2, 0.7 };

// Gradient of the height field (dh/dx, dh/dz) at world xz.  A wave whose period
// the footprint does not resolve fades out, and the slope variance it leaves
// behind is returned as the extra roughness of the sheen.
float2 waterSlope(float2 xz, float time, float footprint, out float lostVar) {
    float2 slope = float2(0.0);
    lostVar = 0.0;
    for (int i = 0; i < WATER_WAVES; ++i) {
        float4 w = WATER_WAVE_A[i];
        float k = w.z;
        float omega = 0.9 * sqrt(k);
        float attenuation = 1.0 - smoothstep(0.25, 1.0, k * footprint);
        float phase = k * dot(w.xy, xz) - omega * time + WATER_WAVE_PHASE[i];
        slope += w.xy * (w.w * attenuation * cos(phase));
        lostVar += 0.5 * w.w * w.w * (1.0 - attenuation * attenuation);
    }
    return slope;
}

float2 waterSlope(float2 xz, float time) {
    float lostVar;
    return waterSlope(xz, time, 0.0, lostVar);
}

// Shading normal of a horizontal water face, perturbed by the wave field and
// kept on the same side as the geometric normal.  Flowing water (a sloped top
// quad) carries the wave pattern downhill; its vertical sides stay flat.
float3 waterWaveNormal(float3 worldPosition, float3 geometricNormal, float time,
        float footprint, out float lostVar) {
    lostVar = 0.0;
    if (abs(geometricNormal.y) < 0.7) {
        return geometricNormal;
    }
    float2 xz = worldPosition.xz;
    float2 downhill = geometricNormal.xz * sign(geometricNormal.y);
    float flow = length(downhill);
    if (flow > 0.02) {
        xz -= downhill / flow * (time * min(flow * 10.0, 1.6));
    }
    float2 slope = waterSlope(xz, time, footprint, lostVar);
    // The underside of the sheet sees the same slope with the opposite sign.
    float3 perturbed = safeNormalize(geometricNormal +
        float3(-slope.x, 0.0, -slope.y) * sign(geometricNormal.y), geometricNormal);
    return dot(perturbed, geometricNormal) > 0.0 ? perturbed : geometricNormal;
}

// Vanilla face shade baked into a section vertex colour (mirror of
// RtLighting.cardinalShade): the biome tint of a fluid is that colour with the
// shade divided back out.
float cardinalFaceShade(float3 normal) {
    return normal.y * normal.y * (normal.y > 0.0 ? 1.0 : 0.5)
        + normal.x * normal.x * 0.6 + normal.z * normal.z * 0.8;
}

float3 waterBiomeTint(float3 vertexColor, float3 geometricNormal) {
    return clamp(vertexColor / max(cardinalFaceShade(geometricNormal), 0.5),
        float3(0.0), float3(1.0));
}

// A fluid is the one translucent surface the auto-PBR atlas tags with water's
// reflectance and roughness (f0 0.02, roughness 0.02).  Glass is f0 0.04 and
// ice is roughness 0.05, so both stay panes.  The texel alpha is deliberately
// not part of the test: the 26.3 water sprite is opaque and its translucency
// lives in the render pass.
bool waterIsSurface(bool translucent, float3 f0, float roughness, float metallic) {
    return translucent && luminance(f0) <= 0.025 && roughness <= 0.035 && metallic < 0.5;
}
