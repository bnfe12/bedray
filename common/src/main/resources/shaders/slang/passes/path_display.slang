// Path-tracing display step for Bedray 2.0: scene-referred radiance -> reprojected running mean -> exposure ->
// tone curve -> the renderer's RGBA8 output image.
//
// path_trace.slang deliberately writes linear radiance without exposure or tone map. This pass is the one place that
// applies them, so accumulation stays in linear HDR. The host decides when the whole history is invalid (lighting,
// settings or scene changed) and passes accumFrames = 0; the pass never invents a global reset for camera motion:
// a moved camera reprojects each pixel through the previous view-projection and keeps the history it can prove.
//
//   0 radiance     RGBA16F storage image, read  (path_trace.slang outImage, this frame)
//   1 accum        RGBA32F storage image, read/write (rgb running mean, a per-pixel sample count)
//   2 outImage     RGBA8   storage image, write (rt_output, the image the composite and the denoisers read)
//   3 depth        R32F    storage image, read  (path_trace.slang outDepth, ray distance from the bobbed eye)
//   4 historyDepth R32F    storage image, read/write (depth of the previous frame, for the disocclusion test)
//   5 frame        ConstantBuffer<FrameConstants>

public module path_display_pass;

import "contracts/contracts.slang";
import tonemap_curve;

struct PathDisplayPush {
    uint   accumFrames;   // samples already in accum; 0 = discard history
    uint   flags;         // PATH_DISPLAY_FLAG_*
    float  exposure;      // linear multiplier applied before the tone curve
    float  maxRadiance;   // clamp of a single sample before it enters the mean (firefly guard)
    float4 reuse;         // xyz: current anchor minus previous anchor, w: disocclusion tolerance (relative)
};

static const uint PATH_DISPLAY_FLAG_ACCUMULATE = 1u << 0u;
static const uint PATH_DISPLAY_FLAG_TONEMAP = 1u << 1u;
static const uint PATH_DISPLAY_FLAG_REPROJECT = 1u << 2u;
static const uint PATH_DISPLAY_FLAG_CAMERA_STATIC = 1u << 3u;
// The clip space the renderer's projection matrix produces is upside down on a flipped-Y device; the legacy temporal
// chain flips the reprojected y before it becomes a pixel, and the history pixel must be looked up the same way.
static const uint PATH_DISPLAY_FLAG_FLIP_Y = 1u << 4u;

// path_trace.slang writes this for a primary miss; such a pixel has no position to reproject.
static const float PATH_DISPLAY_SKY_DEPTH = 999999.0;
static const float PATH_DISPLAY_MAX_COUNT = 16383.0;

