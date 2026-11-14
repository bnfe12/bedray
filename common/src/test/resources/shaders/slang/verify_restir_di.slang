// Same-receiver RIS contracts using the production emitter adapter and merge.
import "contracts/material.slang";
import "light/block_emitters.slang";
import "pathtrace/path_tracer.slang";
import "restir/restir_di.slang";
import "restir/di_block_emitters.slang";
import "surface/surface.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<uint> sink;

DIReservoir freshBatch(float weightSum) {
    BlockEmitterWinner winner;
    winner.candidate = blockEmitterEmptyCandidate(0u);
    winner.candidate.point.position = float3(1.0, 0.0, 0.0);
    winner.candidate.point.recordIndex = 17u;
    winner.candidate.phat = 1.0;
    winner.weightSum = weightSum;
    winner.sampleCount = 4.0;
    winner.hasWinner = weightSum > 0.0;
    return blockEmitterDiInitialReservoir(winner);
}

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    DIReservoir zeroFresh = freshBatch(0.0);
    sink[0] = zeroFresh.stats.x == 4.0 && zeroFresh.stats.y == 0.0 && zeroFresh.sample.w == 0.0;

    DIReservoir history = freshBatch(4.0);
    uint seed = 17u;
    DIReservoir merged = mergeReservoirs(zeroFresh, history, 1.0,
        RESTIR_DI_TEMPORAL_RATIO_CAP, RESTIR_DI_M_CAP, seed);
    DIReservoirResult resolved = reservoirResolve(merged, float3(0.0));
    sink[1] = merged.stats.x == 8.0 && merged.stats.y == 4.0
        && resolved.valid && resolved.weight == 0.5;

    // Enumerate four independent draws with weight 4 at p=1/4, or 0 otherwise.
    // All 4^4 sequences are equally likely. History contributes a fixed W=4, M=4.
    float estimateSum = 0.0;
    for (uint sequence = 0u; sequence < 256u; ++sequence) {
        float weightSum = 0.0;
        for (uint draw = 0u; draw < 4u; ++draw) {
            if (((sequence >> (2u * draw)) & 3u) == 0u) weightSum += 4.0;
        }
        uint sequenceSeed = sequence + 1u;
        DIReservoir result = mergeReservoirs(freshBatch(weightSum), history, 1.0,
            RESTIR_DI_TEMPORAL_RATIO_CAP, RESTIR_DI_M_CAP, sequenceSeed);
        estimateSum += reservoirResolve(result, float3(0.0)).weight;
    }
    sink[2] = estimateSum == 256.0;

    DIReservoir occluded = history;
    occluded.stats.y = 0.0;
    DIReservoir current = freshBatch(8.0);
    uint before = seed;
    DIReservoir unchanged = mergeReservoirs(current, occluded, 1.0,
        RESTIR_DI_TEMPORAL_RATIO_CAP, RESTIR_DI_M_CAP, seed);
    sink[3] = all(unchanged.stats == current.stats) && all(unchanged.sample == current.sample)
        && all(unchanged.source == current.source) && seed == before;

    PathTraceOptions defaults = defaultPathTraceOptions();
    sink[4] = pathSeesEmission(2u, false, defaults.emitterNeeAvailable)
        && !pathSeesEmission(2u, false, true)
        && pathSeesEmission(1u, false, true)
        && pathSeesEmission(2u, true, true);

    float3 straight = float3(0, 0, 1);
    defaults.emitterListRange = 128.0;
    sink[5] = abs(pathEmitterResidual(defaults, 12.0, straight) - 0.4375) < 1e-6
        && abs(pathEmitterResidual(defaults, 18.0, straight) - 0.80859375) < 1e-6
        && pathEmitterResidual(defaults, 24.0, straight) == 1.0
        && pathEmitterResidual(defaults, 10.0 + 15.0, straight) == 1.0;
    defaults.emitterListRange = 8.0;
    sink[6] = abs(pathEmitterResidual(defaults, 4.0, straight) - 0.4375) < 1e-6
        && pathEmitterResidual(defaults, 9.0, straight) == 1.0;

    MaterialSample glass = {};
    glass.alphaMode = AlphaMode.Translucent;
    glass.albedo = float3(0.25, 1.0, 0.0625);
    glass.f0 = float3(0.04);
    glass.alpha = 0.5;
    glass.ior = 1.5;
    glass.thickness = 2.0;
    glass.transmission = 1.0;
    float3 thinTint = float3(0.5, 1.0, 0.25);
    sink[7] = all(abs(materialTransmittance(glass, straight, straight) - 0.96 * thinTint) < 1.0e-5)
        && all(abs(materialTransmittance(glass, straight, float3(0.8, 0, 0.6)) - 0.9501696 * thinTint) < 1.0e-5);
    glass.alphaMode = AlphaMode.Optical;
    sink[8] = all(abs(materialTransmittance(glass, straight, straight)
        - float3(0.06, 0.96, 0.00375)) < 1.0e-5);
}
