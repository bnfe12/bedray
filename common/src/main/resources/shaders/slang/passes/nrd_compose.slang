// NRD result composition, AOT Slang entry.
//
// Reads the denoiser outputs, remodulates the demodulated diffuse radiance by
// the albedo guide and writes the final RGBA8 image. The host binds the same
// 14-image descriptor set as the pack entry; only the used bindings are
// declared here.
//
//  0 viewZ (R32F)            read
//  3 albedo (RGBA8)          read
//  4 direct light (RGBA16F)  read
//  7 NRD diffuse output      read
//  8 final output (RGBA8)    write
// 11 NRD specular output     read

public module nrd_compose_pass;

import "tonemap_curve.slang";

[[vk::image_format("r32f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> gbViewZ;
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> gbAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(4, 0)]] RWTexture2D<float4> gbDirect;
[[vk::image_format("rgba16f")]] [[vk::binding(7, 0)]] RWTexture2D<float4> nrdOutDiffuse;
[[vk::image_format("rgba8")]] [[vk::binding(8, 0)]] RWTexture2D<float4> rtOutput;
[[vk::image_format("rgba16f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> nrdOutSpecular;

// Four scalars keep the reflected size at the host's 56 B; a float4 would
// raise the block alignment to 16 and Slang would report 64 B.
struct NrdPush {
    uint normalEncoding;
    uint roughnessEncoding;
    uint denoiser;           // 0 REBLUR, 1 RELAX, 2 DLSS-RR
    uint flags;              // bit 0 tonemap, bit 1 split view
    float hitDist0;
    float hitDist1;
    float hitDist2;
    float hitDist3;          // denoising range
    uint2 size;
    float exposure;
    uint pad;
    uint2 motionAddr;
};

[[vk::push_constant]] NrdPush pc;

float3 yCoCgToLinear(float3 c)
{
    float t = c.x - c.z;
    return max(float3(t + c.y, c.x + c.z, t - c.y), 0.0);
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
    if (ignored || ((pc.flags & 2u) != 0u && uint(p.x) < pc.size.x / 2u)) {
        return;
    }

    float4 den = nrdOutDiffuse[p];
    float3 color;
    if (pc.denoiser == 2u) {
        color = max(den.xyz, 0.0);
    } else {
        float4 denSpec = nrdOutSpecular[p];
        float3 ambient = pc.denoiser == 0u ? yCoCgToLinear(den.xyz) : max(den.xyz, 0.0);
        float3 specular = pc.denoiser == 0u ? yCoCgToLinear(denSpec.xyz) : max(denSpec.xyz, 0.0);
        float3 albedo = gbAlbedo[p].rgb;
        float3 direct = gbDirect[p].rgb;
        color = albedo * ambient + specular + direct;
    }
    if ((pc.flags & 1u) != 0u) {
        color = tonemapColor(color * pc.exposure);
    }
    rtOutput[p] = float4(clamp(color, 0.0, 1.0), 1.0);
}
