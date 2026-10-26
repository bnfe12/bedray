// Bloom, stage 1: downsample mip i-1 -> mip i with the 13 tap Jimenez filter.

import "bloom/bloom.slang";

[[vk::binding(0, 0)]] Sampler2D srcTex;
// Bloom mips are RGBA16F in RtBloomPass; the explicit format must match the bound view.
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> dstImg;

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
    dstImg[p] = float4(bloomSanitize(bloomDown13Sampled(srcTex, uv, texel)), 1.0);
}
