// Typed Radiance Cascades trace: first-surface shading.
//
// The walk hands this module one decoded surface and the ray's view direction.
// It returns emission plus at most one next-event-estimation (NEE) sample.
// Traversal and light sampling are generic: the module never owns a binding,
// a backend or a seed policy.
//
// Contract
// --------
//   * Emission is always sampled; a shadow-overflowed NEE walk is reported as
//     shadowTruncated, not as zero transmission, so the caller can mark the
//     segment TRUNCATED instead of storing a half-known direct term.
//   * A malformed sample (non-finite or negative radiance, non-finite or
//     non-positive light PDF, non-unit direction/filter) returns valid = false.
//     It is never turned into trusted black.
//   * The caller applies the segment's own transmittance and status flags;
//     this module knows nothing about near/far interval ownership.

public module rc_trace_shade;

__exported import "radiance_cascades/rc_trace_options.slang";
import "contracts/hit.slang";
__exported import "contracts/light.slang";
import "contracts/material.slang";
import "contracts/math.slang";
import "shading/bsdf.slang";
__exported import "surface/surface.slang";
import "surface/visibility.slang";
__exported import "traversal/traversal.slang";

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
                        // sourcePdf was validated positive above; a floor here
                        // would only hide a malformed sampler behind a biased
                        // estimate.  The finite check below still rejects an
                        // overflowed division.
                        float3 contribution = bsdf * light.radiance * transmittance /
                            light.sourcePdf;
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
