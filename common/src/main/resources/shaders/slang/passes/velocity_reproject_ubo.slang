// Velocity reprojection with the host's existing UBO + buffer-address contract.
//
// This entry is the AOT Slang replacement for the runtime-compiled utility
// shader. The Java host keeps its 368-byte constant buffer, its two bindings
// and its optional raw device-address inputs, so the migration does not change
// the frame graph or the upscaler/DLSS-RR call sites.
//
//   binding 0: sampled depth (R32F/D32)
//   binding 1: RG16F motion output, xy = previous-minus-current pixels
//   binding 2: FrameUniforms (368 B)
//
// Optional raw addresses in FrameUniforms:
//   entityMotion.xy  float4 per pixel (xyz = prev - cur world, w = entity)
//   entityMotion.zw  float per pixel disocclusion mask
//   prevDepth.xy     float per pixel previous linear view Z
//
// Depth conventions (camDelta.w):
//   0 hardware NDC, 1 linear view Z, 2 ray distance.

public module velocity_reproject_ubo_pass;

struct FrameUniforms {
    float4x4 invViewProj;
    float4x4 prevViewProj;
    float4 camDelta;          // xyz = current - previous origin, w = convention
    float4 cameraBobOrigin;   // xyz = effective camera origin
    float4 renderResolution;  // xy = size, zw = inverse size
    float4x4 entityCurrTransform;
    float4x4 entityPrevTransform;
    float4 entityControl;     // x hasEntity, y customIndex, z historyReset, w threshold
    uint4 entityMotion;       // xy motion address, zw mask address
    uint4 prevDepth;          // xy previous linear-view-Z address
    float4 jitter;            // xy current, zw previous, render pixels
};

