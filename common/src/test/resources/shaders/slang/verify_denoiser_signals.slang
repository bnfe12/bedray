// Actual production math, runnable with Slang's CPU target or as a one-thread compute dispatch.
// Each sink slot is one named regression verdict (1 = passed). No scene/GPU/vendor SDK is needed for CPU execution.
import "pathtrace/path_trace_entry.slang";
import "pathtrace/path_tracer.slang";
import "pathtrace/denoiser_signal.slang";
import "denoise/nrd_pack.slang";
import "denoise/material_factors.slang";
import "shading/bsdf.slang";
import "post/output_resolve.slang";
import "light/emitter_selection.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<uint> sink;
[[vk::binding(1, 0)]] StructuredBuffer<uint4> emitterFixture;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> globalEmitterFixture;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> emptyEmitterFixture;
[[vk::binding(4, 0)]] StructuredBuffer<uint4> singleEmitterFixture;

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

    // RR geometry is independent of NRD radiance demodulation and camera depth (900 above).
    sink[16] = pathDenoiserReflectionDistance(specular, false) == 3.0
        && pathDenoiserReflectionDistance(diffuse, false) == 0.0
        && pathDenoiserReflectionDistance(transmission, false) == 0.0;
    sink[17] = pathDenoiserReflectionDistance(escaped, false) == 65504.0
        && pathDenoiserReflectionDistance(specular, true) == 0.0;
    PathDenoiserContinuation stopped = emptyPathDenoiserContinuation();
    pathDenoiserBegin(stopped, BSDF_LOBE_SPECULAR, float3(0.0), float3(1.0));
    sink[18] = pathDenoiserReflectionDistance(stopped, false) == 0.0;
    sink[19] = rr.reflectionHitDistance == 8.0 && near3(rr.diffuse.rgb, float3(0.6));
    pc.denoiser = 2u;
    input.viewZ = 1.0e6;
    NrdPackedSample ignoredRr = nrdPackSample(input, pc);
    sink[20] = ignoredRr.reflectionHitDistance == 0.0;
    float4 forward = float4(0.0,0.0,1.0,1.0), right = float4(1.0,0.0,0.0,1.0), up = float4(0.0,1.0,0.0,0.0);
    sink[21] = abs(resolvedRayDistance(5.0, float2(0.0), float2(1.0,0.0), forward, right, up) - 5.0 * sqrt(2.0)) < 1.0e-5;
    sink[22] = abs(resolvedRayDistance(5.0, float2(1.0,0.0), float2(0.0), forward, right, up) - 5.0 / sqrt(2.0)) < 1.0e-5;
    sink[23] = resolvedRayDistance(1.0e6, float2(1.0), float2(0.0), forward, right, up) == 1.0e6;
    FrameConstants frame = {};
    frame.camera.jitter = float4(0.125, -0.375, 1.0/8.0, 1.0/4.0);
    sink[24] = all(pathTracePrimaryJitter(frame, int2(1,2), 0u, 4u, PATH_TRACE_OPTION_GBUFFER) == frame.camera.jitter.xy)
        && all(pathTracePrimaryJitter(frame, int2(1,2), 3u, 4u, PATH_TRACE_OPTION_GBUFFER) == frame.camera.jitter.xy);
    sink[25] = resolvedRayDistance(asfloat(0x7fc00000u), float2(0.0), float2(0.0), forward, right, up) == 1.0e6;
    sink[26] = blockEmitterCount(emitterFixture) == 2u
        && blockEmitterId(emitterFixture, 0u) == 17u && blockEmitterId(emitterFixture, 1u) == 9u;
    sink[27] = blockEmitterFind(emitterFixture, 17u) == 0u && blockEmitterFind(emitterFixture, 9u) == 1u
        && blockEmitterFind(emitterFixture, 8u) == 0xFFFFFFFFu && blockEmitterFind(emitterFixture, 0u) == 0xFFFFFFFFu;
    BlockEmitterRecord quad = {};
    quad.edge1 = float4(2.0,0.0,0.0,0.0); quad.edge2 = float4(0.0,2.0,0.0,0.0);
    float2 uv;
    sink[28] = blockEmitterCoordinates(float3(0.5,1.5,0.0), quad, float3(0.0), uv)
        && all(abs(uv - float2(0.25,0.75)) < 1.0e-5);
    sink[29] = !blockEmitterCoordinates(float3(0.5,1.5,1.0), quad, float3(0.0), uv)
        && !blockEmitterCoordinates(float3(2.1,1.5,0.0), quad, float3(0.0), uv);
    quad.edge2 = float4(1.0,2.0,0.0,0.0);
    sink[30] = blockEmitterCoordinates(float3(1.25,1.5,0.0), quad, float3(0.0), uv)
        && all(abs(uv - float2(0.25,0.75)) < 1.0e-5);
    quad.edge2 = quad.edge1;
    sink[31] = !blockEmitterCoordinates(float3(0.5,0.0,0.0), quad, float3(0.0), uv);
    float3 nearLeft = float3(0.0,0.0,2.0), nearRight = float3(11.0,0.0,2.0);
    float q0 = blockEmitterSelectionProbability(emitterFixture, 0u, nearLeft);
    float q1 = blockEmitterSelectionProbability(emitterFixture, 1u, nearLeft);
    sink[32] = abs(q0 + q1 - 1.0) < 1.0e-6
        && abs(q0 - (0.125 * sqrt(0.5) + 0.875 * (51.0/52.0))) < 1.0e-6;
    sink[33] = q0 > 0.9 && blockEmitterSelectionProbability(emitterFixture, 1u, nearRight) > 0.85;
    uint leftCount = 0u;
    bool pdfAgrees = true;
    float estimate = 0.0;
    for (uint draw = 0u; draw < 8192u; ++draw) {
        BlockEmitterSelection s = blockEmitterSelect(emitterFixture, nearLeft, (float(draw) + 0.5) / 8192.0);
        if (s.index == 0u) leftCount++;
        pdfAgrees = pdfAgrees && s.index < 2u && abs(s.probability - (s.index == 0u ? q0 : q1)) < 1.0e-6;
        estimate += (s.index == 0u ? 3.0 : 7.0) / s.probability;
    }
    sink[34] = pdfAgrees && abs(float(leftCount)/8192.0 - q0) < 2.0/8192.0;
    float far0 = blockEmitterSelectionProbability(emitterFixture, 0u, float3(1.0e30));
    float far1 = blockEmitterSelectionProbability(emitterFixture, 1u, float3(1.0e30));
    sink[35] = far0 > 0.0 && far1 > 0.0 && abs(far0+far1-1.0) < 1.0e-6;
    bool globalMatches = true;
    for (uint draw = 0u; draw < 128u; ++draw) {
        float u = (float(draw) + 0.5)/128.0;
        BlockEmitterSelection s = blockEmitterSelect(globalEmitterFixture, nearLeft, u);
        uint index = min(uint(u*u*2.0), 1u);
        globalMatches = globalMatches && s.index == index
            && abs(s.probability - blockEmitterIndexProbability(index,2u)) < 1.0e-6;
    }
    sink[36] = globalMatches;
    sink[37] = abs(far0 - (0.125*sqrt(0.5)+0.875*0.5)) < 1.0e-6;
    sink[38] = blockEmitterCount(emptyEmitterFixture) == 0u
        && blockEmitterSelect(emptyEmitterFixture, nearLeft, 0.5).probability == 0.0
        && blockEmitterSelectionProbability(emitterFixture,2u,nearLeft) == 0.0;
    sink[39] = abs(estimate/8192.0 - 10.0) < 0.02;
    BlockEmitterSelection one = blockEmitterSelect(singleEmitterFixture,nearLeft,0.75);
    sink[40] = one.index == 0u && one.probability == 1.0
        && blockEmitterSelectionProbability(singleEmitterFixture,0u,nearRight) == 1.0;
}
