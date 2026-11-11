// Frame statistics of the Slang path tracer: the four counters the renderer reads back to drive auto exposure and
// the F3 line.
//
// The GLSL ray pass wrote these inline and was deleted with the legacy pipeline; since then the head of every StatsBuf
// slot stayed zero, which muted two things at once - RtExposure.observe never received a sample, so path_auto_exposure
// could not adapt, and hit_pct / hist_pct printed as if the frame had been measured. This pass derives them from what
// the path already produces, from the one stage that can see all of it: after the display step, reading the tracer's
// own radiance, the running mean's sample count and the primary hit distance. One invocation meters each selected tile,
// covering a sixteenth of the screen without repeating the tile's reads for each of its pixels.
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
    if ((p.x & 3) != 0 || (p.y & 3) != 0) {
        return;
    }

    // The meter reads the linear mean of the whole 4x4 tile of the running mean, not this pixel's own 1-spp sample.
    // A log-average of single path samples is biased low by their variance (E[log x] < log E[x]), and the variance is
    // not a property of the scene: it rises while the camera moves (reservoirs and history restart), with fewer rays
    // per pixel and with every disocclusion. The exposure followed the noise instead of the light - brighter while
    // walking, a few seconds later darker on the same view (owner screenshots 2026-11-11), and a different brightness
    // per preset. Averaging accumulated pixels before the log reduces that variance. Weight the result by the number
    // of valid pixels; hit/history counts still describe those individual pixels, preserving the host counter ABI.
    float tileSum = 0.0;
    uint tileCount = 0u;
    uint hitCount = 0u;
    uint historyCount = 0u;
    for (int ty = 0; ty < 4; ++ty) {
        for (int tx = 0; tx < 4; ++tx) {
            int2 q = p + int2(tx, ty);
            if (q.x >= int(size.x) || q.y >= int(size.y)) continue;
            float4 own = radiance[q];
            // The tracer's verdict, not coverage: invalid current samples cannot enter the meter via an old mean.
            if (!(own.a > 0.0) || any(isnan(own.rgb)) || any(isinf(own.rgb))) continue;
            float4 mean = accum[q];
            float3 rgb = mean.a > 0.0 && !any(isnan(mean.rgb)) && !any(isinf(mean.rgb)) ? mean.rgb : own.rgb;
            tileSum += max(luminance(rgb), 0.0);
            tileCount += 1u;
            float hitDistance = depth[q];
            bool hit = hitDistance > 0.0 && hitDistance < PATH_STATS_SKY_DISTANCE;
            hitCount += hit ? 1u : 0u;
            historyCount += hit && mean.a > 1.0 ? 1u : 0u;
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
