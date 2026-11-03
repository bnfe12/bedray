// Bloom, stage 0: prefilter + first downsample.
//
// The display image (RGBA8, already tone mapped) is converted back to scene space, the soft threshold is applied to
// the peak channel, a Karis average over the 13 taps suppresses fireflies, and the result lands in mip 0 (half
// resolution, RGBA16F). The sky is excluded by the ray distance, so it cannot glow.

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
    // Alpha: the sun's visibility on the frame before any glow, the same value in every texel; the apply stage reads it.
    float sunVisibility = pc.sunDir.w > 0.0 ? bloomSunVisibility(depthTex, srcTex, int2(srcSize), pc) : 0.0;
    dstImg[p] = float4(bloomSanitize(bloomDown13Prefilter(srcTex, depthTex, uv, texel, pc)), sunVisibility);
}