[[vk::image_format("rgba16f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> radiance;
[[vk::image_format("rgba32f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> accum;
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> outImage;
[[vk::image_format("r32f")]] [[vk::binding(3, 0)]] RWTexture2D<float> depth;
[[vk::image_format("r32f")]] [[vk::binding(4, 0)]] RWTexture2D<float> historyDepth;
[[vk::binding(5, 0)]] ConstantBuffer<FrameConstants> frame;

[[vk::push_constant]] PathDisplayPush pc;

float3 pathDisplaySanitize(float3 c, float limit) {
    if (any(isnan(c)) || any(isinf(c))) {
        return float3(0.0);
    }
    return clamp(c, float3(0.0), float3(limit));
}

// Unjittered view direction of a pixel, from the same camera basis path_trace.slang traced with. History is
// reprojected from pixel centres; the sub-pixel jitter belongs to the sample, not to the geometry.
float3 pathDisplayViewDirection(FrameConstants f, int2 p) {
    float2 ndc = (float2(p) + 0.5) * f.camera.jitter.zw * 2.0 - 1.0;
    float tanHalf = f.camera.forward.w;
    float aspect = f.camera.right.w;
    return normalize(f.camera.forward.xyz
        + f.camera.right.xyz * (ndc.x * tanHalf * aspect)
        - f.camera.up.xyz * (ndc.y * tanHalf));
}

struct PathDisplayHistory {
    bool   valid;
    float3 radiance;
    float  count;
};

// Reprojects the pixel into the previous frame and accepts the history only when the previous depth agrees with the
// distance the reprojected point had there. A wrong match is a disocclusion: the pixel starts over instead of
// dragging the old surface across the screen.
PathDisplayHistory pathDisplayReproject(FrameConstants f, int2 p, float2 size, float hitDistance,
        float3 cameraDelta, float tolerance, bool flipY) {
    PathDisplayHistory history;
    history.valid = false;
    history.radiance = float3(0.0);
    history.count = 0.0;

    float3 position = f.camera.origin.xyz + pathDisplayViewDirection(f, p) * hitDistance;
    float3 previous = position + cameraDelta;
    float previousDistance = length(previous);
    float4 clip = mul(f.camera.prevViewProjection, float4(previous, 1.0));
    if (!(clip.w > 1.0e-4)) {
        return history;
    }
    float2 ndc = clip.xy / clip.w;
    if (flipY) {
        ndc.y = -ndc.y;
    }
    int2 q = int2(floor((ndc * 0.5 + 0.5) * size));
    if (q.x < 0 || q.y < 0 || q.x >= int(size.x) || q.y >= int(size.y)) {
        return history;
    }
    float stored = historyDepth[q];
    if (!(stored > 0.0) || stored >= PATH_DISPLAY_SKY_DEPTH) {
        return history;
    }
    if (abs(stored - previousDistance) > max(tolerance, 0.0) * max(previousDistance, 1.0)) {
        return history;
    }
    float4 sample = accum[q];
    if (!(sample.a > 0.0)) {
        return history;
    }
    history.valid = true;
    history.radiance = sample.rgb;
    history.count = sample.a;
    return history;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    outImage.GetDimensions(size.x, size.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }

    float limit = pc.maxRadiance > 0.0 ? pc.maxRadiance : 65000.0;
    float3 current = pathDisplaySanitize(radiance[p].rgb, limit);

    float3 mean = current;
    float count = 1.0;
    bool accumulate = (pc.flags & PATH_DISPLAY_FLAG_ACCUMULATE) != 0u && pc.accumFrames > 0u;
    if (accumulate) {
        float hitDistance = depth[p];
        bool hit = hitDistance > 0.0 && hitDistance < PATH_DISPLAY_SKY_DEPTH;
        bool samePixel = (pc.flags & PATH_DISPLAY_FLAG_CAMERA_STATIC) != 0u
            || (pc.flags & PATH_DISPLAY_FLAG_REPROJECT) == 0u;
        if (samePixel) {
            float4 history = accum[p];
            if (history.a > 0.0) {
                mean = pathDisplaySanitize(history.rgb, limit)
                    + (current - pathDisplaySanitize(history.rgb, limit)) / (history.a + 1.0);
                count = min(history.a + 1.0, PATH_DISPLAY_MAX_COUNT);
            }
        } else if (hit) {
            PathDisplayHistory history = pathDisplayReproject(frame, p, float2(size), hitDistance,
                pc.reuse.xyz, pc.reuse.w, (pc.flags & PATH_DISPLAY_FLAG_FLIP_Y) != 0u);
            if (history.valid) {
                float3 sanitized = pathDisplaySanitize(history.radiance, limit);
                mean = sanitized + (current - sanitized) / (history.count + 1.0);
                count = min(history.count + 1.0, PATH_DISPLAY_MAX_COUNT);
            }
        }
        // A sky pixel seen by a moved camera has no depth to reproject, so it restarts; a static camera keeps it.
    }

    accum[p] = float4(mean, count);
    historyDepth[p] = depth[p];

    float3 exposed = mean * max(pc.exposure, 0.0);
    float3 display = (pc.flags & PATH_DISPLAY_FLAG_TONEMAP) != 0u ? tonemapColor(exposed) : saturate(exposed);
    outImage[p] = float4(saturate(display), 1.0);
}
