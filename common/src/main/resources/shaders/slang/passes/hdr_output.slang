// HDR output, stage 2 (runs in the engine's present command buffer, just before the swapchain blit).
//
// The swapchain is HDR10: A2B10G10R10 + ST2084 (PQ), BT.2020 primaries. This pass turns the finished frame into PQ code
// values; the engine's own blit then copies them to the swapchain.
//
//   frameCopy     the engine target as the GUI left it (sRGB encoded, a copy so it can be read with the other images)
//   sdrReference  the SDR world picture stage 1 saved: a pixel equal to it was not touched by the GUI
//   hdrScene      stage 1's HDR world picture (valid only when flags bit 0 is set)
//   outputPq      PQ code values, 0..1, RGBA16F; the blit converts them to the 10-bit swapchain
//
// A pixel the GUI did not touch takes hdrScene (HDR highlights); every other pixel (menus, hotbar, text, translucent
// overlays) is the SDR colour decoded with the sRGB curve and placed at paper white, so the interface is never brighter
// than the player's paper white setting.

import "post/hdr_common.slang";

[[vk::image_format("rgba8")]] [[vk::binding(0, 0)]] RWTexture2D<float4> frameCopy;
[[vk::image_format("rgba8")]] [[vk::binding(1, 0)]] RWTexture2D<float4> sdrReference;
[[vk::image_format("rgba16f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> hdrScene;
[[vk::image_format("rgba16f")]] [[vk::binding(3, 0)]] RWTexture2D<float4> outputPq;

struct HdrOutputPush {
    uint2 size;
    uint flags;         // bit 0: hdrScene and sdrReference are valid for this frame
    float paperWhite;   // nits of SDR white
    float peakNits;     // nits the signal is clamped to
    float pad0;
    float pad1;
    float pad2;
};

[[vk::push_constant]] HdrOutputPush pc;

static const uint HDR_FLAG_SCENE = 1u;
// One 8-bit step is 1/255; the equality test tolerates half of it so a re-quantised copy still matches.
static const float SAME_PIXEL_TOLERANCE = 0.5 / 255.0;

// SMPTE ST 2084 inverse EOTF: absolute nits / 10000 -> code value.
float3 pqEncode(float3 nits) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    float3 y = saturate(nits / 10000.0);
    float3 ym = pow(y, float3(m1));
    return pow((c1 + c2 * ym) / (1.0 + c3 * ym), float3(m2));
}

float3 rec709ToRec2020(float3 c) {
    return float3(
        dot(float3(0.627404, 0.329283, 0.043313), c),
        dot(float3(0.069097, 0.919540, 0.011362), c),
        dot(float3(0.016391, 0.088013, 0.895595), c));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 p = tid.xy;
    if (any(p >= pc.size)) return;
    float4 frame = frameCopy[p];
    float3 lin = srgbToLinear(saturate(frame.rgb));
    if ((pc.flags & HDR_FLAG_SCENE) != 0u) {
        float3 diff = abs(frame.rgb - sdrReference[p].rgb);
        if (all(diff <= float3(SAME_PIXEL_TOLERANCE))) {
            lin = max(hdrScene[p].rgb, float3(0.0));
        }
    }
    float3 nits = rec709ToRec2020(lin * pc.paperWhite);
    nits = clamp(nits, float3(0.0), float3(pc.peakNits));
    outputPq[p] = float4(pqEncode(nits), 1.0);
}
