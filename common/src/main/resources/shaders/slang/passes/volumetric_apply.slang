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
    float aerialSigma;  // distance haze: extinction per block of sea-level air (0 = off); was pad0
    uint  aerialPacked; // bits 0..17: haze colour, 6 bits per channel R | G << 6 | B << 12 (display space);
                        // bits 18..31: camera world height, 14 bits over [-64, 576); was pad1
};

// Distance haze ("air perspective"): the picture of a hit at distance d is blended towards the horizon colour by
// 1 - exp(-tau), tau = sigma * d * (d / 128) * mean height factor along the ray: the foreground stays clear and the haze
// builds up with distance (a straight sigma * d veiled the shadows of dunes 50 blocks away, d^1.5 still did); the air is thickest low (valleys, sea level) and
// thins out towards the peaks. The blend runs in linear display light (decode the 8-bit picture, lerp, encode again); the
// scene-linear route (inverse tone map) was tried first and washed even the near terrain out, because the sky is far brighter
// than a sunlit surface in scene units. The shafts are added on top afterwards. Sky pixels (no hit) are left alone,
// except next to geometry: the colour comes from the upscaler and its silhouette is a pixel off the full-resolution depth,
// so the sky pixels within two pixels of a hit take that hit's distance (the haze colour is the sky colour, so a true
// sky pixel changes by almost nothing).
static const float AERIAL_MAX_FOG = 0.92;     // the farthest terrain keeps 8 % of its own colour
static const float HAZE_DISTANCE_REF = 128.0; // tau grows like d^2 around this distance
static const float HAZE_HEIGHT_BASE = 64.0;   // full density up to this world height (sea level)
static const float HAZE_HEIGHT_TOP = 192.0;   // the density has fallen to the floor here
static const float HAZE_HEIGHT_FLOOR = 0.2;   // peaks keep some haze
static const int   HAZE_HEIGHT_SAMPLES = 6;
static const float DISPLAY_GAMMA = 2.2;

float hazeHeightFactor(float worldY) {
    return lerp(1.0, HAZE_HEIGHT_FLOOR, smoothstep(HAZE_HEIGHT_BASE, HAZE_HEIGHT_TOP, worldY));
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

    float rawDepth = outDepth[p];
    bool sky = isnan(rawDepth) || rawDepth <= 0.0 || rawDepth >= 1.0e5;
    float depth = sky ? 1.0e6 : rawDepth;
    // distance the haze uses: the pixel's own hit, or for a sky pixel beside geometry the nearest neighbouring hit
    float hazeDepth = sky ? 1.0e6 : rawDepth;
    if (sky && pc.aerialSigma > 0.0) {
        const int2 offsets[8] = { int2(2, 0), int2(-2, 0), int2(0, 2), int2(0, -2), int2(1, 1), int2(-1, 1), int2(1, -1), int2(-1, -1) };
        int2 maxPixel = int2(fullSize) - int2(1, 1);
        for (int k = 0; k < 8; ++k) {
            float d = outDepth[clamp(int2(p) + offsets[k], int2(0, 0), maxPixel)];
            if (!isnan(d) && d > 0.0 && d < 1.0e5) hazeDepth = min(hazeDepth, d);
        }
    }

    float exposure = pc.exposure;
    if (!isFinite(exposure) || exposure <= 0.0) exposure = 1.1;

    float4 colour = outImage[p];
    float3 updated = colour.rgb;
    bool changed = false;

    // Distance haze first (it dims the surface), the light shafts are scattered in front of it.
    if (pc.aerialSigma > 0.0 && hazeDepth < 1.0e5) {
        // Same camera basis as the shaft march (the basis already carries the renderer's y flip).
        float3 forward = safeNormalize(frame.camera.forward.xyz, float3(0.0, 0.0, -1.0));
        float3 right = safeNormalize(frame.camera.right.xyz, float3(1.0, 0.0, 0.0));
        float3 up = safeNormalize(frame.camera.up.xyz, float3(0.0, 1.0, 0.0));
        float tanHalf = max(frame.camera.forward.w, 1.0e-4);
        float aspect = max(frame.camera.right.w, 1.0e-4);
        float2 ndc = (float2(p) + 0.5) / float2(fullSize) * 2.0 - 1.0;
        float3 rayDir = normalize(forward + right * (ndc.x * tanHalf * aspect) - up * (ndc.y * tanHalf));
        float cameraY = float(pc.aerialPacked >> 18u) * (640.0 / 16383.0) - 64.0;
        float rho = 0.0;
        for (int i = 0; i < HAZE_HEIGHT_SAMPLES; ++i) {
            rho += hazeHeightFactor(cameraY + rayDir.y * hazeDepth * ((float(i) + 0.5) / float(HAZE_HEIGHT_SAMPLES)));
        }
        rho /= float(HAZE_HEIGHT_SAMPLES);
        float tau = pc.aerialSigma * hazeDepth * (hazeDepth / HAZE_DISTANCE_REF) * rho;
        float fog = min(1.0 - exp(-tau), AERIAL_MAX_FOG);
        float3 fogColour = float3(float(pc.aerialPacked & 63u), float((pc.aerialPacked >> 6u) & 63u),
            float((pc.aerialPacked >> 12u) & 63u)) * (1.0 / 63.0);
        if (pc.tonemapped != 0u) {
            // blend the light, not the encoded picture: decode, lerp, encode
            float3 lit = pow(max(updated, float3(0.0)), float3(DISPLAY_GAMMA));
            float3 haze = pow(max(fogColour, float3(0.0)), float3(DISPLAY_GAMMA));
            updated = pow(lerp(lit, haze, fog), float3(1.0 / DISPLAY_GAMMA));
        } else {
            updated = lerp(updated, fogColour, fog);
        }
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
