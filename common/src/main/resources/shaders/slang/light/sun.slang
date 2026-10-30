// Sun and moon disk sampling.
//
// The key light is a finite angular disk, not a delta light: sampling its solid
// angle is what makes soft shadows and a correct target PDF possible.  The
// sampler is shared by ReSTIR DI and path tracing.

public module sun_light;

import "contracts/frame.slang";
import "contracts/light.slang";
import "contracts/math.slang";
import "sampling.slang";

static const float SUN_MIN_ANGULAR_RADIUS = 1.0e-4;
static const float SUN_FAR_DISTANCE = 1.0e6;

float directionalSelectionProbability(float intensity, float otherIntensity,
        float requestedProbability) {
    if (intensity <= 0.0) return 0.0;
    if (otherIntensity <= 0.0) return 1.0;
    return clamp(requestedProbability, 0.0, 1.0);
}

float sunSolidAngle(float angularRadius) {
    float radius = max(angularRadius, SUN_MIN_ANGULAR_RADIUS);
    return TWO_PI * (1.0 - cos(radius));
}

struct SunMoonSampler : ILightSampler {
    SunState sun;
    float sunProbability;   // host-provided split between sun and moon
    // Draw the disc axis instead of a point of the disc.  The density stays the true disc's, so the flux the estimate
    // carries is unchanged and only the penumbra disappears: a hard-edged shadow from the same sun.
    bool hardShadows;

    LightSample sample(LightReceiver receiver, inout uint seed) {
        LightSample result = invalidLightSample();

        float sunIntensity = luminance(sun.color.rgb) * max(sun.color.w, 0.0);
        float moonIntensity = luminance(sun.moonColor.rgb) * max(sun.moonColor.w, 0.0);
        float sunPdf = directionalSelectionProbability(sunIntensity, moonIntensity, sunProbability);
        float moonPdf = directionalSelectionProbability(moonIntensity, sunIntensity, 1.0 - sunProbability);
        bool hasSun = sunPdf > 0.0;
        bool hasMoon = moonPdf > 0.0;
        if (!hasSun && !hasMoon) return result;

        bool pickSun = hasSun && (!hasMoon || rand01(seed) < sunPdf);
        LightKind kind = pickSun ? LightKind.Sun : LightKind.Moon;
        float3 axis = pickSun ? safeNormalize(sun.direction.xyz, float3(0.0, 1.0, 0.0))
                              : safeNormalize(sun.moonDirection.xyz, float3(0.0, -1.0, 0.0));
        float radius = max(pickSun ? sun.direction.w : sun.moonDirection.w,
                           SUN_MIN_ANGULAR_RADIUS);
        float3 radiance = pickSun
            ? sun.color.rgb * max(sun.color.w, 0.0)
            : sun.moonColor.rgb * max(sun.moonColor.w, 0.0);
        if (luminance(radiance) <= 0.0) return result;

        // A hard shadow samples the axis while keeping the disc's density: the same flux, no penumbra.
        float3 direction = hardShadows ? axis : coneAround(axis, radius, seed);
        // A leaf card is one-sided for reflection but its far side transmits the key light: a plant seen against the
        // sun has the shaded face toward the camera, and gating on that face would hand it no sample at all - the
        // legacy path lit exactly this case with its own transmission term.  The acceptance must stay two-sided, so it
        // is the absolute cosine: flipping the normal would answer only the back-lit half and starve a front-lit plant.
        float cosSurface = receiver.foliage ? abs(dot(receiver.normal, direction))
                                            : lightCosine(receiver.normal, direction);
        if (cosSurface <= 0.0) return result;

        float selectionPdf = pickSun ? sunPdf : moonPdf;
        float sourcePdf = selectionPdf / sunSolidAngle(radius);
        if (sourcePdf <= 0.0) return result;

        result.valid = true;
        result.kind = kind;
        result.position = receiver.position + direction * SUN_FAR_DISTANCE;
        result.direction = direction;
        result.radiance = radiance;
        result.distance = SUN_FAR_DISTANCE;
        result.sourcePdf = sourcePdf;
        result.targetPdf = luminance(radiance) * cosSurface;
        result.lightIndex = pickSun ? 0u : 1u;
        return result;
    }

    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        LightEvaluation result = invalidLightEvaluation();
        if (sample.kind != LightKind.Sun && sample.kind != LightKind.Moon) return result;

        bool isSun = sample.kind == LightKind.Sun;
        float3 axis = isSun ? safeNormalize(sun.direction.xyz, float3(0.0, 1.0, 0.0))
                            : safeNormalize(sun.moonDirection.xyz, float3(0.0, -1.0, 0.0));
        float radius = max(isSun ? sun.direction.w : sun.moonDirection.w,
                           SUN_MIN_ANGULAR_RADIUS);
        float3 direction = safeNormalize(sample.direction, axis);
        if (dot(direction, axis) < cos(radius)) return result;

        float3 radiance = isSun
            ? sun.color.rgb * max(sun.color.w, 0.0)
            : sun.moonColor.rgb * max(sun.moonColor.w, 0.0);
        // Same leaf rule as sample(): the far side of a plant transmits the key light, so the estimate is normalised
        // on whichever face reaches the sun, never only on the shaded one.
        float cosSurface = receiver.foliage ? abs(dot(receiver.normal, direction))
                                            : lightCosine(receiver.normal, direction);
        if (cosSurface <= 0.0 || luminance(radiance) <= 0.0) return result;

        float sunIntensity = luminance(sun.color.rgb) * max(sun.color.w, 0.0);
        float moonIntensity = luminance(sun.moonColor.rgb) * max(sun.moonColor.w, 0.0);
        float sunPdf = directionalSelectionProbability(sunIntensity, moonIntensity, sunProbability);
        float moonPdf = directionalSelectionProbability(moonIntensity, sunIntensity, 1.0 - sunProbability);
        float selectionPdf = isSun ? sunPdf : moonPdf;
        if (selectionPdf <= 0.0) return result;

        result.valid = true;
        result.radiance = radiance;
        result.sourcePdf = selectionPdf / sunSolidAngle(radius);
        result.targetPdf = luminance(radiance) * cosSurface;
        return result;
    }
};
