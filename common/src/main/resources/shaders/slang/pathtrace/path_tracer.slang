// Iterative path tracer for Bedray 2.0.
//
// The loop is written against ITraversal, ILightSampler and IEnvironmentSampler.
// It never names a backend, a GPU or a material atlas layout: surface decoding
// is delegated to surface.slang and lobe selection to shading/bsdf.slang.
//
// Direct lighting uses next-event estimation with the filtered visibility walk,
// so a cutout hole is skipped and stained glass tints the shadow.  Indirect
// lighting uses BSDF sampling.  Emission is added on camera rays and after a
// delta bounce; a diffuse bounce relies on NEE, which avoids double counting an
// emitter that a BSDF ray could also reach.

public module path_tracer;

import "contracts/contracts.slang";
import "light/sky.slang";
import "sampling.slang";
import "shading/bsdf.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
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

struct PathTraceResult {
    float3 radiance;
    float3 throughput;
    float  firstHitDistance;    // parameter from primary origin; distance when primary direction is unit length
    uint   depth;
    uint   surfaceHits;
    uint   flags;
};

PathTraceResult emptyPathTraceResult() {
    PathTraceResult result;
    result.radiance = float3(0.0);
    result.throughput = float3(1.0);
    result.firstHitDistance = 0.0;
    result.depth = 0u;
    result.surfaceHits = 0u;
    result.flags = 0u;
    return result;
}

float pathMaxComponent(float3 value) {
    return max(value.r, max(value.g, value.b));
}

// Traces one path.  `seed` is advanced in place so a caller can derive the next
// path's seed without a global random stream.
PathTraceResult tracePath<T : ITraversal, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    PathTraceResult result = emptyPathTraceResult();
    result.firstHitDistance = options.maxDistance;

    // A primary direction is unit length by the caller's contract, so
    // firstHitDistance is a distance. Reject a degenerate interval or
    // non-finite primary data instead of letting traversal treat it as a miss.
    if (!isFinite(primaryRay.origin) || !isFinite(primaryRay.direction) ||
        !(dot(primaryRay.direction, primaryRay.direction) > 0.0) ||
        !isFinite(primaryRay.tMin) || !isFinite(primaryRay.tMax) ||
        primaryRay.tMin < 0.0 || !(primaryRay.tMax > primaryRay.tMin)) {
        result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
        return result;
    }

    float3 throughput = float3(1.0);
    Ray ray = primaryRay;
    bool lastBounceWasDelta = true;
    bool firstSurfaceAccepted = false;
    // Cutout holes move the origin along the current ray. Keep the displacement
    // of the primary ray only, so a later hit is measured from the camera.
    float primaryAdvance = 0.0;
    uint step = 0u;

    for (; step < options.maxSurfaceSteps; ++step) {
        // Cutout advances shrink tMax. An empty interval means the path was
        // truncated, not that it escaped into the environment.
        if (!(ray.tMax > ray.tMin) || !isFinite(ray.tMax)) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }
        SurfaceHit hit = traversal.traceClosest(ray);
        if (!hit.hit) {
            result.radiance += throughput * environment.radiance(ray.direction);
            result.flags |= PATH_TRACE_FLAG_ENVIRONMENT;
            break;
        }

        if (!isFinite(hit.t) || hit.t < ray.tMin || hit.t > ray.tMax) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit, materialOptions);
        if (!surface.valid) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
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
            continue;
        }

        if (!firstSurfaceAccepted) {
            if (!isFinite(primaryAdvance + hit.t)) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            firstSurfaceAccepted = true;
            result.firstHitDistance = primaryAdvance + hit.t;
            result.flags |= PATH_TRACE_FLAG_PRIMARY_HIT;
        }
        result.surfaceHits++;

        float3 normal = surface.shadingNormal;
        float3 view = -ray.direction;
        if (dot(normal, view) < 0.0) normal = -normal;

        if (lastBounceWasDelta) {
            result.radiance += throughput * surface.material.emission;
        }

        // ---- Next-event estimation ----
        LightSample light = lights.sample(hit.position, normal, seed);
        if (light.valid && light.sourcePdf > 0.0) {
            float3 lightDirection = light.direction;
            float cosine = max(dot(normal, lightDirection), 0.0);
            if (cosine > 0.0) {
                float3 bsdf = bsdfEvaluate(surface.material, view, normal, lightDirection);
                if (bsdf.r + bsdf.g + bsdf.b > 0.0) {
                    // Offset from the geometric face, not the normal-mapped
                    // shading normal: a tilted normal can start the shadow ray
                    // inside the surface and either leak light or self-shadow.
                    float3 shadowNormal = safeNormalize(surface.hit.geometricNormal, normal);
                    if (dot(shadowNormal, view) < 0.0) shadowNormal = -shadowNormal;
                    SurfaceVisibility shadow = traceShadowVisibility(traversal,
                        atlas, blockMaterials, hit.position, shadowNormal, lightDirection,
                        light.distance, options.rayBias, materialOptions);
                    float3 transmittance = shadow.result.visible
                        ? shadow.result.transmittance
                        : float3(0.0);
                    if (transmittance.r + transmittance.g + transmittance.b > 0.0) {
                        result.radiance += throughput * bsdf * light.radiance *
                            transmittance / max(light.sourcePdf, 1.0e-6);
                        result.flags |= PATH_TRACE_FLAG_FILTERED_SHADOW;
                    }
                }
            }
        }

        // ---- BSDF sampling ----
        BSDFSample bsdf = bsdfSample(surface.material, view, normal, seed);
        if (!bsdf.valid || bsdf.pdf <= 0.0) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }

        throughput *= bsdf.weight;
        lastBounceWasDelta = bsdf.delta;
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

        if (!isFinite(throughput) || pathMaxComponent(throughput) > options.maxRadiance) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }

        float3 origin = hit.position + bsdf.direction * options.rayBias;
        if (dot(bsdf.direction, normal) < 0.0) {
            origin = hit.position - normal * options.rayBias;
        }
        ray = makeRay(origin, bsdf.direction, 0.0, options.maxDistance);
    }

    if (step == options.maxSurfaceSteps) {
        result.flags |= PATH_TRACE_FLAG_SURFACE_LIMIT;
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
float3 tracePathRadiance<T : ITraversal, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    return tracePath(traversal, lights, environment, atlas, blockMaterials,
        primaryRay, materialOptions, options, seed).radiance;
}
