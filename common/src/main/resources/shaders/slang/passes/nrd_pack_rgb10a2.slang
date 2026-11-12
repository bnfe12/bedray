// NRD input packing, AOT Slang entry.
//
// One source for NRD (REBLUR/RELAX) and DLSS-RR input preparation. The host
// keeps its 14-image descriptor set and its 56-byte push block; only the
// bindings this entry reads or writes are declared.
//
//  0 viewZ (R32F)                 read
//  1 normal+roughness (RGBA16F)   read
//  2 diffuse radiance+hit (RGBA16F) read
//  3 albedo (RGBA8)               read
//  4 direct light (RGBA16F)       read
//  5 NRD normal/roughness (R10G10B10A2_UNORM) write
//  6 NRD diffuse input (RGBA16F)  write
//  9 specular radiance+hit (RGBA16F) read
// 10 NRD specular input (RGBA16F) write
// 12 NRD motion (RGBA16F)         write
// 13 specular albedo (RGBA8)      read
//
// The NRD build must report R10G10B10A2 normal/roughness. The host rejects
// RGBA16_SNORM explicitly instead of binding a view the SPIR-V format does not
// match.

public module nrd_pack_rgb10a2_pass;

[[vk::image_format("r32f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> gbViewZ;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> gbNormalRough;
[[vk::image_format("rgba16f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> gbDiffuse;
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> gbAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(4, 0)]] RWTexture2D<float4> gbDirect;
[[vk::image_format("rgb10a2")]] [[vk::binding(5, 0)]] RWTexture2D<float4> nrdInNormalRough;
[[vk::image_format("rgba16f")]] [[vk::binding(6, 0)]] RWTexture2D<float4> nrdInDiffuse;
[[vk::image_format("rgba16f")]] [[vk::binding(9, 0)]] RWTexture2D<float4> gbSpecular;
[[vk::image_format("rgba16f")]] [[vk::binding(10, 0)]] RWTexture2D<float4> nrdInSpecular;
[[vk::image_format("rgba16f")]] [[vk::binding(12, 0)]] RWTexture2D<float4> nrdInMotion;
[[vk::image_format("rgba8")]] [[vk::binding(13, 0)]] RWTexture2D<float4> gbSpecAlbedo;

// The hit-distance vec4 is spelled as four scalars: a float4 would give the
// block 16-byte alignment and Slang would round the reflected size to 64 B,
// while the Vulkan host contract is 56 B.  The byte layout is identical.
struct NrdPush {
    uint normalEncoding;     // nrd::NormalEncoding
    uint roughnessEncoding;  // nrd::RoughnessEncoding
    uint denoiser;           // 0 REBLUR, 1 RELAX, 2 DLSS-RR
    uint flags;              // bit 0 tonemap, bit 1 split view
    float hitDist0;          // REBLUR A
    float hitDist1;          // REBLUR B
    float hitDist2;          // REBLUR C
    float hitDist3;          // denoising range
    uint2 size;
    float exposure;
    uint pad;
    uint2 motionAddr;        // Vec4Buf address of the primary-hit world motion
};

[[vk::push_constant]] NrdPush pc;

static const float FP16_MAX = 65504.0;

float3 encodeOct1010(float3 n, float roughness)
{
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    float3 r;
    r.y = n.y * 0.5 + 0.5;
    r.x = n.x * 0.5 + r.y;
    r.y -= n.x * 0.5;
    roughness = max(roughness, 1.5 / 512.0);
    r.z = (n.z < 0.0 ? -roughness : roughness) * 0.5 + 0.5;
    return r;
}

float4 packNormalRoughness(float3 n, float roughness)
{
    if (pc.roughnessEncoding == 2u) {
        roughness = sqrt(clamp(roughness, 0.0, 1.0));
    } else if (pc.roughnessEncoding == 0u) {
        roughness *= roughness;
    }
    if (pc.normalEncoding == 2u) {
        return float4(encodeOct1010(n, roughness), 0.0);
    }
    n /= max(abs(n.x), max(abs(n.y), abs(n.z)));
    if (pc.normalEncoding == 0u || pc.normalEncoding == 3u) {
        n = n * 0.5 + 0.5;
    }
    return float4(n, roughness);
}

float3 linearToYCoCg(float3 c)
{
    return float3(dot(c, float3(0.25, 0.5, 0.25)), dot(c, float3(0.5, 0.0, -0.5)),
        dot(c, float3(-0.25, 0.5, -0.25)));
}

float specMagicCurve(float roughness, float power)
{
    float f = 1.0 - exp2(-200.0 * roughness * roughness);
    return f * pow(clamp(roughness, 0.0, 1.0), power);
}

float hitDistanceNormalization(float viewZ, float3 params, float roughness)
{
    return (params.x + abs(viewZ) * params.y) * lerp(params.z, 1.0, specMagicCurve(roughness, 0.5));
}

bool invalid3(float3 v)
{
    return any(isnan(v)) || any(isinf(v));
}

uint64_t addressOf(uint low, uint high)
{
    return (uint64_t)low | ((uint64_t)high << 32);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 threadId : SV_DispatchThreadID)
{
    int2 p = int2(threadId.xy);
    if (uint(p.x) >= pc.size.x || uint(p.y) >= pc.size.y) {
        return;
    }
    float viewZ = gbViewZ[p].x;
    bool ignored = !(abs(viewZ) < pc.hitDist3);
    if (ignored) {
        nrdInNormalRough[p] = packNormalRoughness(float3(0.0, 0.0, 1.0), 1.0);
        nrdInDiffuse[p] = float4(0.0, 0.0, 0.0, 0.0);
        nrdInSpecular[p] = float4(0.0, 0.0, 0.0, 0.0);
        if (pc.denoiser != 2u) {
            nrdInMotion[p] = float4(0.0, 0.0, 0.0, 0.0);
        }
        return;
    }

    float4 nr = gbNormalRough[p];
    float3 n = nr.xyz * rsqrt(dot(nr.xyz, nr.xyz) + 1.0e-9);
    nrdInNormalRough[p] = packNormalRoughness(n, nr.w);
    float roughness = clamp(nr.w, 0.0, 1.0);

    float4 d = gbDiffuse[p];
    float3 radiance = invalid3(d.rgb) ? float3(0.0) : clamp(d.rgb, 0.0, FP16_MAX);
    float dist = (isnan(d.a) || isinf(d.a)) ? 0.0 : max(d.a, 0.0);
    float4 sd = gbSpecular[p];
    float3 specRadiance = invalid3(sd.rgb) ? float3(0.0) : clamp(sd.rgb, 0.0, FP16_MAX);
    float specDist = (isnan(sd.a) || isinf(sd.a)) ? 0.0 : max(sd.a, 0.0);

    if (pc.denoiser == 2u) {
        // DLSS-RR: one raw HDR colour and the two albedo guides; its motion
        // comes from the velocity pass, so IN_MV stays untouched.
        float3 direct = gbDirect[p].rgb;
        if (invalid3(direct)) {
            direct = float3(0.0);
        }
        nrdInDiffuse[p] = float4(clamp(radiance + specRadiance + direct, 0.0, FP16_MAX), 1.0);
        nrdInSpecular[p] = float4(clamp(gbSpecAlbedo[p].rgb, 0.0, 1.0), 1.0);
        return;
    }

    float3 motion = float3(0.0);
    if (pc.motionAddr.x != 0u || pc.motionAddr.y != 0u) {
        float4* buffer = (float4*)addressOf(pc.motionAddr.x, pc.motionAddr.y);
        float4 m = buffer[uint(p.y) * pc.size.x + uint(p.x)];
        if (m.w > 0.5 && !invalid3(m.xyz) && !(m.x > 1.0e29)) {
            motion = m.xyz;
        }
    }
    nrdInMotion[p] = float4(motion, 0.0);

    float3 albedo = max(gbAlbedo[p].rgb, float3(0.02));
    float3 ambient = radiance / albedo;
    if (pc.denoiser == 0u) {
        float3 hitDist = float3(pc.hitDist0, pc.hitDist1, pc.hitDist2);
        float normHit = clamp(dist / hitDistanceNormalization(viewZ, hitDist, 1.0), 0.0, 1.0);
        nrdInDiffuse[p] = float4(linearToYCoCg(ambient), normHit);
        float normHitSpec = clamp(specDist / hitDistanceNormalization(viewZ, hitDist, roughness), 0.0, 1.0);
        nrdInSpecular[p] = float4(linearToYCoCg(specRadiance), normHitSpec);
    } else {
        nrdInDiffuse[p] = float4(ambient, min(dist, FP16_MAX));
        nrdInSpecular[p] = float4(specRadiance, min(specDist, FP16_MAX));
    }
}
