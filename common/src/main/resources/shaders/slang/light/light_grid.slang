// Bedray 2.0 — 3D Spatial Light Grid (Clustered Emitter Hierarchy).
//
// Implements Section 3.1 of the Architectural Manifesto:
//  Spatial Light Grid (3D cluster grid for block emitters).
//
// Solves emitter variance in scenes with thousands of active torches, redstone,
// lava pools, and glowing lichen. Instead of sampling globally from a flat array,
// rays in a region sample only emitters intersecting their local 3D cluster.

public module light_grid;

import "contracts/light.slang";
import "contracts/math.slang";
import "light/block_emitters.slang";
import "sampling.slang";

// ============================================================================
// 1. Cluster Grid Configuration & Data Structures
// ============================================================================

public struct LightGridParams {
    public float3 gridOrigin;       // Minimum world-space corner of the cluster grid
    public float  cellSize;         // World-space edge of one cubic cluster (e.g. 8.0 or 16.0 blocks)
    public int3   clusterDim;       // Number of clusters along (X, Y, Z), e.g. (32, 16, 32)
    public uint   maxLightsPerCell; // Soft/hard limit per cluster cell
};

// Compact 8-byte cell descriptor stored in an SSBO
public struct LightClusterCell {
    public uint offset; // Start offset into the clusterLightIndices buffer
    public uint count;  // Number of active emitter indices assigned to this cell
};

// Returns 1D flat cluster index from a world position
public int getClusterCellIndex(float3 worldPos, LightGridParams params) {
    float3 localPos = worldPos - params.gridOrigin;
    int3 coord = int3(floor(localPos / params.cellSize));
    if (any(coord < int3(0)) || any(coord >= params.clusterDim)) {
        return -1; // Outside clustered volume
    }
    return coord.x + params.clusterDim.x * (coord.y + params.clusterDim.y * coord.z);
}

// ============================================================================
// 2. Clustered Emitter Candidate Sampling (for ReSTIR DI & Path Tracing)
// ============================================================================

public struct ClusteredLightSampler {
    public StructuredBuffer<BlockEmitterRecord> records;
    public StructuredBuffer<LightClusterCell>   cells;
    public StructuredBuffer<uint>               cellIndices;
    public MaterialAtlas                        atlas;
    public LightGridParams                      gridParams;
    public float3                               cameraRelative;
    public float                                maxLightRadius;

    // Samples an emitter candidate for a receiver at worldPosition
    public BlockEmitterCandidate sampleClusterCandidate(
        LightReceiver receiver,
        float3 worldPosition,
        inout uint rngSeed,
        out float selectionPdf)
    {
        selectionPdf = 0.0;
        int cellIdx = getClusterCellIndex(worldPosition, gridParams);

        // Fallback: If point is outside grid or cell is empty, fallback to global list
        if (cellIdx < 0) {
            uint globalCount = blockEmitterCount(records);
            if (globalCount == 0u) {
                return blockEmitterEmptyCandidate(0u);
            }
            uint pick = blockEmitterPickIndex(globalCount, rngSeed);
            selectionPdf = blockEmitterIndexProbability(pick, globalCount);
            float2 quad = float2(rand01(rngSeed), rand01(rngSeed));
            return blockEmitterCandidate(records, atlas, pick, quad, receiver, cameraRelative, maxLightRadius);
        }

        LightClusterCell cell = cells[uint(cellIdx)];
        if (cell.count == 0u) {
            return blockEmitterEmptyCandidate(0u);
        }

        // Discrete uniform pick within the local cluster
        uint localPick = min(uint(rand01(rngSeed) * float(cell.count)), cell.count - 1u);
        uint emitterIndex = cellIndices[cell.offset + localPick];
        selectionPdf = 1.0 / float(cell.count);

        float2 quadUv = float2(rand01(rngSeed), rand01(rngSeed));
        return blockEmitterCandidate(records, atlas, emitterIndex, quadUv, receiver, cameraRelative, maxLightRadius);
    }

    // Advanced 2-candidate Resampled Importance Sampling (RIS) inside the local cluster:
    // Selects the highest-flux / nearest emitter with reduced variance
    public BlockEmitterCandidate sampleImportanceClusterCandidate(
        LightReceiver receiver,
        float3 worldPosition,
        inout uint rngSeed,
        out float effectiveWeight)
    {
        effectiveWeight = 0.0;
        int cellIdx = getClusterCellIndex(worldPosition, gridParams);
        if (cellIdx < 0) {
            float pdf;
            BlockEmitterCandidate c = sampleClusterCandidate(receiver, worldPosition, rngSeed, pdf);
            if (c.phat > 0.0 && pdf > 1.0e-7) {
                effectiveWeight = c.phat / pdf;
            }
            return c;
        }

        LightClusterCell cell = cells[uint(cellIdx)];
        if (cell.count == 0u) {
            return blockEmitterEmptyCandidate(0u);
        }

        // Small 2-4 candidate RIS within cluster
        const uint NUM_CANDIDATES = min(cell.count, 4u);
        float weightSum = 0.0;
        BlockEmitterCandidate winner = blockEmitterEmptyCandidate(0u);
        float winnerPhat = 0.0;

        for (uint i = 0u; i < NUM_CANDIDATES; ++i) {
            uint localPick = min(uint(rand01(rngSeed) * float(cell.count)), cell.count - 1u);
            uint emitterIndex = cellIndices[cell.offset + localPick];
            float2 quadUv = float2(rand01(rngSeed), rand01(rngSeed));

            BlockEmitterCandidate candidate = blockEmitterCandidate(
                records, atlas, emitterIndex, quadUv, receiver, cameraRelative, maxLightRadius);

            if (candidate.phat > 0.0) {
                // Proposal density is 1 / count
                float candidateWeight = candidate.phat * float(cell.count);
                weightSum += candidateWeight;
                if (rand01(rngSeed) * weightSum <= candidateWeight) {
                    winner = candidate;
                    winnerPhat = candidate.phat;
                }
            }
        }

        if (winnerPhat > 0.0 && NUM_CANDIDATES > 0u) {
            effectiveWeight = weightSum / (float(NUM_CANDIDATES) * winnerPhat);
        }
        return winner;
    }
};
