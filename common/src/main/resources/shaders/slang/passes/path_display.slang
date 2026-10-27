// Path-tracing display step for Bedray 2.0: scene-referred radiance -> reprojected running mean -> exposure ->
// tone curve -> the renderer's RGBA8 output image.
//
// path_trace.slang deliberately writes linear radiance without exposure or tone map. This pass is the one place that
// applies them, so accumulation stays in linear HDR. The host decides when the whole history is invalid (lighting,
// settings or scene changed) and passes accumFrames = 0; the pass never invents a global reset for camera motion:
// a moved camera reprojects each pixel through the previous view-projection and keeps the history it can prove.
//
//   0 radiance     RGBA16F storage image, read  (path_trace.slang outImage, this frame; a = 0 rejects the sample)
//   1 accum        RGBA32F storage image, read/write (rgb running mean, a per-pixel sample count)
//   2 outImage     RGBA8   storage image, write (rt_output, the image the composite and the denoisers read)
//   3 depth        R32F    storage image, read  (path_trace.slang outDepth, ray distance from the bobbed eye)
//   4 historyDepth R32F    storage image, read/write (depth of the previous frame, for the disocclusion test)
//   5 frame        ConstantBuffer<FrameConstants>
//   6 motion       RGBA16F storage image, read (path_trace.slang outMotion: the primary surface's own displacement,
//                  in world blocks; rgb zero says "the surface moved with the world", which is what the static and
//                  camera-only reprojection already assume)

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
[[vk::image_format("rgba16f")]] [[vk::binding(6, 0)]] RWTexture2D<float4> motion;

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
//
// surfaceMotion is the hit surface's own displacement (previous position minus current, in world blocks), which the
// tracer measured against the geometry it hit. A mob part that walked three blocks is therefore asked for its
// history three blocks away, where that part's own pixels were, instead of at the pixel the camera-only guess lands
// on - and the depth test still has to agree, so a part that moved behind a wall loses its history rather than
// dragging the wall's.
PathDisplayHistory pathDisplayReproject(FrameConstants f, int2 p, float2 size, float hitDistance,
        float3 cameraDelta, float3 surfaceMotion, float tolerance, bool flipY) {
    PathDisplayHistory history;
    history.valid = false;
    history.radiance = float3(0.0);
    history.count = 0.0;

    float3 position = f.camera.origin.xyz + pathDisplayViewDirection(f, p) * hitDistance;
    float3 previous = position + cameraDelta + surfaceMotion;
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
    // The tracer put its verdict in the alpha lane: a path whose arithmetic went non-finite is not a sample of the
    // integrand, and one bad frame would sit in this pixel's mean for the rest of the history's life. Weight 0 keeps
    // both the mean and the count exactly as the history had them, so the next accepted sample still divides by N+1.
    float weight = radiance[p].a > 0.0 ? 1.0 : 0.0;
    bool rejected = weight == 0.0;

    float3 mean = current * weight;
    float count = weight;
    bool accumulate = (pc.flags & PATH_DISPLAY_FLAG_ACCUMULATE) != 0u && pc.accumFrames > 0u;
    if (accumulate) {
        float hitDistance = depth[p];
        bool hit = hitDistance > 0.0 && hitDistance < PATH_DISPLAY_SKY_DEPTH;
        bool samePixel = (pc.flags & PATH_DISPLAY_FLAG_CAMERA_STATIC) != 0u
            || (pc.flags & PATH_DISPLAY_FLAG_REPROJECT) == 0u;
        if (samePixel) {
            float4 history = accum[p];
            if (history.a > 0.0) {
                float3 previous = pathDisplaySanitize(history.rgb, limit);
                mean = previous + (current - previous) * (weight / (history.a + weight));
                count = min(history.a + weight, PATH_DISPLAY_MAX_COUNT);
            }
        } else if (hit) {
            PathDisplayHistory history = pathDisplayReproject(frame, p, float2(size), hitDistance,
                pc.reuse.xyz, motion[p].rgb, pc.reuse.w, (pc.flags & PATH_DISPLAY_FLAG_FLIP_Y) != 0u);
            if (history.valid) {
                float3 sanitized = pathDisplaySanitize(history.radiance, limit);
                mean = sanitized + (current - sanitized) * (weight / (history.count + weight));
                count = min(history.count + weight, PATH_DISPLAY_MAX_COUNT);
            }
        }
        // A sky pixel seen by a moved camera has no depth to reproject, so it restarts; a static camera keeps it.
    }

    accum[p] = float4(mean, count);
    // The depth lane has to describe the surface the stored mean came from, because the next frame finds that history by
    // matching it. A rejected sample kept the old mean, and the depth this frame traced may be the far plane the primary
    // guard left behind - so a rejected pixel only takes the new depth when the tracer proved one.
    if (!rejected || depth[p] < PATH_DISPLAY_SKY_DEPTH) {
        historyDepth[p] = depth[p];
    }

    float3 exposed = mean * max(pc.exposure, 0.0);
    float3 display = (pc.flags & PATH_DISPLAY_FLAG_TONEMAP) != 0u ? tonemapColor(exposed) : saturate(exposed);
    outImage[p] = float4(saturate(display), 1.0);
}
