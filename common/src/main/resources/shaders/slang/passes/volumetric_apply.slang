// Volumetric apply entry for Bedray 2.0 (stage 1 of 2).
//
// Full resolution: depth-aware bilateral upsample of the accumulated
// scattering (bilinear weights x shaft-range similarity, nearest fallback when
// all taps are rejected) and addition to the display image in scene space —
// tonemapInverse -> add the exposure-scaled scattering -> tonemapColor, with
// only the DIFFERENCE applied so a pixel without scattering stays bit
// identical.

public module volumetric_apply_pass;

import "contracts/frame.slang";
import "contracts/math.slang";
import "light/volumetric.slang";
import "tonemap_curve.slang";

[[vk::image_format("rgba8")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::image_format("rgba32f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> accumImage;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;

struct VolumetricApplyPush {
    float exposure;     // frame exposure (the march stage writes no statistics)
    float range;        // shaft range in blocks (same value the march used)
    uint  tonemapped;   // 1 when outImage holds the tone-mapped display colour
    uint  frameIndex;
    uint  pad0;
    uint  pad1;
};

[[vk::push_constant]] VolumetricApplyPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    uint2 fullSize = frame.camera.size.xy;
    uint2 p = dispatchThreadID.xy;
    if (p.x >= fullSize.x || p.y >= fullSize.y) return;

    uint2 accumSize;
    accumImage.GetDimensions(accumSize.x, accumSize.y);
    uint hw = accumSize.x;
    uint hh = accumSize.y / 2u;
    uint2 imageSize;
    uint2 depthSize;
    outImage.GetDimensions(imageSize.x, imageSize.y);
    outDepth.GetDimensions(depthSize.x, depthSize.y);
    uint2 expectedHalfSize = fullSize / 2u + fullSize % 2u;
    if (any(fullSize == 0u) || any(imageSize != fullSize) || any(depthSize != fullSize) ||
        hw == 0u || hh == 0u || (accumSize.y & 1u) != 0u ||
        hw != expectedHalfSize.x || hh != expectedHalfSize.y) return;
    uint cur = pc.frameIndex & 1u;

    float depth = outDepth[p];
    if (isnan(depth) || depth <= 0.0) depth = 1.0e6;
    float range = max(pc.range, 8.0);
    float tEnd = min(depth, range);
    if (tEnd <= 0.5) return;

    // Bilateral upsample: bilinear weights x similarity of the shaft range,
    // nearest sample when every weight is rejected.
    float2 hp = (float2(p) + 0.5) * 0.5 - 0.5;
    int2 base = int2(floor(hp));
    float2 f = frac(hp);
    float3 sum = float3(0.0);
    float weightSum = 0.0;
    float3 nearest = float3(0.0);
    float nearestDifference = 1.0e9;
    for (int j = 0; j < 4; ++j) {
        int2 o = int2(j & 1, j >> 1);
        int2 t = clamp(base + o, int2(0, 0), int2(hw, hh) - int2(1, 1));
        float4 h = accumImage[int2(t.x, t.y + int(cur * hh))];
        float difference = abs(h.a - tEnd);
        float w = (o.x == 0 ? 1.0 - f.x : f.x) * (o.y == 0 ? 1.0 - f.y : f.y) *
            exp(-difference / (0.06 * tEnd + 0.25));
        sum += h.rgb * w;
        weightSum += w;
        if (difference < nearestDifference) {
            nearestDifference = difference;
            nearest = h.rgb;
        }
    }
    float3 scatter = weightSum > 1.0e-3 ? sum / weightSum : nearest;
    if (max(scatter.r, max(scatter.g, scatter.b)) <= 1.0e-5) return;

    float exposure = pc.exposure;
    if (!isFinite(exposure) || exposure <= 0.0) exposure = 1.1;

    float4 colour = outImage[p];
    float3 updated = colour.rgb +
        shaftDisplayComposite(colour.rgb, scatter, exposure, pc.tonemapped != 0u);
    outImage[p] = float4(clamp(updated, float3(0.0), float3(1.0)), colour.a);
}
