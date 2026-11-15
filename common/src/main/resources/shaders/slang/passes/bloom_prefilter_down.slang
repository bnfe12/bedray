// Bloom, stage 0: prefilter + first downsample.
//
// Threshold the source and downsample to bloom mip 0 (half resolution, RGBA16F).
// The alpha of its first texel stores the sun visibility used by the apply stage.

import "bloom/bloom.slang";

[[vk::binding(0, 0)]] Sampler2D srcTex;
// Bloom mips are RGBA16F in RtBloomPass; the explicit format must match the bound view.
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> dstImg;
[[vk::binding(3, 0)]] Sampler2D depthTex;

[[vk::push_constant]] BloomPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    dstImg.GetDimensions(size.x, size.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }
    float2 uv = (float2(p) + 0.5) / float2(size);
    uint2 srcSize;
    srcTex.GetDimensions(srcSize.x, srcSize.y);
    float2 texel = 1.0 / float2(srcSize);
    float sunVisibility = 0.0;
    if (all(p == int2(0)) && pc.sunDir.w > 0.0) {
        sunVisibility = bloomSunVisibility(depthTex, srcTex, int2(srcSize), pc);
    }
    float3 glow = float3(0.0);
    if (pc.p0.z > 0.0) {
        glow = bloomSanitize(bloomDown13Prefilter(srcTex, depthTex, uv, texel, pc));
    }
    dstImg[p] = float4(glow, sunVisibility);
}
