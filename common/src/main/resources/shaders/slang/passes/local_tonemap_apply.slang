// Local tone mapping, stage 2: rewrites the presented image from the exposed HDR with a local base compression.
//
// Replaces the DLSS-RR compose's `tonemapColor(hdr * exposure)` (passes/nrd_compose.slang, denoiser 2) with
// `tonemapColor(hdr * exposure * 2^gain(base))`: the base is the block log luminance, upsampled over a 4x4 block
// neighbourhood with a tent weight and a joint-bilateral weight on the pixel's own log luminance, so a bright source
// next to a dark wall does not darken the wall (no halo) and the pixel keeps its ratio to its base (texture survives).

import "post/local_tonemap.slang";

[[vk::image_format("rgba16f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> hdrColor;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> blockLum;
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> outputColor;
[[vk::image_format("r32f")]] [[vk::binding(3, 0)]] RWTexture2D<float> displayDepth;

[[vk::push_constant]] LocalTonemapPush pc;

// Range sigma of the bilateral weight, in stops of luminance.
static const float BILATERAL_SIGMA = 1.25;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 p = tid.xy;
    if (any(p >= pc.size)) return;
    float3 hdr = max(hdrColor[p].rgb, float3(0.0));
    float3 c = hdr * max(pc.exposure, 0.0);
    if (any(isnan(c)) || any(isinf(c))) {
        c = float3(0.0);
    }
    float own = log2(max(dot(c, TM_LUMA), LOCAL_TONEMAP_EPS));

    // Block coordinate of this pixel, centred on block centres.
    float2 bc = (float2(p) + 0.5) / float(LOCAL_TONEMAP_BLOCK) - 0.5;
    int2 b0 = int2(floor(bc));
    float2 f = bc - float2(b0);
    float wSum = 0.0;
    float baseSum = 0.0;
    for (int dy = -1; dy <= 2; ++dy) {
        for (int dx = -1; dx <= 2; ++dx) {
            int2 b = clamp(b0 + int2(dx, dy), int2(0), int2(pc.lowSize) - 1);
            // Tent over two blocks around the pixel's continuous block position.
            float2 dist = abs(float2(dx, dy) - f);
            float2 tent = max(1.0 - dist * 0.5, float2(0.0));
            float spatial = tent.x * tent.y;
            float4 block = blockLum[b];
            float base = block.r;
            float range = (base - own) / BILATERAL_SIGMA;
            float w = spatial * exp(-0.5 * range * range) * block.a;
            wSum += w;
            baseSum += w * base;
        }
    }
    float localBase = wSum > 1.0e-6 ? baseSum / wSum : own;
    // The sky keeps the global curve: it is display colour rebuilt into scene units, not light to compress.
    float gain = localTonemapIsSky(displayDepth[p]) ? 1.0 : exp2(localTonemapGainStops(localBase, pc));
    outputColor[p] = float4(tonemapColor(c * gain), 1.0);
}
