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
// The beam was walked as two legs - the column along the refracted direction and
// the sky along the air one - rather than as one straight ray.
static const uint SURFACE_VISIBILITY_FLAG_REFRACTED = 1u << 3u;

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

        // A shadow ray sees a leaf as a coloured filter, not a wall.  Only the shadow wrappers below set the flag:
        // visibility, AO, sky and camera queries keep the binary cutout, because a canopy still occludes them.
        if ((options.flags & MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE) != 0u
                && materialFiltersAsFoliage(surface.material)) {
            transmittance *= foliageLayerTransmittance(surface);
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

        if ((options.flags & MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE) != 0u
                && materialFiltersAsFoliage(surface.material)) {
            transmittance *= foliageLayerTransmittance(surface);
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
    // One offset only: the origin is already a bias off the surface, so tMin must not add a second one and start the
    // test twice as far away, which leaks light through carpets, glass and slabs.
    float3 origin = surfacePosition + normal * bias;
    float tMax = max(distance - bias, bias);
    Ray ray = makeRay(origin, direction, 0.0, tMax);
    options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE;
    return traceFilteredVisibility(traversal, atlas, blockMaterials, ray, options);
}

// Shadow ray for a receiver whose medium state is known: an underwater
// receiver starts inside the body, so the column between it and the light is
// attenuated instead of transparent.
SurfaceVisibility traceShadowVisibilityMedium<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        float3 surfacePosition, float3 normal, float3 direction,
        float distance, float bias, MaterialSamplingOptions options, WaterMedium medium) {
    // One offset only: the origin is already a bias off the surface, so tMin must not add a second one and start the
    // test twice as far away, which leaks light through carpets, glass and slabs.
    float3 origin = surfacePosition + normal * bias;
    float tMax = max(distance - bias, bias);
    Ray ray = makeRay(origin, direction, 0.0, tMax);
    options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE;
    return traceFilteredVisibilityMedium(traversal, atlas, blockMaterials, ray, options, medium);
}

// Shadow beam for an underwater receiver lit by a light that came through the
// surface.  The straight sight line to that light is not the path the beam took:
// it bent on crossing the plane, so the column is walked along the refracted
// direction and whatever is left of the distance along the air direction, from
// the point where the beam crosses.  Both legs keep the filtered walk, so a cutout
// hole is still skipped and a stained-glass pane still tints.
//
// The face itself is traced by neither leg - the column stops a bias below it and
// the sky starts a bias above it - because the crossing is what `WaterBeam` names,
// and a ray that hit its own water sheet would pay the Fresnel twice.
SurfaceVisibility traceShadowBeam<T : ISceneTracer>(T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        float3 surfacePosition, float3 normal, float3 lightDirection,
        float distance, float bias, MaterialSamplingOptions options,
        WaterMedium medium, WaterBeam beam) {
    float3 origin = surfacePosition + normal * bias;
    // Both legs of the beam are shadow rays: a leaf filters the column and the sky alike.
    options.flags |= MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE;
    SurfaceVisibility visibility = traceFilteredVisibilityMedium(traversal, atlas, blockMaterials,
        makeRay(origin, -beam.direction, 0.0, max(beam.column - bias, bias)), options, medium);
    visibility.flags |= SURFACE_VISIBILITY_FLAG_REFRACTED;
    if (!surfaceVisibilityProven(visibility)) {
        // Something opaque sits in the column between the receiver and the
        // surface.  The sky leg cannot reach this point any more than the water
        // leg could, so its answer would only hide which blocker mattered.
        return visibility;
    }

    // Lifted along the beam rather than along the face normal: the crossing is a
    // plane the receiver's own normal says nothing about, and a starting point
    // that stays inside the sheet re-hits it at grazing sun angles.
    float3 entry = beam.entry + lightDirection * (bias / max(lightDirection.y, 0.1));
    SurfaceVisibility air = traceFilteredVisibility(traversal, atlas, blockMaterials,
        makeRay(entry, lightDirection, 0.0, max(distance - beam.column, bias)), options);
    visibility.layers += air.layers;
    visibility.flags |= air.flags;
    // The sky leg has to prove itself the way the column did: an overflow there means the walk stopped counting
    // filters, not that the sky is open, and the caller only trusts a proven sight line.
    bool skyClear = surfaceVisibilityProven(air);
    visibility.result.visible = skyClear;
    visibility.result.blocker = air.result.blocker;
    visibility.result.distance = distance;
    visibility.result.transmittance = visibility.result.transmittance
        * air.result.transmittance * waterBeamTransmittance(lightDirection.y);
    return visibility;
}
