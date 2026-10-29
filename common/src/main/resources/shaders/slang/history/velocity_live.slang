// Live RG16F temporal-consumer contract, separate from typed RGBA16F velocity.
// Keep every live GLSL branch/threshold; migrate ownership and compilation only.
public module velocity_live;

struct VelocityLiveUniforms {
    float4x4 invViewProj;          // 0
    float4x4 prevViewProj;         // 64
    float4 camDelta;              // 128: current raw camera - previous, w=depth convention
    float4 cameraBobOrigin;       // 144: effective origin in raw-camera-relative space
    float4 renderResolution;      // 160: width, height, reciprocal width, reciprocal height
    float4x4 entityCurrTransform;  // 176
    float4x4 entityPrevTransform;  // 240
    float4 entityControl;         // 304
    uint4 entityMotion;           // 320: entity float4 BDA, disocclusion float BDA
    uint4 prevDepth;              // 336: previous linear view-Z float BDA
    float4 jitter;                // 352: current.xy, previous.zw, render pixels
};

uint64_t velocityLiveAddress(uint2 words) {
    return uint64_t(words.x) | (uint64_t(words.y) << 32);
}

// Emit the same GLSL.std.450 MatrixInverse instruction as the legacy inverse().
// Slang has no matrix inverse intrinsic; this avoids a new inversion algorithm.
float4x4 inverse(float4x4 matrix) {
    return spirv_asm {
        result: $$float4x4 = OpExtInst glsl450 MatrixInverse $matrix
    };
}

