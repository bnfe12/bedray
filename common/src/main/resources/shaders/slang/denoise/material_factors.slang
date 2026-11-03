// GGX environment reflectance and stable NRD material factors.
// Formula contract: NVIDIA NRD 4.18 NRD.hlsli / NRD_MaterialFactors and
// Ray Tracing Gems, chapter 32, equation 4. Both pack and compose use these factors.
public module material_factors;

float3 denoiserEnvironmentReflectance(float3 f0, float NoV, float roughness) {
    NoV = saturate(NoV);
    float m = saturate(roughness * roughness);
    float4 x = float4(1.0, saturate(NoV), NoV * NoV, NoV * NoV * NoV);
    float4 y = float4(1.0, m, m * m, m * m * m);
    float2x2 biasNumerator = float2x2(0.99044, -1.28514, 1.29678, -0.755907);
    float3x3 biasDenominator = float3x3(1.0, 2.92338, 59.4188,
        20.3225, -27.0302, 222.592, 121.563, 626.13, 316.627);
    float2x2 scaleNumerator = float2x2(0.0365463, 3.32707, 9.0632, -9.04756);
    float3x3 scaleDenominator = float3x3(1.0, 3.59685, -1.36772,
        9.04401, -16.3174, 9.22949, 5.56589, 19.7886, -20.2123);
    float bias = dot(mul(biasNumerator, x.xy), y.xy)
        / max(dot(mul(biasDenominator, x.xyw), y.xyw), 1.0e-6);
    float scale = dot(mul(scaleNumerator, x.xy), y.xy)
        / max(dot(mul(scaleDenominator, x.xzw), y.xyw), 1.0e-6);
    return saturate(f0 * scale + bias);
}

float3 denoiserDiffuseFactor(float3 albedo, float3 reflectance) {
    return lerp(float3(0.02), float3(1.0), saturate(albedo) * (1.0 - saturate(reflectance)));
}

float3 denoiserSpecularFactor(float3 reflectance, float roughness) {
    float roughnessFactor = lerp(0.1, 1.0, saturate(roughness));
    return lerp(float3(0.02), float3(1.0), saturate(reflectance) * roughnessFactor);
}
