// Iterative path tracer for Bedray 2.0.
//
// The loop is written against ISceneTracer, ILightSampler, IEnvironmentSampler and IReuseSampler.
// It never names a backend, a GPU or a material atlas layout: surface decoding
// is delegated to surface.slang and lobe selection to shading/bsdf.slang.
//
// Direct lighting uses next-event estimation with the filtered visibility walk,
// so a cutout hole is skipped and stained glass tints the shadow.  The pixel's
// own first surface may instead be served from a cross-frame reservoir (the
// IReuseSampler branch); the loop then shades both branches it owns and reports
// the shadow outcome back to the record, without knowing what a reservoir is.
// Indirect lighting uses BSDF sampling.  Emission is added on every accepted
// hit, so a caller whose sampler also draws surface emitters must restore the
// "previous bounce was delta" gate and use MIS, or an emitter hit would be
// counted twice.
//
// A horizontal fluid face is also a medium boundary.  The path keeps the water
// body it is currently in: a refracted transmission through the face enters or
// leaves it, every segment inside is attenuated by the body's extinction, and
// each segment adds the light it scatters towards the eye.  The same state
// travels into the shadow rays, so an underwater receiver is lit through its
// own water column instead of through clear air.  The water surface itself is
// clear (`surface.slang`); all of its colour is this absorption.

public module path_tracer;

import "contracts/contracts.slang";
import "light/sky.slang";
import "sampling.slang";
import "shading/bsdf.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
import "surface/water.slang";
import "traversal/traversal.slang";

static const uint PATH_TRACE_FLAG_ENVIRONMENT      = 1u << 0u;
static const uint PATH_TRACE_FLAG_RUSSIAN_ROULETTE = 1u << 1u;
static const uint PATH_TRACE_FLAG_DEPTH_LIMIT      = 1u << 2u;
static const uint PATH_TRACE_FLAG_INVALID_SAMPLE   = 1u << 3u;
static const uint PATH_TRACE_FLAG_SURFACE_LIMIT    = 1u << 4u;
static const uint PATH_TRACE_FLAG_FILTERED_SHADOW  = 1u << 5u;
// The primary ray hit an accepted surface, so firstHitDistance is a real depth rather than the far plane.  A depth
// buffer cannot infer this from surfaceHits: a path that only crosses cutout holes never accepted a surface.
static const uint PATH_TRACE_FLAG_PRIMARY_HIT      = 1u << 6u;
// The two ways a path ends badly are not the same fault.  INVALID_SAMPLE means the arithmetic could not be trusted -
// a non-finite ray, hit, advance or throughput, or a hit outside the interval the caller gave.  TRUNCATED means the
// path stopped for a legitimate reason: the block decode refused the surface, the lobe had nothing to sample, cutout
// advances used up the interval, or the throughput reached the firefly guard.  The radiance gathered before a
// truncation is a short but honest sample of the integrand, so it stays in the mean; only a non-finite fault is worth
// dropping from a history that lives for thousands of frames.
static const uint PATH_TRACE_FLAG_TRUNCATED        = 1u << 7u;
// The path crossed a horizontal fluid face and is now tracing inside a water
// body.  Kept as a diagnostic: the medium changes what a segment means, and a
// frame that never sets it is a frame where the fluid was not recognised.
static const uint PATH_TRACE_FLAG_WATER_MEDIUM     = 1u << 8u;

// Bits of PathTraceOptions.flags.  These are pass options (what the host asks the
// pass to produce), not properties of a sample: the tracer ignores them and the
// entry pass reads them.
static const uint PATH_TRACE_OPTION_GBUFFER        = 1u << 0u;  // fill PathTraceResult.primary for the denoiser guides

struct PathTraceOptions {
    uint  maxDepth;             // scattering events before the path stops
    uint  maxSurfaceSteps;      // geometric candidates, including cutout holes
    uint  minRussianDepth;      // first depth where Russian roulette is allowed
    float rayBias;
    float russianMinProbability;
    float maxDistance;          // secondary ray far plane
    float maxRadiance;          // firefly / overflow guard
    float alphaCutoff;
    uint  flags;
};

PathTraceOptions defaultPathTraceOptions() {
    PathTraceOptions options;
    options.maxDepth = 8u;
    options.maxSurfaceSteps = 64u;
    options.minRussianDepth = 3u;
    options.rayBias = 1.0e-3;
    options.russianMinProbability = 0.05;
    options.maxDistance = 1.0e6;
    options.maxRadiance = 1.0e6;
    options.alphaCutoff = 0.5;
    options.flags = 0u;
    return options;
}

