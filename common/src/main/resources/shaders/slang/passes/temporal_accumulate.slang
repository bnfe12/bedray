// Temporal accumulation: noisy radiance + reprojected history -> accumulated radiance (rgb) and age (a).
//
// Resource contract (depth == clip.w of that frame's view-projection, world positions absolute):
//   currentImg      rgba16f  producer: tracer     rgb scene radiance, a unused
//   currentGeomImg  rgba16f  producer: G-buffer   xyz unit world normal, w linear depth, 0 = sky
//   positionImg     rgba32f  producer: G-buffer   xyz absolute world position
//   historyImg      rgba16f  producer: this pass, previous frame, ping-pong   rgb radiance, a age in frames
//   historyGeomImg  rgba16f  producer: G-buffer, previous frame               same layout as currentGeomImg
//   dstImg          rgba16f  consumer: atrous     rgb accumulated radiance, a age (0 = sky / no history)
// History is reset (age 1) on: first frame, RESET flag, resize, off-screen, depth/normal disagreement,
// non-finite data. Scene motion and material identity are not tracked: moving objects ghost until the age
// cap, and their policy is an open owner decision.

import "denoise/temporal.slang";
import "contracts/history.slang";

[[vk::binding(0, 0)]] Texture2D<float4> currentImg;
[[vk::binding(1, 0)]] Texture2D<float4> currentGeomImg;
[[vk::binding(2, 0)]] Texture2D<float4> positionImg;
[[vk::binding(3, 0)]] Texture2D<float4> historyImg;
[[vk::binding(4, 0)]] Texture2D<float4> historyGeomImg;
[[vk::binding(5, 0)]] ConstantBuffer<HistoryHeader> header;
[[vk::image_format("rgba16f")]] [[vk::binding(6, 0)]] RWTexture2D<float4> dstImg;

[[vk::push_constant]] TemporalPush pc;

float3 loadRadiance(int2 p, int2 size) {
    int2 q = clamp(p, int2(0), size - 1);
    float3 c = currentImg.Load(int3(q, 0)).rgb;
    return temporalFinite(c) ? max(c, float3(0.0)) : float3(0.0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 usize;
    dstImg.GetDimensions(usize.x, usize.y);
    int2 size = int2(usize);
    int2 p = int2(tid.xy);
    if (p.x >= size.x || p.y >= size.y) {
        return;
    }

    float3 current = loadRadiance(p, size);
    float4 geom = currentGeomImg.Load(int3(p, 0));
    float3 worldPos = positionImg.Load(int3(p, 0)).xyz;
    float3 normal = float3(0.0);
    bool surface = temporalFiniteScalar(geom.w) && geom.w > 0.0 && temporalFinite(worldPos) &&
                   temporalUnitNormal(geom.xyz, normal);
    if (!surface) {
        dstImg[p] = float4(current, 0.0);   // sky or unusable geometry: nothing to accumulate
        return;
    }

    HistoryPolicy policy = defaultHistoryPolicy();
    policy.flags = pc.flags;

    HistoryStatus status = HistoryStatus.Reset;
    float3 history = current;
    float historyAge = 0.0;
    Reprojection reprojection = reprojectToHistory(header, worldPos);
    status = reprojection.status;
    if (reprojection.valid && temporalFinite(reprojection.pixel.xyx) && temporalFiniteScalar(reprojection.depth)) {
        int2 hp = int2(reprojection.pixel);
        float4 h = historyImg.Load(int3(hp, 0));
        status = temporalValidate(h, historyGeomImg.Load(int3(hp, 0)), normal, reprojection.depth, policy);
        if (status == HistoryStatus.Valid) {
            history = h.rgb;
            historyAge = h.a;
        }
    }

    // Neighbourhood statistics of the noisy input for the ghosting clamp.
    float3 m1 = float3(0.0);
    float3 m2 = float3(0.0);
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            float3 c = loadRadiance(p + int2(dx, dy), size);
            m1 += c;
            m2 += c * c;
        }
    }
    float3 mean = m1 / 9.0;
    float3 deviation = sqrt(max(m2 / 9.0 - mean * mean, float3(0.0)));
    history = temporalClampHistory(history, mean, deviation, pc.clampSigma);

    TemporalResult result = temporalAccumulate(current, history, historyAge, status, policy, pc);
    dstImg[p] = float4(result.radiance, result.age);
}
