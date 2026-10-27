// Velocity (motion vector) reprojection for Bedray 2.0.
//
// Port of the legacy GLSL `velocity_reproject.comp` rewritten against the
// Bedray 2.0 contracts.  The module is pure math over parameters: no bindings,
// no global state, no #ifdef.  The compute entry
// (`passes/velocity_reproject.slang`) owns the bindings and the push block.
//
// What one invocation computes for one render pixel:
//
//   * the world-space surface (or sky ray) the pixel holds, back-projected
//     with the depth convention of that buffer;
//   * the previous-frame clip position of that surface, advanced by static
//     camera motion and — when the record carries it — dynamic entity motion;
//   * the motion vector in render pixels, plus two scalar masks the temporal
//     consumers need: `dynamic` (the surface moved independently of the
//     camera) and `disocclusion` (1 = no valid history at this pixel).
//
// Depth conventions of the input buffer (the legacy `camDelta.w` selector):
//
//   HardwareNdc   NDC z of the unjittered projection; sky = 0 or >= 1e5
//   LinearViewZ   linear view Z of an RT G-buffer, negative in front of the
//                 camera; sky = |z| >= 1e5.  Not an NDC depth: it never goes
//                 through invViewProj as z.
//   RayDistance   positive distance along the primary ray (the Slang path
//                 tracer's outDepth); sky = 1e6.
//
// Supported motion: rigid entity transforms and per-pixel pre-computed entity
// motion vectors (the legacy ringPrev record, sentinel 1e30 = no previous
// match).  Skinned deformation is out of scope: such vertices are marked
// disoccluded, exactly like the legacy pass did.

public module velocity;

import "contracts/frame.slang";
import "contracts/hit.slang";
import "contracts/history.slang";
import "contracts/math.slang";

// Depth convention selector (the legacy camDelta.w values, named).
enum VelocityDepthConvention : uint {
    HardwareNdc = 0u,
    LinearViewZ = 1u,
    RayDistance = 2u,
};

// Disocclusion sentinel of a per-vertex entity motion record: the entity has
// no previous-frame match (new or changed mesh).
static const float VELOCITY_DISOCCLUSION_SENTINEL = 1.0e30;
static const float VELOCITY_SKY_DISTANCE = 1.0e5;
static const float VELOCITY_MIN_CLIP_W = 1.0e-6;
static const float VELOCITY_ENTITY_PROXIMITY = 2.5;     // blocks around the entity origin
static const float VELOCITY_DEPTH_TOLERANCE_K = 0.03;   // relative depth-validation window
static const float VELOCITY_DEPTH_TOLERANCE_B = 0.1;
static const float VELOCITY_TELEPORT_DEFAULT = 16.0;    // blocks per frame before history resets

struct VelocityMotionRecord {
    float3 motion;      // world-block translation of the surface (prev position - cur), from the RT entity ring
    bool   disoccluded; // true when the record has no valid previous match
    bool   present;     // the record exists (buffer bound and flag set)
    bool   dynamic;     // the record carries a non-zero motion
};

// Everything the reprojection needs beyond FrameConstants.  FrameConstants
// carries invViewProjection, prevViewProjection, camera.origin, jitter.xy and
// size; the fields below are the legacy FrameUniforms the contract does not
// have yet (each one is listed in the notes as a contract gap).
struct VelocityParams {
    float4x4        invViewProj;      // current unjittered invViewProj (the contract has the jittered one!)
    float4x4        prevViewProj;     // previous unjittered prevProj * prevViewRotation
    float3          camDelta;         // translation added to a current-frame camera-
                                      // relative position to reach the previous frame
                                      // (host-filled; sign documented in the entry)
    float3          bobOrigin;        // effective (bobbed) camera origin in raw-camera-relative space
    VelocityDepthConvention convention;
    float2          jitterCurrent;    // sub-pixel jitter of this frame, render pixels
    float2          jitterPrevious;   // sub-pixel jitter of the previous frame
    // Entity block.  All optional: `hasEntity` = 0 disables the transforms, an
    // empty motion record disables the per-pixel path.  The buffers themselves
    // are entry bindings (dummy-bound when absent, the path_trace convention).
    bool   hasEntity;
    uint   entityCustomIndex;         // the id the depth g-channel encodes, 0 = none
    float4x4 entityCurrent;          // object -> world, current frame
    float4x4 entityCurrentInverse;   // world -> object, supplied by the entity owner
    float4x4 entityPrevious;         // object -> world, previous frame
    bool   entityHistoryReset;       // the entity was spawned/teleported this frame
    float  entityTeleportThreshold;  // blocks per frame; <= 0 uses VELOCITY_TELEPORT_DEFAULT
    // Per-pixel entity motion of the RT primary hit (legacy ringPrev record:
    // xyz = prev - cur world, w = 1 entity; w = 1 with |x| > 1e29 = no match).
    VelocityMotionRecord entityMotion;
    bool   hasPrevDepth;             // previous frame's linear view Z bound
};

