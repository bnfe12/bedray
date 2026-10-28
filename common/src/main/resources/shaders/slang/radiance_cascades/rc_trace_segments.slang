// OBSOLETE EXPERIMENT, not an integration candidate. Use rc_trace.slang as
// the current trace source. This earlier alternative loses far-segment emission
// on a miss and adds environment in every cascade; it is retained only as WIP
// reference and must not be called by a render pass.
//
// Segment-record Radiance Cascades probe-ray tracing for Bedray 2.0.
//
// This is the trace owner's answer to the typed segment atlas
// (radiance_cascades/segment_store.slang + interval_transport.slang): the same
// walk as rc_trace.slang, but the two intervals are produced and stored as
// RCSegment records through rcWriteRaySegments, with segment-local radiance
// and transmittance that rcComposeSegments can consume without
// reinterpretation.
//
// Record semantics agreed with interval_transport (the storage owner's doc
// asked for exactly this agreement):
//
//   * L and T are segment-local.  L is the emitted/reflected radiance of the
//     surfaces inside the segment, weighted by the filters crossed before
//     them inside the same segment.  T is the light that arrives through the
//     segment's far boundary: the product of the translucent panes crossed
//     inside the segment, 1 when the segment is empty or ends on an opaque
//     surface.
//   * A contributing opaque hit ends the walk: its own segment is RESOLVED
//     with T = 0, and the opposite segment resolves as an empty bounded miss
//     (near) or stores as an unresolved record (far behind an opaque near
//     hit).  The compose rule "exactly opaque foreground transport can be
//     resolved without a back sample" makes both pairs resolvable without
//     fabricating black back records.
//   * A full-range miss puts the environment into the far record:
//     L = T_far * environment.radiance(direction), T = T_far, flagged
//     RESOLVED | ENVIRONMENT.  The compose guard stops anything from being
//     counted behind it.
//   * A budget-exhausted walk stores unresolved records that keep
//     RC_SEGMENT_TRUNCATED.  Per the transport contract a truncated input
//     cannot become a resolved black sample, so partial radiance is cleared.
//   * Absorption (a pane driving transmittance to zero) ends the walk: the
//     segment where it happened keeps its accumulated emission; the segment
//     beyond it is resolved-empty (nothing behind an exactly opaque boundary
//     contributes).
//
// Addressing, storage layout and validity rules are not duplicated here:
// cascadeRay / decodeRadianceDispatch come from radiance_cascades.slang, the
// index math and writes come from radiance_cascades_store.slang and
// segment_store.slang.  This module is uncompiled by agreement (the migration
// freeze forbids slangc runs); it is L1 source only.

public module rc_trace_segments;

import "radiance_cascades.slang";
import "radiance_cascades/radiance_cascades_store.slang";
import "radiance_cascades/interval_transport.slang";
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

static const uint RCTS_MAX_SURFACE_STEPS = 16u;

struct RCTraceSegmentOptions {
    uint  maxDepth;      // shaded translucent-emission events along one ray (>= 1)
    float rayBias;       // offset when stepping past a surface
    float nearDistance;  // explicit override; 0 = use the cascade ray's range
    float farDistance;   // explicit override; 0 = use the cascade ray's range
    float maxRadiance;   // per-record firefly clamp
    uint  flags;         // reserved, must be 0 today
};

RCTraceSegmentOptions defaultRCTraceSegmentOptions() {
    RCTraceSegmentOptions result;
    result.maxDepth = 2u;
    result.rayBias = 1.0e-3;
    result.nearDistance = 0.0;
    result.farDistance = 0.0;
    result.maxRadiance = 1.0e6;
    result.flags = 0u;
    return result;
}

// Neutral stand-ins so the required no-sampler signatures share one walk with
// the full form.  A disabled sampler returns an invalid sample; a disabled
// environment returns zero radiance, which stores a resolved-zero far record
// flagged BOUNDED_MISS - exactly the plain contract.
struct RCTSNoLightSampler : ILightSampler {
    LightSample sample(LightReceiver receiver, inout uint seed) {
        return invalidLightSample();
    }
    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        return invalidLightEvaluation();
    }
};

