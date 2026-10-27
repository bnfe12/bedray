// ReSTIR DI reservoir storage and update (Bitterli et al. 2021).
//
// This module owns the reservoir math only.  Light selection and target-PDF
// evaluation live behind ILightSampler, so the same reservoir code works for
// block emitters, entity emitters, the sun and the moon.  Visibility is queried
// through ITraversal; the reservoir never knows which backend produced the
// shadow ray.
//
// Temporal and spatial reuse both use the same merge primitive.  The ratio cap
// is applied to the receiver-change term, which is the firefly source in an
// unclamped implementation.

public module restir_di;

import "contracts/hit.slang";
import "contracts/light.slang";
import "sampling.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
import "traversal/traversal.slang";

static const float RESTIR_DI_M_CAP = 20.0;
static const float RESTIR_DI_TEMPORAL_RATIO_CAP = 8.0;
static const float RESTIR_DI_SPATIAL_RATIO_CAP = 5.0;
static const float RESTIR_DI_OCCLUSION_DECAY = 0.25;
static const float RESTIR_DI_MIN_PDF = 1.0e-8;

// 48-byte GPU record.  `sample.w` is the target PDF at the receiver that wrote
// the record, which is exactly what a temporal/spatial merge must rescale.
struct DIReservoir {
    float4 sample;   // xyz: light position, w: targetPdf at the writing receiver
    float4 stats;    // x: sample count M, y: weight sum, z: confidence, w: flags
    uint4  source;   // x: lightIndex, y: age, z: LightKind, w: sample flags
};

struct DIReservoirResult {
    bool   valid;
    float3 position;
    float3 direction;
    float  distance;
    float  weight;
    float  targetPdf;
    float3 transmittance;
    uint   lightIndex;
    LightKind kind;
};

DIReservoir emptyReservoir() {
    DIReservoir reservoir;
    reservoir.sample = float4(0.0);
    reservoir.stats = float4(0.0);
    reservoir.source = uint4(0u);
    return reservoir;
}

// Reconstructs the light sample stored in a reservoir at a new receiver.  For
// directional lights the stored position is far away, so the direction is
// accurate to the usual sun-distance epsilon.
LightSample reservoirLightSample(DIReservoir reservoir, float3 surfacePosition) {
    LightSample sample = invalidLightSample();
    if (reservoir.stats.x <= 0.0 || reservoir.sample.w <= 0.0) return sample;

    float3 toLight = reservoir.sample.xyz - surfacePosition;
    float distanceToLight = length(toLight);
    if (distanceToLight <= 1.0e-5) return sample;

    sample.valid = true;
    sample.kind = LightKind(reservoir.source.z);
    sample.position = reservoir.sample.xyz;
    sample.direction = toLight / distanceToLight;
    sample.distance = distanceToLight;
    sample.targetPdf = reservoir.sample.w;
    sample.lightIndex = reservoir.source.x;
    sample.flags = reservoir.source.w;
    return sample;
}

// Weighted reservoir sampling of one candidate.  A candidate with a zero
// target PDF contributes nothing and must not advance the sample count.
void reservoirAddSample(inout DIReservoir reservoir, LightSample candidate, inout uint seed) {
    if (!candidate.valid || candidate.targetPdf <= 0.0 || candidate.sourcePdf <= 0.0) {
        return;
    }

    float weight = candidate.targetPdf / max(candidate.sourcePdf, RESTIR_DI_MIN_PDF);
    if (weight <= 0.0) return;

    reservoir.stats.x += 1.0;
    reservoir.stats.y += weight;

    if (rand01(seed) * reservoir.stats.y < weight) {
        reservoir.sample = float4(candidate.position, candidate.targetPdf);
        reservoir.source = uint4(candidate.lightIndex, 0u, uint(candidate.kind), candidate.flags);
    }
}

// Merges a reservoir that was produced at another receiver.  `incomingTargetPdf`
// must be evaluated at the *current* receiver; the stored PDF in
// `incoming.sample.w` is the one from the original receiver.
DIReservoir mergeReservoirs(DIReservoir current, DIReservoir incoming,
        float incomingTargetPdf, float ratioCap, float mCap, inout uint seed) {
    float incomingM = min(incoming.stats.x, mCap);
    if (incomingM <= 0.0) return current;

    float ratio = clamp(incomingTargetPdf / max(incoming.sample.w, RESTIR_DI_MIN_PDF),
                        0.0, ratioCap);
    float incomingWeight = incoming.stats.y * (incomingM / max(incoming.stats.x, 1.0)) * ratio;
    if (incomingWeight <= 0.0) return current;

    float totalWeight = current.stats.y + incomingWeight;
    float accept = incomingWeight / max(totalWeight, RESTIR_DI_MIN_PDF);
    if (rand01(seed) < accept) {
        current.sample = incoming.sample;
        current.sample.w = incomingTargetPdf;
        current.source = incoming.source;
    }

    current.stats.x += incomingM;
    current.stats.y = totalWeight;
    current.stats.z = max(current.stats.z, incoming.stats.z);
    return current;
}