struct VelocityResult {
    float2 motionPixels;     // previous pixel - current pixel, render pixels
    float2 previousUv;
    float  previousClipW;    // |previous clip.w|: the view-axis depth the depth
                             // validation compares the previous buffer against
    float  dynamicMask;      // 1 = the surface moved independently of the camera
    float  disocclusionMask; // 1 = no valid history for this pixel
    bool   sky;
    uint   flags;            // reserved
};

VelocityResult emptyVelocityResult() {
    VelocityResult result;
    result.motionPixels = float2(0.0);
    result.previousUv = float2(0.0);
    result.dynamicMask = 0.0;
    result.disocclusionMask = 1.0;
    result.sky = true;
    result.flags = 0u;
    return result;
}

// The depth sample of one pixel, already decoded into a convention-dependent
// "is sky" answer.  `depthSample` is the raw texel (r = depth, g = entity id
// when the buffer carries one).
bool velocityIsSky(VelocityDepthConvention convention, float depth) {
    if (convention == VelocityDepthConvention.LinearViewZ) {
        return !(abs(depth) < VELOCITY_SKY_DISTANCE);
    }
    if (convention == VelocityDepthConvention.RayDistance) {
        return !(abs(depth) < VELOCITY_SKY_DISTANCE);
    }
    return depth >= VELOCITY_SKY_DISTANCE || depth <= 1.0e-7;
}

// Back-projects one pixel into the effective camera-relative world position
// for the given convention.  `ndc` is the UNJITTERED pixel's NDC: the caller
// adds the frame jitter to the pixel coordinate before building it, so the
// reprojection does not follow every Halton phase.
float3 velocityWorldPosition(VelocityDepthConvention convention, float4x4 invViewProj,
        float3 bobOrigin, float2 ndc, float depth) {
    float4 rayW = mul(invViewProj, float4(ndc, 0.5, 1.0));
    float3 rayEnd = rayW.xyz / rayW.w;
    if (convention == VelocityDepthConvention.LinearViewZ) {
        // Pixel-centre ray scaled so its projection on the view axis equals
        // |viewZ| (the G-buffer stores -dot(hit - origin, forward)).
        float3 dir = safeNormalize(rayEnd - bobOrigin, float3(0.0, 0.0, -1.0));
        float4 fwdW = mul(invViewProj, float4(0.0, 0.0, 0.5, 1.0));
        float3 fwd = safeNormalize(fwdW.xyz / fwdW.w - bobOrigin, float3(0.0, 0.0, -1.0));
        return bobOrigin + dir * (abs(depth) / max(dot(dir, fwd), 1.0e-4));
    }
    if (convention == VelocityDepthConvention.RayDistance) {
        // Distance along the pixel-centre ray from the effective origin.
        return bobOrigin + safeNormalize(rayEnd - bobOrigin, float3(0.0, 0.0, -1.0)) * depth;
    }
    float4 world = mul(invViewProj, float4(ndc.x, ndc.y, depth, 1.0));
    return world.xyz / world.w;
}

// Whether the depth texel tags this pixel as the tracked entity (the g
// channel id match, or proximity to the entity origin — the legacy rule).
bool velocityIsEntity(float4 depthSample, VelocityParams params, float3 worldPosition) {
    if (!params.hasEntity) return false;
    uint customIndex = uint(depthSample.g * 65535.0 + 0.5);
    if (params.entityCustomIndex != 0u && customIndex == params.entityCustomIndex) return true;
    float3 entityOrigin = mul(params.entityCurrent, float4(0.0, 0.0, 0.0, 1.0)).xyz;
    return length(worldPosition - entityOrigin) < VELOCITY_ENTITY_PROXIMITY;
}

// Previous-frame clip position of a moving entity surface.  A teleport (reset
// flag, or more motion than the threshold) invalidates the history by
// returning the current NDC as the previous position, which the caller reads
// as "no motion, full disocclusion".
float4 velocityEntityPreviousClip(VelocityParams params, float3 worldPosition,
        float2 ndc, out float dynamicMask) {
    dynamicMask = 0.0;
    float3 currentOrigin = mul(params.entityCurrent, float4(0.0, 0.0, 0.0, 1.0)).xyz;
    float3 previousOrigin = mul(params.entityPrevious, float4(0.0, 0.0, 0.0, 1.0)).xyz;
    float3 delta = currentOrigin - previousOrigin;
    float moveDistance = length(delta);
    float threshold = params.entityTeleportThreshold > 0.0
        ? params.entityTeleportThreshold
        : VELOCITY_TELEPORT_DEFAULT;
    bool teleported = params.entityHistoryReset || moveDistance > threshold;
    dynamicMask = moveDistance > 1.0e-4 ? 1.0 : 0.0;

    if (teleported) {
        return float4(ndc * 1.0, 0.5, 1.0);   // w = 1, xy = current NDC: zero motion, disoccluded by caller
    }
    // Rigid transform only; skinned deformation is marked disoccluded by the
    // producer (no per-vertex deformation motion exists in the pipeline).
    float4 localPosition = mul(params.entityCurrentInverse, float4(worldPosition, 1.0));
    float4 previous = mul(params.entityPrevious, localPosition);
    return mul(params.prevViewProj, previous);
}

