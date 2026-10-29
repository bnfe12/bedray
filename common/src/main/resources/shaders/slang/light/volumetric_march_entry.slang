// Shared volumetric march/store entry body. The two binding adapters choose
// their section decoder; arithmetic and temporal accumulation live here once.
// Extracted unchanged from passes/volumetric_march.slang. Existing bob-origin,
// binary visibility and exposure semantics are deliberately preserved.
public module volumetric_march_entry;

import "contracts/frame.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "light/volumetric.slang";
import "traversal/traversal.slang";

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

void volumetricMarchEntry<T : ISceneTracer>(T traversal, uint3 dispatchThreadID,
        RWTexture2D<float> outDepth, RWTexture2D<float4> accumImage,
        FrameConstants frame, VolumetricMarchPush pc)
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
