// Frame statistics of the Slang path tracer: the four counters the renderer reads back to drive auto exposure and
// the F3 line.
//
// The GLSL ray pass wrote these inline and was deleted with the legacy pipeline; since then the head of every StatsBuf
// slot stayed zero, which muted two things at once - RtExposure.observe never received a sample, so path_auto_exposure
// could not adapt, and hit_pct / hist_pct printed as if the frame had been measured. This pass derives them from what
// the path already produces, from the one stage that can see all of it: after the display step, reading the tracer's
// own radiance, the running mean and the primary hit distance. Each invocation owns one 4x4 tile.
//
//   0 radiance RGBA16F storage image, read  (path_trace_scene.slang outImage: rgb = this frame's linear mean, a = 0 rejects)
//   1 accum    RGBA32F storage image, read  (path_display.slang: rgb running mean, a the count behind it)
//   2 depth    R32F    storage image, read  (rt_depth: the primary ray's hit distance, or the miss sentinel)
//   3 stats    RWStructuredBuffer<uint>, write (stats[0..3] = hits, samples, lumLogSum, histAccepted; the host binds
//                                               exactly those sixteen bytes, so no other field of the slot is reachable)
//
// RtExposure and RtFrameRenderer decode the weighted log-luminance as 2^(sum/64/count - 16).
// The fixed-point limits are 0 at 2^-16 and 2048 at 2^16.

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
// One 4x4 tile in sixteen is metered. Its leader counts every valid pixel: the same sample rate the legacy counters
// used, and the rate the host's hit_pct, hist_pct and average luminance were calibrated for.
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
    uint2 tile = tid.xy;
    uint2 p = tile * 4u;
    if (any(p >= size)) {
        return;
    }
    // Whole 4x4 tiles, so the counter's pixels stay together in one cache region instead of one scattered texel each.
    uint tilesPerRow = (uint(size.x) + 3u) >> 2u;
    uint site = tile.x + tile.y * tilesPerRow + pc.frameIndex;
    if (site % PATH_STATS_TILES_PER_SAMPLE != 0u) {
        return;
    }

    // Average linear radiance before taking the logarithm so sample variance moves exposure less.
    // The current trace's verdict also gates the running mean.
    float tileSum = 0.0;
    uint tileCount = 0u;
    uint hitCount = 0u;
    uint historyCount = 0u;
    uint2 end = min(size, p + 4u);
    for (uint y = p.y; y < end.y; y++) {
        for (uint x = p.x; x < end.x; x++) {
            uint2 q = uint2(x, y);
            float4 sample = radiance[q];
            if (!(sample.a > 0.0) || !all(isfinite(sample.rgb))) continue;
            float4 mean = accum[q];
            bool hasMean = mean.a > 0.0 && all(isfinite(mean.rgb));
            tileSum += max(luminance(hasMean ? mean.rgb : sample.rgb), 0.0);
            tileCount++;
            float hitDistance = depth[q];
            bool hit = hitDistance > 0.0 && hitDistance < PATH_STATS_SKY_DISTANCE;
            hitCount += hit ? 1u : 0u;
            historyCount += hit && hasMean && mean.a > 1.0 ? 1u : 0u;
        }
    }
    if (tileCount == 0u) return;
    float lit = tileSum / float(tileCount);
    if (!(lit > PATH_STATS_LUM_FLOOR)) {
        lit = PATH_STATS_LUM_FLOOR;   // NaN and black both mean "darker than the meter resolves"
    } else if (lit > PATH_STATS_LUM_CEIL) {
        lit = PATH_STATS_LUM_CEIL;
    }
    uint lumWord = uint(round((log2(lit) + 16.0) * 64.0));

    InterlockedAdd(stats[0], hitCount);
    InterlockedAdd(stats[1], tileCount);
    InterlockedAdd(stats[2], lumWord * tileCount);
    InterlockedAdd(stats[3], historyCount);
}