// The full reprojection of one pixel.  `depthSample` is the raw depth texel,
// `ndc` the UNJITTERED pixel NDC (pixel + 0.5 + jitter.xy mapped to [-1, 1]),
// `uv` the same pixel's unjittered UV.  `entityMotion` is the per-pixel RT
// record (present = false when the buffer is not bound this frame).
VelocityResult velocityReproject(VelocityParams params, float4 depthSample,
        float2 ndc, float2 uv, VelocityMotionRecord entityMotion) {
    VelocityResult result = emptyVelocityResult();
    float depth = depthSample.r;
    float dynamicMask = 0.0;
    float disocclusionMask = 0.0;

    bool sky = velocityIsSky(params.convention, depth);
    result.sky = sky;
    float4 previousClip;

    if (sky) {
        // A sky pixel follows the ray direction, not a surface: unproject the
        // direction (a point at infinity through the same basis) and project
        // only the rotation into the previous frame.
        float4 rayW = mul(params.invViewProj, float4(ndc, 0.5, 1.0));
        float3 rayDir = safeNormalize(rayW.xyz / rayW.w - params.bobOrigin,
            float3(0.0, 0.0, -1.0));
        previousClip = mul(params.prevViewProj, float4(rayDir, 0.0));
    } else {
        float3 worldPosition = velocityWorldPosition(params.convention,
            params.invViewProj, params.bobOrigin, ndc, depth);

        if (velocityIsEntity(depthSample, params, worldPosition)) {
            previousClip = velocityEntityPreviousClip(params, worldPosition, ndc,
                dynamicMask);
        } else {
            float3 objectMotion = float3(0.0);
            if (entityMotion.present) {
                if (entityMotion.disoccluded) {
                    // Entity without a previous-frame match: no history.
                    disocclusionMask = 1.0;
                } else {
                    objectMotion = entityMotion.motion;
                    dynamicMask = dot(entityMotion.motion, entityMotion.motion) > 1.0e-8
                        ? 1.0 : 0.0;
                }
            }
            // Static world: prev = cur + objectMotion + cameraDelta, all in
            // the current camera-relative space (the legacy camDelta sign).
            previousClip = mul(params.prevViewProj,
                float4(worldPosition + objectMotion + params.camDelta, 1.0));
        }
    }

    float2 previousUv = uv;
    if (previousClip.w > VELOCITY_MIN_CLIP_W) {
        previousUv = (previousClip.xy / previousClip.w) * 0.5 + 0.5;
    } else {
        disocclusionMask = 1.0;
    }
    if (previousUv.x < 0.0 || previousUv.x > 1.0 || previousUv.y < 0.0 || previousUv.y > 1.0) {
        disocclusionMask = 1.0;
    }

    result.previousUv = previousUv;
    result.previousClipW = abs(previousClip.w);
    result.dynamicMask = dynamicMask;
    result.disocclusionMask = disocclusionMask;
    result.motionPixels = (previousUv - uv);
    // Motion in pixels is completed by the caller (it owns the render size);
    // the sanitisation below is shared.
    if (!isFinite(result.motionPixels.x) || !isFinite(result.motionPixels.y) || !isFinite(result.dynamicMask) ||
        !isFinite(result.disocclusionMask)) {
        result.motionPixels = float2(0.0);
        result.dynamicMask = 0.0;
        result.disocclusionMask = 1.0;
    }
    return result;
}

// Previous-frame depth validation for the linear-viewZ convention: the
// reprojected point must be the surface the previous frame actually saw at
// that pixel.  `previousZ` is the previous frame's |viewZ| at the tapped
// texel, `expected` the current reprojection's |clip.w|.  The previous G-buffer
// pixel q holds the surface of the unjittered pixel q + jitterPrevious (the
// caller applies the offset before the tap).
bool velocityDepthValid(float previousZ, float expected) {
    if (!isFinite(previousZ) || !isFinite(expected) || previousZ < 0.0 || expected < 0.0 ||
        !(previousZ < VELOCITY_SKY_DISTANCE)) return false;
    return abs(previousZ - expected) <=
        VELOCITY_DEPTH_TOLERANCE_K * expected + VELOCITY_DEPTH_TOLERANCE_B;
}

// Sky validation for the linear-viewZ convention: a sky pixel is valid only
// when the previous frame also shows sky at that pixel.
bool velocitySkyDepthValid(float previousZ) {
    return isFinite(previousZ) && previousZ >= VELOCITY_SKY_DISTANCE;
}

// Clamps a motion vector to half the larger screen dimension (the legacy
// guard) and sanitises it.
float2 velocityClampMotion(float2 motionPixels, float2 renderSize) {
    float maximum = max(renderSize.x, renderSize.y) * 0.5;
    float lengthSquared = motionPixels.x * motionPixels.x + motionPixels.y * motionPixels.y;
    if (lengthSquared > maximum * maximum) {
        motionPixels = normalize(motionPixels) * maximum;
    }
    if (!isFinite(motionPixels.x) || !isFinite(motionPixels.y)) return float2(0.0);
    return motionPixels;
}
