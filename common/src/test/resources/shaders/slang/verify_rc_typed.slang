// Compile-only harness for the typed Radiance Cascades atlas.
//
// It exercises both buffer flavours of the typed store API: a writable
// RWStructuredBuffer<RCSegment> (the trace producer) and a read-only
// StructuredBuffer<RCSegment> (a resolve consumer).  Both must instantiate
// rcResolveRay, rcProbeIrradiance and rcProbeComplete.  The old float4 atlas is
// deliberately not imported here: it is a different record format.
//
// The layout comes from rc_atlas_layout.slang, not from the legacy store, so
// this harness also proves that the typed path has no dependency on the float4
// module.  Importing segment_store.slang alone is enough for RCSegment and
// RadianceCascadeLayout because the module re-exports both owners.
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

    RCSegment writableRay = rcResolveRay(writableRecords, layout, capacity, 0u, 0u, 0u);
    float3 writableIrradiance = rcProbeIrradiance(writableRecords, layout, capacity,
        0u, 0u, float3(0.0, 1.0, 0.0), 0u);
    bool writableComplete = rcProbeComplete(writableRecords, layout, capacity, 0u, 0u);

    RCSegment readableRay = rcResolveRay(readableRecords, layout, capacity, 0u, 0u, 0u);
    float3 readableIrradiance = rcProbeIrradiance(readableRecords, layout, capacity,
        0u, 0u, float3(0.0, 1.0, 0.0), 0u);
    bool readableComplete = rcProbeComplete(readableRecords, layout, capacity, 0u, 0u);

    sink[0] = float4(writableIrradiance + readableIrradiance,
                     writableRay.radiance.x + readableRay.radiance.x);
    sink[1] = float4(writableComplete ? 1.0 : 0.0, readableComplete ? 1.0 : 0.0, 0.0, 0.0);
}
