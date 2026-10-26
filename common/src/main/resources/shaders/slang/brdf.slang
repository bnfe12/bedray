// brdf.slang — Physically based shading: GGX microfacet BRDF, Fresnel, Lambert.
// Ported from rt_common_core.glsl (BRDF section) and rt_rays.comp (reflection sampling).
// Pure math — no scene access.

module brdf;

import types;
import sampling;

// ---- Fresnel ----

// Schlick's approximation: F(cosTheta) = F0 + (1 - F0) * (1 - cosTheta)^5
float3 fresnelSchlick(float cosTheta, float3 f0) {
    float t = 1.0 - cosTheta;
    float t2 = t * t;
    return f0 + (1.0 - f0) * (t2 * t2 * t);
}

float fresnelSchlickScalar(float cosTheta, float f0) {
    float t = 1.0 - cosTheta;
    float t2 = t * t;
    return f0 + (1.0 - f0) * (t2 * t2 * t);
}

// ---- GGX / Trowbridge-Reitz NDF ----

// D(h) = alpha^2 / (PI * ((n·h)^2 * (alpha^2 - 1) + 1)^2)
float ggxNDF(float NdotH, float alpha) {
    float a2    = alpha * alpha;
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

// Sample visible normals of the GGX distribution.
// Returns the half vector h in tangent space.
float3 sampleGGXVNDF(float3 Ve, float alpha, float2 u) {
    // Stretch the view vector
    float3 Vh = normalize(float3(alpha * Ve.x, alpha * Ve.y, Ve.z));

    // Orthonormal basis around Vh
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);

    // Sample point on disk
    float r   = sqrt(u.x);
    float phi = TWO_PI * u.y;
    float t1  = r * cos(phi);
    float t2  = r * sin(phi);
    float s   = 0.5 * (1.0 + Vh.z);
    t2 = (1.0 - s) * sqrt(1.0 - t1 * t1) + s * t2;

    // Compute normal
    float3 Nh = t1 * T1 + t2 * T2 + sqrt(max(0.0, 1.0 - t1 * t1 - t2 * t2)) * Vh;

    // Unstretch
    return normalize(float3(alpha * Nh.x, alpha * Nh.y, max(0.0, Nh.z)));
}

// ---- Reflection ray generation ----

// Generate a reflection direction using GGX VNDF importance sampling.
// Returns the reflected direction and the sample weight (F * G2 / G1).
// `V` is the incoming (view) direction pointing away from the surface.
// `N` is the shading normal.
float3 sampleReflection(float3 V, float3 N, float roughness, float f0,
                         inout uint seed, out float3 weight) {
    float alpha = roughness * roughness;

    // Build tangent frame around N
    float3 T, B;
    basisAround(N, T, B);

    // Transform V to tangent space
    float3 Ve = float3(dot(V, T), dot(V, B), dot(V, N));

    // Sample half vector
    float2 u  = float2(rand01(seed), rand01(seed));
    float3 He = sampleGGXVNDF(Ve, alpha, u);

    // Reflect
    float3 Le = reflect(-Ve, He);
    if (Le.z <= 0.0) {
        weight = float3(0.0);
        return float3(0.0);
    }

    // Transform back to world space
    float3 L = T * Le.x + B * Le.y + N * Le.z;

    // Weight: F(V·H) * G2(V,L) / G1(V)
    float VdotH = max(dot(Ve, He), 0.0);
    float NdotV = max(Ve.z, 0.001);
    float NdotL = max(Le.z, 0.001);
    float3 F    = fresnelSchlick(VdotH, float3(f0));
    float  G2   = ggxSmithG2(NdotV, NdotL, alpha);
    float  G1   = ggxSmithG1(NdotV, alpha);
    weight = F * (G2 / G1);

    return normalize(L);
}

// ---- Energy-conserving diffuse/specular split ----

// Determines how much energy goes to diffuse vs specular lobe.
// Metals: all energy to specular (no diffuse).
// Dielectrics: probability of specular reflection ~ Fresnel at the view angle.
float specularProbability(float3 V, float3 N, float f0, bool isMetal) {
    if (isMetal) return 1.0;
    float cosTheta = max(dot(V, N), 0.0);
    float F = fresnelSchlickScalar(cosTheta, f0);
    // Clamp to avoid zero probability for either lobe
    return clamp(F, 0.04, 0.96);
}
