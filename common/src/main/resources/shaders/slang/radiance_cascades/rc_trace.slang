// Radiance Cascades probe-ray tracing for Bedray 2.0.
//
// One invocation traces one cascade probe ray and produces two typed
// RCSegment records.  Addressing (cascade layout, probe positions, Fibonacci
// directions, dispatch decoding) is owned by radiance_cascades.slang; the
// record and its front-to-back composition are owned by
// radiance_cascades/interval_transport.slang; record addressing and writes are
// owned by radiance_cascades/segment_store.slang.  This module only walks the
// ray, shades the first contributing surface, assigns per-segment status and
// hands both segments to the typed store.
//
// Interval model
// --------------
// The ray range [tNear, tFar] is split at mid = 0.5 * (tNear + tFar).  Segment
// L and T are segment-local, exactly as interval_transport.slang defines:
//
//   L  radiance emitted or reflected inside the segment, attenuated only by
//      filters that occur earlier *inside the same segment*
//   T  product of the segment's filter colours; T is not pre-applied to L
//
// The two records are never encoded as (Ln, Tn * Lf).  A consumer composes
// them with the shared function:
//
//   L_total = L_near + T_near * L_far
//   T_total = T_near * T_far
//
// The old float4 atlas and its confidence-weighted rcProbeIrradiance are not
// touched or called.  This module stores only through rcWriteRaySegments; the
// typed resolve (rcProbeIrradiance over RCSegment) lives in
// radiance_cascades/segment_store.slang.
//
// Status scopes
// -------------
// RCSegment.flags describes exactly that segment: RESOLVED, BLOCKED,
// BOUNDED_MISS, ENVIRONMENT, TRUNCATED, CLAMPED.  An event bit (HIT, FILTERED,
// NEE, DEPTH_LIMIT, SHADOW_TRUNCATED, CLAMPED, INVALID) is likewise reported
// per segment in nearEvents/farEvents.
//
// RCTraceResult.flags is a ray-level diagnostic aggregate.  RC_TRACE_FLAG_HIT,
// _BLOCKED, _BOUNDED_MISS, _NEE and the other aggregate bits say that at least
// one segment had that property; they must never be used to decide *which*
// segment.  Use nearSegment/farSegment, nearEvents/farEvents or the stored
// records for that.  In particular there is no ray-wide "near hit" or
// "far environment" bit.
//
// Segment outcomes
// ----------------
//   full traversal, no environment owner   far RESOLVED | BOUNDED_MISS, T_far
//                                          is the far filter product
//   full traversal, environment owner      far RESOLVED | ENVIRONMENT, L_far
//                                          includes T_far * environment; this
//                                          is the only place sky is added
//   opaque/zero-filter termination in far  far RESOLVED | BLOCKED, T_far = 0
//   opaque/zero-filter termination in near near RESOLVED | BLOCKED, T_near = 0;
//                                          far is left unresolved because it
//                                          was never observed and is hidden by
//                                          T_near = 0
//   step budget or shadow budget exhausted the affected segment is
//                                          TRUNCATED (no RESOLVED, confidence
//                                          0); a complete near segment stays
//                                          RESOLVED
//   non-finite/invalid sample              the affected segment is unresolved
//                                          (flags 0) and carries INVALID; no
//                                          value is turned into trusted black
//
// Only exactly zero transmittance is a terminal absorption.  There is no
// 1e-3 absorption threshold: a small nonzero T is still a valid filter and the
// back segment remains relevant, as interval_transport.slang requires.
//
// Store limitation
// ----------------
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
// rcWriteRaySegments also does not make the two writes atomic; readers need a
// pass barrier.
//
// Environment policy
// ------------------
// A bounded interval that misses must not add sky in every cascade.  The walk
// is given the cascade index; options.environmentPolicy = TerminalCascade
// permits environment radiance only for options.environmentCascade (default:
// the farthest cascade).  If options.worldBoundaryDistance > 0, the trace's
// tFar must also reach that distance.  Environment is added to the far segment
// only, after the far filter, and the ENVIRONMENT bit prevents any further
// back segment from being composed behind it.
//
// With the native cascade ranges (c4 far = 64 blocks) environment is counted
// only if the world boundary is at most 64 blocks away, or if the host raises
// the terminal cascade's far override to the boundary distance.  That is a
// host/resolve decision, not a guarantee of this module.
//
// Temporal history
// ----------------
// The previous typed buffer is read through the same canonical layout and
// historyCapacity.  The current probe's world position is mapped into the
// previous clipmap lattice, and the ray direction index is mapped through the
// current and previous rotations.  History is rejected when either revision
// differs, when the probe is off the previous lattice, or when either fresh or
// history segment is unresolved/truncated.
//
// rcProvisionalBlendRayHistory is deliberately named provisional.  Lerping L
// and T independently is only exact when the reprojected path has the same
// filter stack and terminal topology; the field layout cannot prove that.
// The helper also requires equal terminal classes (BLOCKED / BOUNDED_MISS /
// ENVIRONMENT), but it is a heuristic, not an unbiased temporal resolve.  The
// resolve owner must define filter weights, clamping, status transitions and
// cross-cascade history before this can be treated as a guarantee.  It is not
// called by the non-blended entries.
//
// Cross-cascade resolve
// ---------------------
// This module does not resolve across cascades.  rcComposeSegments is only
// valid for the near/far pair of one probe ray (same origin and direction).
// rcCascadeOwnershipBand/rcApplyCascadeOwnershipBand expose a distance-band
// *proposal* for the resolve owner: retrace each cascade over the band
// (far(c-1), far(c)] so the bands do not overlap, then reconstruct each band
// at the shading point and integrate each cascade's own ray set.  Probe
// lattices and ray sets differ across cascades, so records from different
// cascades cannot be zipped or composed by index.  No estimator, weight or
// environment handoff is implemented here; RC_TRACE_NOTES.md states the open
// questions.
//
// Cost contract: one geometric trace per surface event, bounded by
// RCT_MAX_SURFACE_STEPS; at most one next-event-estimation shadow walk per
// ray, on the first opaque/cutout surface.  No recursion, no allocation.