[[vk::binding(0, 0)]] Sampler2D depthTexture;
[[vk::image_format("rg16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outMotionVectors;
[[vk::binding(2, 0)]] ConstantBuffer<FrameUniforms> u;

uint64_t addressOf(uint low, uint high)
{
    return (uint64_t)low | ((uint64_t)high << 32);
}

float4 loadMotion(uint2 address, uint index)
{
    float4* buffer = (float4*)addressOf(address.x, address.y);
    return buffer[index];
}

void storeMask(uint2 address, uint index, float value)
{
    float* buffer = (float*)addressOf(address.x, address.y);
    buffer[index] = value;
}

float loadDepth(uint2 address, uint index)
{
    float* buffer = (float*)addressOf(address.x, address.y);
    return buffer[index];
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 threadId : SV_DispatchThreadID)
{
    int2 pixelCoord = int2(threadId.xy);
    int2 size = int2(u.renderResolution.xy);
    if (pixelCoord.x >= size.x || pixelCoord.y >= size.y) {
        return;
    }

    // The projection was jittered by jitter.xy, which moves the content by
    // -jitter.xy pixels: this pixel holds the surface of the unjittered pixel
    // p + jitter.xy. Unproject that exact point.
    float2 texelUv = (float2(pixelCoord) + 0.5) * u.renderResolution.zw;
    float2 uv = (float2(pixelCoord) + 0.5 + u.jitter.xy) * u.renderResolution.zw;
    float2 ndc = uv * 2.0 - 1.0;
    float4 depthSample = depthTexture.SampleLevel(texelUv, 0.0);
    float depth = depthSample.r;

    bool isSky = false;
    float4 prevClip;
    float dynamicObjectMask = 0.0;
    float disocclusionMask = 0.0;

    bool linearViewZ = u.camDelta.w > 0.5 && u.camDelta.w < 1.5;
    bool rayDistance = u.camDelta.w > 1.5;
    bool skyPixel = (linearViewZ || rayDistance)
        ? !(abs(depth) < 1.0e5)
        : (depth >= 1.0e5 || depth <= 1.0e-7);
    if (skyPixel) {
        isSky = true;
        float4 rayW = mul(u.invViewProj, float4(ndc, 0.5, 1.0));
        float3 rayDir = normalize(rayW.xyz / rayW.w - u.cameraBobOrigin.xyz);
        prevClip = mul(u.prevViewProj, float4(rayDir, 0.0));
    } else {
        float4 worldPosRel4;
        float3 worldPosRel;
        if (linearViewZ) {
            float4 rayW = mul(u.invViewProj, float4(ndc, 0.5, 1.0));
            float3 origin = u.cameraBobOrigin.xyz;
            float3 dir = normalize(rayW.xyz / rayW.w - origin);
            float4 fwdW = mul(u.invViewProj, float4(0.0, 0.0, 0.5, 1.0));
            float3 fwd = normalize(fwdW.xyz / fwdW.w - origin);
            worldPosRel = origin + dir * (abs(depth) / max(dot(dir, fwd), 1.0e-4));
            worldPosRel4 = float4(worldPosRel, 1.0);
        } else if (rayDistance) {
            float4 rayW = mul(u.invViewProj, float4(ndc, 0.5, 1.0));
            worldPosRel = u.cameraBobOrigin.xyz
                + normalize(rayW.xyz / rayW.w - u.cameraBobOrigin.xyz) * depth;
            worldPosRel4 = float4(worldPosRel, 1.0);
        } else {
            worldPosRel4 = mul(u.invViewProj, float4(ndc.x, ndc.y, depth, 1.0));
            worldPosRel = worldPosRel4.xyz / worldPosRel4.w;
        }

        bool isEntity = false;
        if (u.entityControl.x > 0.5) {
            uint customIndex = uint(depthSample.g * 65535.0 + 0.5);
            if (customIndex == uint(u.entityControl.y) && customIndex > 0u) {
                isEntity = true;
            } else {
                float3 entityPos = mul(u.entityCurrTransform, float4(0.0, 0.0, 0.0, 1.0)).xyz;
                if (length(worldPosRel - entityPos) < 2.5) {
                    isEntity = true;
                }
            }
        }

        if (isEntity) {
            float3 currPos = mul(u.entityCurrTransform, float4(0.0, 0.0, 0.0, 1.0)).xyz;
            float3 prevPos = mul(u.entityPrevTransform, float4(0.0, 0.0, 0.0, 1.0)).xyz;
            float moveDist = length(currPos - prevPos);
            float teleportThreshold = u.entityControl.w > 0.0 ? u.entityControl.w : 16.0;
            bool teleported = (u.entityControl.z > 0.5) || (moveDist > teleportThreshold);

            dynamicObjectMask = moveDist > 1.0e-4 ? 1.0 : 0.0;
            disocclusionMask = 0.0;

            if (teleported) {
                disocclusionMask = 1.0;
                prevClip = float4(ndc * worldPosRel4.w, 0.5, 1.0);
            } else {
                // Invert the entity's affine basis without a matrix inverse:
                // c0..c2 are the images of the unit axes, so the rows of the
                // inverse are the cross products divided by the determinant.
                float3 c0 = mul(u.entityCurrTransform, float4(1.0, 0.0, 0.0, 0.0)).xyz;
                float3 c1 = mul(u.entityCurrTransform, float4(0.0, 1.0, 0.0, 0.0)).xyz;
                float3 c2 = mul(u.entityCurrTransform, float4(0.0, 0.0, 1.0, 0.0)).xyz;
                float3 origin = mul(u.entityCurrTransform, float4(0.0, 0.0, 0.0, 1.0)).xyz;
                float det = dot(c0, cross(c1, c2));
                float3 d = worldPosRel - origin;
                float3 local = (abs(det) > 1.0e-12)
                    ? float3(dot(cross(c1, c2), d), dot(cross(c2, c0), d), dot(cross(c0, c1), d)) / det
                    : d;
                float4 prevPosRel = mul(u.entityPrevTransform, float4(local, 1.0));
                prevClip = mul(u.prevViewProj, float4(prevPosRel.xyz, 1.0));
            }
        } else {
            float3 objectMotion = float3(0.0);
            if (u.entityMotion.x != 0u || u.entityMotion.y != 0u) {
                float4 m = loadMotion(u.entityMotion.xy, uint(pixelCoord.y) * uint(size.x) + uint(pixelCoord.x));
                if (m.w > 0.5 && m.x > 1.0e29) {
                    disocclusionMask = 1.0;
                } else if (m.w > 0.5) {
                    objectMotion = m.xyz;
                    dynamicObjectMask = dot(m.xyz, m.xyz) > 1.0e-8 ? 1.0 : 0.0;
                }
            }
            float4 prevPosRel = float4(worldPosRel + objectMotion + u.camDelta.xyz, 1.0);
            prevClip = mul(u.prevViewProj, prevPosRel);
        }
    }

    float2 prevUv = uv;
    if (prevClip.w > 1.0e-6) {
        prevUv = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
    } else {
        disocclusionMask = 1.0;
    }

    if (prevUv.x < 0.0 || prevUv.x > 1.0 || prevUv.y < 0.0 || prevUv.y > 1.0) {
        disocclusionMask = 1.0;
    } else if (!isSky && linearViewZ && (u.prevDepth.x != 0u || u.prevDepth.y != 0u) && abs(prevClip.w) > 1.0e-7) {
        int2 q = clamp(int2(prevUv * u.renderResolution.xy - u.jitter.zw), int2(0, 0), size - int2(1, 1));
        float prevZ = abs(loadDepth(u.prevDepth.xy, uint(q.y) * uint(size.x) + uint(q.x)));
        float expected = abs(prevClip.w);
        if (!(prevZ < 1.0e5) || abs(prevZ - expected) > 0.03 * expected + 0.1) {
            disocclusionMask = 1.0;
        }
    } else if (isSky && linearViewZ && (u.prevDepth.x != 0u || u.prevDepth.y != 0u) && prevClip.w > 1.0e-6) {
        int2 q = clamp(int2(prevUv * u.renderResolution.xy - u.jitter.zw), int2(0, 0), size - int2(1, 1));
        float prevZ = abs(loadDepth(u.prevDepth.xy, uint(q.y) * uint(size.x) + uint(q.x)));
        if (prevZ < 1.0e5) {
            disocclusionMask = 1.0;
        }
    }

    float2 motionVector = (prevUv - uv) * u.renderResolution.xy;
    float maxMotion = max(u.renderResolution.x, u.renderResolution.y) * 0.5;
    if (length(motionVector) > maxMotion) {
        motionVector = normalize(motionVector) * maxMotion;
    }

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

    outMotionVectors[pixelCoord] = float4(motionVector, dynamicObjectMask, disocclusionMask);
    if (u.entityMotion.z != 0u || u.entityMotion.w != 0u) {
        storeMask(u.entityMotion.zw, uint(pixelCoord.y) * uint(size.x) + uint(pixelCoord.x), disocclusionMask);
    }
}
