// Camera-weighted primary-lobe signals. This module never changes the integrand.
public module denoiser_signal;

import "contracts/math.slang";
import "shading/bsdf.slang";

static const float PATH_SIGNAL_MISS_DISTANCE = 65504.0;

struct PathDenoiserSignal {
    float3 diffuse;
    float3 specular;
    float diffuseHitDistance;
    float specularHitDistance;
    bool bypass;
};

PathDenoiserSignal emptyPathDenoiserSignal() {
    PathDenoiserSignal signal = {};
    return signal;
}

PathDenoiserSignal bypassedPathDenoiserSignal(float reflectionDistance = 0.0) {
    PathDenoiserSignal signal = emptyPathDenoiserSignal();
    signal.bypass = true;
    // Bypassing an uncertified NRD radiance estimator does not erase independently measured RR geometry.
    signal.specularHitDistance = isfinite(reflectionDistance)
        ? clamp(reflectionDistance, 0.0, PATH_SIGNAL_MISS_DISTANCE) : 0.0;
    return signal;
}

// One selected primary lobe owns the entire suffix, including later specular/diffuse events.
// Distance is the first secondary edge only; holes/filters do not create a vertex.
struct PathDenoiserContinuation {
    uint lobe;
    float3 radianceBefore;
    float3 primaryPosition;
    float hitDistance;
    bool pending;
};

PathDenoiserContinuation emptyPathDenoiserContinuation() {
    PathDenoiserContinuation continuation = {};
    return continuation;
}

void pathDenoiserBegin(inout PathDenoiserContinuation continuation, uint lobe,
        float3 radianceBefore, float3 primaryPosition) {
    continuation.lobe = lobe;
    if (lobe != BSDF_LOBE_DIFFUSE && lobe != BSDF_LOBE_SPECULAR) return;
    continuation.radianceBefore = radianceBefore;
    continuation.primaryPosition = primaryPosition;
    continuation.pending = true;
}

void pathDenoiserHit(inout PathDenoiserContinuation continuation, float3 position) {
    if (!continuation.pending) return;
    continuation.hitDistance = length(position - continuation.primaryPosition);
    continuation.pending = false;
}

void pathDenoiserMiss(inout PathDenoiserContinuation continuation) {
    if (!continuation.pending) return;
    continuation.hitDistance = PATH_SIGNAL_MISS_DISTANCE;
    continuation.pending = false;
}

// RR needs geometry even though it consumes total HDR rather than NRD's split radiance.
// Zero means no observed reflection: diffuse/transmission, a stopped path, or an invalid sample.
// The distance starts at the primary surface, never at the camera or the bias-shifted ray origin.
float pathDenoiserReflectionDistance(PathDenoiserContinuation continuation, bool invalidSample) {
    if (invalidSample || continuation.pending || continuation.lobe != BSDF_LOBE_SPECULAR) return 0.0;
    return isfinite(continuation.hitDistance) ? clamp(continuation.hitDistance, 0.0, PATH_SIGNAL_MISS_DISTANCE) : 0.0;
}

PathDenoiserSignal pathDenoiserResolve(PathDenoiserContinuation continuation,
        float3 radianceBeforeClamp, float3 radiance, bool bypass) {
    if (bypass || continuation.lobe == BSDF_LOBE_TRANSMISSION) return bypassedPathDenoiserSignal();
    PathDenoiserSignal signal = emptyPathDenoiserSignal();
    if (continuation.pending || continuation.lobe == BSDF_LOBE_NONE) return signal;
    // Apply the integrand's component clamp to every contribution with the SAME scale.
    // Independent signal clamps would brighten their recombination.
    float3 scale = float3(
        radianceBeforeClamp.r > 0.0 ? radiance.r / radianceBeforeClamp.r : 0.0,
        radianceBeforeClamp.g > 0.0 ? radiance.g / radianceBeforeClamp.g : 0.0,
        radianceBeforeClamp.b > 0.0 ? radiance.b / radianceBeforeClamp.b : 0.0);
    float3 suffix = max(radianceBeforeClamp - continuation.radianceBefore, float3(0.0)) * scale;
    if (continuation.lobe == BSDF_LOBE_DIFFUSE) {
        signal.diffuse = suffix;
        signal.diffuseHitDistance = continuation.hitDistance;
    } else {
        signal.specular = suffix;
        signal.specularHitDistance = continuation.hitDistance;
    }
    return signal;
}

float3 pathDenoiserBypass(float3 radiance, PathDenoiserSignal signal) {
    return max(radiance - signal.diffuse - signal.specular, float3(0.0));
}
