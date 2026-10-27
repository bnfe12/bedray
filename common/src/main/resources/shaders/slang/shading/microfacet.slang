// Microfacet BRDF math for Bedray 2.0.
//
// This is the canonical implementation used by new shading modules.  The old
// brdf.slang is kept only as a compatibility re-export; the math is identical
// in behaviour but no longer depends on the legacy types module.

public module microfacet;

import "contracts/math.slang";
import "sampling.slang";

// ---- Fresnel ----

float3 fresnelSchlick(float cosTheta, float3 f0) {
    float t = 1.0 - cosTheta;
    return f0 + (1.0 - f0) * pow5(t);
}

float fresnelSchlickScalar(float cosTheta, float f0) {
    float t = 1.0 - cosTheta;
    return f0 + (1.0 - f0) * pow5(t);
}

// ---- GGX / Trowbridge-Reitz NDF ----

float ggxNDF(float NdotH, float alpha) {
    float a2 = alpha * alpha;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

// ---- Smith G2 / G1 for GGX ----

float ggxSmithG1(float NdotV, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * NdotV / (NdotV + sqrt(a2 + (1.0 - a2) * NdotV * NdotV));
}

float ggxSmithG2(float NdotV, float NdotL, float alpha) {
    return ggxSmithG1(NdotV, alpha) * ggxSmithG1(NdotL, alpha);
}

// ---- GGX VNDF sampling (Heitz 2018) ----

float3 sampleGGXVNDF(float3 Ve, float alpha, float2 u) {
    float3 Vh = normalize(float3(alpha * Ve.x, alpha * Ve.y, Ve.z));

    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);

    float r = sqrt(u.x);
    float phi = TWO_PI * u.y;
    float t1 = r * cos(phi);
    float t2 = r * sin(phi);
    float s = 0.5 * (1.0 + Vh.z);
    t2 = (1.0 - s) * sqrt(1.0 - t1 * t1) + s * t2;

    float3 Nh = t1 * T1 + t2 * T2 + sqrt(max(0.0, 1.0 - t1 * t1 - t2 * t2)) * Vh;
    return normalize(float3(alpha * Nh.x, alpha * Nh.y, max(0.0, Nh.z)));
}

// ---- Reflection ray generation ----

// `V` points away from the surface, `N` is the shading normal.
float3 sampleReflection(float3 V, float3 N, float roughness, float f0,
        inout uint seed, out float3 weight) {
    float alpha = max(roughness * roughness, 1.0e-4);

    float3 T, B;
    basisAround(N, T, B);
    float3 Ve = float3(dot(V, T), dot(V, B), dot(V, N));

    float2 u = float2(rand01(seed), rand01(seed));
    float3 He = sampleGGXVNDF(Ve, alpha, u);
    float3 Le = reflect(-Ve, He);
    if (Le.z <= 0.0) {
        weight = float3(0.0);
        return float3(0.0);
    }

    float3 L = T * Le.x + B * Le.y + N * Le.z;
    float VdotH = max(dot(Ve, He), 0.0);
    float NdotV = max(Ve.z, 0.001);
    float NdotL = max(Le.z, 0.001);
    float3 F = fresnelSchlick(VdotH, float3(f0));
    float G2 = ggxSmithG2(NdotV, NdotL, alpha);
    float G1 = ggxSmithG1(NdotV, alpha);
    weight = F * (G2 / max(G1, 1.0e-6));
    return normalize(L);
}

// Probability of taking the specular lobe.  Metals are pure specular; a
// dielectric uses its Fresnel reflectance at the view angle.
float specularProbability(float3 V, float3 N, float f0, bool isMetal) {
    if (isMetal) return 1.0;
    float cosTheta = max(dot(V, N), 0.0);
    float F = fresnelSchlickScalar(cosTheta, f0);
    return clamp(F, 0.04, 0.96);
}

// PDF of a GGX reflection direction for a given view direction.  This is the
// visible-normal (VNDF) density that sampleGGXVNDF draws from, not the plain
// half-vector NDF density; MIS and reservoir reuse must use the same density as
// the sampler.
float ggxReflectionPdf(float3 V, float3 N, float3 L, float roughness) {
    float alpha = max(roughness * roughness, 1.0e-4);
    float3 H = safeNormalize(V + L, N);
    float NdotH = max(dot(N, H), 0.0);
    float NdotV = max(dot(N, V), 1.0e-4);
    return ggxNDF(NdotH, alpha) * ggxSmithG1(NdotV, alpha) / (4.0 * NdotV);
}

float cosinePdf(float3 N, float3 L) {
    return max(dot(N, L), 0.0) * INV_PI;
}
