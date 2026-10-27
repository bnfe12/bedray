// Light-sampling contract for Bedray 2.0.
//
// A light sampler is independent of the reservoir and of the traversal backend.
// ReSTIR DI, path tracing and Radiance Cascades can therefore share sun, moon,
// sky and emitter samplers without knowing how a shadow ray is traced.

public module light_contract;

import "contracts/math.slang";

enum LightKind : uint {
    Sun         = 0u,
    Moon        = 1u,
    Sky         = 2u,
    Emitter     = 3u,
    Environment = 4u,
};

// `targetPdf` is p-hat: the unshadowed integrand at the receiver that produced
// the sample.  `sourcePdf` is the sampler density in solid angle.  Keeping both
// makes temporal/spatial reuse a pure rescaling operation.
struct LightSample {
    bool      valid;
    LightKind kind;
    float3    position;      // emitter position; for directional lights p + dir * far
    float3    direction;     // surface -> light, normalized
    float3    radiance;      // emitted radiance, not cosine-weighted
    float     distance;
    float     targetPdf;
    float     sourcePdf;
    uint      lightIndex;
    uint      flags;
};

struct LightEvaluation {
    bool   valid;
    float3 radiance;
    float  targetPdf;
    float  sourcePdf;
};

// The surface a light is sampled *for*.  Position and normal are what a sampler
// can see today; `emission` is the one piece of receiver state it cannot infer,
// and an emitter sampler needs it: a face that is itself a light must not be lit
// by its own quad, or a torch glows twice (once as `emitted`, once as NEE).
struct LightReceiver {
    float3 position;
    float3 normal;      // shading normal, already flipped to the ray side
    float  emission;    // the receiver's own emissive mask; >= 0.5 is a light face
};

LightReceiver lightReceiver(float3 position, float3 normal, float emission) {
    LightReceiver receiver;
    receiver.position = position;
    receiver.normal = normal;
    receiver.emission = emission;
    return receiver;
}

interface ILightSampler {
    LightSample sample(LightReceiver receiver, inout uint seed);
    LightEvaluation evaluate(LightReceiver receiver, LightSample sample);
};

LightSample invalidLightSample() {
    LightSample result;
    result.valid = false;
    result.kind = LightKind.Environment;
    result.position = float3(0.0);
    result.direction = float3(0.0, 1.0, 0.0);
    result.radiance = float3(0.0);
    result.distance = 0.0;
    result.targetPdf = 0.0;
    result.sourcePdf = 0.0;
    result.lightIndex = 0u;
    result.flags = 0u;
    return result;
}

LightEvaluation invalidLightEvaluation() {
    LightEvaluation result;
    result.valid = false;
    result.radiance = float3(0.0);
    result.targetPdf = 0.0;
    result.sourcePdf = 0.0;
    return result;
}

float3 lightDirectionFromPosition(float3 surfacePosition, float3 lightPosition) {
    return safeNormalize(lightPosition - surfacePosition, float3(0.0, 1.0, 0.0));
}

float lightCosine(float3 surfaceNormal, float3 direction) {
    return max(dot(surfaceNormal, direction), 0.0);
}
