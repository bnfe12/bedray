// Segment-local transport for Radiance Cascades. The atlas and its consumers
// must use the same reflected record layout; this module does not reinterpret
// the older float4 atlas or implement cross-cascade interpolation.
public module interval_transport;

static const uint RC_SEGMENT_RESOLVED = 1u << 0u;
static const uint RC_SEGMENT_BLOCKED = 1u << 1u;
static const uint RC_SEGMENT_BOUNDED_MISS = 1u << 2u;
static const uint RC_SEGMENT_ENVIRONMENT = 1u << 3u;
static const uint RC_SEGMENT_TRUNCATED = 1u << 4u;
static const uint RC_SEGMENT_CLAMPED = 1u << 5u;

// L and T describe only this segment: L for the segment's emitted/reflected
// radiance and T for light arriving through its far boundary. Confidence is
// metadata, not an interpolation weight between consecutive segments.
// A producer must clear RESOLVED on incomplete or invalid data.
struct RCSegment {
    float3 radiance;
    float confidence;
    float3 transmittance;
    uint flags;
};

RCSegment rcUnresolvedSegment() {
    RCSegment segment;
    segment.radiance = float3(0.0);
    segment.confidence = 0.0;
    segment.transmittance = float3(0.0);
    segment.flags = 0u;
    return segment;
}

// Keep only diagnostics with an unambiguous meaning for an unresolved record.
// Unknown or contradictory flags must not accidentally make it resolved.
RCSegment rcUnresolvedWithDiagnostics(uint flags) {
    RCSegment segment = rcUnresolvedSegment();
    segment.flags = flags & RC_SEGMENT_TRUNCATED;
    return segment;
}

// Only exactly zero transmission makes the back segment irrelevant. A small
// nonzero value is not proof of occlusion. Invalid estimates remain unresolved
// instead of being silently turned into black, resolved lighting.
bool rcSegmentValid(RCSegment segment) {
    return (segment.flags & RC_SEGMENT_RESOLVED) != 0u &&
        (segment.flags & RC_SEGMENT_TRUNCATED) == 0u &&
        all(isfinite(segment.radiance)) && all(isfinite(segment.transmittance)) &&
        isfinite(segment.confidence) && segment.confidence > 0.0 &&
        all(segment.transmittance >= float3(0.0)) &&
        all(segment.transmittance <= float3(1.0));
}

RCSegment rcComposeSegments(RCSegment front, RCSegment back) {
    if (!rcSegmentValid(front)) return rcUnresolvedWithDiagnostics(front.flags);
    if (all(front.transmittance == float3(0.0))) return front;
    // Environment already represents the incoming light beyond this segment;
    // adding another segment behind it would count that contribution twice.
    if ((front.flags & RC_SEGMENT_ENVIRONMENT) != 0u) return rcUnresolvedSegment();
    if (!rcSegmentValid(back)) return rcUnresolvedWithDiagnostics(back.flags);

    RCSegment result;
    result.radiance = front.radiance + front.transmittance * back.radiance;
    result.transmittance = front.transmittance * back.transmittance;
    result.confidence = min(front.confidence, back.confidence);
    result.flags = front.flags | back.flags;
    if (!all(isfinite(result.radiance))) return rcUnresolvedSegment();
    return result;
}