struct RCTSNoEnvironment : IEnvironmentSampler {
    float3 radiance(float3 direction) { return float3(0.0); }
    SkySample sample(float3 normal, inout uint seed) {
        SkySample result;
        result.valid = false;
        result.direction = float3(0.0, 1.0, 0.0);
        result.radiance = float3(0.0);
        result.pdf = 0.0;
        return result;
    }
    float pdf(float3 normal, float3 direction) { return 0.0; }
};

// Resolved record with a firefly clamp.  The clamp is a documented estimator
// change, so it raises RC_SEGMENT_CLAMPED instead of applying silently.
RCSegment rctsResolved(float3 radiance, float3 transmittance, uint extraFlags,
        float maxRadiance) {
    float maximum = max(radiance.r, max(radiance.g, radiance.b));
    if (maximum > maxRadiance) {
        radiance *= maxRadiance / max(maximum, 1.0e-6);
        extraFlags |= RC_SEGMENT_CLAMPED;
    }
    RCSegment segment;
    segment.radiance = clampRadiance(radiance, maxRadiance);
    segment.confidence = 1.0;
    segment.transmittance = clamp(transmittance, float3(0.0), float3(1.0));
    segment.flags = RC_SEGMENT_RESOLVED | extraFlags;
    if (!all(isfinite(segment.radiance)) || !all(isfinite(segment.transmittance))) {
        return rcUnresolvedWithDiagnostics(RC_SEGMENT_TRUNCATED);
    }
    return segment;
}

// Shading of one contributing surface: emission plus, exactly once per ray,
// one NEE sample through the material-filtered visibility walk.  NEE carries
// no segment flag: the transport record only describes transport events, and
// a direct-light sample is not one.
float3 rctsShadeSurface<T : ISceneTracer, L : ILightSampler>(
        T traversal, L lights,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceSample surface, float3 view,
        MaterialSamplingOptions materialOptions, RCTraceSegmentOptions options,
        inout uint seed) {
    float3 radiance = surface.material.emission;

    float3 normal = surface.shadingNormal;
    if (dot(normal, view) < 0.0) normal = -normal;

    LightSample light = lights.sample(lightReceiver(surface.hit.position, normal, surface.material.emissionMask), seed);
    if (light.valid && light.sourcePdf > 0.0) {
        float cosine = max(dot(normal, light.direction), 0.0);
        if (cosine > 0.0) {
            float3 bsdf = bsdfEvaluate(surface.material, view, normal, light.direction);
            if (bsdf.r + bsdf.g + bsdf.b > 0.0) {
                SurfaceVisibility shadow = traceShadowVisibility(traversal,
                    atlas, blockMaterials, surface.hit.position, normal,
                    light.direction, light.distance, options.rayBias,
                    materialOptions);
                float3 transmittance = surfaceVisibilityProven(shadow)
                    ? shadow.result.transmittance
                    : float3(0.0);
                if (transmittance.r + transmittance.g + transmittance.b > 0.0) {
                    radiance += bsdf * light.radiance * transmittance /
                        max(light.sourcePdf, 1.0e-6);
                }
            }
        }
    }
    return radiance;
}