public module rc_trace;

import "radiance_cascades.slang";
import "radiance_cascades/interval_transport.slang";
import "radiance_cascades/radiance_cascades_store.slang";
import "radiance_cascades/segment_store.slang";
import "contracts/hit.slang";
import "contracts/light.slang";
import "contracts/material.slang";
import "contracts/math.slang";
import "light/sky.slang";
import "shading/bsdf.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
import "traversal/traversal.slang";

// Geometric step budget of one probe ray.  Cutout foliage stacks a handful of
// layers at most; this cap bounds the walk without making it a path tracer.
static const uint RCT_MAX_SURFACE_STEPS = 16u;

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
    float worldBoundaryDistance;  // >0: the miss window must reach it; 0 = trusted terminal cascade
    uint  flags;                  // reserved, must be 0 today
};

RCTraceOptions defaultRCTraceOptions() {
    RCTraceOptions result;
    result.maxDepth = 2u;
    result.rayBias = 1.0e-3;
    result.nearDistance = 0.0;
    result.farDistance = 0.0;
    result.maxRadiance = 1.0e6;
    result.environmentPolicy = uint(RCEnvironmentPolicy.TerminalCascade);
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

// ---- Input validation and record helpers -----------------------------------

bool rcTraceOptionsValid(RCTraceOptions options) {
    if (!isFinite(options.rayBias) || !(options.rayBias > 0.0)) return false;
    if (!isFinite(options.nearDistance) || options.nearDistance < 0.0) return false;
    if (!isFinite(options.farDistance) || options.farDistance < 0.0) return false;
    if (!isFinite(options.maxRadiance) || !(options.maxRadiance > 0.0)) return false;
    if (!isFinite(options.worldBoundaryDistance) || options.worldBoundaryDistance < 0.0) return false;
    if (options.flags != 0u) return false;
    if (options.environmentPolicy == uint(RCEnvironmentPolicy.TerminalCascade)) {
        if (options.environmentCascade >= RADIANCE_CASCADE_COUNT) return false;
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

RCSegment rcResolvedEmptySegment() {
    RCSegment segment;
    segment.radiance = float3(0.0);
    segment.confidence = 1.0;
    segment.transmittance = float3(1.0);
    segment.flags = RC_SEGMENT_RESOLVED;
    return segment;
}

RCSegment rcTruncatedSegment() {
    RCSegment segment = rcUnresolvedSegment();
    segment.flags = RC_SEGMENT_TRUNCATED;
    return segment;
}

// Finite, non-negative, in-range values only.  Invalid values stay unresolved:
// they are never clamped into a trusted zero and never stored as resolved.
RCSegment rcFinalizeSegment(float3 radianceValue, float3 transmittanceValue,
        uint segmentFlags, uint events, float maxRadiance,
        out bool valid, out bool clamped) {
    valid = false;
    clamped = false;
    if ((events & RC_TRACE_EVENT_INVALID) != 0u) return rcUnresolvedSegment();
    if (!isFinite(radianceValue) || !isFinite(transmittanceValue)) return rcUnresolvedSegment();
    if (any(radianceValue < float3(0.0))) return rcUnresolvedSegment();
    if (any(transmittanceValue < float3(0.0)) || any(transmittanceValue > float3(1.0))) {
        return rcUnresolvedSegment();
    }

    float3 radiance = radianceValue;
    float maximum = max(radiance.r, max(radiance.g, radiance.b));
    if (maximum > maxRadiance) {
        clamped = true;
        radiance = clampRadiance(radiance, maxRadiance);
    }

    RCSegment segment;
    segment.radiance = radiance;
    segment.confidence = 1.0;
    segment.transmittance = transmittanceValue;
    segment.flags = RC_SEGMENT_RESOLVED |
        (segmentFlags & (RC_SEGMENT_BLOCKED | RC_SEGMENT_BOUNDED_MISS |
                         RC_SEGMENT_ENVIRONMENT | RC_SEGMENT_CLAMPED));
    if (clamped) segment.flags |= RC_SEGMENT_CLAMPED;
    valid = true;
    return segment;
}

RCSegment rcFinalizeIntervalState(bool invalid, bool truncated, bool complete,
        float3 radiance, float3 transmittance, uint segmentFlags, uint events,
        float maxRadiance, out bool valid, out bool clamped, out bool rejected) {
    valid = false;
    clamped = false;
    rejected = false;
    if (invalid) return rcUnresolvedSegment();
    if (truncated) return rcTruncatedSegment();
    if (!complete) return rcUnresolvedSegment();
    RCSegment segment = rcFinalizeSegment(radiance, transmittance, segmentFlags,
        events, maxRadiance, valid, clamped);
    if (!valid) rejected = true;
    return segment;
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
    if (options.worldBoundaryDistance > 0.0 && tFar < options.worldBoundaryDistance) return false;
    return true;
}

bool rcAccumulateAttenuated(inout float3 radiance, float3 transmittance,
        float3 contribution) {
    float3 weighted = transmittance * contribution;
    if (!isFinite(weighted) || any(weighted < float3(0.0))) return false;
    radiance += weighted;
    return isFinite(radiance) && all(radiance >= float3(0.0));
}

bool rcApplyFilter(inout float3 transmittance, float3 filter) {
    float3 product = transmittance * filter;
    if (!isFinite(product) || any(product < float3(0.0)) ||
        any(product > float3(1.0))) return false;
    transmittance = product;
    return true;
}

// ---- Surface shading -------------------------------------------------------

struct RCShadedSurface {
    bool   valid;
    bool   clamped;
    bool   usedNee;
    bool   shadowTruncated;
    float3 radiance;
};

// Emission plus, exactly once per ray, one NEE sample through the
// material-filtered visibility walk.  An overflowed shadow walk is a
// truncation, not a zero-transmission proof: the emission is known but the
// direct term is not, so the caller must mark the segment TRUNCATED.  Invalid
// data (non-finite or negative radiance, non-finite/non-positive light PDF, a
// non-unit direction/filter value) invalidates the sample; it is never black.
RCShadedSurface rcShadeSurface<T : ISceneTracer, L : ILightSampler>(
        T traversal, L lights,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceSample surface, float3 view,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    RCShadedSurface result;
    result.valid = false;
    result.clamped = false;
    result.usedNee = false;
    result.shadowTruncated = false;
    result.radiance = float3(0.0);

    if (!isFinite(surface.hit.position)) return result;
    float3 radiance = surface.material.emission;
    if (!isFinite(radiance) || any(radiance < float3(0.0))) return result;

    float3 normal = surface.shadingNormal;
    if (!isFinite(normal) || dot(normal, normal) <= MATH_EPSILON) return result;
    normal = normalize(normal);
    if (dot(normal, view) < 0.0) normal = -normal;

    LightSample light = lights.sample(lightReceiver(surface.hit.position, normal, surface.material.emissionMask), seed);
    // ILightSampler has no separate failure code: valid == false is the
    // sampler's "no sample" result (no light or internal failure), so it skips
    // NEE by contract.  A purportedly valid sample must instead be usable: a
    // NaN/zero/negative sourcePdf or any other non-finite consumed field is
    // malformed and must not be treated as "no light", which would resolve an
    // emission-only record from bad data.
    if (light.valid) {
        float directionLengthSquared = dot(light.direction, light.direction);
        if (!isFinite(light.radiance) || !isFinite(light.direction) ||
            !isFinite(light.distance) || !isFinite(light.sourcePdf) ||
            any(light.radiance < float3(0.0)) || light.distance < 0.0 ||
            !(light.sourcePdf > 0.0) ||
            !(directionLengthSquared > 0.0) ||
            abs(directionLengthSquared - 1.0) > 1.0e-3) return result;

        float cosine = max(dot(normal, light.direction), 0.0);
        if (cosine > 0.0) {
            float3 bsdf = bsdfEvaluate(surface.material, view, normal, light.direction);
            if (!isFinite(bsdf) || any(bsdf < float3(0.0))) return result;
            if (bsdf.r + bsdf.g + bsdf.b > 0.0) {
                SurfaceVisibility shadow = traceShadowVisibility(traversal,
                    atlas, blockMaterials, surface.hit.position, normal,
                    light.direction, light.distance, options.rayBias,
                    materialOptions);
                if ((shadow.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) != 0u) {
                    result.shadowTruncated = true;
                } else {
                    float3 transmittance = float3(0.0);
                    if (shadow.result.visible) {
                        transmittance = shadow.result.transmittance;
                        if (!isFinite(transmittance) ||
                            any(transmittance < float3(0.0)) ||
                            any(transmittance > float3(1.0))) return result;
                    }
                    if (transmittance.r + transmittance.g + transmittance.b > 0.0) {
                        float3 contribution = bsdf * light.radiance * transmittance /
                            max(light.sourcePdf, 1.0e-6);
                        if (!isFinite(contribution) ||
                            any(contribution < float3(0.0))) return result;
                        radiance += contribution;
                        result.usedNee = true;
                    }
                }
            }
        }
    }

    if (!isFinite(radiance) || any(radiance < float3(0.0))) return result;
    float maximum = max(radiance.r, max(radiance.g, radiance.b));
    if (maximum > options.maxRadiance) {
        result.clamped = true;
        radiance = clampRadiance(radiance, options.maxRadiance);
    }
    result.radiance = radiance;
    result.valid = true;
    return result;
}

// ---- The walk --------------------------------------------------------------

// The ray origin is kept fixed and every event advances tMin, so hit
// distances stay in the original ray parameterization and the mid split never
// drifts.  One opaque/cutout surface ends the walk; translucent surfaces are
// filters whose emissions are preserved.  Every exit path leaves both segment
// records in a state that either composes or is explicitly untrusted.
void rcWalkRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceRay rayValue, uint cascade, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed,
        out RCSegment nearSegment, out RCSegment farSegment,
        out float firstHitDistance, out bool hit,
        out uint nearEvents, out uint farEvents, out uint rayFlags) {
    nearSegment = rcUnresolvedSegment();
    farSegment = rcUnresolvedSegment();
    firstHitDistance = 0.0;
    hit = false;
    nearEvents = 0u;
    farEvents = 0u;
    rayFlags = 0u;

    if (cascade >= RADIANCE_CASCADE_COUNT ||
        !rcTraceOptionsValid(options) ||
        !rcTraceRayValid(rayValue)) {
        rayFlags |= RC_TRACE_FLAG_INVALID_INPUT;
        return;
    }

    float tNear = rcTraceNear(options, rayValue);
    float tFar = rcTraceFar(options, rayValue);
    if (!isFinite(tNear) || !isFinite(tFar) || tNear < 0.0) {
        rayFlags |= RC_TRACE_FLAG_INVALID_INPUT;
        return;
    }
    firstHitDistance = tFar;   // documented no-hit value

    if (!(tFar > tNear)) {
        // Empty window: no geometry and no direction segment from which the
        // environment could be reached.  A resolved identity record (L = 0,
        // T = 1) composes correctly; it is not a black fallback.
        nearSegment = rcResolvedEmptySegment();
        farSegment = rcResolvedEmptySegment();
        rayFlags |= RC_TRACE_FLAG_EMPTY_WINDOW;
        return;
    }

    // Overflow-safe midpoint: tNear >= 0 and tFar is finite.
    float mid = tNear + 0.5 * (tFar - tNear);

    float3 radianceNear = float3(0.0);
    float3 radianceFar = float3(0.0);
    float3 transmittanceNear = float3(1.0);
    float3 transmittanceFar = float3(1.0);
    uint nearFlags = 0u;
    uint farFlags = 0u;
    bool nearComplete = false;
    bool nearInvalid = false;
    bool farInvalid = false;
    bool nearTruncated = false;
    bool farTruncated = false;
    uint emissionEvents = 0u;
    bool finished = false;

    Ray ray = makeRay(rayValue.origin, rayValue.direction, tNear, tFar);

    for (uint step = 0u; step < RCT_MAX_SURFACE_STEPS; ++step) {
        // Do not rely on the backend to reject an empty [tMin, tMax] window
        // after a hole at the far boundary.
        SurfaceHit hitValue = missHit(ray.tMax);
        if (ray.tMax > ray.tMin) hitValue = traversal.traceClosest(ray);

        if (!hitValue.hit) {
            // The whole remaining window was traversed.  Both segments are
            // complete; environment is optional and belongs to its owner only.
            nearComplete = true;
            if (rcEnvironmentAllowed(options, cascade, tFar)) {
                float3 environmentRadiance = environment.radiance(rayValue.direction);
                if (!isFinite(environmentRadiance) ||
                    any(environmentRadiance < float3(0.0))) {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    radianceFar += transmittanceFar * environmentRadiance;
                    if (!isFinite(radianceFar) ||
                        any(radianceFar < float3(0.0))) {
                        farInvalid = true;
                        farEvents |= RC_TRACE_EVENT_INVALID;
                    } else {
                        farFlags |= RC_SEGMENT_ENVIRONMENT;
                    }
                }
            } else {
                farFlags |= RC_SEGMENT_BOUNDED_MISS;
            }
            finished = true;
            break;
        }

        if (!isFinite(hitValue.t) ||
            hitValue.t < ray.tMin || hitValue.t > ray.tMax) {
            // A backend contract violation.  The event cannot be attributed
            // to a segment reliably; the earliest incomplete segment is
            // invalidated, and a complete near segment stays valid.
            if (nearComplete) {
                farInvalid = true;
                farEvents |= RC_TRACE_EVENT_INVALID;
            } else {
                nearInvalid = true;
                nearEvents |= RC_TRACE_EVENT_INVALID;
            }
            finished = true;
            break;
        }

        bool inNear = hitValue.t <= mid;
        if (!inNear) nearComplete = true;

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hitValue,
            materialOptions);
        if (!surface.valid) {
            // Unlike the visibility walk, the typed transport record must not
            // claim a resolved zero-throughput result for a material it could
            // not decode.  The geometric hit is reported; the segment is not.
            if (!hit) {
                hit = true;
                firstHitDistance = hitValue.t;
                rayFlags |= RC_TRACE_FLAG_HIT;
            }
            if (inNear) {
                nearInvalid = true;
                nearEvents |= RC_TRACE_EVENT_HIT | RC_TRACE_EVENT_INVALID;
            } else {
                farInvalid = true;
                farEvents |= RC_TRACE_EVENT_HIT | RC_TRACE_EVENT_INVALID;
            }
            finished = true;
            break;
        }

        if (surfaceIsHole(surface, materialOptions.alphaCutoff)) {
            // A cutout hole is not a surface and not a hit.  Keep the original
            // origin and advance tMin; firstHitDistance remains the real t of
            // the first accepted surface.
            float nextMin = hitValue.t + options.rayBias;
            if (!isFinite(nextMin) || !(nextMin > ray.tMin)) {
                if (inNear) {
                    nearInvalid = true;
                    nearEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                }
                finished = true;
                break;
            }
            ray.tMin = nextMin;
            continue;
        }

        if (!hit) {
            hit = true;
            firstHitDistance = hitValue.t;
            rayFlags |= RC_TRACE_FLAG_HIT;
        }

        if (materialIsTranslucent(surface.material)) {
            // A filter, not a terminating surface.  The authored emission is
            // preserved in this segment with this segment's current T; the
            // filter then affects only this segment's later events.
            uint filterEvent = RC_TRACE_EVENT_HIT | RC_TRACE_EVENT_FILTERED;
            if (inNear) nearEvents |= filterEvent; else farEvents |= filterEvent;

            float3 emission = surface.material.emission;
            if (!isFinite(emission) || any(emission < float3(0.0))) {
                if (inNear) {
                    nearInvalid = true;
                    nearEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                }
                finished = true;
                break;
            }

            if (any(emission > float3(0.0))) {
                if (emissionEvents < options.maxDepth) {
                    emissionEvents++;
                    bool accumulated = false;
                    if (inNear) {
                        accumulated = rcAccumulateAttenuated(radianceNear,
                            transmittanceNear, emission);
                    } else {
                        accumulated = rcAccumulateAttenuated(radianceFar,
                            transmittanceFar, emission);
                    }
                    if (!accumulated) {
                        if (inNear) {
                            nearInvalid = true;
                            nearEvents |= RC_TRACE_EVENT_INVALID;
                        } else {
                            farInvalid = true;
                            farEvents |= RC_TRACE_EVENT_INVALID;
                        }
                        finished = true;
                        break;
                    }
                } else {
                    if (inNear) nearEvents |= RC_TRACE_EVENT_DEPTH_LIMIT;
                    else farEvents |= RC_TRACE_EVENT_DEPTH_LIMIT;
                }
            }

            float3 filter = surfaceTransmittance(surface, rayValue.direction);
            if (!isFinite(filter) || any(filter < float3(0.0)) ||
                any(filter > float3(1.0))) {
                if (inNear) {
                    nearInvalid = true;
                    nearEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                }
                finished = true;
                break;
            }

            bool applied = false;
            if (inNear) {
                applied = rcApplyFilter(transmittanceNear, filter);
            } else {
                applied = rcApplyFilter(transmittanceFar, filter);
            }
            if (!applied) {
                if (inNear) {
                    nearInvalid = true;
                    nearEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                }
                finished = true;
                break;
            }

            float3 segmentTransmittance = inNear ? transmittanceNear : transmittanceFar;
            float maximum = max(segmentTransmittance.r,
                max(segmentTransmittance.g, segmentTransmittance.b));
            if (maximum <= 0.0) {
                // Exactly zero throughput (including underflow) is the only
                // resolved absorption.  The current segment keeps its earlier
                // emissions and reports BLOCKED; nothing behind it can matter.
                if (inNear) {
                    nearFlags |= RC_SEGMENT_BLOCKED;
                    nearComplete = true;
                } else {
                    farFlags |= RC_SEGMENT_BLOCKED;
                }
                finished = true;
                break;
            }

            float nextMin = hitValue.t + options.rayBias;
            if (!isFinite(nextMin) || !(nextMin > ray.tMin)) {
                if (inNear) {
                    nearInvalid = true;
                    nearEvents |= RC_TRACE_EVENT_INVALID;
                } else {
                    farInvalid = true;
                    farEvents |= RC_TRACE_EVENT_INVALID;
                }
                finished = true;
                break;
            }
            ray.tMin = nextMin;
            continue;
        }

        // Contributing opaque / cutout-above-threshold surface.  Add to the
        // segment that owns hitValue.t, then terminate that segment.
        RCShadedSurface shaded = rcShadeSurface(traversal, lights, atlas,
            blockMaterials, surface, -rayValue.direction, materialOptions,
            options, seed);

        uint surfaceEvent = RC_TRACE_EVENT_HIT;
        if (!shaded.valid) {
            if (inNear) {
                nearInvalid = true;
                nearEvents |= surfaceEvent | RC_TRACE_EVENT_INVALID;
            } else {
                farInvalid = true;
                farEvents |= surfaceEvent | RC_TRACE_EVENT_INVALID;
            }
            finished = true;
            break;
        }

        if (shaded.usedNee) surfaceEvent |= RC_TRACE_EVENT_NEE;
        if (shaded.clamped) surfaceEvent |= RC_TRACE_EVENT_CLAMPED;
        if (shaded.shadowTruncated) {
            surfaceEvent |= RC_TRACE_EVENT_SHADOW_TRUNCATED;
        }
        if (inNear) nearEvents |= surfaceEvent; else farEvents |= surfaceEvent;

        if (shaded.shadowTruncated) {
            // The opaque surface ends the ray, but its direct term is not
            // known.  Do not store a resolved value for this segment.
            if (inNear) nearTruncated = true; else farTruncated = true;
            finished = true;
            break;
        }

        if (inNear) {
            if (!rcAccumulateAttenuated(radianceNear, transmittanceNear,
                    shaded.radiance)) {
                nearInvalid = true;
                nearEvents |= RC_TRACE_EVENT_INVALID;
                finished = true;
                break;
            }
            transmittanceNear = float3(0.0);
            nearFlags |= RC_SEGMENT_BLOCKED;
            nearComplete = true;
        } else {
            if (!rcAccumulateAttenuated(radianceFar, transmittanceFar,
                    shaded.radiance)) {
                farInvalid = true;
                farEvents |= RC_TRACE_EVENT_INVALID;
                finished = true;
                break;
            }
            transmittanceFar = float3(0.0);
            farFlags |= RC_SEGMENT_BLOCKED;
        }
        finished = true;
        break;
    }

    if (!finished) {
        // The geometric budget ran out before a hit/miss/termination.  The far
        // segment is never complete here; the near segment is complete only if
        // an event beyond the midpoint was processed.
        rayFlags |= RC_TRACE_FLAG_STEP_LIMIT;
        farTruncated = true;
        if (!nearComplete) nearTruncated = true;
    }

    bool nearValid = false;
    bool nearClamped = false;
    bool nearRejected = false;
    nearSegment = rcFinalizeIntervalState(nearInvalid, nearTruncated, nearComplete,
        radianceNear, transmittanceNear, nearFlags, nearEvents,
        options.maxRadiance, nearValid, nearClamped, nearRejected);
    if (nearClamped) nearEvents |= RC_TRACE_EVENT_CLAMPED;
    if (nearRejected) {
        nearEvents |= RC_TRACE_EVENT_INVALID;
    }

    bool farValid = false;
    bool farClamped = false;
    bool farRejected = false;
    bool farTerminal = (farFlags & (RC_SEGMENT_BLOCKED |
        RC_SEGMENT_BOUNDED_MISS | RC_SEGMENT_ENVIRONMENT)) != 0u;
    farSegment = rcFinalizeIntervalState(farInvalid, farTruncated, farTerminal,
        radianceFar, transmittanceFar, farFlags, farEvents,
        options.maxRadiance, farValid, farClamped, farRejected);
    if (farClamped) farEvents |= RC_TRACE_EVENT_CLAMPED;
    if (farRejected) {
        farEvents |= RC_TRACE_EVENT_INVALID;
    }

    // Derive the ray-level aggregate from the records that actually survived
    // finalization.  It is diagnostic only; the exact scope is per segment.
    if (((nearSegment.flags | farSegment.flags) & RC_SEGMENT_BLOCKED) != 0u) {
        rayFlags |= RC_TRACE_FLAG_BLOCKED;
    }
    if ((farSegment.flags & RC_SEGMENT_BOUNDED_MISS) != 0u) {
        rayFlags |= RC_TRACE_FLAG_BOUNDED_MISS;
    }
    if ((farSegment.flags & RC_SEGMENT_ENVIRONMENT) != 0u) {
        rayFlags |= RC_TRACE_FLAG_ENVIRONMENT;
    }
    if (((nearSegment.flags | farSegment.flags) & RC_SEGMENT_CLAMPED) != 0u ||
        ((nearEvents | farEvents) & RC_TRACE_EVENT_CLAMPED) != 0u) {
        rayFlags |= RC_TRACE_FLAG_CLAMPED;
    }
    if (((nearEvents | farEvents) & RC_TRACE_EVENT_FILTERED) != 0u) {
        rayFlags |= RC_TRACE_FLAG_FILTERED;
    }
    if (((nearEvents | farEvents) & RC_TRACE_EVENT_NEE) != 0u) {
        rayFlags |= RC_TRACE_FLAG_NEE;
    }
    if (((nearEvents | farEvents) & RC_TRACE_EVENT_DEPTH_LIMIT) != 0u) {
        rayFlags |= RC_TRACE_FLAG_DEPTH_LIMIT;
    }
    if (((nearEvents | farEvents) & RC_TRACE_EVENT_SHADOW_TRUNCATED) != 0u) {
        rayFlags |= RC_TRACE_FLAG_SHADOW_TRUNCATED;
    }
    if (((nearEvents | farEvents) & RC_TRACE_EVENT_INVALID) != 0u) {
        rayFlags |= RC_TRACE_FLAG_INVALID_SAMPLE;
    }

    if (!hit) firstHitDistance = tFar;
}

// ---- Single-ray and store entries ------------------------------------------

RCTraceResult rcBuildTraceResult(RadianceRay rayValue, uint cascade,
        RCTraceOptions options, RCSegment nearSegment, RCSegment farSegment,
        float firstHitDistance, bool hit, uint nearEvents, uint farEvents,
        uint rayFlags) {
    RCTraceResult result = emptyRCTraceResult();
    result.hit = hit;
    result.nearSegment = nearSegment;
    result.farSegment = farSegment;
    result.composed = rcComposeSegments(nearSegment, farSegment);
    result.cascade = cascade;
    result.flags = rayFlags;
    result.nearEvents = nearEvents;
    result.farEvents = farEvents;
    if ((rayFlags & RC_TRACE_FLAG_INVALID_INPUT) != 0u) {
        result.distance = 0.0;
    } else {
        result.distance = hit ? firstHitDistance : rcTraceFar(options, rayValue);
    }
    return result;
}

// Traces the full [tNear, tFar] range and returns both segment records plus
// their shared composition.  Both resources are required: there is no neutral
// sampler that turns a missing light or environment binding into black.  A
// host that composites environment in a separate pass selects
// RCEnvironmentPolicy.Disabled; the environment sampler is then not consulted.
// A host without a light sampler cannot call this entry.  `cascade` identifies
// the level for the environment policy.
RCTraceResult traceCascadeRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceRay rayValue, uint cascade, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed) {
    RCSegment nearSegment;
    RCSegment farSegment;
    float firstHitDistance;
    bool hit;
    uint nearEvents;
    uint farEvents;
    uint rayFlags;
    rcWalkRay(traversal, lights, environment, atlas, blockMaterials, rayValue,
        cascade, materialOptions, options, seed, nearSegment, farSegment,
        firstHitDistance, hit, nearEvents, farEvents, rayFlags);
    return rcBuildTraceResult(rayValue, cascade, options, nearSegment,
        farSegment, firstHitDistance, hit, nearEvents, farEvents, rayFlags);
}

