// Typed Radiance Cascades trace: store, probe and dispatch entries.
//
// These entries are the host-facing producer boundary.  They trace one ray or
// one probe and write whole RCSegment records through segment_store.slang.
// Temporal reuse is deliberately absent; see rc_trace_history.slang for the
// explicitly provisional blend.
//
// Store contract
// --------------
// rcWriteRaySegments validates both addresses, then normalizes every segment
// that fails rcSegmentValid (unresolved or TRUNCATED) to
// rcUnresolvedWithDiagnostics(flags).  Radiance, confidence and transmittance
// are cleared, but RC_SEGMENT_TRUNCATED survives as a diagnostic, and
// rcComposeSegments carries it through when that segment is needed.  No other
// trace-local event is stored: the stored record has RCSegment.flags only, so
// INVALID, NEE, SHADOW_TRUNCATED and the other per-segment events exist just in
// nearEvents/farEvents of RCTraceResult and cannot be recovered from the atlas.
// A valid stored record keeps its own segment-local L/T; a record without
// RESOLVED is untrusted, never black.  A valid front with exactly zero
// transmittance still makes an unresolved back irrelevant.
//
// rcWriteRaySegments does not make the two writes atomic; readers need a pass
// barrier.  `stored` reports address success, not ray validity.  A fresh trace
// writes (not accumulates); cross-frame accumulation belongs to the resolve
// owner.
//
// Dispatch contract
// -----------------
// traceCascadeProbe returns the number of rays whose address pair was not
// written (non-canonical layout, insufficient capacity).  Zero does not mean
// every segment resolved; read the typed records or use traceCascadeRay for
// status.  traceCascadeDispatch decodes a flat dispatch index; a stale
// oversized dispatch returns the cascade sentinel and writes nothing.

public module rc_trace_dispatch;

__exported import "radiance_cascades.slang";
__exported import "radiance_cascades/rc_trace_options.slang";
__exported import "radiance_cascades/rc_trace_walk.slang";
__exported import "radiance_cascades/segment_store.slang";
__exported import "contracts/light.slang";
__exported import "light/sky.slang";
__exported import "surface/surface.slang";
__exported import "traversal/traversal.slang";

// Traces one ray and writes both whole records through the typed store.  On a
// valid address pair, a segment that fails rcSegmentValid is normalized to
// unresolved with RC_SEGMENT_TRUNCATED retained when present.  If the pair
// cannot be addressed (non-canonical layout, capacity too small, invalid
// dispatch), nothing is written and STORE_FAILED is set.
RCTraceResult rcTraceAndStoreRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, uint cascade, uint probeIndex, uint rayIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    RCTraceResult result = emptyRCTraceResult();
    if (cascade >= RADIANCE_CASCADE_COUNT) {
        result.flags |= RC_TRACE_FLAG_INVALID_INPUT;
        return result;
    }

    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (probeIndex >= probeCount(desc) || rayIndex >= desc.rayCount) {
        result.flags |= RC_TRACE_FLAG_INVALID_DISPATCH;
        return result;
    }

    RadianceRay rayValue = cascadeRay(desc, probeIndex, rayIndex, frameRotation,
        cameraOrigin);
    result = traceCascadeRay(traversal, lights, environment, atlas,
        blockMaterials, rayValue, cascade, materialOptions, options, seed);
    result.cascade = cascade;
    result.probeIndex = probeIndex;
    result.rayIndex = rayIndex;
    result.stored = rcWriteRaySegments(segments, layout, capacity, cascade,
        probeIndex, rayIndex, result.nearSegment, result.farSegment);
    if (!result.stored) result.flags |= RC_TRACE_FLAG_STORE_FAILED;
    return result;
}

// Fresh probe trace, no temporal reuse.  Returns the number of rays whose
// store address pair was not written.  A zero count does not mean every
// segment resolved; read the typed records or use traceCascadeRay for status.
uint traceCascadeProbe<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, uint cascade, uint probeIndex, float3 cameraOrigin,
        uint frameRotation, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed) {
    if (cascade >= RADIANCE_CASCADE_COUNT || cascade >= layout.cascadeCount) {
        return 0u;
    }
    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (probeIndex >= probeCount(desc)) return 0u;

    uint storeFailures = 0u;
    for (uint rayIndex = 0u; rayIndex < desc.rayCount; ++rayIndex) {
        RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
            atlas, blockMaterials, layout, segments, capacity, cascade,
            probeIndex, rayIndex, cameraOrigin, frameRotation, materialOptions,
            options, seed);
        if (!result.stored) storeFailures++;
    }
    return storeFailures;
}

// decodeRadianceDispatch splits a flat dispatch index into cascade / probe /
// ray, so one compute dispatch covers the whole cascade set.  The sentinel
// cascade means a stale oversized dispatch: the store is not touched.
uint traceCascadeDispatch<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, uint globalIndex, float3 cameraOrigin, uint frameRotation,
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
    return result.stored ? 0u : 1u;
}