// Final unbiased weight W = wSum / (M * p-hat(y)).
DIReservoirResult reservoirResolve(DIReservoir reservoir, float3 surfacePosition) {
    DIReservoirResult result;
    result.valid = false;
    result.position = float3(0.0);
    result.direction = float3(0.0);
    result.distance = 0.0;
    result.weight = 0.0;
    result.targetPdf = 0.0;
    result.transmittance = float3(1.0);
    result.lightIndex = 0u;
    result.kind = LightKind.Environment;

    if (reservoir.stats.x <= 0.0 || reservoir.stats.y <= 0.0 ||
        reservoir.sample.w <= 0.0) {
        return result;
    }

    float3 toLight = reservoir.sample.xyz - surfacePosition;
    float distanceToLight = length(toLight);
    if (distanceToLight <= 1.0e-5) return result;

    result.valid = true;
    result.position = reservoir.sample.xyz;
    result.direction = toLight / distanceToLight;
    result.distance = distanceToLight;
    result.targetPdf = reservoir.sample.w;
    result.weight = reservoir.stats.y /
        max(reservoir.stats.x * reservoir.sample.w, RESTIR_DI_MIN_PDF);
    result.lightIndex = reservoir.source.x;
    result.kind = LightKind(reservoir.source.z);
    return result;
}

// Initial candidate of a frame.  One candidate is enough for the temporal
// reservoir; the caller may add more with reservoirAddSample.
DIReservoir restirDiSample<S : ILightSampler>(S sampler,
        float3 surfacePosition, float3 surfaceNormal, inout uint seed) {
    DIReservoir reservoir = emptyReservoir();
    LightSample candidate = sampler.sample(lightReceiver(surfacePosition, surfaceNormal, 0.0), seed);
    if (!candidate.valid || candidate.targetPdf <= 0.0 || candidate.sourcePdf <= 0.0) {
        return reservoir;
    }

    reservoir.sample = float4(candidate.position, candidate.targetPdf);
    reservoir.stats = float4(1.0, candidate.targetPdf / candidate.sourcePdf, 1.0, 0.0);
    reservoir.source = uint4(candidate.lightIndex, 0u, uint(candidate.kind), candidate.flags);
    return reservoir;
}

// Re-evaluates a history sample at the current receiver before merging.
DIReservoir restirDiTemporal<S : ILightSampler>(S sampler,
        DIReservoir current, DIReservoir history,
        float3 surfacePosition, float3 surfaceNormal,
        float mCap, float ratioCap, inout uint seed) {
    if (history.stats.x <= 0.0) return current;

    LightSample historySample = reservoirLightSample(history, surfacePosition);
    LightEvaluation evaluation = sampler.evaluate(lightReceiver(surfacePosition, surfaceNormal, 0.0), historySample);
    if (!evaluation.valid || evaluation.targetPdf <= 0.0) return current;

    DIReservoir merged = mergeReservoirs(current, history, evaluation.targetPdf,
        ratioCap, mCap, seed);
    merged.source.y = min(merged.source.y + 1u, 0xFFFFu);
    return merged;
}

// Spatial reuse uses the same merge primitive but does not age the sample.
DIReservoir restirDiSpatial<S : ILightSampler>(S sampler,
        DIReservoir current, DIReservoir neighbor,
        float3 surfacePosition, float3 surfaceNormal,
        float mCap, float ratioCap, inout uint seed) {
    if (neighbor.stats.x <= 0.0) return current;

    LightSample neighborSample = reservoirLightSample(neighbor, surfacePosition);
    LightEvaluation evaluation = sampler.evaluate(lightReceiver(surfacePosition, surfaceNormal, 0.0), neighborSample);
    if (!evaluation.valid || evaluation.targetPdf <= 0.0) return current;

    return mergeReservoirs(current, neighbor, evaluation.targetPdf,
        ratioCap, mCap, seed);
}

// A broken light must not keep glowing through the reservoir for many frames.
DIReservoir restirDiOcclusionFeedback(DIReservoir reservoir, bool occluded) {
    if (occluded) reservoir.stats.y *= RESTIR_DI_OCCLUSION_DECAY;
    return reservoir;
}

// Resolves the reservoir and traces one shadow ray.  The bias keeps the ray
// origin outside the emitter's own surface.
DIReservoirResult restirDiResolveVisible<T : ITraversal>(DIReservoir reservoir,
        T traversal, float3 surfacePosition, float bias) {
    DIReservoirResult result = reservoirResolve(reservoir, surfacePosition);
    if (!result.valid) return result;

    float tMax = max(result.distance - bias, 0.001);
    Ray shadowRay = makeRay(surfacePosition + result.direction * bias,
        result.direction, 0.001, tMax);
    VisibilityResult visibility = traversal.traceVisibility(shadowRay);
    result.valid = visibility.visible;
    result.transmittance = visibility.visible ? float3(1.0) : float3(0.0);
    return result;
}

// Material-aware variant.  The raw form above is the cheap geometric query;
// this one applies cutout and translucent rules through the shared surface
// layer, so a reservoir cannot be rejected by a leaf card or a glass pane.
DIReservoirResult restirDiResolveVisibleFiltered<T : ITraversal>(DIReservoir reservoir,
        T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        float3 surfacePosition, float3 surfaceNormal, float bias,
        MaterialSamplingOptions materialOptions) {
    DIReservoirResult result = reservoirResolve(reservoir, surfacePosition);
    if (!result.valid) return result;

    SurfaceVisibility visibility = traceShadowVisibility(traversal, atlas,
        blockMaterials, surfacePosition, surfaceNormal, result.direction,
        result.distance, bias, materialOptions);
    result.valid = visibility.result.visible;
    result.transmittance = visibility.result.visible
        ? visibility.result.transmittance
        : float3(0.0);
    return result;
}
