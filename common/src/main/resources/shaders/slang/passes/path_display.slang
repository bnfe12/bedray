// Path-tracing display step for Bedray 2.0: scene-referred radiance -> running mean -> exposure -> tone curve -> the
// renderer's RGBA8 output image.
//
// path_trace.slang deliberately writes linear radiance without exposure or tone map. This pass is the one place that
// applies them, so accumulation stays in linear HDR. The host decides when history is invalid (camera or settings
// changed) and passes accumFrames = 0; the pass never infers a reset itself.
//
//   0 radiance  RGBA16F storage image, read  (path_trace.slang outImage, this frame)
//   1 accum     RGBA32F storage image, read/write (running mean, owned by the host pass)
//   2 outImage  RGBA8   storage image, write (rt_output, the image the composite and the denoisers read)

public module path_display_pass;

import tonemap_curve;

struct PathDisplayPush {
    uint  accumFrames;   // samples already in accum; 0 = discard history
    uint  flags;         // PATH_DISPLAY_FLAG_*
    float exposure;      // linear multiplier applied before the tone curve
    float maxRadiance;   // clamp of a single sample before it enters the mean (firefly guard)
};

static const uint PATH_DISPLAY_FLAG_ACCUMULATE = 1u << 0u;
static const uint PATH_DISPLAY_FLAG_TONEMAP = 1u << 1u;

[[vk::image_format("rgba16f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> radiance;
[[vk::image_format("rgba32f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> accum;
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> outImage;

[[vk::push_constant]] PathDisplayPush pc;

float3 pathDisplaySanitize(float3 c, float limit) {
    if (any(isnan(c)) || any(isinf(c))) {
        return float3(0.0);
    }
    return clamp(c, float3(0.0), float3(limit));
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
    if ((pc.flags & PATH_DISPLAY_FLAG_ACCUMULATE) != 0u && pc.accumFrames > 0u) {
        float3 history = pathDisplaySanitize(accum[p].rgb, limit);
        float n = float(pc.accumFrames);
        mean = history + (current - history) / (n + 1.0);
    }
    accum[p] = float4(mean, 1.0);

    float3 exposed = mean * max(pc.exposure, 0.0);
    float3 display = (pc.flags & PATH_DISPLAY_FLAG_TONEMAP) != 0u ? tonemapColor(exposed) : saturate(exposed);
    outImage[p] = float4(saturate(display), 1.0);
}
