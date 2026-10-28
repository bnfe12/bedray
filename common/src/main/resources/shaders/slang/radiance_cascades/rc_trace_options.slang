// Typed Radiance Cascades trace contract: options, diagnostics and result.
//
// This module is the vocabulary shared by every trace entry.  It owns no
// traversal, shading or storage:
//
//   RCTraceOptions    host policy (distance window, budgets, environment)
//   RCTraceResult     near/far records plus ray-level diagnostics
//   RC_TRACE_*_*      diagnostic bits, scoped exactly as documented below
//
// Status scopes
// -------------
// RCSegment.flags describes exactly one segment: RESOLVED, BLOCKED,
// BOUNDED_MISS, ENVIRONMENT, TRUNCATED, CLAMPED.  RC_TRACE_EVENT_* is likewise
// reported per segment in nearEvents/farEvents and never changes transport.
//
// RCTraceResult.flags is a ray-level aggregate.  Each bit means "at least one
// segment (or the walk itself) had this property"; it never says which segment.
// Use nearSegment/farSegment for that.  There is deliberately no ray-wide
// "near hit" or "far environment" bit.
//
// Environment policy
// ------------------
// A bounded interval that misses must not add sky in every cascade.  The walk
// is given the cascade index; RCEnvironmentPolicy.TerminalCascade permits
// environment radiance only for options.environmentCascade, and only when
// options.worldBoundaryDistance is positive and the trace's tFar reaches it.
// A miss inside a cave is not proof of open sky: a terminal cascade can run out
// of its own range while still under a roof.  Environment is added to the far
// segment after the far filter, and the ENVIRONMENT bit prevents anything from
// being composed behind it.
//
// A zero boundary disables environment (the safe default) rather than acting as
// "trusted terminal cascade".  The host must assert a real boundary and, for
// the native c4 range, raise the terminal cascade's far override to reach it.
// That assertion is a host/resolve decision, not a guarantee of this module.

public module rc_trace_options;

__exported import "radiance_cascades.slang";
__exported import "radiance_cascades/interval_transport.slang";
import "contracts/math.slang";
static const uint RCT_INTERVAL_NEAR = 0u;
static const uint RCT_INTERVAL_FAR  = 1u;

// Sentinel for "this world position has no address in the previous grid".
static const uint RCT_INVALID_PROBE = 0xFFFFFFFFu;

// Per-segment events.  These are diagnostics, not RCSegment flags: the shared
// record cannot store them and they never change the transport value.  A bit
// in nearEvents applies only to the near segment; the same bit in farEvents
// applies only to the far segment.
static const uint RC_TRACE_EVENT_HIT               = 1u << 0u;
static const uint RC_TRACE_EVENT_FILTERED          = 1u << 1u;
static const uint RC_TRACE_EVENT_NEE               = 1u << 2u;
static const uint RC_TRACE_EVENT_DEPTH_LIMIT       = 1u << 3u;
static const uint RC_TRACE_EVENT_SHADOW_TRUNCATED  = 1u << 4u;
static const uint RC_TRACE_EVENT_CLAMPED           = 1u << 5u;
static const uint RC_TRACE_EVENT_INVALID           = 1u << 6u;

// Ray-level aggregate flags.  Each one means "at least one segment (or the
// walk itself) had this property"; it never identifies the segment.  Exact
// scope lives in nearSegment/farSegment and nearEvents/farEvents.
static const uint RC_TRACE_FLAG_HIT              = 1u << 0u;
static const uint RC_TRACE_FLAG_ENVIRONMENT      = 1u << 1u;
static const uint RC_TRACE_FLAG_STEP_LIMIT       = 1u << 2u;
static const uint RC_TRACE_FLAG_BLOCKED          = 1u << 3u;
static const uint RC_TRACE_FLAG_BOUNDED_MISS     = 1u << 4u;
static const uint RC_TRACE_FLAG_CLAMPED          = 1u << 5u;
static const uint RC_TRACE_FLAG_FILTERED         = 1u << 6u;
static const uint RC_TRACE_FLAG_NEE              = 1u << 7u;
static const uint RC_TRACE_FLAG_DEPTH_LIMIT      = 1u << 8u;
static const uint RC_TRACE_FLAG_SHADOW_TRUNCATED = 1u << 9u;
static const uint RC_TRACE_FLAG_INVALID_INPUT    = 1u << 10u;
static const uint RC_TRACE_FLAG_INVALID_SAMPLE   = 1u << 11u;
static const uint RC_TRACE_FLAG_EMPTY_WINDOW     = 1u << 12u;
static const uint RC_TRACE_FLAG_INVALID_DISPATCH = 1u << 13u;
static const uint RC_TRACE_FLAG_STORE_FAILED     = 1u << 14u;

// Only the terminal cascade may turn a miss into environment radiance.
// Disabled is an explicit policy for a pass that composites environment
// elsewhere; it is not a silent black fallback.
enum RCEnvironmentPolicy : uint {
    Disabled        = 0u,
    TerminalCascade = 1u,
};

struct RCTraceOptions {
    uint  maxDepth;               // translucent-emission events along one ray
    float rayBias;                // offset when stepping past a surface; must be > 0
    float nearDistance;           // explicit override; 0 = use the cascade ray's range
    float farDistance;            // explicit override; 0 = use the cascade ray's range
    float maxRadiance;            // per-segment firefly clamp
    uint  environmentPolicy;      // RCEnvironmentPolicy
    uint  environmentCascade;     // cascade that owns the environment
    float worldBoundaryDistance;  // >0: the miss window must reach it; 0 = no proven boundary, no environment
    uint  flags;                  // reserved, must be 0 today
};

