// Material-filtered visibility for Bedray 2.0.
//
// ISceneTracer reports the first geometric blocker.  That is not enough for a
// shadow ray: a cutout hole must be skipped, a stained-glass pane must tint the
// light, and an opaque wall must end the ray.  This module walks the geometric
// candidates one by one, applies the material rules from surface.slang, and
// returns a VisibilityResult with the accumulated transmittance.
//
// The walk is backend independent.  It uses traceClosest rather than
// traceVisibility because a filtered ray needs more than the first candidate;
// the backend still owns the acceleration structure and the intersection test.

public module surface_visibility;

import "contracts/hit.slang";
import "contracts/material.slang";
import "contracts/math.slang";
import "surface/surface.slang";
import "surface/water.slang";
import "traversal/traversal.slang";

static const uint SURFACE_VISIBILITY_MAX_LAYERS = 16u;
static const float SURFACE_VISIBILITY_BIAS = 1.0e-3;
static const float SURFACE_VISIBILITY_MIN_TRANSMITTANCE = 1.0e-3;

static const uint SURFACE_VISIBILITY_FLAG_OVERFLOW = 1u << 0u;
static const uint SURFACE_VISIBILITY_FLAG_FILTERED = 1u << 1u;
static const uint SURFACE_VISIBILITY_FLAG_ABSORBED = 1u << 2u;

struct SurfaceVisibility {
    VisibilityResult result;
    uint             layers;
    uint             flags;
};

// Advances the ray start just past a geometric candidate.  The origin is kept,
// so blocker.t stays relative to the original ray and a caller can compare it
// with the light distance without a second origin correction.
Ray skipSurfaceCandidate(Ray ray, float hitDistance, float bias) {
    ray.tMin = hitDistance + bias;
    return ray;
}

SurfaceVisibility traceFilteredVisibility<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray ray, MaterialSamplingOptions options) {
    SurfaceVisibility visibility;
    visibility.result = visibleResult(ray.tMax);
    visibility.layers = 0u;
    visibility.flags = 0u;

    float3 transmittance = float3(1.0);
    Ray current = ray;

    for (uint layer = 0u; layer < SURFACE_VISIBILITY_MAX_LAYERS; ++layer) {
        if (current.tMax <= current.tMin) break;

        SurfaceHit hit = traversal.traceClosest(current);
        if (!hit.hit) {
            visibility.result.visible = true;
            visibility.result.distance = ray.tMax;
            visibility.result.transmittance = transmittance;
            visibility.result.blocker = missHit(ray.tMax);
            return visibility;
        }

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit, options, current.direction);
        if (!surface.valid) {
            // A malformed hit is safer to treat as opaque than to let light
            // through an unknown surface.
            visibility.result = blockedResult(hit, float3(0.0));
            visibility.flags |= SURFACE_VISIBILITY_FLAG_ABSORBED;
            return visibility;
        }

        if (surfaceIsHole(surface, options.alphaCutoff)) {
            current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);
            visibility.layers++;
            continue;
        }

        if (materialIsTranslucent(surface.material)) {
            transmittance *= surfaceTransmittance(surface, ray.direction);
            visibility.layers++;
            visibility.flags |= SURFACE_VISIBILITY_FLAG_FILTERED;

            float maximum = max(transmittance.r, max(transmittance.g, transmittance.b));
            if (maximum <= SURFACE_VISIBILITY_MIN_TRANSMITTANCE) {
                visibility.result = blockedResult(hit, float3(0.0));
                visibility.flags |= SURFACE_VISIBILITY_FLAG_ABSORBED;
                return visibility;
            }

            current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);
            continue;
        }

        // Opaque or cutout texel above the threshold: the ray ends here.
        visibility.result = blockedResult(hit, float3(0.0));
        return visibility;
    }

    // The layer budget ran out while the ray was still passing through
    // filters.  The remaining energy is kept, but the caller can see that the
    // walk was truncated instead of silently treating it as a clean ray.
    visibility.flags |= SURFACE_VISIBILITY_FLAG_OVERFLOW;
    visibility.result.visible = true;
    visibility.result.distance = ray.tMax;
    visibility.result.transmittance = transmittance;
    visibility.result.blocker = missHit(ray.tMax);
    return visibility;
}

