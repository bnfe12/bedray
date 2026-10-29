// Velocity reprojection entry for Bedray 2.0.
//
// One thread per render pixel: reads the depth texel, back-projects the
// surface with the buffer's depth convention (history/velocity.slang),
// advances it into the previous frame (camera motion, rigid entity motion,
// per-pixel RT entity motion), and writes the motion vector in render pixels
// plus the dynamic/disocclusion masks the temporal consumers read.
//
// Bindings (numbers mirror the legacy pass where the resource exists):
//   0  depthTexture      combined image sampler of the frame depth.  r = depth
//                        in the convention below; g = entity custom index
//                        (0.0 when the producer does not tag entities).
//   1  outMotion         RGBA16F storage image: xy = motion in render pixels,
//                        z = dynamic mask, w = disocclusion mask. RGBA16F avoids
//                        requiring shaderStorageImageExtendedFormats for RG16F;
//                        the optional disocclusion buffer mirrors w.
//   2  prevDepth         StructuredBuffer<float>: previous frame's linear view
//                        Z (only for the LinearViewZ convention).  Optional:
//                        bind a 1-element dummy and clear hasPrevDepth.
//   3  entityMotion      StructuredBuffer<float4>: per-pixel RT entity motion
//                        (xyz = prev - cur world, w = 1 entity, |x| > 1e30 =
//                        no previous match).  Optional: dummy-bind and clear
//                        hasEntityMotion.
//   4  disocclusionOut   RWStructuredBuffer<float>: per-pixel disocclusion mask
//                        for the denoiser/upscaler reactive path.  Optional:
//                        dummy-bind and clear hasDisocclusionOut.
//  16  frame             ConstantBuffer<FrameConstants> (608 B).

public module velocity_reproject_pass;

import "contracts/frame.slang";
import "contracts/math.slang";
import "history/velocity.slang";

[[vk::binding(0, 0)]] Sampler2D depthTexture; // combined nearest sampler
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outMotion;
[[vk::binding(2, 0)]] StructuredBuffer<float> prevDepth;
[[vk::binding(3, 0)]] StructuredBuffer<float4> entityMotion;
[[vk::binding(4, 0)]] RWStructuredBuffer<float> disocclusionOut;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;

struct VelocityPush {
    float3 bobOrigin;         // effective (bobbed) camera origin in raw-camera-relative space
    float  camDeltaSign;      // legacy camDelta: current origin - previous origin is supplied in camDelta below
    float3 camDelta;          // the translation ADDED to a current-frame camera-relative
                              // position to obtain the previous-frame one.  The legacy
                              // uniform mixes two sign conventions: fill this from the
                              // host as (previous origin - current origin) for the static
                              // path to be correct.
    uint   convention;        // VelocityDepthConvention
    uint   hasPrevDepth;      // 1 = prevDepth is bound with real data
    uint   hasEntityMotion;   // 1 = entityMotion is bound with real data
    uint   hasDisocclusionOut;// 1 = disocclusionOut is bound and must be written
    uint   hasEntity;         // reserved: the entry fills identity transforms and no
                              // host source for entity transforms exists yet, so keep 0
    uint   entityCustomIndex; // depth g-channel id of the tracked entity, 0 = none
    uint   entityHistoryReset;// 1 = spawn/teleport this frame
    float  entityTeleportThreshold; // blocks per frame; 0 = module default
    uint   padJitter;         // explicit std430 gap: float2 aligns to 8 bytes
    float2 jitterPrevious;    // previous frame's sub-pixel jitter, render pixels
    float2 renderSize;        // render resolution (same as frame.camera.size.xy)
    uint   pad0;
    uint   pad1;
    uint2  pad2;              // explicit tail to the 96-byte, 16-byte-aligned block
};

