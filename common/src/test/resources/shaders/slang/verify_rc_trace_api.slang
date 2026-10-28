// Compile-only harness for the typed Radiance Cascades trace API.
//
// This is the integration-facing contract of rc_trace.slang: it constructs the
// exact argument types a host pass needs (layout, options, material options,
// history record) and instantiates every public entry point, fresh and
// provisional, plus the ownership-band proposal.
//
// It deliberately uses mock backends.  Compilation proves that the module
// split, re-exports, overloads and generic constraints are coherent; it does
// not execute a dispatch, validate bindings, or prove any visual result.
//
// Usage (same arguments as gradle/slang.gradle):
//   slangc -target spirv -profile cs_6_5 -entry main ^
//     -matrix-layout-column-major -I common/src/main/resources/shaders/slang ^
//     -o <temp>/verify_rc_trace_api.spv ^
//     common/src/test/resources/shaders/slang/verify_rc_trace_api.slang

import "radiance_cascades.slang";
import "radiance_cascades/rc_trace.slang";
import "contracts/hit.slang";
import "contracts/light.slang";
import "contracts/scene_tracer.slang";
import "light/sky.slang";
import "surface/surface.slang";
import "traversal/entity_geometry.slang";

struct MockTracer : ISceneTracer {
    SurfaceHit traceClosest(Ray ray) {
        return missHit(ray.tMax);
    }
    VisibilityResult traceVisibility(Ray ray) {
        return visibleResult(ray.tMax);
    }
    float traceDistance(Ray ray) {
        return ray.tMax;
    }
}

struct MockLightSampler : ILightSampler {
    LightSample sample(LightReceiver receiver, inout uint seed) {
        return invalidLightSample();
    }
    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        return invalidLightEvaluation();
    }
}

struct MockEnvironment : IEnvironmentSampler {
    float3 radiance(float3 direction) {
        return float3(0.0);
    }
    SkySample sample(float3 normal, inout uint seed) {
        SkySample result;
        result.valid = false;
        result.direction = float3(0.0, 1.0, 0.0);
        result.radiance = float3(0.0);
        result.pdf = 0.0;
        return result;
    }
    float pdf(float3 normal, float3 direction) {
        return 0.0;
    }
}

