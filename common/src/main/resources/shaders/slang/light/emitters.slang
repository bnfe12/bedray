// Emitter sampling for ReSTIR DI and path tracing.
//
// An EmitterRecord is a sampling record, not a mesh record.  The host builds it
// from emissive faces; a block edit that does not change an emitter does not
// rebuild the list.  The sampler is uniform-area by default; an importance-
// sorted sampler can replace it without touching reservoir math.

public module emitters;

import "contracts/light.slang";
import "contracts/math.slang";
import "sampling.slang";

static const uint EMITTER_FLAG_TWO_SIDED = 1u << 0u;

struct EmitterRecord {
    float4 p0;        // xyz: quad center, w: area in blocks^2
    float4 e1;        // xyz: edge 1, w: flags
    float4 e2;        // xyz: edge 2, w: reserved
    float4 radiance;  // rgb: emitted radiance, w: intensity scale
    uint4  meta;      // x: lightIndex, y: materialId, z: source flags, w: reserved
};

struct EmitterList {
    StructuredBuffer<EmitterRecord> emitters;
    uint count;
};

float3 emitterNormal(EmitterRecord emitter) {
    float3 crossEdge = cross(emitter.e1.xyz, emitter.e2.xyz);
    float lengthCross = length(crossEdge);
    return lengthCross > 1.0e-6 ? crossEdge / lengthCross : float3(0.0, 1.0, 0.0);
}

bool emitterIsTwoSided(EmitterRecord emitter) {
    return (uint(emitter.e1.w + 0.5) & EMITTER_FLAG_TWO_SIDED) != 0u;
}

float3 emitterRadiance(EmitterRecord emitter) {
    return emitter.radiance.rgb * emitter.radiance.w;
}

float emitterArea(EmitterRecord emitter) {
    return max(emitter.p0.w, 1.0e-6);
}

float emitterCosine(EmitterRecord emitter, float3 direction) {
    float3 normal = emitterNormal(emitter);
    return emitterIsTwoSided(emitter)
        ? abs(dot(normal, direction))
        : max(dot(normal, -direction), 0.0);
}

// Uniform-area emitter sampler.  Selection is uniform over the list; a
// production sampler may sort by importance, but the reservoir only needs a
// consistent sourcePdf.
struct UniformEmitterSampler : ILightSampler {
    EmitterList lights;
    float maxDistance;

    LightSample sample(float3 surfacePosition, float3 surfaceNormal, inout uint seed) {
        LightSample result = invalidLightSample();
        if (lights.count == 0u) return result;

        uint index = min(uint(rand01(seed) * float(lights.count)), lights.count - 1u);
        EmitterRecord emitter = lights.emitters[index];
        float2 uv = float2(rand01(seed), rand01(seed));
        float3 lightPosition = emitter.p0.xyz + emitter.e1.xyz * uv.x + emitter.e2.xyz * uv.y;

        float3 toLight = lightPosition - surfacePosition;
        float distanceToLight = length(toLight);
        if (distanceToLight <= 1.0e-5 || distanceToLight > maxDistance) return result;

        float3 direction = toLight / distanceToLight;
        float cosLight = emitterCosine(emitter, direction);
        float cosSurface = lightCosine(surfaceNormal, direction);
        if (cosLight <= 0.0 || cosSurface <= 0.0) return result;

        float3 radiance = emitterRadiance(emitter);
        float area = emitterArea(emitter);
        float selectionPdf = 1.0 / float(lights.count);
        // The contract defines sourcePdf in solid angle.  Uniform-area
        // selection therefore needs the area-to-solid-angle Jacobian.
        float solidAnglePdf = selectionPdf * distanceToLight * distanceToLight /
            max(area * cosLight, 1.0e-6);

        result.valid = true;
        result.kind = LightKind.Emitter;
        result.position = lightPosition;
        result.direction = direction;
        result.radiance = radiance;
        result.distance = distanceToLight;
        result.sourcePdf = solidAnglePdf;
        result.targetPdf = luminance(radiance) * cosLight * cosSurface /
            max(distanceToLight * distanceToLight, 1.0e-6);
        result.lightIndex = index;
        return result;
    }

    LightEvaluation evaluate(float3 surfacePosition, float3 surfaceNormal, LightSample sample) {
        LightEvaluation result = invalidLightEvaluation();
        if (sample.lightIndex >= lights.count) return result;

        EmitterRecord emitter = lights.emitters[sample.lightIndex];
        float3 toLight = sample.position - surfacePosition;
        float distanceToLight = length(toLight);
        if (distanceToLight <= 1.0e-5 || distanceToLight > maxDistance) return result;

        float3 direction = toLight / distanceToLight;
        float cosLight = emitterCosine(emitter, direction);
        float cosSurface = lightCosine(surfaceNormal, direction);
        if (cosLight <= 0.0 || cosSurface <= 0.0) return result;

        float3 radiance = emitterRadiance(emitter);
        float area = emitterArea(emitter);
        result.valid = true;
        result.radiance = radiance;
        result.sourcePdf = (1.0 / float(lights.count)) * distanceToLight * distanceToLight /
            max(area * cosLight, 1.0e-6);
        result.targetPdf = luminance(radiance) * cosLight * cosSurface /
            max(distanceToLight * distanceToLight, 1.0e-6);
        return result;
    }
};
