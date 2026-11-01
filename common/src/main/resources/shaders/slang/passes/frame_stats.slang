// Frame statistics of the Slang path tracer: the four counters the renderer reads back to drive auto exposure and
// the F3 line.
//
// The GLSL ray pass wrote these inline and was deleted with the legacy pipeline; since then the head of every StatsBuf
// slot stayed zero, which muted two things at once - RtExposure.observe never received a sample, so path_auto_exposure
// could not adapt, and hit_pct / hist_pct printed as if the frame had been measured. This pass derives them from what
// the path already produces, from the one stage that can see all of it: after the display step, reading the tracer's
// own radiance, the running mean's sample count and the primary hit distance. It adds no new image and no new work per
// pixel beyond a sixteenth of the screen.
//
//   0 radiance RGBA16F storage image, read  (path_trace.slang outImage: rgb = this frame's linear mean, a = 0 rejects)
//   1 accum    RGBA32F storage image, read  (path_display.slang: rgb running mean, a the count behind it)
//   2 depth    R32F    storage image, read  (rt_depth: the primary ray's hit distance, or the miss sentinel)
//   3 stats    RWStructuredBuffer<uint>, write (stats[0..3] = hits, samples, lumLogSum, histAccepted; the host binds
//                                               exactly those sixteen bytes, so no other field of the slot is reachable)
//
// The luminance word is an ABI with RtExposure and RtFrameRenderer, which decode it as 2^(sum/64/count - 16): a sample
// contributes 64*(log2(luminance) + 16). Both ends of that range are the fixed point's own limits, not a preference -
// darker clamps to zero, brighter to 2048, and the host's floor and ceiling are the same two numbers.

import "contracts/math.slang";

struct FrameStatsPush {
    uint frameIndex;   // rotates which tiles are metered, so a still camera does not weigh the same pixels all session
    uint reserved;
};

// Above every real hit: the tracer writes 1e6 for a primary miss and the history lane writes 999999 for sky, and both
// are the same "no geometry" answer to the hit share.
static const float PATH_STATS_SKY_DISTANCE = 999999.0;
// The range 64*(log2 l + 16) can hold: 0 at 2^-16 and 2048 at 2^16.
static const float PATH_STATS_LUM_FLOOR = 1.52587890625e-5; // 2^-16
static const float PATH_STATS_LUM_CEIL = 65536.0;           // 2^16
// One 4x4 tile in sixteen is metered, every pixel of it: the same rate the legacy counters used, and the rate the
// host's hit_pct, hist_pct and average luminance were calibrated for.
static const uint PATH_STATS_TILES_PER_SAMPLE = 16u;

[[vk::image_format("rgba16f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> radiance;
[[vk::image_format("rgba32f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> accum;
[[vk::image_format("r32f")]] [[vk::binding(2, 0)]] RWTexture2D<float> depth;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> stats;

[[vk::push_constant]] FrameStatsPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    depth.GetDimensions(size.x, size.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }
    // Whole 4x4 tiles, so the counter's pixels stay together in one cache region instead of one scattered texel each.
    uint tilesPerRow = (uint(size.x) + 3u) >> 2u;
    uint site = (uint(p.x) >> 2u) + (uint(p.y) >> 2u) * tilesPerRow + pc.frameIndex;
    if (site % PATH_STATS_TILES_PER_SAMPLE != 0u) {
        return;
    }

    float4 sample = radiance[p];
    // The tracer's verdict, not coverage: a pixel whose arithmetic went non-finite is no sample of the integrand, and
    // one such frame would sit in the metered mean for the meter's whole time constant. It is not a sample here
    // either, so it cannot dilute the hit share or the average luminance.
    if (!(sample.a > 0.0) || any(isnan(sample.rgb)) || any(isinf(sample.rgb))) {
        return;
    }

    float hitDistance = depth[p];
    bool hit = hitDistance > 0.0 && hitDistance < PATH_STATS_SKY_DISTANCE;

    float lit = luminance(sample.rgb);
    if (!(lit > PATH_STATS_LUM_FLOOR)) {
        lit = PATH_STATS_LUM_FLOOR;   // NaN and black both mean "darker than the meter resolves"
    } else if (lit > PATH_STATS_LUM_CEIL) {
        lit = PATH_STATS_LUM_CEIL;
    }
    uint lumWord = uint(round((log2(lit) + 16.0) * 64.0));

    // The host prints this share against hits, so only a hit pixel may join its numerator. The count the display step
    // leaves behind says which it was: one sample is a pixel that started over, anything more is history it kept.
    uint historyKept = hit && accum[p].a > 1.0 ? 1u : 0u;

    InterlockedAdd(stats[0], hit ? 1u : 0u);
    InterlockedAdd(stats[1], 1u);
    InterlockedAdd(stats[2], lumWord);
    InterlockedAdd(stats[3], historyKept);
}