// Traces one ray and writes both whole records through the typed store.  A
// fresh trace writes (not accumulates): cross-frame accumulation belongs to
// the resolve owner.  On a valid address pair, a segment that fails
// rcSegmentValid is normalized to unresolved with RC_SEGMENT_TRUNCATED retained
// when present; `stored` reports address success, not ray validity.  If the
// pair cannot be addressed (non-canonical layout, capacity too small, invalid
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

// ---- Temporal history mapping ----------------------------------------------

// Caller-owned reprojection inputs.  `segments`/`historyCapacity` describe the
// completed previous-frame typed atlas; the current and history atlases must
// use the same canonical layout and the same trace options (distance band,
// environment policy, budgets), or the two records are not comparable.  Only
// the caller can decide that history is still valid after disocclusion.
struct RCTemporalHistory {
    StructuredBuffer<RCSegment> segments;
    uint   historyCapacity;           // RCSegment element count actually bound
    float3 previousCameraOrigin;      // origin that produced the previous clipmap
    uint   previousRotation;
    uint   previousSceneRevision;
    uint   currentSceneRevision;
    float  weight;                    // history weight in (0, 1]
    bool   valid;                     // caller reprojection/disocclusion result
};

// Inverse of cascadeProbePosition for a previous-frame clipmap.  Returns false
// when the current probe's world position falls outside the previous grid or
// only approximately matches its lattice.  The array index is expected to
// change after a scroll; that is why it is derived from world position.
bool rcHistoryProbeIndex(RadianceCascadeDesc desc, float3 probePosition,
        float3 previousCameraOrigin, out uint historyProbeIndex) {
    historyProbeIndex = RCT_INVALID_PROBE;
    if (desc.probeAxis == 0u || !(desc.spacing > 0.0)) return false;
    if (!isFinite(probePosition) || !isFinite(previousCameraOrigin)) return false;

    float axis = float(desc.probeAxis);
    float3 previousBase = floor(previousCameraOrigin / desc.spacing) * desc.spacing;
    float3 coordinate = (probePosition - previousBase) / desc.spacing +
        float3(0.5 * axis - 0.5);
    float3 rounded = floor(coordinate + float3(0.5));

    if (any(rounded < float3(0.0)) || any(rounded >= float3(axis))) return false;
    if (any(abs(coordinate - rounded) > float3(1.0e-3))) return false;

    uint index = probeLinearIndex(uint3(rounded), desc.probeAxis);
    if (index >= probeCount(desc)) return false;
    historyProbeIndex = index;
    return true;
}