[[vk::push_constant]] VelocityPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    int2 size = int2(frame.camera.size.xy);
    int2 pixelCoord = int2(dispatchThreadID.xy);
    if (any(size <= 0) || any(frame.camera.size.xy > uint2(65535u)) ||
        pixelCoord.x >= size.x || pixelCoord.y >= size.y) return;

    uint2 outSize;
    outMotion.GetDimensions(outSize.x, outSize.y);
    if (any(uint2(size) != outSize)) return;
    uint pixelIndex = uint(pixelCoord.y) * uint(size.x) + uint(pixelCoord.x);

    // The projection was jittered by jitter.xy, which moves the content by
    // -jitter.xy pixels: this pixel therefore holds the surface of the
    // unjittered pixel p + jitter.xy.  Unproject that exact point, otherwise
    // the reprojection error follows every Halton phase.
    float2 invSize = 1.0 / float2(size);
    float2 texelUv = (float2(pixelCoord) + 0.5) * invSize;
    float2 uv = (float2(pixelCoord) + 0.5 + frame.camera.jitter.xy) * invSize;
    float2 ndc = uv * 2.0 - 1.0;
    float4 depthSample = depthTexture.SampleLevel(texelUv, 0.0);

    VelocityParams params;
    params.invViewProj = frame.camera.invViewProjection;
    params.prevViewProj = frame.camera.prevViewProjection;
    params.camDelta = pc.camDelta;
    params.bobOrigin = pc.bobOrigin;
    params.convention = VelocityDepthConvention(pc.convention);
    params.jitterCurrent = frame.camera.jitter.xy;
    params.jitterPrevious = pc.jitterPrevious;
    // Entity matrices are not supplied by this entry yet; never consume the
    // placeholder identities as real transforms, even for a malformed push.
    params.hasEntity = false;
    params.entityCustomIndex = pc.entityCustomIndex;
    params.entityCurrent = float4x4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0);
    params.entityCurrentInverse = params.entityCurrent;
    params.entityPrevious = params.entityCurrent;
    params.entityHistoryReset = false;
    params.entityTeleportThreshold = pc.entityTeleportThreshold;
    params.hasPrevDepth = pc.hasPrevDepth != 0u;

    VelocityMotionRecord motionRecord;
    motionRecord.present = false;
    motionRecord.disoccluded = false;
    motionRecord.dynamic = false;
    motionRecord.motion = float3(0.0);
    if (pc.hasEntityMotion != 0u) {
        uint count, stride;
        entityMotion.GetDimensions(count, stride);
        if (pixelIndex >= count) {
            motionRecord.present = true;
            motionRecord.disoccluded = true;
        } else {
            float4 m = entityMotion.Load(pixelIndex);
            bool finiteMotion = isFinite(m.x) && isFinite(m.y) && isFinite(m.z) && isFinite(m.w);
            motionRecord.present = !finiteMotion || m.w > 0.5;
            motionRecord.disoccluded = motionRecord.present && (!finiteMotion || abs(m.x) > 1.0e29);
            if (!motionRecord.disoccluded) {
                motionRecord.motion = m.xyz;
                motionRecord.dynamic = dot(m.xyz, m.xyz) > 1.0e-8;
            }
        }
    }

    VelocityResult result = velocityReproject(params, depthSample, ndc, uv,
        motionRecord);

    if (!isFinite(depthSample.r) || pc.convention > uint(VelocityDepthConvention.RayDistance)) {
        result.motionPixels = float2(0.0);
        result.dynamicMask = 0.0;
        result.disocclusionMask = 1.0;
    }

    uint previousCount = 0u;
    uint previousStride = 0u;
    if (params.hasPrevDepth) {
        prevDepth.GetDimensions(previousCount, previousStride);
        if (previousCount < uint(size.x) * uint(size.y)) {
            params.hasPrevDepth = false;
            result.disocclusionMask = 1.0;
        }
    }

    // Previous-frame depth validation (linear-viewZ convention only): the
    // reprojected point must be the surface the previous frame saw.  The
    // previous G-buffer pixel q holds the surface of the unjittered pixel
    // q + jitterPrevious.
    if (!result.sky && params.convention == VelocityDepthConvention.LinearViewZ &&
        params.hasPrevDepth && abs(result.previousClipW) > 1.0e-7) {
        int2 q = clamp(int2(result.previousUv * float2(size) - pc.jitterPrevious),
            int2(0, 0), size - int2(1, 1));
        float previousZ = abs(prevDepth.Load(uint(q.y) * uint(size.x) + uint(q.x)));
        if (!velocityDepthValid(previousZ, result.previousClipW)) {
            result.disocclusionMask = 1.0;
        }
    } else if (result.sky && params.convention == VelocityDepthConvention.LinearViewZ &&
        params.hasPrevDepth && result.previousClipW > 1.0e-6) {
        int2 q = clamp(int2(result.previousUv * float2(size) - pc.jitterPrevious),
            int2(0, 0), size - int2(1, 1));
        float previousZ = abs(prevDepth.Load(uint(q.y) * uint(size.x) + uint(q.x)));
        if (!velocitySkyDepthValid(previousZ)) {
            result.disocclusionMask = 1.0;
        }
    }

    float2 motion = velocityClampMotion(result.motionPixels * float2(size),
        float2(size));

    float4 stored = float4(motion, result.dynamicMask, result.disocclusionMask);
    if (!isFinite(stored.x) || !isFinite(stored.y) || !isFinite(stored.z) || !isFinite(stored.w))
        stored = float4(0.0, 0.0, 0.0, 1.0);
    outMotion[pixelCoord] = stored;
    if (pc.hasDisocclusionOut != 0u) {
        uint count, stride;
        disocclusionOut.GetDimensions(count, stride);
        if (pixelIndex < count) disocclusionOut[pixelIndex] = stored.w;
    }
}
