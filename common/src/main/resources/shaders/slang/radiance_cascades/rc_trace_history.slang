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
//
// History telemetry
// -----------------
// Every blend attempt returns RCTemporalBlendCode; the blended entries fold the
// codes into RCTemporalStatus.  A host that runs the provisional path needs the
// acceptance rate and the rejection buckets, otherwise a history that is never
// accepted is indistinguishable from one that is always accepted.  The status
// is diagnostic only: it never changes the blend math.

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

// Why one provisional blend did or did not commit.  The names are deliberately
// specific: "rejected" alone cannot tell a disocclusion from a topology change.
enum RCTemporalBlendCode : uint {
    Accepted              = 0u,
    InvalidCascade        = 1u,
    HistoryUnusable       = 2u,
    InvalidLayout         = 3u,
    FreshInvalid          = 4u,
    SegmentInvalid        = 5u,
    InvalidWeight         = 6u,
    TerminalClassMismatch = 7u,
    OpacityMismatch       = 8u,
    StoreFailed           = 9u,
    InvalidAddress        = 10u,   // probe/ray outside the canonical grid
};

// Diagnostic counters for a probe or dispatch blended entry.  Exactly one
// counter is incremented per blend attempt, so the five buckets sum to the
// number of attempts.  `storeFailed` is a blend that was accepted but whose
// records could not be written; it is not folded into `accepted`.
struct RCTemporalStatus {
    uint accepted;
    uint rejectedMapping;   // history invalid, off-lattice, revision changed
    uint rejectedInvalid;   // layout/address/weight/segment data invalid
    uint rejectedClass;     // terminal class or opacity changed
    uint storeFailed;
};

RCTemporalStatus emptyRCTemporalStatus() {
    RCTemporalStatus status;
    status.accepted = 0u;
    status.rejectedMapping = 0u;
    status.rejectedInvalid = 0u;
    status.rejectedClass = 0u;
    status.storeFailed = 0u;
    return status;
}

void rcAccumulateTemporalStatus(inout RCTemporalStatus status,
        RCTemporalBlendCode code) {
    if (code == RCTemporalBlendCode.Accepted) {
        status.accepted++;
    } else if (code == RCTemporalBlendCode.HistoryUnusable) {
        status.rejectedMapping++;
    } else if (code == RCTemporalBlendCode.TerminalClassMismatch ||
               code == RCTemporalBlendCode.OpacityMismatch) {
        status.rejectedClass++;
    } else if (code == RCTemporalBlendCode.StoreFailed) {
        status.storeFailed++;
    } else {
        status.rejectedInvalid++;
    }
}

