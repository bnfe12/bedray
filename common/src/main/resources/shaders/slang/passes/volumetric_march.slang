// Volumetric march entry for Bedray 2.0 (stage 0 of 2).
//
// Half resolution: marches the view ray of one of the four full-resolution
// pixels of a 2x2 block (rotating per frame, so the accumulation sees all hit
// distances), jitters the steps, stores the scattered radiance into the
// current half of the accumulation image and blends it with the reprojected
// previous half.  The apply stage (`passes/volumetric_apply.slang`) upsamples
// and composites it into the display image.
//
// Accumulation image layout (RGBA32F, hw x 2*hh, hw = ceil(W/2), hh = ceil(H/2)):
//   rows [cur*hh, +hh) : rgb scattered radiance (pre-exposure), a = shaft range
//   rows [prv*hh, +hh) : previous frame, cur = frameIndex & 1
//
// This entry is the host boundary: it owns the bindings and turns
// FrameConstants + push parameters into the pure functions of
// light/volumetric.slang.  Binding numbers mirror the legacy post pass where
// the resource exists.
//
//   1  outDepth        R32F storage image (read-only here; first-hit distance,
//                      1e6 on a miss - the path tracer's convention)
//   2  sections        StructuredBuffer<LegacySection> (LegacySectionTraversal)
//   0  tlas            acceleration structure
//  11 accumImage       RGBA32F storage image (the two parity halves)
//  16 frame            ConstantBuffer<FrameConstants> (608 B)

public module volumetric_march_pass;

import "contracts/frame.slang";
import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "light/volumetric.slang";
import "traversal/legacy_section_traversal.slang";
import "traversal/traversal.slang";

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::image_format("rgba32f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> accumImage;
[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;

struct VolumetricMarchPush {
    float3 keyDir;            // xyz: towards the key light (sun or moon)
    float  strength;          // settings strength, 1.0 = vanilla look
    float3 keyColor;          // rgb: key light radiance
    float  range;             // shaft range in blocks (legacy clamps >= 8)
    float3 prevOriginDelta;   // previous camera origin minus current (camera-relative rebase)
    float  rain;              // 0 clear .. 1 rain
    float  exposure;          // frame exposure (darkness adaptation only)
    float  inscatter;         // settings in-scattering weight
    float  density;           // host fog density multiplier
    float  worldHeightOffset; // camera world height for the height profile
    float  rayBias;
    uint   steps;             // march steps (clamped 2..48 in the module)
    uint   frameIndex;
    uint   historyValid;      // 0 = store fresh only
    uint   flipY;             // renderer NDC.y flip flag
    uint   instanceMask;      // TLAS instance mask of the world
    uint   pad0;
};

[[vk::push_constant]] VolumetricMarchPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    // Accumulation image is hw x 2*hh; the entry covers the current half.
    uint2 accumSize;
    accumImage.GetDimensions(accumSize.x, accumSize.y);
    uint hw = accumSize.x;
    uint hh = accumSize.y / 2u;
    uint2 q = dispatchThreadID.xy;
    if (hw == 0u || hh == 0u || (accumSize.y & 1u) != 0u || q.x >= hw || q.y >= hh) return;

    uint2 fullSize = frame.camera.size.xy;
    uint2 depthSize;
    outDepth.GetDimensions(depthSize.x, depthSize.y);
    uint2 expectedHalfSize = fullSize / 2u + fullSize % 2u;
    if (any(fullSize == 0u) || any(depthSize != fullSize) ||
        hw != expectedHalfSize.x || hh != expectedHalfSize.y) return;
    uint cur = pc.frameIndex & 1u;
    uint prv = cur ^ 1u;

    // One of the four full-resolution pixels of the 2x2 block per frame
    // (rotating), so the accumulation sees all hit distances.
    uint cell = q.x + q.y + pc.frameIndex;
    int2 pf = min(q * 2u + uint2(cell & 1u, (cell >> 1u) & 1u), fullSize - uint2(1u, 1u));
    float depth = outDepth[pf];
    if (isnan(depth) || depth <= 0.0) depth = 1.0e6;
    float range = max(pc.range, 8.0);
    float tEnd = min(depth, range);

    // Camera basis from the same source the path tracer uses: the host derives
    // forward/right/up/tan/aspect from invViewProj (see the wiring notes).
    float3 forward = safeNormalize(frame.camera.forward.xyz, float3(0.0, 0.0, -1.0));
    float3 right = safeNormalize(frame.camera.right.xyz, float3(1.0, 0.0, 0.0));
    float3 up = safeNormalize(frame.camera.up.xyz, float3(0.0, 1.0, 0.0));
    float tanHalf = max(frame.camera.forward.w, 1.0e-4);
    float aspect = max(frame.camera.right.w, 1.0e-4);
    float2 ndc = (float2(pf) + 0.5) / fullSize * 2.0 - 1.0;
    if (pc.flipY != 0u) ndc.y = -ndc.y;
    float3 dir = normalize(forward + right * (ndc.x * tanHalf * aspect) - up * (ndc.y * tanHalf));

    LegacySectionTraversal traversal;
    traversal.tlas = tlas;
    traversal.sections = sections;
    traversal.instanceMask = pc.instanceMask;

    float dark = volumetricDarkness(pc.exposure);
    float densityMul = pc.density * volumetricWeather(pc.keyDir.y, pc.rain) *
        (1.0 + 2.2 * dark);
    float jitter = shaftJitter(float2(pf), pc.frameIndex);

    float3 scatter = marchLightShaft(traversal, dir, tEnd, pc.steps, jitter,
        pc.keyDir, pc.keyColor, pc.worldHeightOffset, densityMul, dark,
        pc.inscatter, pc.strength, pc.rayBias);

    // Temporal accumulation: reproject the end point of the ray into the
    // previous frame, 4 bilinear taps validated by the shaft range.
    float alpha = 1.0;
    float3 history = float3(0.0);
    if (pc.historyValid != 0u) {
        float3 rayEnd = dir * tEnd;
        bool ok;
        float2 pix = shaftHistoryPixel(frame.camera.prevViewProjection,
            pc.prevOriginDelta, rayEnd, float2(fullSize), pc.flipY != 0u, ok);
        if (ok) {
            float2 f = frac(pix);
            int2 base = int2(floor(pix));
            float weightSum = 0.0;
            for (int j = 0; j < 4; ++j) {
                int2 o = int2(j & 1, j >> 1);
                int2 t = base + o;
                if (t.x < 0 || t.y < 0 || t.x >= int(hw) || t.y >= int(hh)) continue;
                float w = (o.x == 0 ? 1.0 - f.x : f.x) * (o.y == 0 ? 1.0 - f.y : f.y);
                float4 h = accumImage[int2(t.x, t.y + int(prv * hh))];
                if (abs(h.a - tEnd) > shaftHistoryTolerance(tEnd)) continue;
                history += h.rgb * w;
                weightSum += w;
            }
            if (weightSum > 0.25) {
                history /= weightSum;
                alpha = 0.10;
            }
        }
    }
    // The stored value is next frame's history: gate both terms before the mix
    // so one NaN cannot perpetuate itself.
    if (!isFinite(history)) history = float3(0.0);
    if (!isFinite(scatter)) scatter = float3(0.0);
    float3 stored = lerp(history, scatter, alpha);
    accumImage[uint2(q.x, q.y + cur * hh)] = float4(stored, tEnd);
}