// The primary surface as a denoiser sees it: the guide channels an NRD / DLSS-RR input pack needs, captured at the
// vertex the camera actually sees rather than re-derived by the pass.  A pass cannot recompute these without a second
// primary trace whose hit could disagree with the one the radiance came from.
struct PathPrimaryGuide {
    float3 position;      // camera-relative world position of the primary surface
    float3 normal;        // view-facing shading normal
    float3 albedo;        // linear diffuse albedo, floored for the demodulation
    float3 specularF0;    // interface reflectance at normal incidence
    float3 direct;        // this sample's next-event estimate at the primary vertex
    float  roughness;     // linear roughness (0..1)
    float  metallic;      // 0 = dielectric, 1 = metal
    bool   valid;
};

struct PathTraceResult {
    float3 radiance;
    float3 throughput;
    float  firstHitDistance;    // parameter from primary origin; distance when primary direction is unit length
    float4 primaryMotion;       // xyz: where the primary surface stood one frame ago minus where it is now, in
                                // world blocks; w: 1 when that surface is a captured model rather than terrain
    PathPrimaryGuide primary;   // guide channels, filled only when the primary ray hit a decodable surface
    uint   depth;
    uint   surfaceHits;
    uint   flags;
};

PathTraceResult emptyPathTraceResult() {
    PathTraceResult result;
    result.radiance = float3(0.0);
    result.throughput = float3(1.0);
    result.firstHitDistance = 0.0;
    result.primaryMotion = float4(0.0);
    result.primary.position = float3(0.0);
    result.primary.normal = float3(0.0, 0.0, 1.0);
    result.primary.albedo = float3(0.0);
    result.primary.specularF0 = float3(0.0);
    result.primary.direct = float3(0.0);
    result.primary.roughness = 1.0;
    result.primary.metallic = 0.0;
    result.primary.valid = false;
    result.depth = 0u;
    result.surfaceHits = 0u;
    result.flags = 0u;
    return result;
}

float pathMaxComponent(float3 value) {
    return max(value.r, max(value.g, value.b));
}

// The one place "do not show this sample to the accumulator" is spelled out, so a pass cannot invent its own rule and
// silently start dropping truncated paths.
bool pathTraceSampleRejected(uint flags) {
    return (flags & PATH_TRACE_FLAG_INVALID_SAMPLE) != 0u;
}

// Shades one direct-light sample: the BSDF term of the legacy NEE times the filtered visibility walk.  The caller
// multiplies the result by its throughput.  `traced` says a shadow ray really ran, `occluded` that it came back
// blocked; a reservoir needs both, because a sample this receiver cannot even see must fade instead of persisting.
// `medium` is the water body the receiver stands in: an underwater receiver is shadowed through its own column.
float3 pathTraceDirectTerm<T : ISceneTracer>(T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceSample surface, float3 view, float3 normal, float3 position, LightSample light, float rayBias,
        MaterialSamplingOptions materialOptions, WaterMedium medium, out bool traced, out bool occluded) {
    float3 zero = float3(0.0);
    traced = false;
    occluded = false;
    if (!light.valid || !(light.sourcePdf > 0.0)) {
        return zero;
    }
    float3 lightDirection = light.direction;
    float cosine = max(dot(normal, lightDirection), 0.0);
    if (cosine <= 0.0) {
        return zero;
    }
    float3 bsdf = bsdfEvaluate(surface.material, view, normal, lightDirection);
    if (bsdf.r + bsdf.g + bsdf.b <= 0.0) {
        return zero;
    }
    // Offset from the geometric face, not the normal-mapped
    // shading normal: a tilted normal can start the shadow ray
    // inside the surface and either leak light or self-shadow.
    float3 shadowNormal = safeNormalize(surface.hit.geometricNormal, normal);
    if (dot(shadowNormal, view) < 0.0) {
        shadowNormal = -shadowNormal;
    }
    traced = true;
    SurfaceVisibility shadow = traceShadowVisibilityMedium(traversal, atlas, blockMaterials, position, shadowNormal,
        lightDirection, light.distance, rayBias, materialOptions, medium);
    // Sixteen filters deep the walk gives up and keeps the energy it collected.  That is not a proven sight line:
    // calling it one would light this surface through an opaque blocker hidden behind the glass.  Reporting it as
    // occluded also lets a reused record fade instead of persisting.
    float3 transmittance = surfaceVisibilityProven(shadow) ? shadow.result.transmittance : float3(0.0);
    if (transmittance.r + transmittance.g + transmittance.b <= 0.0) {
        occluded = true;
        return zero;
    }
    return bsdf * light.radiance * transmittance / max(light.sourcePdf, 1.0e-6);
}

