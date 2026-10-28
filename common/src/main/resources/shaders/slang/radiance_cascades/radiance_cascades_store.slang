// Radiance Cascades storage and update.
//
// radiance_cascades.slang owns the spatial/angular layout (cascade count,
// probe grid, ray directions).  This module owns the GPU records and the
// operations a pass performs on them:
//
//   * interval addressing (near/far per probe ray),
//   * accumulation of a traced sample,
//   * temporal blending against the previous frame,
//   * irradiance reconstruction for a shading normal.
//
// The record is float4 so a cascade atlas is one contiguous buffer regardless
// of cascade size.  `a` is confidence, not alpha: a zero-confidence interval
// has never been traced and must not contribute to reconstruction.

public module radiance_cascades_store;

import "radiance_cascades.slang";

static const uint RC_INTERVALS_PER_RAY = 2u;   // near, far
static const uint RC_INVALID_RECORD = 0xFFFFFFFFu;
static const float RC_MAX_CONFIDENCE = 64.0;

// Host-side description of the atlas.  Arrays are indexed by cascade and are
// filled by radianceCascadeLayout(); the shader never recomputes offsets.
struct RadianceCascadeLayout {
    uint probeAxis[RADIANCE_CASCADE_COUNT];
    uint rayCount[RADIANCE_CASCADE_COUNT];
    uint probeCount[RADIANCE_CASCADE_COUNT];
    uint baseOffset[RADIANCE_CASCADE_COUNT];
    uint totalRecords;
    uint cascadeCount;
    uint intervalsPerRay;
    uint flags;
};

RadianceCascadeLayout radianceCascadeLayout() {
    RadianceCascadeLayout layout;
    uint offset = 0u;
    for (uint cascade = 0u; cascade < RADIANCE_CASCADE_COUNT; ++cascade) {
        RadianceCascadeDesc desc = radianceCascade(cascade);
        uint probes = probeCount(desc);
        layout.probeAxis[cascade] = desc.probeAxis;
        layout.rayCount[cascade] = desc.rayCount;
        layout.probeCount[cascade] = probes;
        layout.baseOffset[cascade] = offset;
        offset += probes * desc.rayCount * RC_INTERVALS_PER_RAY;
    }
    layout.totalRecords = offset;
    layout.cascadeCount = RADIANCE_CASCADE_COUNT;
    layout.intervalsPerRay = RC_INTERVALS_PER_RAY;
    layout.flags = 0u;
    return layout;
}

uint rcIntervalIndex(RadianceCascadeLayout layout, uint cascade, uint probeIndex,
        uint rayIndex, uint interval) {
    if (cascade >= RADIANCE_CASCADE_COUNT ||
        probeIndex >= layout.probeCount[cascade] ||
        rayIndex >= layout.rayCount[cascade] ||
        interval >= RC_INTERVALS_PER_RAY) {
        return RC_INVALID_RECORD;
    }
    return layout.baseOffset[cascade] +
        (probeIndex * layout.rayCount[cascade] + rayIndex) * RC_INTERVALS_PER_RAY + interval;
}

float4 rcLoadInterval(StructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, uint rayIndex, uint interval) {
    uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
    if (index == RC_INVALID_RECORD) return float4(0.0);
    return intervals[index];
}

// A pass often owns one writable atlas and also reads it back (temporal blend,
// reconstruction).  This overload avoids binding the same buffer twice.
float4 rcLoadInterval(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, uint rayIndex, uint interval) {
    uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
    if (index == RC_INVALID_RECORD) return float4(0.0);
    return intervals[index];
}

void rcStoreInterval(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, uint rayIndex, uint interval,
        float3 radiance, float confidence) {
    uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
    if (index == RC_INVALID_RECORD) return;
    intervals[index] = float4(radiance, clamp(confidence, 0.0, RC_MAX_CONFIDENCE));
}

// Running-confidence accumulation.  A pass may trace the same interval more
// than once per frame (multi-sample GI); confidence then grows instead of
// overwriting the previous estimate.
void rcAccumulateInterval(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, uint rayIndex, uint interval,
        float3 radiance, float weight) {
    uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
    if (index == RC_INVALID_RECORD || weight <= 0.0) return;

    float4 previous = intervals[index];
    float totalWeight = previous.a + weight;
    if (totalWeight <= 0.0) return;

    float3 blended = (previous.rgb * previous.a + radiance * weight) / totalWeight;
    intervals[index] = float4(blended, clamp(totalWeight, 0.0, RC_MAX_CONFIDENCE));
}

