// Typed Radiance Cascades trace: provisional temporal history.
//
// This module is a separate opt-in surface.  It is not imported by the fresh
// trace entries and the default bedray root documents it as heuristic.
//
// The previous typed buffer is read through the same canonical layout and
// historyCapacity.  The current probe's world position is mapped into the
// previous clipmap lattice, and the ray direction index is mapped through the
// current and previous rotations.  History is rejected when either revision
// differs, when the probe is off the previous lattice, or when either fresh or
// history segment is unresolved/truncated.
//
// rcProvisionalBlendRayHistory is deliberately named provisional.  Lerping L
// and T independently is only exact when the reprojected path has the same
// filter stack and terminal topology; the field layout cannot prove that.
// The helper also requires equal terminal classes (BLOCKED / BOUNDED_MISS /
// ENVIRONMENT), but it is a heuristic, not an unbiased temporal resolve.  The
// resolve owner must define filter weights, clamping, status transitions and
// cross-cascade history before this can be treated as a guarantee.  It is not
// called by the non-blended entries.
//
// `current` and history.segments must be distinct buffers; the caller must
// have completed the previous frame's writes and supplied the GPU memory
// dependency.

public module rc_trace_history;

__exported import "radiance_cascades.slang";
__exported import "radiance_cascades/rc_trace_options.slang";
__exported import "radiance_cascades/rc_trace_dispatch.slang";
__exported import "radiance_cascades/segment_store.slang";
import "contracts/math.slang";
import "contracts/light.slang";
import "light/sky.slang";
import "surface/surface.slang";
import "traversal/traversal.slang";

// Caller-owned reprojection inputs.  `segments`/`historyCapacity` describe the
// completed previous-frame typed atlas; the current and history atlases must
// use the same canonical layout and the same trace options (distance band,
// environment policy, budgets), or the two records are not comparable.  Only
// the caller can decide that history is still valid after disocclusion.
struct RCTemporalHistory {
    StructuredBuffer<RCSegment> segments;
    uint   historyCapacity;           // RCSegment element count actually bound
    float3 previousCameraOrigin;      // origin that produced the previous clipmap
    uint   previousRotation;
    uint   previousSceneRevision;
    uint   currentSceneRevision;
    float  weight;                    // history weight in (0, 1]
    bool   valid;                     // caller reprojection/disocclusion result
};

// Inverse of cascadeProbePosition for a previous-frame clipmap.  Returns false
// when the current probe's world position falls outside the previous grid or
// only approximately matches its lattice.  The array index is expected to
// change after a scroll; that is why it is derived from world position.
bool rcHistoryProbeIndex(RadianceCascadeDesc desc, float3 probePosition,
        float3 previousCameraOrigin, out uint historyProbeIndex) {
    historyProbeIndex = RCT_INVALID_PROBE;
    if (desc.probeAxis == 0u || !(desc.spacing > 0.0)) return false;
    if (!isFinite(probePosition) || !isFinite(previousCameraOrigin)) return false;

    float axis = float(desc.probeAxis);
    float3 previousBase = floor(previousCameraOrigin / desc.spacing) * desc.spacing;
    float3 coordinate = (probePosition - previousBase) / desc.spacing +
        float3(0.5 * axis - 0.5);
    float3 rounded = floor(coordinate + float3(0.5));

    if (any(rounded < float3(0.0)) || any(rounded >= float3(axis))) return false;
    if (any(abs(coordinate - rounded) > float3(1.0e-3))) return false;

    uint index = probeLinearIndex(uint3(rounded), desc.probeAxis);
    if (index >= probeCount(desc)) return false;
    historyProbeIndex = index;
    return true;
}

bool rcHistoryUsable(RCTemporalHistory history, RadianceCascadeDesc desc,
        float3 probePosition, out uint historyProbeIndex) {
    historyProbeIndex = RCT_INVALID_PROBE;
    if (!history.valid) return false;
    if (!isFinite(history.weight) || history.weight <= 0.0 || history.weight > 1.0) {
        return false;
    }
    if (history.previousSceneRevision != history.currentSceneRevision) return false;
    return rcHistoryProbeIndex(desc, probePosition, history.previousCameraOrigin,
        historyProbeIndex);
}