// Traces one path.  `seed` is advanced in place so a caller can derive the next
// path's seed without a global random stream.
PathTraceResult tracePath<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler, R : IReuseSampler>(
        T traversal, L lights, E environment, R reuse,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    PathTraceResult result = emptyPathTraceResult();
    result.firstHitDistance = options.maxDistance;

    // A path that never reaches a surface has no sample of its own to publish, and the pixel's record still has to be
    // rewritten: a stale history would resurrect a light that has left the screen.  `reuseHandled` is set by the branch
    // that wrote it.
    bool reuseHandled = false;

    // A primary direction is unit length by the caller's contract, so
    // firstHitDistance is a distance. Reject a degenerate interval or
    // non-finite primary data instead of letting traversal treat it as a miss.
    if (!isFinite(primaryRay.origin) || !isFinite(primaryRay.direction) ||
        !(dot(primaryRay.direction, primaryRay.direction) > 0.0) ||
        !isFinite(primaryRay.tMin) || !isFinite(primaryRay.tMax) ||
        primaryRay.tMin < 0.0 || !(primaryRay.tMax > primaryRay.tMin)) {
        result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
        if (reuse.reuseActive()) {
            reuse.reuseClear();
        }
        return result;
    }

    float3 throughput = float3(1.0);
    Ray ray = primaryRay;
    bool firstSurfaceAccepted = false;
    // Cutout holes move the origin along the current ray. Keep the displacement
    // of the primary ray only, so a later hit is measured from the camera.
    float primaryAdvance = 0.0;
    uint step = 0u;

    // The water body the current ray runs in and the ray coordinate where its
    // current segment began.  A hole advance moves the origin, so the same
    // physical point sits at -rayBias on the new ray; tracking the coordinate
    // rather than an accumulated length keeps the next segment exact.
    WaterMedium medium = noWaterMedium();
    float mediumStart = 0.0;
    // Light arriving at the water body from above.  Only a path that entered
    // the water needs it, so it is fetched on the first entry instead of once
    // per path.
    float3 waterDown = float3(0.0);
    bool waterDownReady = false;

    for (; step < options.maxSurfaceSteps; ++step) {
        // A non-finite interval cannot be traced at all; an empty one is a path cut short by the cutout advances.
        if (!isFinite(ray.tMax)) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }
        if (!(ray.tMax > ray.tMin)) {
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }
        // The eye's own ray is the one a backend may answer with less geometry:
        // a first-person capture puts the local player's body around the camera.
        // A cutout hole does not increment surfaceHits, so a ray that walked
        // through the body's own skin keeps looking past it, and only the first
        // accepted surface starts the bounces that must see the body - it is
        // geometry a light has to cross.
        SurfaceHit hit = result.surfaceHits == 0u ? traversal.tracePrimary(ray)
                                                  : traversal.traceClosest(ray);
        if (!hit.hit) {
            // A ray inside the body that finds no surface still crossed water:
            // the tail is absorbed like every other segment (the legacy bound
            // of 48 blocks keeps an open ray from going black at the far plane).
            if (medium.inside) {
                float tail = clamp(ray.tMax - mediumStart, 0.0, 48.0);
                float3 tailMidpoint = ray.origin + ray.direction * (mediumStart + tail * 0.5);
                result.radiance += throughput * waterInScatter(medium, tailMidpoint.y, waterDown, tail);
                throughput *= waterSegmentTransmittance(medium, tail);
            }
            result.radiance += throughput * environment.radiance(ray.direction);
            result.flags |= PATH_TRACE_FLAG_ENVIRONMENT;
            break;
        }

        if (!isFinite(hit.t) || hit.t < ray.tMin || hit.t > ray.tMax) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }

        // Distance absorption of the segment that ends on this candidate.  The
        // in-scattered light is added at the throughput the segment started
        // with; the transmitted part continues through the extinction.
        if (medium.inside) {
            float segment = max(hit.t - mediumStart, 0.0);
            float3 midpoint = ray.origin + ray.direction * (mediumStart + segment * 0.5);
            result.radiance += throughput * waterInScatter(medium, midpoint.y, waterDown, segment);
            throughput *= waterSegmentTransmittance(medium, segment);
        }
        mediumStart = hit.t;

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit, materialOptions, ray.direction);
        if (!surface.valid) {
            // A hit this decoder cannot name.  Nothing produced a bad number, so the radiance gathered up to here is
            // still a sample; the path simply stops short.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        if (surfaceIsHole(surface, options.alphaCutoff)) {
            // A cutout hole is not a surface: continue the same ray just past
            // the candidate without spending a scattering event.
            float advance = hit.t + options.rayBias;
            if (!isFinite(advance) || advance <= 0.0 ||
                (!firstSurfaceAccepted && !isFinite(primaryAdvance + advance))) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            if (!firstSurfaceAccepted) primaryAdvance += advance;
            float3 advancedOrigin = ray.origin + ray.direction * advance;
            if (!isFinite(advancedOrigin)) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            ray.origin = advancedOrigin;
            ray.tMax = max(ray.tMax - advance, 0.0);
            ray.tMin = 0.0;
            // The candidate now sits rayBias behind the new origin, so the
            // medium segment must continue from there, not from zero.
            mediumStart = -options.rayBias;
            continue;
        }

        if (!firstSurfaceAccepted) {
            if (!isFinite(primaryAdvance + hit.t)) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            firstSurfaceAccepted = true;
            result.firstHitDistance = primaryAdvance + hit.t;
            // What the accumulator needs is not this frame's surface but the same
            // surface as the previous frame saw it, so the question goes to the
            // backend that owns the geometry and stops here for every other ray.
            result.primaryMotion = float4(traversal.surfaceMotion(hit),
                hit.kind == HitKind.Entity ? 1.0 : 0.0);
            result.flags |= PATH_TRACE_FLAG_PRIMARY_HIT;
        }
        result.surfaceHits++;

        float3 normal = surface.shadingNormal;
        float3 view = -ray.direction;
        if (dot(normal, view) < 0.0) normal = -normal;

        // The face of a glowing block is seen as its own emission, and the light it casts on other surfaces comes from
        // NEE; a sampler that reads the emitter list rejects the receiver's own quad through LightReceiver.emission.
        result.radiance += throughput * surface.material.emission;

        // ---- Next-event estimation ----
        LightReceiver receiver = lightReceiver(hit.position, normal, surface.material.emissionMask);
        // Only the pixel's own first surface owns the screen-space record; a deeper vertex would write someone else's
        // receiver into it and drag that light across the screen.
        receiver.primary = result.surfaceHits == 1u;
        // The reservoir belongs to this surface, so its history has to be looked up where this surface was - which for
        // a mob part is not the pixel the camera alone points at.
        receiver.motion = result.primaryMotion.xyz;
        float3 direct = float3(0.0);
        if (receiver.primary && reuse.reuseActive()) {
            // The reservoir carries the emissive branch across frames, so the composite must not also draw it; the key
            // light is still sampled the plain way.  One extra shadow ray per pixel, no duplicated emitter batch.
            bool reusedTraced;
            bool reusedOccluded;
            LightSample reused = reuse.reuseSample(receiver, seed);
            reuseHandled = true;
            direct += pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                reused, options.rayBias, materialOptions, medium, reusedTraced, reusedOccluded);
            // A sample this receiver could not even shade has not been proven visible either: decaying it lets a record
            // that lost its light fade out instead of sitting at full weight until the surface changes.
            reuse.reuseConfirm(!reusedTraced || reusedOccluded);
            bool keyTraced;
            bool keyOccluded;
            direct += pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                lights.sample(receiver, seed), options.rayBias, materialOptions, medium, keyTraced, keyOccluded);
        } else {
            bool lightTraced;
            bool lightOccluded;
            direct += pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                lights.sample(receiver, seed), options.rayBias, materialOptions, medium, lightTraced, lightOccluded);
        }
        if (direct.r + direct.g + direct.b > 0.0) {
            result.flags |= PATH_TRACE_FLAG_FILTERED_SHADOW;
        }
        // The guide channels of the surface the camera sees, captured here because this is where its material, its
        // view-facing normal and its own next-event estimate exist.  `receiver.primary` is true exactly once per path,
        // at the first accepted surface; the throughput there is still 1, so `direct` is the primary vertex's own term.
        if (receiver.primary) {
            result.primary.valid = true;
            result.primary.position = hit.position;
            result.primary.normal = normal;
            result.primary.albedo = clamp(surface.material.albedo, float3(0.02), float3(1.0));
            result.primary.specularF0 = surface.material.f0;
            result.primary.roughness = surface.material.roughness;
            result.primary.metallic = surface.material.metallic;
            result.primary.direct = direct;
        }
        result.radiance += throughput * direct;

        // ---- BSDF sampling ----
        BSDFSample bsdf = bsdfSample(surface.material, view, normal, seed);
        if (!bsdf.valid || bsdf.pdf <= 0.0) {
            // No lobe left to sample (an albedo that reflects nothing, or a
            // VNDF reflection below the shading horizon with zero BRDF).  A
            // short path, not a broken one: direct light and emission at this
            // vertex are already in `result.radiance`.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        // A refracted transmission through a horizontal fluid face crosses the
        // water boundary; the transmitted direction says which side the next
        // segment runs on.  A reflection (including total internal reflection)
        // stays on its own side and toggles nothing.
        if ((surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u
                && bsdf.lobe == BSDF_LOBE_TRANSMISSION) {
            float faceY = surface.hit.geometricNormal.y;
            if (waterEntersMedium(medium.inside, faceY, bsdf.direction.y)) {
                if (!waterDownReady) {
                    waterDown = environment.radiance(float3(0.0, 1.0, 0.0));
                    waterDownReady = true;
                }
                medium = waterMediumFrom(waterBiomeTint(hit.tint, surface.hit.geometricNormal),
                    hit.position.y);
                result.flags |= PATH_TRACE_FLAG_WATER_MEDIUM;
            } else if (waterLeavesMedium(medium.inside, faceY, bsdf.direction.y)) {
                medium.inside = false;
            }
        }

        throughput *= bsdf.weight;
        result.depth++;

        if (pathMaxComponent(throughput) <= 0.0) {
            break;
        }

        if (result.depth >= options.maxDepth) {
            result.flags |= PATH_TRACE_FLAG_DEPTH_LIMIT;
            break;
        }

        if (result.depth >= options.minRussianDepth) {
            float survival = clamp(pathMaxComponent(throughput),
                options.russianMinProbability, 0.95);
            if (rand01(seed) >= survival) {
                result.flags |= PATH_TRACE_FLAG_RUSSIAN_ROULETTE;
                break;
            }
            throughput /= max(survival, 1.0e-4);
        }

        if (!isFinite(throughput)) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }
        if (pathMaxComponent(throughput) > options.maxRadiance) {
            // The firefly guard.  The path would blow up, so it ends here, but the tail clamps what came before to a
            // finite value: a bright bounce is a real sample, not a broken one.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        float3 origin = hit.position + bsdf.direction * options.rayBias;
        if (dot(bsdf.direction, normal) < 0.0) {
            origin = hit.position - normal * options.rayBias;
        }
        ray = makeRay(origin, bsdf.direction, 0.0, options.maxDistance);
        // The new origin sits at most one bias behind the interaction; the
        // segment starts there (the legacy path ignored this offset too).
        mediumStart = 0.0;
    }

    if (step == options.maxSurfaceSteps) {
        result.flags |= PATH_TRACE_FLAG_SURFACE_LIMIT;
    }

    // Nothing wrote this pixel's record (a sky pixel, a truncated interval, a surface that stopped the path): publish an
    // empty one.
    if (!reuseHandled && reuse.reuseActive()) {
        reuse.reuseClear();
    }

    result.throughput = throughput;
    if (!isFinite(result.radiance)) {
        result.radiance = float3(0.0);
        result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
    }
    result.radiance = clampRadiance(result.radiance, options.maxRadiance);
    return result;
}

// Radiance-only convenience for passes that do not need the diagnostics.
float3 tracePathRadiance<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler, R : IReuseSampler>(
        T traversal, L lights, E environment, R reuse,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    return tracePath(traversal, lights, environment, reuse, atlas, blockMaterials,
        primaryRay, materialOptions, options, seed).radiance;
}