bool rcHistoryUsable(RCTemporalHistory history, RadianceCascadeDesc desc,
        float3 probePosition, out uint historyProbeIndex) {
    historyProbeIndex = RCT_INVALID_PROBE;
    if (!history.valid) return false;
    if (!isFinite(history.weight) || history.weight <= 0.0 || history.weight > 1.0) {
        return false;
    }
    if (history.previousSceneRevision != history.currentSceneRevision) return false;
    return rcHistoryProbeIndex(desc, probePosition, history.previousCameraOrigin,
        historyProbeIndex);
}

// The store/engine convention is
//   direction(rayIndex, rotation) = base((rayIndex + rotation) % count).
// Undo the previous rotation to find the record that held the same world
// direction in the history atlas.
uint rcHistoryRayIndex(uint rayIndex, uint currentRotation,
        uint historyRotation, uint rayCount) {
    if (rayCount == 0u) return rayIndex;
    uint directionIndex = (rayIndex + currentRotation % rayCount) % rayCount;
    return (directionIndex + rayCount - historyRotation % rayCount) % rayCount;
}

bool rcReadMappedHistoryRay(RCTemporalHistory history,
        RadianceCascadeLayout layout, uint cascade, uint rayIndex,
        float3 probePosition, uint currentRotation,
        out RCSegment nearSegment, out RCSegment farSegment) {
    nearSegment = rcUnresolvedSegment();
    farSegment = rcUnresolvedSegment();
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    uint historyProbeIndex;
    if (!rcHistoryUsable(history, desc, probePosition, historyProbeIndex)) {
        return false;
    }
    uint historyRayIndex = rcHistoryRayIndex(rayIndex, currentRotation,
        history.previousRotation, desc.rayCount);
    nearSegment = rcReadSegment(history.segments, layout,
        history.historyCapacity, cascade, historyProbeIndex, historyRayIndex,
        RCT_INTERVAL_NEAR);
    farSegment = rcReadSegment(history.segments, layout,
        history.historyCapacity, cascade, historyProbeIndex, historyRayIndex,
        RCT_INTERVAL_FAR);
    return true;
}

