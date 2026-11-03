// Typed Radiance Cascades trace: the interval walk.
//
// One invocation walks one cascade probe ray over [tNear, tFar] and produces
// two segment-local RCSegment records.  Addressing belongs to
// radiance_cascades.slang, record semantics to interval_transport.slang, and
// store addresses/writes to segment_store.slang.  This module owns only the
// walk, its segment finalization and the single-ray result.
//
// Interval model
// --------------
// The ray range is split at mid = 0.5 * (tNear + tFar).  Segment L and T are
// segment-local, exactly as interval_transport.slang defines:
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
// Status scopes and environment policy are defined in rc_trace_options.slang.
// This module decides which segment owns an event; it never invents a
// ray-wide status.
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
// Only exactly zero transmittance is a terminal absorption.  There is no 1e-3
// absorption threshold: a small nonzero T is still a valid filter and the back
// segment remains relevant, as interval_transport.slang requires.
//
// Cost contract: one geometric trace per surface event, bounded by
// RCT_MAX_SURFACE_STEPS; at most one next-event-estimation shadow walk per
// ray, on the first opaque/cutout surface.  No recursion, no allocation.

public module rc_trace_walk;

__exported import "radiance_cascades.slang";
__exported import "radiance_cascades/interval_transport.slang";
__exported import "radiance_cascades/rc_trace_options.slang";
__exported import "radiance_cascades/rc_trace_shade.slang";
import "contracts/hit.slang";
__exported import "contracts/light.slang";
import "contracts/material.slang";
import "contracts/math.slang";
__exported import "light/sky.slang";
__exported import "surface/surface.slang";
__exported import "traversal/traversal.slang";

// Geometric step budget of one probe ray.  Cutout foliage stacks a handful of
// layers at most; this cap bounds the walk without making it a path tracer.
static const uint RCT_MAX_SURFACE_STEPS = 16u;

// ---- Record finalization ----------------------------------------------------

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

// ---- The walk ---------------------------------------------------------------

// The ray origin is kept fixed and every event advances tMin, so hit distances
// stay in the original ray parameterization and the mid split never drifts.
// One opaque/cutout surface ends the walk; translucent surfaces are filters
// whose emissions are preserved.  Every exit path leaves both segment records
// in a state that either composes or is explicitly untrusted.
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
            materialOptions, rayValue.direction);
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

// ---- Single-ray entry -------------------------------------------------------

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
// sampler that turns a missing light or environment binding into black.  The
// default options select RCEnvironmentPolicy.Disabled, so the environment
// sampler is not consulted until the host opts in with TerminalCascade and a
// positive world boundary.  A host without a light sampler cannot call this
// entry.  `cascade` identifies the level for the environment policy.
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
