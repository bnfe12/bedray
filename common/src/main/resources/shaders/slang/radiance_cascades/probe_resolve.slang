// Typed interval atlas: probe reconstruction.
//
// Resolves one probe's two intervals per ray into a cosine-weighted average
// radiance.  This is the typed replacement for the float4
// rcLegacyProbeIrradiance, and it deliberately keeps the two concepts separate:
//
//   radiance   average radiance over the valid rays, NOT pi-scaled irradiance
//   complete   every ray composed to a valid record; a partial probe still
//              produces a value, so a caller that needs a trustworthy estimate
//              must check this flag or use rcProbeComplete
//
// Rays whose composition is unresolved are skipped and the remaining cosine
// weights are renormalised.  That is a deliberately biased estimate for a
// partial probe; it is why completeness is part of the result instead of a
// separate function the caller can forget.
//
// This resolves one probe's two intervals per ray.  It does not interpolate
// across cascades or probe positions; that mapping belongs to the integration.
// The canonical layout is validated once per probe, not once per ray.
//
// Mapping note: one invocation per probe is the simple reference mapping.  A
// production resolve for the high cascades should split (probe, ray) across a
// thread group and reduce, because cascade 4 has 4096 rays for only 8 probes.

public module probe_resolve;

import "radiance_cascades.slang";
__exported import "radiance_cascades/segment_record.slang";

// Value and status of one probe reconstruction.  `radiance` is meaningful only
// when `probeValid` is true; `complete` additionally says that no ray was
// skipped.  A caller must not treat radiance as a confident estimate when
// `complete` is false.
struct RCProbeRadiance {
    float3 radiance;
    uint   validRays;
    uint   totalRays;
    bool   complete;
    bool   layoutValid;
    bool   probeValid;
};

RCProbeRadiance rcResolveProbeFrom<T : IRCSegmentSource>(T source,
        RadianceCascadeLayout layout, uint capacity, uint cascade, uint probeIndex,
        float3 normal, uint rotation) {
    RCProbeRadiance result;
    result.radiance = float3(0.0);
    result.validRays = 0u;
    result.totalRays = 0u;
    result.complete = false;
    result.layoutValid = false;
    result.probeValid = false;

    if (!rcLayoutCanonical(layout, capacity)) return result;
    result.layoutValid = true;
    if (cascade >= RADIANCE_CASCADE_COUNT) return result;
    if (probeIndex >= layout.probeCount[cascade]) return result;

    float normalLengthSquared = dot(normal, normal);
    if (!(normalLengthSquared > 0.0) || !isfinite(normalLengthSquared)) return result;
    float3 unitNormal = normalize(normal);
    result.probeValid = true;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    result.totalRays = desc.rayCount;
    uint base = layout.baseOffset[cascade] +
        probeIndex * layout.rayCount[cascade] * RC_INTERVALS_PER_RAY;

    float3 sum = float3(0.0);
    float weightSum = 0.0;
    for (uint ray = 0u; ray < desc.rayCount; ++ray) {
        uint nearIndex = base + ray * RC_INTERVALS_PER_RAY;
        RCSegment composed = rcComposeSegments(source.rcLoadSegment(nearIndex),
                                                source.rcLoadSegment(nearIndex + 1u));
        if (!rcSegmentValid(composed)) continue;
        result.validRays++;

        float3 direction = cascadeDirection(ray, desc.rayCount, rotation);
        float cosine = max(dot(unitNormal, direction), 0.0);
        if (cosine <= 0.0) continue;
        sum += composed.radiance * cosine;
        weightSum += cosine;
    }

    result.complete = result.validRays == result.totalRays;
    result.radiance = sum / max(weightSum, 1.0e-6);
    return result;
}

RCProbeRadiance rcResolveProbe(StructuredBuffer<RCSegment> records,
        RadianceCascadeLayout layout, uint capacity, uint cascade, uint probeIndex,
        float3 normal, uint rotation) {
    RCReadOnlySegmentSource source;
    source.records = records;
    return rcResolveProbeFrom(source, layout, capacity, cascade, probeIndex, normal, rotation);
}

RCProbeRadiance rcResolveProbe(RWStructuredBuffer<RCSegment> records,
        RadianceCascadeLayout layout, uint capacity, uint cascade, uint probeIndex,
        float3 normal, uint rotation) {
    RCReadWriteSegmentSource source;
    source.records = records;
    return rcResolveProbeFrom(source, layout, capacity, cascade, probeIndex, normal, rotation);
}

// Completeness without computing radiance.  A probe is complete only when every
// ray composes to a valid segment.  An exactly opaque front segment is complete
// without a back sample; a transmissive front with an unresolved back is not.
bool rcProbeCompleteFrom<T : IRCSegmentSource>(T source, RadianceCascadeLayout layout,
        uint capacity, uint cascade, uint probeIndex) {
    if (!rcLayoutCanonical(layout, capacity)) return false;
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;
    if (probeIndex >= layout.probeCount[cascade]) return false;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    uint base = layout.baseOffset[cascade] +
        probeIndex * layout.rayCount[cascade] * RC_INTERVALS_PER_RAY;
    for (uint ray = 0u; ray < desc.rayCount; ++ray) {
        uint nearIndex = base + ray * RC_INTERVALS_PER_RAY;
        RCSegment composed = rcComposeSegments(source.rcLoadSegment(nearIndex),
                                                source.rcLoadSegment(nearIndex + 1u));
        if (!rcSegmentValid(composed)) return false;
    }
    return true;
}

bool rcProbeComplete(StructuredBuffer<RCSegment> records, RadianceCascadeLayout layout,
        uint capacity, uint cascade, uint probeIndex) {
    RCReadOnlySegmentSource source;
    source.records = records;
    return rcProbeCompleteFrom(source, layout, capacity, cascade, probeIndex);
}

bool rcProbeComplete(RWStructuredBuffer<RCSegment> records, RadianceCascadeLayout layout,
        uint capacity, uint cascade, uint probeIndex) {
    RCReadWriteSegmentSource source;
    source.records = records;
    return rcProbeCompleteFrom(source, layout, capacity, cascade, probeIndex);
}