// The store/engine convention is
//   direction(rayIndex, rotation) = base((rayIndex + rotation) % count).
// Undo the previous rotation to find the record that held the same world
// direction in the history atlas.
uint rcHistoryRayIndex(uint rayIndex, uint currentRotation,
        uint historyRotation, uint rayCount) {
    if (rayCount == 0u) return rayIndex;
    uint directionIndex = (rayIndex + currentRotation % rayCount) % rayCount;
    return (directionIndex + rayCount - historyRotation % rayCount) % rayCount;
}

bool rcReadMappedHistoryRay(RCTemporalHistory history,
        RadianceCascadeLayout layout, uint cascade, uint rayIndex,
        float3 probePosition, uint currentRotation,
        out RCSegment nearSegment, out RCSegment farSegment) {
    nearSegment = rcUnresolvedSegment();
    farSegment = rcUnresolvedSegment();
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    uint historyProbeIndex;
    if (!rcHistoryUsable(history, desc, probePosition, historyProbeIndex)) {
        return false;
    }
    uint historyRayIndex = rcHistoryRayIndex(rayIndex, currentRotation,
        history.previousRotation, desc.rayCount);
    nearSegment = rcReadSegment(history.segments, layout,
        history.historyCapacity, cascade, historyProbeIndex, historyRayIndex,
        RCT_INTERVAL_NEAR);
    farSegment = rcReadSegment(history.segments, layout,
        history.historyCapacity, cascade, historyProbeIndex, historyRayIndex,
        RCT_INTERVAL_FAR);
    return true;
}

// BLOCKED / BOUNDED_MISS / ENVIRONMENT describe the terminal topology of a
// segment.  Two valid segments may only be lerped when their classes match:
// blending a sky sample into an occluded sample is not a temporal average.
uint rcSegmentTerminalClass(RCSegment segment) {
    return segment.flags & (RC_SEGMENT_BLOCKED | RC_SEGMENT_BOUNDED_MISS |
        RC_SEGMENT_ENVIRONMENT);
}

RCSegment rcLerpSegment(RCSegment fresh, RCSegment history, float alpha) {
    RCSegment result;
    result.radiance = lerp(history.radiance, fresh.radiance, alpha);
    result.confidence = 1.0;   // both inputs are valid; confidence is a status
    result.transmittance = lerp(history.transmittance, fresh.transmittance, alpha);
    result.flags = fresh.flags | (history.flags & RC_SEGMENT_CLAMPED);
    return result;
}

// Provisional, documented-as-heuristic temporal blend.  Both segments must be
// valid and their terminal classes must match; an opaque near segment is
// blended without requiring a far record.  The lerp is performed on L and T
// independently, which ignores filter-stack and geometric path differences;
// that bias is not removed by these checks.  Returns false (and leaves the
// caller's fresh records untouched) when any precondition fails.
bool rcProvisionalBlendRaySegments(RCSegment freshNear, RCSegment freshFar,
        RCSegment historyNear, RCSegment historyFar, float historyWeight,
        out RCSegment blendedNear, out RCSegment blendedFar) {
    blendedNear = freshNear;
    blendedFar = freshFar;
    if (!isFinite(historyWeight) || historyWeight <= 0.0 || historyWeight > 1.0) {
        return false;
    }
    if (!rcSegmentValid(freshNear) || !rcSegmentValid(historyNear)) return false;
    if (rcSegmentTerminalClass(freshNear) != rcSegmentTerminalClass(historyNear)) {
        return false;
    }

    bool freshNearOpaque = all(freshNear.transmittance == float3(0.0));
    bool historyNearOpaque = all(historyNear.transmittance == float3(0.0));
    if (freshNearOpaque != historyNearOpaque) return false;

    float alpha = 1.0 - historyWeight;
    if (freshNearOpaque) {
        RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
        if (!rcSegmentValid(mixedNear)) return false;
        blendedNear = mixedNear;
        blendedFar = rcUnresolvedSegment();
        return true;
    }

    if (!rcSegmentValid(freshFar) || !rcSegmentValid(historyFar)) return false;
    if (rcSegmentTerminalClass(freshFar) != rcSegmentTerminalClass(historyFar)) {
        return false;
    }

    RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
    RCSegment mixedFar = rcLerpSegment(freshFar, historyFar, alpha);
    if (!rcSegmentValid(mixedNear) || !rcSegmentValid(mixedFar)) return false;
    blendedNear = mixedNear;
    blendedFar = mixedFar;
    return true;
}