// One trace entry plus the history counters it produced.  `trace` is the same
// status a fresh entry returns; `history` is zero when no blend was attempted.
struct RCTraceBlendedStatus {
    RCTraceEntryStatus trace;
    RCTemporalStatus history;
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

RCTemporalBlendCode rcReadMappedHistoryRay(RCTemporalHistory history,
        RadianceCascadeLayout layout, uint cascade, uint rayIndex,
        float3 probePosition, uint currentRotation,
        out RCSegment nearSegment, out RCSegment farSegment) {
    nearSegment = rcUnresolvedSegment();
    farSegment = rcUnresolvedSegment();
    if (!rcLayoutCanonical(layout, history.historyCapacity)) {
        return RCTemporalBlendCode.InvalidLayout;
    }
    if (cascade >= RADIANCE_CASCADE_COUNT) return RCTemporalBlendCode.InvalidCascade;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (rayIndex >= desc.rayCount) return RCTemporalBlendCode.InvalidAddress;
    uint historyProbeIndex;
    if (!rcHistoryUsable(history, desc, probePosition, historyProbeIndex)) {
        return RCTemporalBlendCode.HistoryUnusable;
    }
    uint historyRayIndex = rcHistoryRayIndex(rayIndex, currentRotation,
        history.previousRotation, desc.rayCount);
    uint nearIndex = rcUncheckedIntervalIndex(layout, cascade, historyProbeIndex,
        historyRayIndex, RC_INTERVAL_NEAR);
    uint farIndex = rcUncheckedIntervalIndex(layout, cascade, historyProbeIndex,
        historyRayIndex, RC_INTERVAL_FAR);
    if (nearIndex == RC_INVALID_RECORD || farIndex == RC_INVALID_RECORD) {
        return RCTemporalBlendCode.InvalidAddress;
    }
    RCReadOnlySegmentSource source;
    source.records = history.segments;
    nearSegment = source.rcLoadSegment(nearIndex);
    farSegment = source.rcLoadSegment(farIndex);
    return RCTemporalBlendCode.Accepted;
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
// that bias is not removed by these checks.  On any rejection the caller's
// fresh records are left untouched and the returned code names the reason.
RCTemporalBlendCode rcProvisionalBlendRaySegments(RCSegment freshNear, RCSegment freshFar,
        RCSegment historyNear, RCSegment historyFar, float historyWeight,
        out RCSegment blendedNear, out RCSegment blendedFar) {
    blendedNear = freshNear;
    blendedFar = freshFar;
    if (!isFinite(historyWeight) || historyWeight <= 0.0 || historyWeight > 1.0) {
        return RCTemporalBlendCode.InvalidWeight;
    }
    if (!rcSegmentValid(freshNear) || !rcSegmentValid(historyNear)) {
        return RCTemporalBlendCode.SegmentInvalid;
    }
    if (rcSegmentTerminalClass(freshNear) != rcSegmentTerminalClass(historyNear)) {
        return RCTemporalBlendCode.TerminalClassMismatch;
    }

    bool freshNearOpaque = all(freshNear.transmittance == float3(0.0));
    bool historyNearOpaque = all(historyNear.transmittance == float3(0.0));
    if (freshNearOpaque != historyNearOpaque) return RCTemporalBlendCode.OpacityMismatch;

    float alpha = 1.0 - historyWeight;
    if (freshNearOpaque) {
        RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
        if (!rcSegmentValid(mixedNear)) return RCTemporalBlendCode.SegmentInvalid;
        blendedNear = mixedNear;
        blendedFar = rcUnresolvedSegment();
        return RCTemporalBlendCode.Accepted;
    }

    if (!rcSegmentValid(freshFar) || !rcSegmentValid(historyFar)) {
        return RCTemporalBlendCode.SegmentInvalid;
    }
    if (rcSegmentTerminalClass(freshFar) != rcSegmentTerminalClass(historyFar)) {
        return RCTemporalBlendCode.TerminalClassMismatch;
    }

    RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
    RCSegment mixedFar = rcLerpSegment(freshFar, historyFar, alpha);
    if (!rcSegmentValid(mixedNear) || !rcSegmentValid(mixedFar)) {
        return RCTemporalBlendCode.SegmentInvalid;
    }
    blendedNear = mixedNear;
    blendedFar = mixedFar;
    return RCTemporalBlendCode.Accepted;
}

// Reads the mapped history and rewrites both current records only when the
// provisional blend accepts them.  This is not an unbiased temporal resolve
// and does not define clipping, disocclusion beyond `history.valid`, or
// cross-cascade history.
RCTemporalBlendCode rcProvisionalBlendRayHistory(RWStructuredBuffer<RCSegment> current,
        RCTemporalHistory history, RadianceCascadeLayout layout, uint capacity,
        uint cascade, uint probeIndex, uint rayIndex, float3 probePosition,
        uint currentRotation) {
    if (!rcLayoutCanonical(layout, capacity)) return RCTemporalBlendCode.InvalidLayout;
    if (cascade >= RADIANCE_CASCADE_COUNT) return RCTemporalBlendCode.InvalidCascade;
    if (probeIndex >= layout.probeCount[cascade] ||
        rayIndex >= layout.rayCount[cascade]) {
        return RCTemporalBlendCode.InvalidAddress;
    }
    if (!isFinite(history.weight) || history.weight <= 0.0 || history.weight > 1.0) {
        return RCTemporalBlendCode.InvalidWeight;
    }
    if (!history.valid) return RCTemporalBlendCode.HistoryUnusable;

    RCReadWriteSegmentSource currentSource;
    currentSource.records = current;
    uint nearIndex = rcUncheckedIntervalIndex(layout, cascade, probeIndex, rayIndex,
        RC_INTERVAL_NEAR);
    uint farIndex = rcUncheckedIntervalIndex(layout, cascade, probeIndex, rayIndex,
        RC_INTERVAL_FAR);
    if (nearIndex == RC_INVALID_RECORD || farIndex == RC_INVALID_RECORD) {
        return RCTemporalBlendCode.InvalidAddress;
    }
    RCSegment freshNear = currentSource.rcLoadSegment(nearIndex);
    RCSegment freshFar = currentSource.rcLoadSegment(farIndex);
    if (!rcSegmentValid(freshNear)) return RCTemporalBlendCode.FreshInvalid;

    RCSegment historyNear;
    RCSegment historyFar;
    RCTemporalBlendCode code = rcReadMappedHistoryRay(history, layout, cascade,
        rayIndex, probePosition, currentRotation, historyNear, historyFar);
    if (code != RCTemporalBlendCode.Accepted) return code;
    RCSegment blendedNear;
    RCSegment blendedFar;
    code = rcProvisionalBlendRaySegments(freshNear, freshFar, historyNear,
        historyFar, history.weight, blendedNear, blendedFar);
    if (code != RCTemporalBlendCode.Accepted) return code;

    if (!rcWriteRaySegments(current, layout, capacity, cascade, probeIndex,
            rayIndex, blendedNear, blendedFar)) {
        return RCTemporalBlendCode.StoreFailed;
    }
    return RCTemporalBlendCode.Accepted;
}

// Probe trace with the provisional typed history blend.  The history mapping
// and status checks are real; the lerp is explicitly a heuristic and is not a
// guarantee of the resolve owner.
RCTraceBlendedStatus traceCascadeProbeBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint cascade, uint probeIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    RCTraceBlendedStatus status;
    status.trace = emptyRCTraceEntryStatus();
    status.history = emptyRCTemporalStatus();

    if (!rcLayoutCanonical(layout, capacity)) {
        status.trace.code = uint(RCTraceEntryCode.InvalidLayout);
        return status;
    }
    if (cascade >= RADIANCE_CASCADE_COUNT) {
        status.trace.code = uint(RCTraceEntryCode.InvalidDispatch);
        return status;
    }

    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (probeIndex >= probeCount(desc)) {
        status.trace.code = uint(RCTraceEntryCode.InvalidDispatch);
        return status;
    }

    float3 probePosition = cascadeProbePosition(desc, probeIndex, cameraOrigin);
    status.trace.rays = desc.rayCount;
    for (uint rayIndex = 0u; rayIndex < desc.rayCount; ++rayIndex) {
        RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
            atlas, blockMaterials, layout, segments, capacity, cascade,
            probeIndex, rayIndex, cameraOrigin, frameRotation, materialOptions,
            options, seed);
        rcAccumulateTraceStatus(status.trace, result);
        if (!result.stored) continue;

        RCTemporalBlendCode code = rcProvisionalBlendRayHistory(segments, history,
            layout, capacity, cascade, probeIndex, rayIndex, probePosition,
            frameRotation);
        rcAccumulateTemporalStatus(status.history, code);
    }
    return status;
}