// BLOCKED / BOUNDED_MISS / ENVIRONMENT describe the terminal topology of a
// segment.  Two valid segments may only be lerped when their classes match:
// blending a sky sample into an occluded sample is not a temporal average.
uint rcSegmentTerminalClass(RCSegment segment) {
    return segment.flags & (RC_SEGMENT_BLOCKED | RC_SEGMENT_BOUNDED_MISS |
        RC_SEGMENT_ENVIRONMENT);
}

RCSegment rcLerpSegment(RCSegment fresh, RCSegment history, float alpha) {
    RCSegment result;
    result.radiance = lerp(history.radiance, fresh.radiance, alpha);
    result.confidence = 1.0;   // both inputs are valid; confidence is a status
    result.transmittance = lerp(history.transmittance, fresh.transmittance, alpha);
    result.flags = fresh.flags | (history.flags & RC_SEGMENT_CLAMPED);
    return result;
}

// Provisional, documented-as-heuristic temporal blend.  Both segments must be
// valid and their terminal classes must match; an opaque near segment is
// blended without requiring a far record.  The lerp is performed on L and T
// independently, which ignores filter-stack and geometric path differences;
// that bias is not removed by these checks.  Returns false (and leaves the
// caller's fresh records untouched) when any precondition fails.
bool rcProvisionalBlendRaySegments(RCSegment freshNear, RCSegment freshFar,
        RCSegment historyNear, RCSegment historyFar, float historyWeight,
        out RCSegment blendedNear, out RCSegment blendedFar) {
    blendedNear = freshNear;
    blendedFar = freshFar;
    if (!isFinite(historyWeight) || historyWeight <= 0.0 || historyWeight > 1.0) {
        return false;
    }
    if (!rcSegmentValid(freshNear) || !rcSegmentValid(historyNear)) return false;
    if (rcSegmentTerminalClass(freshNear) != rcSegmentTerminalClass(historyNear)) {
        return false;
    }

    bool freshNearOpaque = all(freshNear.transmittance == float3(0.0));
    bool historyNearOpaque = all(historyNear.transmittance == float3(0.0));
    if (freshNearOpaque != historyNearOpaque) return false;

    float alpha = 1.0 - historyWeight;
    if (freshNearOpaque) {
        RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
        if (!rcSegmentValid(mixedNear)) return false;
        blendedNear = mixedNear;
        blendedFar = rcUnresolvedSegment();
        return true;
    }

    if (!rcSegmentValid(freshFar) || !rcSegmentValid(historyFar)) return false;
    if (rcSegmentTerminalClass(freshFar) != rcSegmentTerminalClass(historyFar)) {
        return false;
    }

    RCSegment mixedNear = rcLerpSegment(freshNear, historyNear, alpha);
    RCSegment mixedFar = rcLerpSegment(freshFar, historyFar, alpha);
    if (!rcSegmentValid(mixedNear) || !rcSegmentValid(mixedFar)) return false;
    blendedNear = mixedNear;
    blendedFar = mixedFar;
    return true;
}