// Reads the mapped history and rewrites both current records only when the
// provisional blend accepts them.  This is not an unbiased temporal resolve
// and does not define clipping, disocclusion beyond `history.valid`, or
// cross-cascade history.
bool rcProvisionalBlendRayHistory(RWStructuredBuffer<RCSegment> current,
        RCTemporalHistory history, RadianceCascadeLayout layout, uint capacity,
        uint cascade, uint probeIndex, uint rayIndex, float3 probePosition,
        uint currentRotation) {
    if (cascade >= RADIANCE_CASCADE_COUNT || !history.valid) return false;

    RCSegment freshNear = rcReadSegment(current, layout, capacity, cascade,
        probeIndex, rayIndex, RCT_INTERVAL_NEAR);
    RCSegment freshFar = rcReadSegment(current, layout, capacity, cascade,
        probeIndex, rayIndex, RCT_INTERVAL_FAR);
    if (!rcSegmentValid(freshNear)) return false;

    RCSegment historyNear;
    RCSegment historyFar;
    if (!rcReadMappedHistoryRay(history, layout, cascade, rayIndex,
            probePosition, currentRotation, historyNear, historyFar)) {
        return false;
    }

    RCSegment blendedNear;
    RCSegment blendedFar;
    if (!rcProvisionalBlendRaySegments(freshNear, freshFar, historyNear,
            historyFar, history.weight, blendedNear, blendedFar)) {
        return false;
    }
    return rcWriteRaySegments(current, layout, capacity, cascade, probeIndex,
        rayIndex, blendedNear, blendedFar);
}

// Probe trace with the provisional typed history blend.  The history mapping
// and status checks are real; the lerp is explicitly a heuristic and is not a
// guarantee of the resolve owner.
uint traceCascadeProbeBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint cascade, uint probeIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    if (cascade >= RADIANCE_CASCADE_COUNT || cascade >= layout.cascadeCount) {
        return 0u;
    }
    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (probeIndex >= probeCount(desc)) return 0u;

    float3 probePosition = cascadeProbePosition(desc, probeIndex, cameraOrigin);
    uint storeFailures = 0u;
    for (uint rayIndex = 0u; rayIndex < desc.rayCount; ++rayIndex) {
        RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
            atlas, blockMaterials, layout, segments, capacity, cascade,
            probeIndex, rayIndex, cameraOrigin, frameRotation, materialOptions,
            options, seed);
        if (!result.stored) {
            storeFailures++;
            continue;
        }
        rcProvisionalBlendRayHistory(segments, history, layout, capacity,
            cascade, probeIndex, rayIndex, probePosition, frameRotation);
    }
    return storeFailures;
}

// Dispatch trace with the provisional typed history blend.  The caller must
// supply the previous-frame camera origin, rotation and scene revision; the
// same array index is not a stable world position after clipmap scrolling.
uint traceCascadeDispatchBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint globalIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    RadianceProbeAddress address = decodeRadianceDispatch(globalIndex);
    if (address.cascade >= RADIANCE_CASCADE_COUNT ||
        address.cascade >= layout.cascadeCount) return 0u;
    RadianceCascadeDesc desc = radianceCascade(address.cascade);
    if (address.probeIndex >= probeCount(desc) ||
        address.rayIndex >= desc.rayCount) return 0u;

    RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
        atlas, blockMaterials, layout, segments, capacity, address.cascade,
        address.probeIndex, address.rayIndex, cameraOrigin, frameRotation,
        materialOptions, options, seed);
    if (!result.stored) return 1u;

    float3 probePosition = cascadeProbePosition(desc, address.probeIndex,
        cameraOrigin);
    rcProvisionalBlendRayHistory(segments, history, layout, capacity,
        address.cascade, address.probeIndex, address.rayIndex, probePosition,
        frameRotation);
    return 0u;
}
