// Bedray 2.0 — Specialized Optical Shading & Micro-Algorithms.
//
// Implements Section 3.3 of the Architectural Manifesto:
//  1. Thin-Slab Subsurface Scattering (Beer-Lambert forward transmission through foliage & banners).
//  2. Analytical Water Caustics (2D wave normal divergence projection on river/ocean beds).
//
// Designed for seamless integration into the Slang BSDF and Deferred Shading passes.

public module optics;

import "contracts/material.slang";
import "contracts/math.slang";

// ============================================================================
// 1. Thin-Slab Subsurface Scattering (Beer-Lambert Absorption & Forward Scattering)
// ============================================================================

public struct ThinSlabParams {
    public float3 scatteringCoeff; // sigma_s (scattering per block unit)
    public float3 absorptionCoeff; // sigma_a (absorption per block unit)
    public float  anisotropy;      // Henyey-Greenstein g parameter (~0.4 - 0.6 for leaves)
    public float  defaultThickness;// default thickness in blocks when not authored (e.g. 0.05 = 5cm)
};

// Default preset for Minecraft oak/birch/jungle foliage and cloth banners
public ThinSlabParams foliageThinSlabParams() {
    ThinSlabParams params;
    // Foliage selectively scatters green/yellow, strongly absorbs red/blue for photosynthesis
    params.scatteringCoeff = float3(1.2, 2.5, 0.8);
    params.absorptionCoeff = float3(4.0, 0.6, 5.0);
    params.anisotropy = 0.55;
    params.defaultThickness = 0.05; // 5 cm leaf layer
    return params;
}

// Henyey-Greenstein phase function for forward transmission
public float henyeyGreensteinPhase(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 / (4.0 * PI)) * (1.0 - g2) / max(denom * sqrt(max(denom, 1.0e-5)), 1.0e-5);
}