// Reads the mapped history and rewrites both current records only when the
// provisional blend accepts them.  `current` and `history.segments` must be
// distinct buffers; the caller must have completed the previous frame's writes
// and supplied the GPU memory dependency.  This is not an unbiased temporal
// resolve and does not define clipping, disocclusion beyond `history.valid`,
// or cross-cascade history.
bool rcProvisionalBlendRayHistory(RWStructuredBuffer<RCSegment> current,
        RCTemporalHistory history, RadianceCascadeLayout layout, uint capacity,
        uint cascade, uint probeIndex, uint rayIndex, float3 probePosition,
        uint currentRotation) {
    if (cascade >= RADIANCE_CASCADE_COUNT || !history.valid) return false;

    RCSegment freshNear = rcReadSegment(current, layout, capacity, cascade,
        probeIndex, rayIndex, RCT_INTERVAL_NEAR);
    RCSegment freshFar = rcReadSegment(current, layout, capacity, cascade,
        probeIndex, rayIndex, RCT_INTERVAL_FAR);
    if (!rcSegmentValid(freshNear)) return false;

    RCSegment historyNear;
    RCSegment historyFar;
    if (!rcReadMappedHistoryRay(history, layout, cascade, rayIndex,
            probePosition, currentRotation, historyNear, historyFar)) {
        return false;
    }

    RCSegment blendedNear;
    RCSegment blendedFar;
    if (!rcProvisionalBlendRaySegments(freshNear, freshFar, historyNear,
            historyFar, history.weight, blendedNear, blendedFar)) {
        return false;
    }
    return rcWriteRaySegments(current, layout, capacity, cascade, probeIndex,
        rayIndex, blendedNear, blendedFar);
}

