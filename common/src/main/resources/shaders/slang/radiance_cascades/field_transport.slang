public module rc_field_transport;
__exported import "radiance_cascades/interval_transport.slang";
__exported import "radiance_cascades/field_mapping.slang";
import "radiance_cascades/rc_trace_walk.slang";

struct RCFieldAverage {
    float3 radiance;
    float3 transmittance;
    float confidence;
    float weight;
    uint diagnostics;
    bool valid;
    bool terminalKnown;
};
RCFieldAverage rcFieldAverageBegin() {
    RCFieldAverage a;
    a.radiance = a.transmittance = float3(0.0);
    a.confidence = 1.0; a.weight = 0.0; a.diagnostics = 0u;
    a.valid = a.terminalKnown = true;
    return a;
}
void rcFieldAverageAdd(inout RCFieldAverage a, RCSegment s, float weight) {
    if (weight == 0.0) return;
    if (!isfinite(weight) || weight < 0.0 || !rcSegmentValid(s)) {
        a.valid = false; a.diagnostics |= s.flags & RC_SEGMENT_TRUNCATED; return;
    }
    a.radiance += weight * s.radiance; a.transmittance += weight * s.transmittance;
    a.weight += weight; a.confidence = min(a.confidence, s.confidence);
    a.diagnostics |= s.flags & RC_SEGMENT_CLAMPED;
    a.terminalKnown = a.terminalKnown && (all(s.transmittance == float3(0.0))
        || (s.flags & RC_FIELD_TERMINAL_KNOWN) != 0u);
}
RCSegment rcFieldAverageEnd(RCFieldAverage a) {
    if (!a.valid || !isfinite(a.weight) || abs(a.weight - 1.0) > 1.0e-4
        || !all(isfinite(a.radiance)) || !all(isfinite(a.transmittance)))
        return rcUnresolvedWithDiagnostics(a.diagnostics);
    RCSegment s;
    // All required branches were present; normalize quadrature roundoff only.
    s.radiance = a.radiance / a.weight;
    s.transmittance = a.transmittance / a.weight;
    s.confidence = a.confidence;
    s.flags = RC_SEGMENT_RESOLVED | a.diagnostics;
    if (all(s.transmittance == float3(0.0))) s.flags |= RC_SEGMENT_BLOCKED;
    else s.flags |= RC_SEGMENT_BOUNDED_MISS;
    if (a.terminalKnown) s.flags |= RC_FIELD_TERMINAL_KNOWN;
    return rcSegmentValid(s) ? s : rcUnresolvedSegment();
}

RCSegment rcFieldTrace<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment, MaterialAtlas atlas, BlockMaterialTable table,
        float3 a, float3 b, uint level, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed) {
    float3 delta = b - a;
    float distance = length(delta);
    if (!all(isfinite(a)) || !all(isfinite(b)) || !isfinite(distance) || distance <= 0.0)
        return rcUnresolvedSegment();
    RadianceRay ray;
    ray.origin = a; ray.direction = delta / distance;
    ray.nearDistance = 0.0;
    ray.solidAngle = 1.0; // walk is segment-local; never pass an undefined struct member
    // Endpoint B is owned by the back tail, including surfaces exactly at B.
    ray.farDistance = asfloat(asuint(distance) - 1u);
    options.nearDistance = options.farDistance = 0.0;
    options.environmentPolicy = uint(RCEnvironmentPolicy.Disabled);
    options.worldBoundaryDistance = 0.0;
    RCTraceResult result = traceCascadeRay(traversal, lights, environment, atlas, table,
        ray, level, materialOptions, options, seed);
    RCSegment s = result.composed;
    if (rcSegmentValid(s) && all(s.transmittance == float3(0.0))) s.flags |= RC_FIELD_TERMINAL_KNOWN;
    return s;
}

RCSegment rcFieldCompose(RCSegment front, RCSegment back) {
    RCSegment result = rcComposeSegments(front, back);
    if (!rcSegmentValid(result)) return result;
    result.flags &= ~RC_FIELD_TERMINAL_KNOWN;
    if (all(result.transmittance == float3(0.0)) || (rcSegmentValid(back)
        && (back.flags & RC_FIELD_TERMINAL_KNOWN) != 0u)) result.flags |= RC_FIELD_TERMINAL_KNOWN;
    return result;
}

RCSegment rcFieldReconstruct<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment, MaterialAtlas atlas, BlockMaterialTable table,
        RCFieldConstants field, RWStructuredBuffer<RCSegment> tails,
        uint level, uint3 probe, uint ray, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed) {
    float3 p = rcFieldProbe(field, level, probe);
    if (level == RC_FIELD_LEVELS - 1u) {
        float3 direction = rcFieldDirection(ray, field.counts[level].y);
        return rcFieldTrace(traversal, lights, environment, atlas, table,
            p + field.ranges[level].x * direction, p + field.ranges[level].y * direction,
            level, materialOptions, options, seed);
    }
    int3 base; float3 fraction;
    if (!rcFieldNeighbors(field, level + 1u, p, base, fraction)) return rcUnresolvedSegment();
    uint face; uint2 cell; uint side = field.counts[level].y;
    rcFieldCell(ray, side, face, cell);
    float area = rcFieldCellArea(ray, side);
    RCFieldAverage average = rcFieldAverageBegin();
    for (uint h = 0u; h < 4u; ++h) {
        uint2 childCell = 2u * cell + uint2(h & 1u, h >> 1u);
        uint childSide = side * 2u;
        uint child = face * childSide * childSide + childCell.x + childSide * childCell.y;
        float3 direction = rcFieldDirection(child, childSide);
        float angularWeight = rcFieldCellArea(child, childSide) / area;
        float3 a = p + field.ranges[level].x * direction;
        for (uint n = 0u; n < 8u; ++n) {
            float weight = angularWeight * rcFieldNeighborWeight(fraction, n);
            if (weight == 0.0) continue;
            uint3 neighbor = rcFieldNeighbor(base, n);
            float3 b = rcFieldProbe(field, level + 1u, neighbor) + field.ranges[level + 1u].x * direction;
            RCSegment front = dot(b - a, direction) > 0.0
                ? rcFieldTrace(traversal, lights, environment, atlas, table, a, b, level,
                    materialOptions, options, seed) : rcUnresolvedSegment();
            RCSegment back = tails[rcFieldIndex(field, level + 1u, neighbor, child)];
            rcFieldAverageAdd(average, rcFieldCompose(front, back), weight);
        }
    }
    return rcFieldAverageEnd(average);
}