// Evaluates thin-slab subsurface transmission from sunlight or strong local emitters.
// V: view direction (pointing away from surface towards camera)
// L: light incident direction (pointing towards light source)
// N: geometric surface normal
public float3 evaluateThinSlabTransmission(
    MaterialSample material,
    float3 V,
    float3 L,
    float3 N,
    float3 lightRadiance,
    ThinSlabParams params)
{
    // Subsurface transmission is only active when light shines from behind the surface (N . L < 0)
    // or when the surface is specifically tagged as foliage / thin subsurface.
    float NdotL = dot(N, L);
    bool backLit = NdotL < 0.0;
    bool isSubsurface = (material.flags & MATERIAL_FLAG_SUBSURFACE) != 0u ||
                        (material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u;

    if (!isSubsurface && !backLit) {
        return float3(0.0);
    }

    // Effective slab thickness along the light ray path
    float slabThickness = material.thickness > 1.0e-4 ? material.thickness : params.defaultThickness;
    float pathLength = slabThickness / max(abs(NdotL), 0.1);

    // Beer-Lambert extinction: exp(-(sigma_a + sigma_s) * d)
    float3 extinction = params.absorptionCoeff + params.scatteringCoeff;
    float3 transmittance = exp(-extinction * pathLength);

    // Forward transmission lobe: peak when looking directly through the slab into the light source
    // View vector V and light vector L: back-scattering occurs when V . (-L) is close to 1
    float cosTheta = dot(V, -L);
    float phase = henyeyGreensteinPhase(cosTheta, params.anisotropy);

    // Modulate by surface base albedo to match the leaf/banner color palette
    float3 transmittedFlux = transmittance * material.albedo * phase * (2.0 * PI);

    return transmittedFlux * lightRadiance;
}

// ============================================================================
// 2. Analytical Water Caustics via 2D Divergence of Wave Normals
// ============================================================================

public struct WaterCausticParams {
    public float focusStrength;     // Intensity scale of convergence
    public float waveScale;         // Spatial frequency of water ripples
    public float waveSpeed;         // Temporal animation speed
    public float3 waterExtinction;  // Absorption per meter (R absorbs fastest)
};

public WaterCausticParams defaultWaterCausticParams() {
    WaterCausticParams p;
    p.focusStrength = 0.85;
    p.waveScale = 1.25;
    p.waveSpeed = 1.2;
    // Water absorbs red light rapidly, green moderately, blue slowly
    p.waterExtinction = float3(0.22, 0.05, 0.015);
    return p;
}

// Procedural wave height function from 3 harmonic waves
float evaluateWaveHeight(float2 uv, float time, float scale) {
    float2 p = uv * scale;
    float h = sin(p.x * 2.1 + time * 1.5) * cos(p.y * 1.8 + time * 1.2);
    h += sin(p.x * 4.3 - time * 2.1 + p.y * 3.2) * 0.5;
    h += cos(p.y * 6.7 + time * 3.0 - p.x * 2.5) * 0.25;
    return h * 0.1; // 10cm amplitude max
}

// Computes 2D wave surface normal and the divergence of horizontal slope (laplacian of height)
void evaluateWaveDivergence(float2 worldXZ, float time, WaterCausticParams params,
                            out float3 waterNormal, out float divergence) {
    float eps = 0.05;
    float hC = evaluateWaveHeight(worldXZ, time * params.waveSpeed, params.waveScale);
    float hR = evaluateWaveHeight(worldXZ + float2(eps, 0.0), time * params.waveSpeed, params.waveScale);
    float hL = evaluateWaveHeight(worldXZ - float2(eps, 0.0), time * params.waveSpeed, params.waveScale);
    float hU = evaluateWaveHeight(worldXZ + float2(0.0, eps), time * params.waveSpeed, params.waveScale);
    float hD = evaluateWaveHeight(worldXZ - float2(0.0, eps), time * params.waveSpeed, params.waveScale);

    float dX = (hR - hL) / (2.0 * eps);
    float dZ = (hU - hD) / (2.0 * eps);

    // Water surface normal
    waterNormal = normalize(float3(-dX, 1.0, -dZ));

    // 2D Laplacian of wave surface = divergence of gradient (focusing power)
    // Positive laplacian means wave valley (converging lens focusing sunlight)
    float laplacian = (hR + hL + hU + hD - 4.0 * hC) / (eps * eps);
    divergence = laplacian;
}

// Computes analytical caustic multiplier for underwater ground points:
// worldPos: position of the sand/gravel/bedrock underwater
// waterSurfaceY: world Y level of water surface above this point
// sunDir: normalized vector towards the sun
// time: game time in seconds
public float3 evaluateWaterCaustics(
    float3 worldPos,
    float  waterSurfaceY,
    float3 sunDir,
    float  time,
    WaterCausticParams params)
{
    float waterDepth = waterSurfaceY - worldPos.y;
    if (waterDepth <= 1.0e-3 || sunDir.y <= 0.05) {
        return float3(1.0); // Not underwater or sun is below horizon
    }

    // Trace back to water entry point along sun ray
    float tWaterEntry = waterDepth / sunDir.y;
    float2 waterSurfaceXZ = worldPos.xz + sunDir.xz * tWaterEntry;

    // Evaluate wave surface normal and convergence divergence at the entry point
    float3 waterNormal;
    float divergence;
    evaluateWaveDivergence(waterSurfaceXZ, time, params, waterNormal, divergence);

    // Caustic intensity formula:
    // When divergence > 0, wave acts as a convex lens, concentrating rays on the sea bed.
    // Soft-saturating rational limiter prevents numerical firefly blowups at deep ocean depths (>10 blocks):
    float rawFocus = divergence * waterDepth * params.focusStrength;
    float softFocus = rawFocus / (1.0 + abs(rawFocus) * 0.25);
    float caustic = max(0.0, 1.0 + softFocus);

    // Non-linear contrast curve for sharp dancing caustic ribbons
    caustic = pow(caustic, 1.5);

    // Optical absorption of water according to Beer's Law across ray path length
    float slantDepth = waterDepth / max(sunDir.y, 0.1);
    float3 waterTransmittance = exp(-params.waterExtinction * slantDepth);

    return waterTransmittance * caustic;
}