// All optional BDA allocations remain borrowed through exact completion/discard.
float4 reprojectLiveVelocity(int2 pixelCoord, int2 size, Sampler2D depthTexture,
                            VelocityLiveUniforms frame) {
    float4x4 invViewProj = frame.invViewProj, prevViewProj = frame.prevViewProj;
    float4 camDelta = frame.camDelta, cameraBobOrigin = frame.cameraBobOrigin;
    float4 renderResolution = frame.renderResolution;
    float4x4 entityCurrTransform = frame.entityCurrTransform, entityPrevTransform = frame.entityPrevTransform;
    float4 entityControl = frame.entityControl, jitter = frame.jitter;
    uint4 entityMotion = frame.entityMotion, prevDepth = frame.prevDepth;
    // The projection was jittered by jitter.xy, which moves the content by -jitter.xy pixels: this pixel therefore holds
    // the surface of the unjittered pixel p + jitter.xy. Unproject that exact point, otherwise the reprojection error
    // follows every Halton phase and the temporal filter shakes along block edges.
    float2 texelUv = (float2(pixelCoord) + float2(0.5)) * renderResolution.zw;
    float2 uv = (float2(pixelCoord) + float2(0.5) + jitter.xy) * renderResolution.zw;
    float2 ndc = uv * 2.0 - 1.0;
    float4 depthSample = depthTexture.SampleLevel(texelUv, 0.0);
    float depth = depthSample.r;

    bool isSky = false;
    float4 prevClip;
    float dynamicObjectMask = 0.0;
    float disocclusionMask = 0.0;

    bool linearViewZ = camDelta.w > 0.5 && camDelta.w < 1.5;
    bool rayDistance = camDelta.w > 1.5;
    bool skyPixel = (linearViewZ || rayDistance) ? !(abs(depth) < 1.0e5) : (depth >= 1.0e5 || depth <= 1.0e-7);
    if (skyPixel) {
        isSky = true;
        float4 rayW = mul(invViewProj, float4(ndc, 0.5, 1.0));
        float3 rayDir = normalize(rayW.xyz / rayW.w - cameraBobOrigin.xyz);
        prevClip = mul(prevViewProj, float4(rayDir, 0.0));
    } else {
        // Honest mathematical back-projection:
        float4 worldPosRel4;
        float3 worldPosRel;
        if (linearViewZ) {
            // pixel-centre ray (unjittered) scaled from the effective bobbed origin so its projection on the view axis is |viewZ|
            float4 rayW = mul(invViewProj, float4(ndc, 0.5, 1.0));
            float3 origin = cameraBobOrigin.xyz;
            float3 dir = normalize(rayW.xyz / rayW.w - origin);
            float4 fwdW = mul(invViewProj, float4(0.0, 0.0, 0.5, 1.0));
            float3 fwd = normalize(fwdW.xyz / fwdW.w - origin);
            worldPosRel = origin + dir * (abs(depth) / max(dot(dir, fwd), 1.0e-4));
            worldPosRel4 = float4(worldPosRel, 1.0);
        } else if (rayDistance) {
            // RT outDepth: distance along the unjittered pixel-centre ray from the effective bobbed camera origin.
            float4 rayW = mul(invViewProj, float4(ndc, 0.5, 1.0));
            worldPosRel = cameraBobOrigin.xyz + normalize(rayW.xyz / rayW.w - cameraBobOrigin.xyz) * depth;
            worldPosRel4 = float4(worldPosRel, 1.0);
        } else {
            worldPosRel4 = mul(invViewProj, float4(ndc.x, ndc.y, depth, 1.0));
            worldPosRel = worldPosRel4.xyz / worldPosRel4.w;
        }

        bool isEntity = false;
        if (entityControl.x > 0.5) {
            uint customIndex = uint(depthSample.g * 65535.0 + 0.5);
            if (customIndex == uint(entityControl.y) && customIndex > 0u) {
                isEntity = true;
            } else {
                float3 entityPos = float3(entityCurrTransform[0][3], entityCurrTransform[1][3], entityCurrTransform[2][3]);
                if (length(worldPosRel - entityPos) < 2.5) {
                    isEntity = true;
                }
            }
        }

        if (isEntity) {
            float moveDist = length(float3(entityCurrTransform[0][3], entityCurrTransform[1][3], entityCurrTransform[2][3]) - float3(entityPrevTransform[0][3], entityPrevTransform[1][3], entityPrevTransform[2][3]));
            float teleportThreshold = entityControl.w > 0.0 ? entityControl.w : 16.0;
            bool teleported = (entityControl.z > 0.5) || (moveDist > teleportThreshold);

            dynamicObjectMask = moveDist > 1.0e-4 ? 1.0 : 0.0;
            // Rigid motion supported; skinned deformation motion is absent (marked disoccluded/reactive)
            disocclusionMask = 0.0;

            if (teleported) {
                disocclusionMask = 1.0;
                prevClip = float4(ndc * worldPosRel4.w, 0.5, 1.0);
            } else {
                float4x4 invCurr = inverse(entityCurrTransform);
                float4 localPos = mul(invCurr, float4(worldPosRel, 1.0));
                float4 prevPosRel = mul(entityPrevTransform, localPos);
                prevClip = mul(prevViewProj, float4(prevPosRel.xyz, 1.0));
            }
        } else {
            float3 objectMotion = float3(0.0);
            if (entityMotion.x != 0u || entityMotion.y != 0u) {
                // moving entity (RT primary hit): per-vertex previous position, interpolated at the hit, world blocks
                float4 m = ((float4*)velocityLiveAddress(entityMotion.xy))[pixelCoord.y * size.x + pixelCoord.x];
                if (m.w > 0.5 && m.x > 1.0e29) {
                    disocclusionMask = 1.0;   // entity without a previous-frame match (new / changed mesh): no history
                } else if (m.w > 0.5) {
                    objectMotion = m.xyz;
                    dynamicObjectMask = dot(m.xyz, m.xyz) > 1.0e-8 ? 1.0 : 0.0;
                }
            }
            float4 prevPosRel = float4(worldPosRel + objectMotion + camDelta.xyz, 1.0);
            prevClip = mul(prevViewProj, prevPosRel);
        }
    }

    float2 prevUv = uv;
    if (prevClip.w > 1e-6) {
        prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
    } else {
        disocclusionMask = 1.0;
    }

    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        disocclusionMask = 1.0;
    } else if (!isSky && linearViewZ && (prevDepth.x != 0u || prevDepth.y != 0u) && abs(prevClip.w) > 1e-7) {
        // depth test against the previous frame: the reprojected point must be the surface seen there. For a perspective
        // projection clip.w = distance along the view axis = |viewZ| (the G-buffer stores -dot(pos, forward)).
        // The previous G-buffer pixel q holds the surface of the unjittered pixel q + jitter.zw.
        int2 q = clamp(int2(prevUv * renderResolution.xy - jitter.zw), int2(0), size - 1);
        float prevZ = abs(((float*)velocityLiveAddress(prevDepth.xy))[q.y * size.x + q.x]);
        float expected = abs(prevClip.w);
        if (!(prevZ < 1.0e5) || abs(prevZ - expected) > 0.03 * expected + 0.1) {
            disocclusionMask = 1.0;
        }
    } else if (isSky && linearViewZ && (prevDepth.x != 0u || prevDepth.y != 0u) && prevClip.w > 1e-6) {
        int2 q = clamp(int2(prevUv * renderResolution.xy - jitter.zw), int2(0), size - 1);
        float prevZ = abs(((float*)velocityLiveAddress(prevDepth.xy))[q.y * size.x + q.x]);
        if (prevZ < 1.0e5) {
            disocclusionMask = 1.0;
        }
    }

    float2 motionVector = (prevUv - uv) * renderResolution.xy;

    float maxMotion = max(renderResolution.x, renderResolution.y) * 0.5;
    if (length(motionVector) > maxMotion) {
        motionVector = normalize(motionVector) * maxMotion;
    }

    // Protection against NaN, Inf
    if (isnan(motionVector.x) || isnan(motionVector.y) || isinf(motionVector.x) || isinf(motionVector.y)) {
        motionVector = float2(0.0);
        disocclusionMask = 1.0;
    }
    if (isnan(dynamicObjectMask) || isinf(dynamicObjectMask)) {
        dynamicObjectMask = 0.0;
    }
    if (isnan(disocclusionMask) || isinf(disocclusionMask)) {
        disocclusionMask = 1.0;
    }

    if (entityMotion.z != 0u || entityMotion.w != 0u) {
        ((float*)velocityLiveAddress(entityMotion.zw))[pixelCoord.y * size.x + pixelCoord.x] = disocclusionMask;
    }
    return float4(motionVector, dynamicObjectMask, disocclusionMask);
}