[[vk::binding(0, 0)]] Sampler2D albedoAtlas;
[[vk::binding(1, 0)]] Sampler2D materialAtlasSampler;
[[vk::binding(2, 0)]] Sampler2D normalAtlas;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> entityRects;
[[vk::binding(4, 0)]] StructuredBuffer<uint> entityTexels;
[[vk::binding(5, 0)]] StructuredBuffer<uint> entityPositions;
[[vk::binding(6, 0)]] StructuredBuffer<uint> entityPrevious;
[[vk::binding(7, 0)]] StructuredBuffer<uint> entityIndices;
[[vk::binding(8, 0)]] StructuredBuffer<uint4> entityAttrs;
[[vk::binding(9, 0)]] StructuredBuffer<BlockMaterial> blockMaterialBuffer;
[[vk::binding(10, 0)]] RWStructuredBuffer<RCSegment> segments;
[[vk::binding(11, 0)]] StructuredBuffer<RCSegment> historySegments;
[[vk::binding(12, 0)]] RWStructuredBuffer<float4> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    MockTracer tracer;
    MockLightSampler lights;
    MockEnvironment environment;

    EntityAtlas entities;
    entities.rects = entityRects;
    entities.texels = entityTexels;
    entities.atlasSize = 0u;

    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas;
    atlas.material = materialAtlasSampler;
    atlas.normal = normalAtlas;
    atlas.entities = entities;

    BlockMaterialTable blockMaterials;
    blockMaterials.materials = blockMaterialBuffer;
    blockMaterials.count = 0u;

    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    RCTraceOptions options = defaultRCTraceOptions();
    options.environmentPolicy = uint(RCEnvironmentPolicy.TerminalCascade);
    options.environmentCascade = RADIANCE_CASCADE_COUNT - 1u;
    options.worldBoundaryDistance = 1024.0;
    options.farDistance = 2048.0;

    RadianceCascadeLayout layout = radianceCascadeLayout();
    uint capacity = layout.totalRecords;
    RadianceRay rayValue = cascadeRay(radianceCascade(0u), 0u, 0u, 0u, float3(0.0));
    uint seed = tid.x;
    uint cascade = 0u;

    RCTraceResult single = traceCascadeRay(tracer, lights, environment, atlas,
        blockMaterials, rayValue, cascade, materialOptions, options, seed);

    RCTraceResult stored = rcTraceAndStoreRay(tracer, lights, environment, atlas,
        blockMaterials, layout, segments, capacity, cascade, 0u, 0u,
        float3(0.0), 0u, materialOptions, options, seed);

    // Every entry reports an explicit status.  Reading all counters keeps the
    // vocabulary pinned: adding or renaming a counter must break this harness.
    RCTraceEntryStatus probeStatus = traceCascadeProbe(tracer, lights,
        environment, atlas, blockMaterials, layout, segments, capacity, cascade,
        0u, float3(0.0), 0u, materialOptions, options, seed);

    RCTraceEntryStatus dispatchStatus = traceCascadeDispatch(tracer, lights,
        environment, atlas, blockMaterials, layout, segments, capacity, 0u,
        float3(0.0), 0u, materialOptions, options, seed);

    RCTraceEntryStatus foldedTrace = emptyRCTraceEntryStatus();
    rcAccumulateTraceStatus(foldedTrace, stored);
    rcAccumulateTraceStatus(foldedTrace, single);
    bool traceCodeIsEnum = probeStatus.code == uint(RCTraceEntryCode.Ok) ||
        probeStatus.code == uint(RCTraceEntryCode.InvalidLayout) ||
        probeStatus.code == uint(RCTraceEntryCode.InvalidDispatch);

    RCTemporalHistory history;
    history.segments = historySegments;
    history.historyCapacity = capacity;
    history.previousCameraOrigin = float3(0.0);
    history.previousRotation = 0u;
    history.previousSceneRevision = 1u;
    history.currentSceneRevision = 1u;
    history.weight = 0.9;
    history.valid = true;

    RCTraceBlendedStatus blendedProbe = traceCascadeProbeBlended(tracer, lights,
        environment, atlas, blockMaterials, layout, segments, capacity, history,
        cascade, 0u, float3(0.0), 0u, materialOptions, options, seed);

    RCTraceBlendedStatus blendedDispatch = traceCascadeDispatchBlended(tracer,
        lights, environment, atlas, blockMaterials, layout, segments, capacity,
        history, 0u, float3(0.0), 0u, materialOptions, options, seed);

    RCTemporalStatus foldedHistory = emptyRCTemporalStatus();
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.Accepted);
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.HistoryUnusable);
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.TerminalClassMismatch);
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.InvalidWeight);
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.InvalidAddress);
    rcAccumulateTemporalStatus(foldedHistory, RCTemporalBlendCode.StoreFailed);

    // The typed probe resolve is the current name; the float4 resolve keeps the
    // rcLegacyProbeIrradiance name and is not imported by this harness.
    RCProbeRadiance probeRadiance = rcResolveProbe(historySegments, layout,
        capacity, cascade, 0u, float3(0.0, 1.0, 0.0), 0u);

    RCCascadeOwnershipBand band;
    bool bandValid = rcCascadeOwnershipBand(cascade, band);
    RCTraceOptions bandOptions;
    bool bandApplied = rcApplyCascadeOwnershipBand(options, cascade, bandOptions);

    RCSegment composed = rcComposeSegments(single.nearSegment, single.farSegment);
    bool optionsValid = rcTraceOptionsValid(options);
    bool environmentAllowed = rcEnvironmentAllowed(options,
        RADIANCE_CASCADE_COUNT - 1u, 2048.0);
    bool historyRayMapped = rcHistoryRayIndex(0u, 0u, 0u, 16u) == 0u;

    sink[0] = float4(single.distance, stored.distance, band.nearDistance,
        band.farDistance);
    sink[1] = float4(composed.radiance, composed.confidence);
    sink[2] = float4(float(probeStatus.rays + dispatchStatus.rays),
        float(probeStatus.stored + dispatchStatus.stored),
        optionsValid ? 1.0 : 0.0, environmentAllowed ? 1.0 : 0.0);
    sink[3] = float4(bandValid ? band.splitDistance : 0.0,
        bandApplied ? bandOptions.farDistance : 0.0,
        historyRayMapped ? 1.0 : 0.0, traceCodeIsEnum ? 1.0 : 0.0);
    sink[4] = float4(float(probeStatus.unresolved + dispatchStatus.unresolved),
        float(probeStatus.storeFailed + dispatchStatus.storeFailed),
        float(probeStatus.invalidInput + dispatchStatus.invalidInput),
        float(probeStatus.truncated + dispatchStatus.truncated));
    sink[5] = float4(float(blendedProbe.trace.stored + blendedDispatch.trace.stored),
        float(blendedProbe.history.accepted + blendedDispatch.history.accepted),
        float(blendedProbe.history.rejectedMapping +
              blendedDispatch.history.rejectedMapping),
        float(blendedProbe.history.rejectedInvalid +
              blendedDispatch.history.rejectedInvalid));
    sink[6] = float4(float(blendedProbe.history.rejectedClass +
                           blendedDispatch.history.rejectedClass),
        float(blendedProbe.history.storeFailed +
              blendedDispatch.history.storeFailed),
        float(foldedTrace.rays),
        float(foldedHistory.accepted + foldedHistory.rejectedMapping +
              foldedHistory.rejectedInvalid + foldedHistory.rejectedClass +
              foldedHistory.storeFailed));
    sink[7] = float4(probeRadiance.radiance, probeRadiance.complete ? 1.0 : 0.0);
    sink[8] = float4(float(probeRadiance.validRays), float(probeRadiance.totalRays),
        probeRadiance.layoutValid ? 1.0 : 0.0,
        probeRadiance.probeValid ? 1.0 : 0.0);
}
