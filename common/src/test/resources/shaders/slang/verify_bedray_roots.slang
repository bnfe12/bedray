// Compile-only harness for the typed public shader surface.
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
[[vk::binding(0, 1)]] RWStructuredBuffer<float4> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    RadianceCascadeLayout layout = radianceCascadeLayout();
    RCTraceOptions options = defaultRCTraceOptions();
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();

    RCSegment unresolved = rcUnresolvedSegment();
    RCSegment composed = rcComposeSegments(unresolved, unresolved);

    sink[0] = float4(composed.radiance, composed.confidence);
    sink[1] = float4(float(layout.totalRecords), 0.0,
        options.worldBoundaryDistance, materialOptions.alphaCutoff);
}
