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
    float cameraY;      // world height of the eye (aerial perspective height profile)
    float aerial;       // distance haze strength: 0 off, 1 the default clear-day haze
};

// ---- Aerial perspective -----------------------------------------------------
// The traced picture had no distance haze at all: the "Distance haze" setting still wrote the parameters of the deleted
// GLSL renderer, which no Slang pass read, so a desert or a valley ended in a hard, flat line (owner, 2026-11-12: "no
// haze that gives the scene depth").  Single scattering of a height-falling air layer, applied to the denoised picture:
//   out = scene * T + inscatter * (1 - T),   T = exp(-opticalDepth)
// The inscatter is the sky light at the horizon plus the key light through a Henyey-Greenstein forward lobe, so the haze
// is bright towards the sun and bluish away from it.
static const float AERIAL_SIGMA = 1.0 / 320.0;      // extinction per block at sea level: 33 % haze at 128 blocks
static const float AERIAL_SEA_LEVEL = 63.0;
static const float AERIAL_SCALE_HEIGHT = 48.0;      // density halves every ~33 blocks above sea level
static const float AERIAL_NEAR_START = 16.0;        // no haze on the foreground
static const float AERIAL_NEAR_FULL = 64.0;
static const float AERIAL_PHASE_G = 0.65;
static const float AERIAL_UNDERGROUND_FADE_TOP = 56.0;   // the eye below this height is in a cave: the sky haze fades out
static const float AERIAL_UNDERGROUND_FADE_BOTTOM = 44.0;

float aerialHeightDensity(float y) {
    return exp(-max(y - AERIAL_SEA_LEVEL, 0.0) / AERIAL_SCALE_HEIGHT);
}

// Optical depth of the exponential layer along the segment from height y0 to y1 over a distance d.
float aerialOpticalDepth(float y0, float y1, float d) {
    float r0 = aerialHeightDensity(y0);
    float r1 = aerialHeightDensity(y1);
    float dy = y1 - y0;
    float mean = abs(dy) < 1.0e-2 ? 0.5 * (r0 + r1) : (r0 - r1) * AERIAL_SCALE_HEIGHT / dy;
    if (y0 < AERIAL_SEA_LEVEL && y1 < AERIAL_SEA_LEVEL) mean = 1.0;
    return AERIAL_SIGMA * max(mean, 0.0) * d;
}

float aerialPhase(float cosTheta) {
    float g = AERIAL_PHASE_G;
    float denom = 1.0 + g * g - 2.0 * g * cosTheta;
    return (1.0 - g * g) / (4.0 * PI * denom * sqrt(denom));
}

float3 aerialViewDirection(FrameConstants frame, uint2 p, uint2 size) {
    float2 ndc = (float2(p) + 0.5) / float2(size) * 2.0 - 1.0;
    float tanHalf = frame.camera.forward.w;
    float aspect = frame.camera.right.w;
    return safeNormalize(frame.camera.forward.xyz + frame.camera.right.xyz * (ndc.x * tanHalf * aspect)
        - frame.camera.up.xyz * (ndc.y * tanHalf), frame.camera.forward.xyz);
}

// The haze of one pixel in scene units times the exposure; `linear` is the displayed colour run back through the curve.
float3 aerialComposite(float3 linear, float depth, float3 dir, FrameConstants frame, VolumetricApplyPush pc,
        float exposure) {
    if (!(pc.aerial > 0.0) || !(depth < 1.0e5)) return linear;
    float cave = smoothstep(AERIAL_UNDERGROUND_FADE_BOTTOM, AERIAL_UNDERGROUND_FADE_TOP, pc.cameraY);
    float near = smoothstep(AERIAL_NEAR_START, AERIAL_NEAR_FULL, depth);
    float y1 = pc.cameraY + dir.y * depth;
    float tau = aerialOpticalDepth(pc.cameraY, y1, depth) * pc.aerial * near * cave;
    if (!(tau > 1.0e-4)) return linear;
    float transmittance = exp(-tau);
    float3 sky = frame.atmosphere.skyLightHorizon.rgb * max(frame.atmosphere.skyLightHorizon.w, 0.0);
    float3 key = frame.sun.color.rgb * aerialPhase(dot(dir, safeNormalize(frame.sun.direction.xyz, float3(0.0, 1.0, 0.0))))
        * (4.0 * PI) * 0.35;
    float3 inscatter = (sky + key) * exposure;
    return linear * transmittance + inscatter * (1.0 - transmittance);
}

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
    float exposure = pc.exposure;
    if (!isFinite(exposure) || exposure <= 0.0) exposure = 1.1;
    float4 colour = outImage[p];
    // The haze needs no shaft: it is applied to every pixel the march may not reach.
    float3 hazed = colour.rgb;
    if (pc.tonemapped != 0u && pc.aerial > 0.0 && depth < 1.0e5) {
        float3 linearColour = tonemapInverse(colour.rgb);
        hazed = tonemapColor(aerialComposite(linearColour, depth, aerialViewDirection(frame, p, fullSize), frame, pc,
            exposure));
    }
    if (tEnd <= 0.5) {
        outImage[p] = float4(clamp(hazed, float3(0.0), float3(1.0)), colour.a);
        return;
    }

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
    if (max(scatter.r, max(scatter.g, scatter.b)) <= 1.0e-5) {
        outImage[p] = float4(clamp(hazed, float3(0.0), float3(1.0)), colour.a);
        return;
    }
    float3 updated = hazed + shaftDisplayComposite(hazed, scatter, exposure, pc.tonemapped != 0u);
    outImage[p] = float4(clamp(updated, float3(0.0), float3(1.0)), colour.a);
}
