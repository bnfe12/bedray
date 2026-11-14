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
    float aerialSigma;  // distance haze: extinction per block of the air (0 = off); was pad0
    uint  aerialColor;  // distance haze colour, 8 bits per channel R | G << 8 | B << 16 (display space); was pad1
};

// Distance haze ("air perspective"): the picture of a hit at distance d is blended towards the horizon colour by
// 1 - exp(-sigma * d). Sky pixels (no hit) are left alone, the shafts are added on top afterwards.
static const float AERIAL_MAX_FOG = 0.92;   // the farthest terrain keeps 8 % of its own colour

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

    float rawDepth = outDepth[p];
    bool sky = isnan(rawDepth) || rawDepth <= 0.0 || rawDepth >= 1.0e5;
    float depth = sky ? 1.0e6 : rawDepth;

    float exposure = pc.exposure;
    if (!isFinite(exposure) || exposure <= 0.0) exposure = 1.1;

    float4 colour = outImage[p];
    float3 updated = colour.rgb;
    bool changed = false;

    // Distance haze first (it dims the surface), the light shafts are scattered in front of it.
    if (pc.aerialSigma > 0.0 && !sky) {
        float fog = min(1.0 - exp(-pc.aerialSigma * depth), AERIAL_MAX_FOG);
        float3 fogColour = float3(float(pc.aerialColor & 255u), float((pc.aerialColor >> 8u) & 255u),
            float((pc.aerialColor >> 16u) & 255u)) * (1.0 / 255.0);
        updated = lerp(updated, fogColour, fog);
        changed = true;
    }

    float range = max(pc.range, 8.0);
    float tEnd = min(depth, range);
    if (tEnd > 0.5) {
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
        if (max(scatter.r, max(scatter.g, scatter.b)) > 1.0e-5) {
            updated += shaftDisplayComposite(updated, scatter, exposure, pc.tonemapped != 0u);
            changed = true;
        }
    }

    if (!changed) return;
    outImage[p] = float4(clamp(updated, float3(0.0), float3(1.0)), colour.a);
}