// ---- Distance-band ownership proposal --------------------------------------
//
// Not used by the default entries and not a cross-cascade resolve.  It gives
// the resolve owner one concrete, non-overlapping distance partition of the
// native ranges: retrace cascade c over (far(c-1), far(c)] so its near/far
// split lies wholly inside that band.  Band c0 starts at the native near plane
// (0.5); the first half block is not covered by any band.  The terminal band
// ends at the native far distance (64); if the world boundary is farther, the
// host must raise that cascade's far override to the boundary distance for the
// environment policy to admit sky.
//
// Two caveats the helper cannot encode: trace windows are closed at both ends,
// so adjacent bands both contain a surface exactly on their shared boundary
// and the resolve must assign that boundary explicitly; and band distances are
// measured from each probe origin, not from the shading point, so the band
// label is not directly a shading-point distance.
struct RCCascadeOwnershipBand {
    float nearDistance;
    float farDistance;
    float splitDistance;
};

bool rcCascadeOwnershipBand(uint cascade, out RCCascadeOwnershipBand band) {
    band.nearDistance = 0.0;
    band.farDistance = 0.0;
    band.splitDistance = 0.0;
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    float nearValue = cascade == 0u
        ? max(desc.nearDistance, 0.0)
        : radianceCascade(cascade - 1u).farDistance;
    if (!(desc.farDistance > nearValue)) return false;

    band.nearDistance = nearValue;
    band.farDistance = desc.farDistance;
    band.splitDistance = nearValue + 0.5 * (desc.farDistance - nearValue);
    return true;
}

