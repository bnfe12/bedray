// Local tone mapping, stage 1: block-mean log2 luminance of the exposed HDR the denoiser resolved.
//
// One thread per 16x16 block of the presented image; it reads every other pixel of its block (64 taps) and writes
// log2 of the mean exposed luminance into r, the block's brightest log2 luminance into g. Stage 2 (local_tonemap_apply)
// upsamples this with a joint-bilateral weight and compresses the bright local base, the way the local tone mapper of
// RTX Remix Minecraft keeps texture visible inside a torch pool (rtx.localtonemap.highlights = 2.5).

import "post/local_tonemap.slang";

[[vk::image_format("rgba16f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> hdrColor;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> blockLum;
[[vk::image_format("r32f")]] [[vk::binding(2, 0)]] RWTexture2D<float> displayDepth;

[[vk::push_constant]] LocalTonemapPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 b = tid.xy;
    if (any(b >= pc.lowSize)) return;
    uint2 origin = b * LOCAL_TONEMAP_BLOCK;
    float sum = 0.0;
    float peak = -32.0;
    float count = 0.0;
    for (uint y = 0u; y < LOCAL_TONEMAP_BLOCK; y += 2u) {
        for (uint x = 0u; x < LOCAL_TONEMAP_BLOCK; x += 2u) {
            uint2 p = origin + uint2(x, y);
            if (any(p >= pc.size) || localTonemapIsSky(displayDepth[p])) continue;
            float l = localTonemapLuminance(hdrColor[p].rgb, pc.exposure);
            sum += l;
            peak = max(peak, log2(max(l, LOCAL_TONEMAP_EPS)));
            count += 1.0;
        }
    }
    // A block of sky only keeps its weight at 0: the apply stage then ignores it.
    float mean = count > 0.0 ? sum / count : LOCAL_TONEMAP_EPS;
    blockLum[b] = float4(log2(max(mean, LOCAL_TONEMAP_EPS)), peak, 0.0, count > 0.0 ? 1.0 : 0.0);
}