RCTraceOptions defaultRCTraceOptions() {
    RCTraceOptions result;
    result.maxDepth = 2u;
    result.rayBias = 1.0e-3;
    result.nearDistance = 0.0;
    result.farDistance = 0.0;
    result.maxRadiance = 1.0e6;
    // No boundary is known here, so the safe default adds no environment.  A
    // host that wants sky in the terminal cascade opts in explicitly with a
    // positive worldBoundaryDistance and a far window that reaches it.
    result.environmentPolicy = uint(RCEnvironmentPolicy.Disabled);
    result.environmentCascade = RADIANCE_CASCADE_COUNT - 1u;
    result.worldBoundaryDistance = 0.0;
    result.flags = 0u;
    return result;
}

struct RCTraceResult {
    bool      hit;            // a non-hole surface was hit anywhere in [tNear, tFar]
    RCSegment nearSegment;    // exact near[near, mid] record
    RCSegment farSegment;     // exact far(mid, far] record
    RCSegment composed;       // rcComposeSegments(nearSegment, farSegment)
    float     distance;       // actual first non-hole surface t; tFar on no hit; 0 on invalid input
    uint      cascade;
    uint      probeIndex;     // 0 in the single-ray entry, populated by the store entry
    uint      rayIndex;       // 0 in the single-ray entry
    uint      flags;          // RC_TRACE_FLAG_* ray-level aggregate
    uint      nearEvents;     // RC_TRACE_EVENT_* scoped to the near segment
    uint      farEvents;      // RC_TRACE_EVENT_* scoped to the far segment
    bool      stored;         // both record addresses were written (not "ray resolved")
};

RCTraceResult emptyRCTraceResult() {
    RCTraceResult result;
    result.hit = false;
    result.nearSegment = rcUnresolvedSegment();
    result.farSegment = rcUnresolvedSegment();
    result.composed = rcUnresolvedSegment();
    result.distance = 0.0;
    result.cascade = RADIANCE_CASCADE_COUNT;
    result.probeIndex = 0u;
    result.rayIndex = 0u;
    result.flags = 0u;
    result.nearEvents = 0u;
    result.farEvents = 0u;
    result.stored = false;
    return result;
}

bool rcTraceOptionsValid(RCTraceOptions options) {
    if (!isFinite(options.rayBias) || !(options.rayBias > 0.0)) return false;
    if (!isFinite(options.nearDistance) || options.nearDistance < 0.0) return false;
    if (!isFinite(options.farDistance) || options.farDistance < 0.0) return false;
    if (!isFinite(options.maxRadiance) || !(options.maxRadiance > 0.0)) return false;
    if (!isFinite(options.worldBoundaryDistance) || options.worldBoundaryDistance < 0.0) return false;
    if (options.flags != 0u) return false;
    if (options.environmentPolicy == uint(RCEnvironmentPolicy.TerminalCascade)) {
        if (options.environmentCascade >= RADIANCE_CASCADE_COUNT) return false;
        // A terminal-cascade miss may still be inside a cave.  Environment
        // needs a positive boundary that the far window can reach; there is
        // no "trust the terminal cascade" shortcut.
        if (!(options.worldBoundaryDistance > 0.0)) return false;
    } else if (options.environmentPolicy != uint(RCEnvironmentPolicy.Disabled)) {
        return false;
    }
    return true;
}

// The trace is entered with engine-generated cascade rays, so the direction
// must already be unit length: t must stay in world units and `mid` must not
// depend on a rescaled direction.
bool rcTraceRayValid(RadianceRay rayValue) {
    if (!isFinite(rayValue.origin) || !isFinite(rayValue.direction)) return false;
    if (!isFinite(rayValue.nearDistance) || !isFinite(rayValue.farDistance)) return false;
    if (rayValue.nearDistance < 0.0 || rayValue.farDistance < 0.0) return false;
    float lengthSquared = dot(rayValue.direction, rayValue.direction);
    if (!(lengthSquared > 0.0) || abs(lengthSquared - 1.0) > 1.0e-3) return false;
    return true;
}

// Explicit near/far override: zero means "use the cascade ray's own range".
// The override is a parameter (never a global binding) so a host can trace a
// band for the typed resolve without a shader variant.
float rcTraceNear(RCTraceOptions options, RadianceRay rayValue) {
    return options.nearDistance > 0.0 ? options.nearDistance : max(rayValue.nearDistance, 0.0);
}

float rcTraceFar(RCTraceOptions options, RadianceRay rayValue) {
    return options.farDistance > 0.0 ? options.farDistance : max(rayValue.farDistance, rayValue.nearDistance);
}

// The only place that decides whether a miss may become environment radiance.
// `cascade` is caller-owned information: the walk itself cannot know which
// level of the cascade pyramid it is tracing.
bool rcEnvironmentAllowed(RCTraceOptions options, uint cascade, float tFar) {
    if (options.environmentPolicy != uint(RCEnvironmentPolicy.TerminalCascade)) return false;
    if (cascade != options.environmentCascade) return false;
    // A miss that stops short of the world boundary may still be under a roof.
    // Zero is not a trusted boundary; it is the absence of proof.
    if (!(options.worldBoundaryDistance > 0.0)) return false;
    if (tFar < options.worldBoundaryDistance) return false;
    return true;
}