// The walk.  One geometric trace per surface event; the near/far split is the
// midpoint of the resolved range, and the transmittance accumulator resets at
// the boundary so each record carries only its own segment's transport.
//
// The walk tracks per-segment accumulators and an end state, and builds both
// records once, at the end - no record is written mid-walk, so every exit
// path (contribution, miss, absorption, budget) funnels through the same
// construction.
void rctsWalkRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceRay rayValue, MaterialSamplingOptions materialOptions,
        RCTraceSegmentOptions options, inout uint seed,
        out RCSegment front, out RCSegment back) {
    float tNear = options.nearDistance > 0.0 ? options.nearDistance
        : max(rayValue.nearDistance, 0.0);
    float tFar = options.farDistance > 0.0 ? options.farDistance
        : max(rayValue.farDistance, rayValue.nearDistance);
    float mid = 0.5 * (tNear + tFar);

    float3 transmittanceNear = float3(1.0);
    float3 transmittanceFar = float3(1.0);
    float3 emissionNear = float3(0.0);
    float3 emissionFar = float3(0.0);
    // End state: 0 = walk still open (budget), 1 = escaped to the environment,
    // 2 = a contributing opaque surface ended the ray.
    uint endState = 0u;
    bool contributedNear = false;
    uint diagnostics = 0u;
    uint shaded = 0u;

    Ray ray = makeRay(rayValue.origin, rayValue.direction, tNear, tFar);
    for (uint step = 0u; step < RCTS_MAX_SURFACE_STEPS; ++step) {
        SurfaceHit hit = traversal.traceClosest(ray);
        if (!hit.hit) {
            endState = 1u;
            break;
        }

        // Hit distances stay in the original ray parameterization: the walk
        // advances tMin and never rebases the origin, so the mid split cannot
        // drift.
        bool nearSegment = hit.t <= mid;

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit,
            materialOptions, ray.direction);
        if (!surface.valid) {
            // Same rule as the visibility walk: an undecodable surface is
            // opaque, never a window.
            endState = 2u;
            contributedNear = nearSegment;
            break;
        }
        if (surfaceIsHole(surface, materialOptions.alphaCutoff)) {
            if (step + 1u == RCTS_MAX_SURFACE_STEPS) diagnostics |= RC_SEGMENT_TRUNCATED;
            ray.tMin = hit.t + options.rayBias;
            continue;
        }

        if (materialIsTranslucent(surface.material)) {
            // A filter, not a contributor: weigh the following events inside
            // the same segment, count the authored emission against the depth
            // budget, keep walking.
            if (shaded < options.maxDepth) {
                shaded++;
                if (nearSegment) {
                    float3 weighted = transmittanceNear * surface.material.emission;
                    float maximum = max(weighted.r, max(weighted.g, weighted.b));
                    if (maximum > options.maxRadiance) {
                        weighted *= options.maxRadiance / max(maximum, 1.0e-6);
                        diagnostics |= RC_SEGMENT_CLAMPED;
                    }
                    emissionNear += weighted;
                } else {
                    float3 weighted = transmittanceFar * surface.material.emission;
                    float maximum = max(weighted.r, max(weighted.g, weighted.b));
                    if (maximum > options.maxRadiance) {
                        weighted *= options.maxRadiance / max(maximum, 1.0e-6);
                        diagnostics |= RC_SEGMENT_CLAMPED;
                    }
                    emissionFar += weighted;
                }
            }
            if (nearSegment) {
                transmittanceNear *= surfaceTransmittance(surface, rayValue.direction);
            } else {
                transmittanceFar *= surfaceTransmittance(surface, rayValue.direction);
            }
            bool absorbedNear = all(transmittanceNear == float3(0.0));
            bool absorbedFar = all(transmittanceFar == float3(0.0));
            if (absorbedNear || absorbedFar) {
                // Absorbed inside a segment: nothing beyond the pane
                // contributes.  The far segment keeps emission accumulated
                // before the absorbing pane; the near segment always does.
                endState = 2u;
                contributedNear = absorbedNear;
                break;
            }
            ray.tMin = hit.t + options.rayBias;
            if (step + 1u == RCTS_MAX_SURFACE_STEPS) diagnostics |= RC_SEGMENT_TRUNCATED;
            continue;
        }

        // Contributing opaque / cutout-above-threshold surface: shade with the
        // segment-local filters and end the ray.
        float3 contribution = rctsShadeSurface(traversal, lights, atlas,
            blockMaterials, surface, -rayValue.direction, materialOptions,
            options, seed);
        if (nearSegment) {
            emissionNear += transmittanceNear * contribution;
        } else {
            emissionFar += transmittanceFar * contribution;
        }
        endState = 2u;
        contributedNear = nearSegment;
        break;
    }

    // Build both records from the accumulators and the end state.
    if (endState == 1u) {
        // Full-range miss: the environment is the far segment's answer,
        // weighted by the far segment's own filters; the near segment is an
        // empty bounded miss.
        front = rctsResolved(emissionNear, transmittanceNear, RC_SEGMENT_BOUNDED_MISS,
            options.maxRadiance);
        float3 env = environment.radiance(rayValue.direction);
        back = rctsResolved(transmittanceFar * env, transmittanceFar,
            RC_SEGMENT_ENVIRONMENT, options.maxRadiance);
        return;
    }

    if (endState == 2u && contributedNear) {
        // An opaque surface inside the near segment: T of that segment is 0,
        // so the compose rule resolves the ray without a back record.  The
        // far segment is left unresolved (blocked is implicit in front.T=0).
        front = rctsResolved(emissionNear, float3(0.0), diagnostics, options.maxRadiance);
        back = rcUnresolvedSegment();
        return;
    }

    if (endState == 2u) {
        // An opaque surface inside the far segment: the near segment is an
        // empty bounded miss, the far segment resolves with T = 0.
        front = rctsResolved(emissionNear, transmittanceNear, RC_SEGMENT_BOUNDED_MISS,
            options.maxRadiance);
        back = rctsResolved(emissionFar, float3(0.0), diagnostics, options.maxRadiance);
        return;
    }

    // Budget exhausted while still walking: a truncated input cannot become a
    // resolved black sample, so both records go out unresolved with the
    // TRUNCATED diagnostic (partial emission is cleared, not stored).
    diagnostics |= RC_SEGMENT_TRUNCATED;
    front = rcUnresolvedWithDiagnostics(diagnostics);
    back = rcUnresolvedWithDiagnostics(diagnostics);
}