// Temporal blend of one record. The caller maps the current world-space probe
// to historyProbeIndex and validates it against the previous clipmap origin,
// scene revision and disocclusion policy. Reusing the same array index is not
// enough after the camera-relative grid scrolls. Rotation is a permutation of
// directions; undo the previous rotation when looking up the historical ray.
void rcTemporalBlend(RWStructuredBuffer<float4> current, StructuredBuffer<float4> history,
        RadianceCascadeLayout layout, uint cascade, uint probeIndex, uint rayIndex,
        uint interval, uint historyProbeIndex, uint currentRotation, uint historyRotation,
        bool historyValid, float historyWeight) {
    uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
    if (index == RC_INVALID_RECORD) return;

    float4 fresh = current[index];
    if (!historyValid || fresh.a <= 0.0 || cascade >= RADIANCE_CASCADE_COUNT) return;
    uint count = layout.rayCount[cascade];
    if (count == 0u || rayIndex >= count) return;
    uint directionIndex = (rayIndex + currentRotation % count) % count;
    uint oldRotation = historyRotation % count;
    uint historyRay = (directionIndex + count - oldRotation) % count;
    uint historyIndex = rcIntervalIndex(layout, cascade, historyProbeIndex, historyRay, interval);
    if (historyIndex == RC_INVALID_RECORD) return;
    float4 previous = history[historyIndex];
    if (previous.a <= 0.0) return;

    float alpha = clamp(1.0 - historyWeight, 0.0, 1.0);
    float3 radiance = lerp(previous.rgb, fresh.rgb, alpha);
    float confidence = lerp(previous.a, fresh.a, alpha);
    current[index] = float4(radiance, confidence);
}

// Clears a probe before a new trace.  Keeping this operation explicit makes a
// pass's write pattern obvious: a probe either accumulates or starts fresh.
void rcClearProbe(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex) {
    if (cascade >= RADIANCE_CASCADE_COUNT) return;
    uint rays = layout.rayCount[cascade];
    for (uint ray = 0u; ray < rays; ++ray) {
        for (uint interval = 0u; interval < RC_INTERVALS_PER_RAY; ++interval) {
            rcStoreInterval(intervals, layout, cascade, probeIndex, ray, interval,
                float3(0.0), 0.0);
        }
    }
}

// Reconstructs irradiance for a shading normal by integrating the near/far
// intervals.  The two intervals are merged by confidence; a caller that needs
// distance-dependent GI can read the intervals directly instead.
float3 rcProbeIrradiance(StructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, float3 normal, uint rotation) {
    if (cascade >= RADIANCE_CASCADE_COUNT) return float3(0.0);

    RadianceCascadeDesc desc = radianceCascade(cascade);
    float3 sum = float3(0.0);
    float weightSum = 0.0;

    for (uint ray = 0u; ray < desc.rayCount; ++ray) {
        float3 direction = cascadeDirection(ray, desc.rayCount, rotation);
        float cosine = max(dot(normalize(normal), direction), 0.0);
        if (cosine <= 0.0) continue;

        float4 nearInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 0u);
        float4 farInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 1u);
        float confidence = nearInterval.a + farInterval.a;
        if (confidence <= 0.0) continue;

        float3 radiance = (nearInterval.rgb * nearInterval.a +
                           farInterval.rgb * farInterval.a) / confidence;
        sum += radiance * cosine;
        weightSum += cosine;
    }

    return sum / max(weightSum, 1.0e-6);
}

// A probe is ready for reconstruction only when every ray has at least one
// traced interval.  The caller can use this to schedule a cheaper update pass.
bool rcProbeComplete(StructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex) {
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;
    uint rays = layout.rayCount[cascade];
    for (uint ray = 0u; ray < rays; ++ray) {
        float4 nearInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 0u);
        float4 farInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 1u);
        if (nearInterval.a <= 0.0 && farInterval.a <= 0.0) return false;
    }
    return true;
}

// Writable-buffer overloads.  A pass that owns the atlas does not need a second
// read-only binding just to reconstruct irradiance.
float3 rcProbeIrradiance(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex, float3 normal, uint rotation) {
    if (cascade >= RADIANCE_CASCADE_COUNT) return float3(0.0);

    RadianceCascadeDesc desc = radianceCascade(cascade);
    float3 sum = float3(0.0);
    float weightSum = 0.0;

    for (uint ray = 0u; ray < desc.rayCount; ++ray) {
        float3 direction = cascadeDirection(ray, desc.rayCount, rotation);
        float cosine = max(dot(normalize(normal), direction), 0.0);
        if (cosine <= 0.0) continue;

        float4 nearInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 0u);
        float4 farInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 1u);
        float confidence = nearInterval.a + farInterval.a;
        if (confidence <= 0.0) continue;

        float3 radiance = (nearInterval.rgb * nearInterval.a +
                           farInterval.rgb * farInterval.a) / confidence;
        sum += radiance * cosine;
        weightSum += cosine;
    }

    return sum / max(weightSum, 1.0e-6);
}

bool rcProbeComplete(RWStructuredBuffer<float4> intervals, RadianceCascadeLayout layout,
        uint cascade, uint probeIndex) {
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;
    uint rays = layout.rayCount[cascade];
    for (uint ray = 0u; ray < rays; ++ray) {
        float4 nearInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 0u);
        float4 farInterval = rcLoadInterval(intervals, layout, cascade, probeIndex, ray, 1u);
        if (nearInterval.a <= 0.0 && farInterval.a <= 0.0) return false;
    }
    return true;
}
