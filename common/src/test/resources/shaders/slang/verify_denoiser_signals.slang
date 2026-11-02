// Actual production math, runnable with Slang's CPU target or as a one-thread compute dispatch.
// Each sink slot is one named regression verdict (1 = passed). No scene/GPU/vendor SDK is needed for CPU execution.
import "pathtrace/path_trace_entry.slang";
import "pathtrace/path_tracer.slang";
import "pathtrace/denoiser_signal.slang";
import "denoise/nrd_pack.slang";
import "denoise/material_factors.slang";
import "shading/bsdf.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<uint> sink;

bool near3(float3 actual, float3 expected) { return all(abs(actual - expected) < 1.0e-5); }

PathTraceResult sampleWithSignal(float3 raw, PathDenoiserSignal signal) {
    PathTraceResult sample = emptyPathTraceResult();
    sample.radiance = raw;
    sample.primary.valid = true;
    sample.primary.normal = float3(0.0, 0.0, 1.0);
    sample.firstHitDistance = 900.0;
    sample.denoiser = signal;
    return sample;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    PathDenoiserContinuation diffuse = emptyPathDenoiserContinuation();
    pathDenoiserBegin(diffuse, BSDF_LOBE_DIFFUSE, float3(2.0), float3(2.0, 0.0, 0.0));
    pathDenoiserHit(diffuse, float3(5.0, 0.0, 0.0));
    pathDenoiserHit(diffuse, float3(100.0, 0.0, 0.0)); // later bounces never replace the first edge
    PathDenoiserSignal diff = pathDenoiserResolve(diffuse, float3(9.0), float3(9.0), false);
    sink[0] = near3(diff.diffuse, float3(7.0)) && near3(diff.specular, float3(0.0))
        && diff.diffuseHitDistance == 3.0 && diff.specularHitDistance == 0.0;

    PathDenoiserContinuation specular = emptyPathDenoiserContinuation();
    pathDenoiserBegin(specular, BSDF_LOBE_SPECULAR, float3(2.0), float3(2.0, 0.0, 0.0));
    pathDenoiserHit(specular, float3(5.0, 0.0, 0.0));
    PathDenoiserSignal spec = pathDenoiserResolve(specular, float3(9.0), float3(9.0), false);
    sink[1] = near3(spec.specular, float3(7.0)) && near3(spec.diffuse, float3(0.0))
        && spec.specularHitDistance == 3.0 && spec.diffuseHitDistance == 0.0;

    PathDenoiserContinuation escaped = emptyPathDenoiserContinuation();
    pathDenoiserBegin(escaped, BSDF_LOBE_SPECULAR, float3(1.0), float3(0.0));
    pathDenoiserMiss(escaped);
    PathDenoiserSignal sky = pathDenoiserResolve(escaped, float3(4.0), float3(4.0), false);
    sink[2] = sky.specularHitDistance == 65504.0 && near3(sky.specular, float3(3.0));

    PathDenoiserContinuation transmission = emptyPathDenoiserContinuation();
    pathDenoiserBegin(transmission, BSDF_LOBE_TRANSMISSION, float3(2.0), float3(0.0));
    PathDenoiserSignal glass = pathDenoiserResolve(transmission, float3(9.0), float3(9.0), false);
    sink[3] = glass.bypass && near3(pathDenoiserBypass(float3(9.0), glass), float3(9.0));
    PathDenoiserSignal water = pathDenoiserResolve(diffuse, float3(9.0), float3(9.0), true);
    sink[4] = water.bypass && near3(water.diffuse + water.specular, float3(0.0));

    PathDenoiserSignal limited = pathDenoiserResolve(diffuse, float3(9.0, 12.0, 3.0), float3(3.0), false);
    sink[5] = near3(limited.diffuse, float3(7.0 / 3.0, 2.5, 1.0))
        && near3(pathDenoiserBypass(float3(3.0), limited) + limited.diffuse, float3(3.0));

    PathTraceMean mean = emptyPathTraceMean();
    PathDenoiserSignal first = diff;
    first.diffuseHitDistance = 2.0;
    accumulatePathTraceSample(mean, sampleWithSignal(float3(9.0), first));
    first.diffuseHitDistance = 6.0;
    accumulatePathTraceSample(mean, sampleWithSignal(float3(9.0), first));
    first = spec;
    first.specularHitDistance = 12.0;
    accumulatePathTraceSample(mean, sampleWithSignal(float3(9.0), first));
    first.specularHitDistance = 3.0;
    accumulatePathTraceSample(mean, sampleWithSignal(float3(9.0), first));
    PathTraceResult empty = emptyPathTraceResult();
    empty.flags = PATH_TRACE_FLAG_ENVIRONMENT;
    empty.radiance = float3(4.0);
    accumulatePathTraceSample(mean, empty); // sky contributes zero lobe radiance, but still counts in the mean
    bool trusted;
    PathTraceResult averaged = pathTraceMeanResult(mean, trusted);
    sink[6] = trusted && near3(averaged.denoiser.diffuse, float3(14.0 / 5.0))
        && near3(averaged.denoiser.specular, float3(14.0 / 5.0));
    sink[7] = averaged.denoiser.diffuseHitDistance == 4.0
        && averaged.denoiser.specularHitDistance == 3.0 && averaged.firstHitDistance == 900.0;

    PathTraceResult rejected = sampleWithSignal(float3(1000.0), diff);
    rejected.flags = PATH_TRACE_FLAG_INVALID_SAMPLE;
    accumulatePathTraceSample(mean, rejected);
    PathTraceResult afterRejected = pathTraceMeanResult(mean, trusted);
    sink[8] = near3(afterRejected.radiance, averaged.radiance)
        && near3(afterRejected.denoiser.diffuse, averaged.denoiser.diffuse);
    accumulatePathTraceSample(mean, sampleWithSignal(float3(9.0), glass));
    PathTraceResult unsupported = pathTraceMeanResult(mean, trusted);
    sink[9] = unsupported.denoiser.bypass && near3(unsupported.denoiser.diffuse + unsupported.denoiser.specular,
        float3(0.0)) && near3(pathDenoiserBypass(unsupported.radiance, unsupported.denoiser), unsupported.radiance);

    NrdPackInput input = {};
    input.viewZ = -10.0;
    input.normalRoughness = float4(0.0, 0.0, 1.0, 0.4);
    input.diffuse = float4(0.2, 0.2, 0.2, 3.0);
    input.specular = float4(0.1, 0.1, 0.1, 8.0);
    input.albedo = float3(0.0, 0.1, 0.9);
    input.specularAlbedo = float3(0.04, 0.2, 0.8);
    input.materialRoughness = 0.4;
    input.materialId = 1.0;
    input.direct = float3(0.3);
    NrdPackPush pc = {};
    pc.normalEncoding = 2u;
    pc.roughnessEncoding = 1u;
    pc.hitDistA = 3.0;
    pc.hitDistB = 0.1;
    pc.hitDistC = 20.0;
    pc.denoisingRange = 1000.0;
    pc.exposure = 1.0;
    float4 material = float4(input.specularAlbedo, input.materialRoughness);
    NrdPackedSample reblur = nrdPackSample(input, pc);
    float3 reblurComposed = nrdComposeColor(reblur.diffuse.rgb, reblur.specular.rgb, input.albedo,
        material, float4(input.direct, 0.0), pc);
    sink[10] = near3(reblurComposed, float3(0.6)) && abs(reblur.normalRoughness.a - 1.0 / 3.0) < 1.0e-6;
    pc.denoiser = 1u;
    NrdPackedSample relax = nrdPackSample(input, pc);
    sink[11] = near3(nrdComposeColor(relax.diffuse.rgb, relax.specular.rgb, input.albedo,
        material, float4(input.direct, 0.0), pc), float3(0.6))
        && relax.diffuse.a == 3.0 && relax.specular.a == 8.0;
    pc.denoiser = 2u;
    NrdPackedSample rr = nrdPackSample(input, pc);
    sink[12] = near3(rr.diffuse.rgb, float3(0.6)) && near3(rr.specular.rgb, input.specularAlbedo);
    pc.denoiser = 0u;
    sink[13] = near3(nrdComposeColor(float3(100.0), float3(100.0), input.albedo, material,
        float4(0.4, 0.4, 0.4, 1.0), pc), float3(0.4)); // bypass rejects reconstructed neighbor energy

    float3 roughNormal = denoiserEnvironmentReflectance(float3(0.04), 1.0, 1.0);
    float3 roughGrazing = denoiserEnvironmentReflectance(float3(0.04), 0.05, 1.0);
    sink[14] = all(roughNormal > 0.0) && all(roughGrazing > roughNormal);
    MaterialSample metal = {};
    metal.metallic = 1.0;
    sink[15] = near3(bsdfReflectionAlbedo(metal), float3(0.0));
}
