// Environment (sky) sampling.
//
// The sky is the third light source after the sun/moon and emitters.  It is not
// a discrete emitter, so it has its own interface instead of being forced into
// ReSTIR DI's reservoir.  A path tracer uses it on a miss; a reflection pass can
// use it for a roughness-dependent environment lobe.

public module sky_light;

import "contracts/frame.slang";
import "contracts/math.slang";
import "sampling.slang";

struct SkySample {
    bool   valid;
    float3 direction;
    float3 radiance;
    float  pdf;
};

interface IEnvironmentSampler {
    float3 radiance(float3 direction);
    SkySample sample(float3 normal, inout uint seed);
    float pdf(float3 normal, float3 direction);
};

// A two-colour gradient dome.  The host fills AtmosphereState from the current
// dimension, weather and time of day; the shader does not know biome names.
struct GradientSky : IEnvironmentSampler {
    AtmosphereState atmosphere;

    float3 radiance(float3 direction) {
        float3 d = safeNormalize(direction, float3(0.0, 1.0, 0.0));
        float t = saturate(d.y * 0.5 + 0.5);
        float3 zenith = atmosphere.zenithColor.rgb * max(atmosphere.zenithColor.w, 0.0);
        float3 horizon = atmosphere.horizonColor.rgb * max(atmosphere.horizonColor.w, 0.0);
        return lerp(horizon, zenith, t);
    }

    float pdf(float3 normal, float3 direction) {
        return max(dot(safeNormalize(normal, float3(0.0, 1.0, 0.0)),
                       safeNormalize(direction, float3(0.0, 1.0, 0.0))), 0.0) * INV_PI;
    }

    SkySample sample(float3 normal, inout uint seed) {
        SkySample result;
        result.valid = false;
        result.direction = float3(0.0, 1.0, 0.0);
        result.radiance = float3(0.0);
        result.pdf = 0.0;

        float3 n = safeNormalize(normal, float3(0.0, 1.0, 0.0));
        float3 direction = cosineAround(n, seed);
        float probability = max(dot(n, direction), 0.0) * INV_PI;
        if (probability <= 0.0) return result;

        result.valid = true;
        result.direction = direction;
        result.radiance = radiance(direction);
        result.pdf = probability;
        return result;
    }
};