RCTraceOptions rcApplyCascadeOwnershipBand(RCTraceOptions options, uint cascade) {
    RCCascadeOwnershipBand band;
    if (rcCascadeOwnershipBand(cascade, band)) {
        options.nearDistance = band.nearDistance;
        options.farDistance = band.farDistance;
    }
    return options;
}

// ---- Probe and dispatch entries --------------------------------------------

// Fresh probe trace, no temporal reuse.  Returns the number of rays whose
// store address pair was not written (non-canonical layout, insufficient
// capacity).  A zero count does not mean every segment resolved; read the
// typed records or use the single-ray entry for status.
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

// Probe trace with the provisional typed history blend.  The history mapping
// and status checks are real; the lerp is explicitly a heuristic and is not a
// guarantee of the resolve owner.
uint traceCascadeProbeBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint cascade, uint probeIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed) {
    if (cascade >= RADIANCE_CASCADE_COUNT || cascade >= layout.cascadeCount) {
        return 0u;
    }
    RadianceCascadeDesc desc = radianceCascade(cascade);
    if (probeIndex >= probeCount(desc)) return 0u;

    float3 probePosition = cascadeProbePosition(desc, probeIndex, cameraOrigin);
    uint storeFailures = 0u;
    for (uint rayIndex = 0u; rayIndex < desc.rayCount; ++rayIndex) {
        RCTraceResult result = rcTraceAndStoreRay(traversal, lights, environment,
            atlas, blockMaterials, layout, segments, capacity, cascade,
            probeIndex, rayIndex, cameraOrigin, frameRotation, materialOptions,
            options, seed);
        if (!result.stored) {
            storeFailures++;
            continue;
        }
        rcProvisionalBlendRayHistory(segments, history, layout, capacity,
            cascade, probeIndex, rayIndex, probePosition, frameRotation);
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

// Dispatch trace with the provisional typed history blend.  The caller must
// supply the previous-frame camera origin, rotation and scene revision; the
// same array index is not a stable world position after clipmap scrolling.
uint traceCascadeDispatchBlended<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, RCTemporalHistory history, uint globalIndex,
        float3 cameraOrigin, uint frameRotation,
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
    if (!result.stored) return 1u;

    float3 probePosition = cascadeProbePosition(desc, address.probeIndex,
        cameraOrigin);
    rcProvisionalBlendRayHistory(segments, history, layout, capacity,
        address.cascade, address.probeIndex, address.rayIndex, probePosition,
        frameRotation);
    return 0u;
}