// Dispatch trace with the provisional typed history blend.  The caller must
// supply the previous-frame camera origin, rotation and scene revision; the
// same array index is not a stable world position after clipmap scrolling.
RCTraceBlendedStatus traceCascadeDispatchBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint globalIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    RCTraceBlendedStatus status;
    status.trace = emptyRCTraceEntryStatus();
    status.history = emptyRCTemporalStatus();

    if (!rcLayoutCanonical(layout, capacity)) {
        status.trace.code = uint(RCTraceEntryCode.InvalidLayout);
        return status;
    }
    RadianceProbeAddress address = decodeRadianceDispatch(globalIndex);
    if (address.cascade >= RADIANCE_CASCADE_COUNT) {
        status.trace.code = uint(RCTraceEntryCode.InvalidDispatch);
        return status;
    }

    RadianceCascadeDesc desc = radianceCascade(address.cascade);
    if (address.probeIndex >= probeCount(desc) ||
        address.rayIndex >= desc.rayCount) {
        status.trace.code = uint(RCTraceEntryCode.InvalidDispatch);
        return status;
    }

    status.trace.rays = 1u;
    RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
        atlas, blockMaterials, layout, segments, capacity, address.cascade,
        address.probeIndex, address.rayIndex, cameraOrigin, frameRotation,
        materialOptions, options, seed);
    rcAccumulateTraceStatus(status.trace, result);
    if (!result.stored) return status;

    float3 probePosition = cascadeProbePosition(desc, address.probeIndex,
        cameraOrigin);
    RCTemporalBlendCode code = rcProvisionalBlendRayHistory(segments, history,
        layout, capacity, address.cascade, address.probeIndex, address.rayIndex,
        probePosition, frameRotation);
    rcAccumulateTemporalStatus(status.history, code);
    return status;
}
