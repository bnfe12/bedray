// Existing NRD/RR signal conversion, shared by format-specific AOT entries.
// Formula mirror: shaders/glsl/rt_pack_nrd.comp and denoise/NrdPackMath.java.
public module nrd_pack;

import "tonemap_curve.slang";

struct NrdPackPush {
    uint normalEncoding;
    uint roughnessEncoding;
    uint denoiser;
    uint flags;
    // Scalar fields retain the legacy offsets without rounding the block to
    // vec4's 16-byte alignment: the complete reflected push remains 56 bytes.
    float hitDistA;
    float hitDistB;
    float hitDistC;
    float denoisingRange;
    uint2 size;
    float exposure;
    uint reserved;
    uint64_t motionAddress;
};

static const float NRD_FP16_MAX = 65504.0;

float3 nrdEncodeOct1010(float3 normal, float roughness) {
    normal /= abs(normal.x) + abs(normal.y) + abs(normal.z);
    float3 result;
    result.y = normal.y * 0.5 + 0.5;
    result.x = normal.x * 0.5 + result.y;
    result.y -= normal.x * 0.5;
    roughness = max(roughness, 1.5 / 512.0);
    result.z = (normal.z < 0.0 ? -roughness : roughness) * 0.5 + 0.5;
    return result;
}

float4 nrdPackNormalRoughness(float3 normal, float roughness, NrdPackPush pc) {
    if (pc.roughnessEncoding == 2u) roughness = sqrt(clamp(roughness, 0.0, 1.0));
    else if (pc.roughnessEncoding == 0u) roughness *= roughness;
    if (pc.normalEncoding == 2u) return float4(nrdEncodeOct1010(normal, roughness), 0.0);
    normal /= max(abs(normal.x), max(abs(normal.y), abs(normal.z)));
    if (pc.normalEncoding == 0u || pc.normalEncoding == 3u) normal = normal * 0.5 + 0.5;
    return float4(normal, roughness);
}

float3 nrdLinearToYCoCg(float3 color) {
    return float3(dot(color, float3(0.25, 0.5, 0.25)),
        dot(color, float3(0.5, 0.0, -0.5)), dot(color, float3(-0.25, 0.5, -0.25)));
}

float3 nrdYCoCgToLinear(float3 color) {
    float value = color.x - color.z;
    return max(float3(value + color.y, color.x + color.z, value - color.y), float3(0.0));
}

float nrdSpecMagicCurve(float roughness, float power) {
    float factor = 1.0 - exp2(-200.0 * roughness * roughness);
    return factor * pow(clamp(roughness, 0.0, 1.0), power);
}

float nrdHitDistanceNormalization(float viewZ, float3 parameters, float roughness) {
    return (parameters.x + abs(viewZ) * parameters.y) *
        lerp(parameters.z, 1.0, nrdSpecMagicCurve(roughness, 0.5));
}

bool nrdInvalid3(float3 value) { return any(isnan(value)) || any(isinf(value)); }
bool nrdIgnored(float viewZ, NrdPackPush pc) { return !(abs(viewZ) < pc.denoisingRange); }

struct NrdPackInput {
    float viewZ;
    float4 normalRoughness;
    float4 diffuse;
    float4 specular;
    float3 albedo;
    float3 direct;
    float3 specularAlbedo;
    float4 motion;
};

struct NrdPackedSample {
    float4 normalRoughness;
    float4 diffuse;
    float4 specular;
    float4 motion;
};

NrdPackedSample nrdPackSample(NrdPackInput input, NrdPackPush pc) {
    NrdPackedSample result;
    result.normalRoughness = nrdPackNormalRoughness(float3(0.0, 0.0, 1.0), 1.0, pc);
    result.diffuse = float4(0.0);
    result.specular = float4(0.0);
    result.motion = float4(0.0);
    if (nrdIgnored(input.viewZ, pc)) return result;
    float4 nr = input.normalRoughness;
    float3 normal = nr.xyz * rsqrt(dot(nr.xyz, nr.xyz) + 1.0e-9);
    result.normalRoughness = nrdPackNormalRoughness(normal, nr.w, pc);
    float roughness = clamp(nr.w, 0.0, 1.0);
    float3 radiance = nrdInvalid3(input.diffuse.rgb) ? float3(0.0) :
        clamp(input.diffuse.rgb, float3(0.0), float3(NRD_FP16_MAX));
    float distance = !isfinite(input.diffuse.a) ? 0.0 : max(input.diffuse.a, 0.0);
    float3 specular = nrdInvalid3(input.specular.rgb) ? float3(0.0) :
        clamp(input.specular.rgb, float3(0.0), float3(NRD_FP16_MAX));
    float specularDistance = !isfinite(input.specular.a) ? 0.0 : max(input.specular.a, 0.0);
    if (pc.denoiser == 2u) {
        float3 direct = nrdInvalid3(input.direct) ? float3(0.0) : input.direct;
        result.diffuse = float4(clamp(radiance + specular + direct, float3(0.0), float3(NRD_FP16_MAX)), 1.0);
        result.specular = float4(clamp(input.specularAlbedo, float3(0.0), float3(1.0)), 1.0);
        return result;
    }
    if (input.motion.w > 0.5 && !nrdInvalid3(input.motion.xyz) && !(input.motion.x > 1.0e29))
        result.motion = float4(input.motion.xyz, 0.0);
    float3 ambient = radiance / max(input.albedo, float3(0.02));
    if (pc.denoiser == 0u) {
        float3 parameters = float3(pc.hitDistA, pc.hitDistB, pc.hitDistC);
        float distanceScale = nrdHitDistanceNormalization(input.viewZ, parameters, 1.0);
        float specularScale = nrdHitDistanceNormalization(input.viewZ, parameters, roughness);
        result.diffuse = float4(nrdLinearToYCoCg(ambient), clamp(distance / distanceScale, 0.0, 1.0));
        result.specular = float4(nrdLinearToYCoCg(specular), clamp(specularDistance / specularScale, 0.0, 1.0));
    } else {
        result.diffuse = float4(ambient, min(distance, NRD_FP16_MAX));
        result.specular = float4(specular, min(specularDistance, NRD_FP16_MAX));
    }
    return result;
}

float3 nrdComposeColor(float3 diffuse, float3 specular, float3 albedo, float3 direct, NrdPackPush pc) {
    float3 color;
    if (pc.denoiser == 2u) color = max(diffuse, float3(0.0));
    else {
        float3 ambient = pc.denoiser == 0u ? nrdYCoCgToLinear(diffuse) : max(diffuse, float3(0.0));
        float3 reflection = pc.denoiser == 0u ? nrdYCoCgToLinear(specular) : max(specular, float3(0.0));
        color = albedo * ambient + reflection + direct;
    }
    if ((pc.flags & 1u) != 0u) color = tonemapColor(color * pc.exposure);
    return clamp(color, float3(0.0), float3(1.0));
}
