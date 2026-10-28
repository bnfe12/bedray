// Compile-only harness for the two public roots.
//
// bedray.slang is the curated current surface (typed RC atlas, current
// traversal backends).  bedray_legacy.slang opts into the float4 RC atlas and
// LegacySectionTraversal.  Both roots must remain importable together without
// ambiguous symbols, and the legacy resolve must keep its explicit name.
//
// This proves source-level wiring only.  It does not execute a dispatch or
// validate any host binding.
//
// Usage:
//   slangc -target spirv -profile cs_6_5 -entry main ^
//     -matrix-layout-column-major -I common/src/main/resources/shaders/slang ^
//     -o <temp>/verify_bedray_roots.spv ^
//     common/src/test/resources/shaders/slang/verify_bedray_roots.slang

import "bedray.slang";
import "bedray_legacy.slang";

[[vk::binding(0, 1)]] StructuredBuffer<RCSegment> typedRecords;
[[vk::binding(1, 1)]] StructuredBuffer<float4> legacyRecords;
[[vk::binding(2, 1)]] RWStructuredBuffer<float4> sink;

// Resolves the legacy type from bedray_legacy.slang.  Never called; a missing
// export is a compile error at the parameter declaration.
uint legacyInstanceMask(LegacySectionTraversal traversal) {
    return traversal.instanceMask;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    RadianceCascadeLayout layout = radianceCascadeLayout();
    RCTraceOptions options = defaultRCTraceOptions();
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();

    RCSegment unresolved = rcUnresolvedSegment();
    RCSegment composed = rcComposeSegments(unresolved, unresolved);
    bool typedComplete = rcProbeComplete(typedRecords, layout,
        layout.totalRecords, 0u, 0u);
    bool legacyComplete = rcLegacyProbeComplete(legacyRecords, layout, 0u, 0u);

    sink[0] = float4(composed.radiance, composed.confidence);
    sink[1] = float4(typedComplete ? 1.0 : 0.0, legacyComplete ? 1.0 : 0.0,
        options.worldBoundaryDistance, materialOptions.alphaCutoff);
}