// Traces one probe ray and stores its near/far segment records.  A true return
// means both addresses were written, not that the ray resolved (the storage
// owner's contract).
bool rctsTraceRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> records,
        uint capacity, RadianceRay rayValue, uint cascade, uint probeIndex,
        uint rayIndex, MaterialSamplingOptions materialOptions,
        RCTraceSegmentOptions options, inout uint seed) {
    RCSegment front;
    RCSegment back;
    rctsWalkRay(traversal, lights, environment, atlas, blockMaterials, rayValue,
        materialOptions, options, seed, front, back);
    return rcWriteRaySegments(records, layout, capacity, cascade, probeIndex,
        rayIndex, front, back);
}

// Dispatch entry: one item per probe ray (decodeRadianceDispatch splits the
// flat index).  A stale oversized dispatch touches nothing.
bool rctsTraceDispatch<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> records,
        uint capacity, uint globalIndex, float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceSegmentOptions options,
        inout uint seed) {
    RadianceProbeAddress address = decodeRadianceDispatch(globalIndex);
    if (address.cascade >= RADIANCE_CASCADE_COUNT) return false;
    if (address.probeIndex >= layout.probeCount[address.cascade]) return false;

    RadianceCascadeDesc desc = radianceCascade(address.cascade);
    RadianceRay rayValue = cascadeRay(desc, address.probeIndex, address.rayIndex,
        frameRotation, cameraOrigin);
    return rctsTraceRay(traversal, lights, environment, atlas, blockMaterials,
        layout, records, capacity, rayValue, address.cascade, address.probeIndex,
        address.rayIndex, materialOptions, options, seed);
}

// Plain dispatch form without light sampler or environment: shares the walk
// through the neutral stand-ins.
bool rctsTraceDispatch<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> records,
        uint capacity, uint globalIndex, float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceSegmentOptions options,
        inout uint seed) {
    RCTSNoLightSampler noLights;
    RCTSNoEnvironment noEnvironment;
    return rctsTraceDispatch(traversal, noLights, noEnvironment, atlas,
        blockMaterials, layout, records, capacity, globalIndex, cameraOrigin,
        frameRotation, materialOptions, options, seed);
}
