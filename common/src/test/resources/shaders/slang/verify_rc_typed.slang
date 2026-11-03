// Compile-only harness for the typed Radiance Cascades atlas.
//
// It exercises both buffer flavours of the typed store API: a writable
// RWStructuredBuffer<RCSegment> (the trace producer) and a read-only
// StructuredBuffer<RCSegment> (a resolve consumer).  Both must instantiate
// rcResolveRay, rcResolveProbe and rcProbeComplete over the generic
// IRCSegmentSource path, plus the record read/write helpers.  The old float4
// atlas is deliberately not imported here: it is a different record format.
//
// The layout comes from rc_atlas_layout.slang, not from the legacy store, so
// this harness also proves that the typed path has no dependency on the float4
// module.  Importing segment_store.slang alone is enough for RCSegment and
// RadianceCascadeLayout because the module re-exports both owners.
//
// rcResolveProbe returns RCProbeRadiance, not a bare float3.  The harness reads
// every field so a future field removal or rename breaks compilation instead of
// silently leaving a stale host contract.
//
// Usage (same arguments as gradle/slang.gradle):
//   slangc -target spirv -profile cs_6_5 -entry main ^
//     -matrix-layout-column-major -I common/src/main/resources/shaders/slang ^
//     -o <temp>/verify_rc_typed.spv ^
//     common/src/test/resources/shaders/slang/verify_rc_typed.slang
//
// This proves generic code generation only.  It does not execute a dispatch,
// validate the reflected record stride, or prove any visual result.

import "radiance_cascades.slang";
import "radiance_cascades/rc_atlas_layout.slang";
import "radiance_cascades/interval_transport.slang";
import "radiance_cascades/segment_store.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<RCSegment> writableRecords;
[[vk::binding(1, 0)]] StructuredBuffer<RCSegment> readableRecords;
[[vk::binding(2, 0)]] RWStructuredBuffer<float4> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    RadianceCascadeLayout layout = radianceCascadeLayout();
    uint capacity = layout.totalRecords;
    bool layoutValid = rcLayoutCanonical(layout, capacity);

    // Flattened index arithmetic is owned by rc_atlas_layout.slang; the record
    // modules call it only after one canonical validation per ray or probe.
    uint nearIndex;
    bool addressed = rcSegmentIndex(layout, capacity, 0u, 0u, 0u,
        RC_INTERVAL_NEAR, nearIndex);

    // Explicit source structs: a pass that owns the atlas can compose a ray
    // without a second read-only binding.
    RCReadOnlySegmentSource readSource;
    readSource.records = readableRecords;
    RCReadWriteSegmentSource writeSource;
    writeSource.records = writableRecords;
    RCSegment explicitRead = rcReadSegmentFrom(readSource, layout, capacity,
        0u, 0u, 0u, RC_INTERVAL_NEAR);
    RCSegment explicitWrite = rcResolveRayFrom(writeSource, layout, capacity,
        0u, 0u, 0u);

    // Public overloads over each buffer flavour.
    RCSegment writableRay = rcResolveRay(writableRecords, layout, capacity, 0u, 0u, 0u);
    RCProbeRadiance writableProbe = rcResolveProbe(writableRecords, layout, capacity,
        0u, 0u, float3(0.0, 1.0, 0.0), 0u);
    bool writableComplete = rcProbeComplete(writableRecords, layout, capacity, 0u, 0u);
    RCSegment writableRead = rcReadSegment(writableRecords, layout, capacity,
        0u, 0u, 0u, RC_INTERVAL_FAR);

    RCSegment readableRay = rcResolveRay(readableRecords, layout, capacity, 0u, 0u, 0u);
    RCProbeRadiance readableProbe = rcResolveProbe(readableRecords, layout, capacity,
        0u, 0u, float3(0.0, 1.0, 0.0), 0u);
    bool readableComplete = rcProbeComplete(readableRecords, layout, capacity, 0u, 0u);
    RCSegment readableRead = rcReadSegment(readableRecords, layout, capacity,
        0u, 0u, 0u, RC_INTERVAL_FAR);

    // Writes validate both addresses before touching the atlas; a caller that
    // owns the record can use either the single-segment or the ray helper.
    bool wroteSegment = rcWriteSegment(writableRecords, layout, capacity,
        0u, 0u, 0u, RC_INTERVAL_NEAR, rcUnresolvedSegment());
    bool wroteRay = rcWriteRaySegments(writableRecords, layout, capacity,
        0u, 0u, 0u, rcUnresolvedSegment(), rcUnresolvedSegment());
    bool clearedRay = rcClearRaySegments(writableRecords, layout, capacity,
        0u, 0u, 0u);

    uint validTotal = writableProbe.validRays + readableProbe.validRays;
    uint rayTotal = writableProbe.totalRays + readableProbe.totalRays;
    bool allComplete = writableProbe.complete && readableProbe.complete &&
        writableProbe.layoutValid && readableProbe.layoutValid &&
        writableProbe.probeValid && readableProbe.probeValid;

    sink[0] = float4(writableProbe.radiance + readableProbe.radiance,
                     writableRay.radiance.x + readableRay.radiance.x);
    sink[1] = float4(writableComplete ? 1.0 : 0.0, readableComplete ? 1.0 : 0.0,
                     layoutValid ? 1.0 : 0.0, addressed ? 1.0 : 0.0);
    sink[2] = float4(float(validTotal), float(rayTotal),
                     allComplete ? 1.0 : 0.0,
                     float(explicitRead.flags | explicitWrite.flags |
                           writableRead.flags | readableRead.flags));
    sink[3] = float4(wroteSegment ? 1.0 : 0.0, wroteRay ? 1.0 : 0.0,
                     clearedRay ? 1.0 : 0.0, float(nearIndex));
}