// Medium-aware walk for a caller that tracks the water body: the receiver may
// start inside a body (an underwater surface), a horizontal fluid face toggles
// the body, and every segment inside it is attenuated by its extinction.  The
// fluid interface contributes only its Fresnel share here - the colour is the
// distance absorption, so tinting the interface too would darken water twice.
// `traceFilteredVisibility` keeps the old behaviour for callers without medium
// state.
SurfaceVisibility traceFilteredVisibilityMedium<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray ray, MaterialSamplingOptions options, WaterMedium medium) {
    SurfaceVisibility visibility;
    visibility.result = visibleResult(ray.tMax);
    visibility.layers = 0u;
    visibility.flags = 0u;

    float3 transmittance = float3(1.0);
    Ray current = ray;
    // hit.t is measured from the original origin across skipSurfaceCandidate,
    // so this stays the end of the last segment handled.
    float lastT = 0.0;

    for (uint layer = 0u; layer < SURFACE_VISIBILITY_MAX_LAYERS; ++layer) {
        if (current.tMax <= current.tMin) break;

        SurfaceHit hit = traversal.traceClosest(current);
        if (!hit.hit) {
            if (medium.inside) {
                transmittance *= waterSegmentTransmittance(medium, max(ray.tMax - lastT, 0.0));
            }
            visibility.result.visible = true;
            visibility.result.distance = ray.tMax;
            visibility.result.transmittance = transmittance;
            visibility.result.blocker = missHit(ray.tMax);
            return visibility;
        }

        if (medium.inside) {
            transmittance *= waterSegmentTransmittance(medium, max(hit.t - lastT, 0.0));
        }
        lastT = hit.t;

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit, options, current.direction);
        if (!surface.valid) {
            visibility.result = blockedResult(hit, float3(0.0));
            visibility.flags |= SURFACE_VISIBILITY_FLAG_ABSORBED;
            return visibility;
        }

        if (surfaceIsHole(surface, options.alphaCutoff)) {
            current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);
            visibility.layers++;
            continue;
        }

        if (materialIsTranslucent(surface.material)) {
            transmittance *= surfaceInterfaceTransmittance(surface, ray.direction);
            visibility.layers++;
            visibility.flags |= SURFACE_VISIBILITY_FLAG_FILTERED;

            bool fluid = (surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u;
            if (fluid && waterEntersMedium(medium.inside, surface.hit.geometricNormal.y, ray.direction.y)) {
                medium = waterMediumFrom(waterBiomeTint(surface.hit.tint,
                    surface.hit.geometricNormal), surface.hit.position.y);
            } else if (fluid && waterLeavesMedium(medium.inside,
                    surface.hit.geometricNormal.y, ray.direction.y)) {
                medium.inside = false;
            }

            float maximum = max(transmittance.r, max(transmittance.g, transmittance.b));
            if (maximum <= SURFACE_VISIBILITY_MIN_TRANSMITTANCE) {
                visibility.result = blockedResult(hit, float3(0.0));
                visibility.flags |= SURFACE_VISIBILITY_FLAG_ABSORBED;
                return visibility;
            }

            current = skipSurfaceCandidate(current, hit.t, SURFACE_VISIBILITY_BIAS);
            continue;
        }

        // Opaque or cutout texel above the threshold: the ray ends here.
        visibility.result = blockedResult(hit, float3(0.0));
        return visibility;
    }

    visibility.flags |= SURFACE_VISIBILITY_FLAG_OVERFLOW;
    visibility.result.visible = true;
    visibility.result.distance = ray.tMax;
    visibility.result.transmittance = transmittance;
    visibility.result.blocker = missHit(ray.tMax);
    return visibility;
}

// A walk that ran out of its layer budget proved nothing: it stopped counting
// filters, it did not reach the light.  Reading that as a clean ray leaks an
// opaque blocker sitting behind the filters, so every consumer asks this
// predicate instead of trusting the walk's own visibility bit.  The energy the
// walk did gather stays in `transmittance` for a caller that wants a soft
// fallback.
bool surfaceVisibilityProven(SurfaceVisibility visibility) {
    return visibility.result.visible
        && (visibility.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) == 0u;
}

// Boolean form for callers that only need "is anything opaque in the way".
// The transmittance is intentionally discarded; use the full form when a
// coloured shadow is required.
bool traceFilteredOccluded<T : ISceneTracer>(T traversal, MaterialAtlas atlas,
        BlockMaterialTable blockMaterials, Ray ray, MaterialSamplingOptions options) {
    SurfaceVisibility visibility = traceFilteredVisibility(traversal, atlas,
        blockMaterials, ray, options);
    return !surfaceVisibilityProven(visibility);
}

// Convenience wrapper that also builds the shadow ray.  `surfacePosition` is
// the receiver, `normal` is its shading normal and `direction` points to the
// light.  `distance` is the light distance; the returned transmittance is the
// colour that reaches the receiver.
SurfaceVisibility traceShadowVisibility<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        float3 surfacePosition, float3 normal, float3 direction,
        float distance, float bias, MaterialSamplingOptions options) {
    float3 origin = surfacePosition + normal * bias;
    float tMax = max(distance - bias, bias);
    Ray ray = makeRay(origin, direction, bias, tMax);
    return traceFilteredVisibility(traversal, atlas, blockMaterials, ray, options);
}

// Shadow ray for a receiver whose medium state is known: an underwater
// receiver starts inside the body, so the column between it and the light is
// attenuated instead of transparent.
SurfaceVisibility traceShadowVisibilityMedium<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        float3 surfacePosition, float3 normal, float3 direction,
        float distance, float bias, MaterialSamplingOptions options, WaterMedium medium) {
    float3 origin = surfacePosition + normal * bias;
    float tMax = max(distance - bias, bias);
    Ray ray = makeRay(origin, direction, bias, tMax);
    return traceFilteredVisibilityMedium(traversal, atlas, blockMaterials, ray, options, medium);
}
