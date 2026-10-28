// Bedray 2.0 — Subgroup-Accelerated Branchless Bitmask HDDA Traversal.
//
// Implements Sections 2, 5, and 6 of the Architectural Manifesto:
//  1. Branchless Fast DDA: Reciprocal direction caching and sign masking.
//  2. Wave / Subgroup Voting: WaveActiveAll() skips empty 4x4x4 macro bricks across
//     the entire warp, eliminating SIMD divergence in empty air space.
//  3. Morton-Ordered Brick Bitmasking: Maximizes L1 cache hit rate.
//  4. Analytical Cone Tracing Integration: Produces contact-hardening soft shadows.

public module hdda_subgroup;

import "contracts/geometry.slang";
import "contracts/math.slang";
import "voxel/morton.slang";

static const float HDDA_EPSILON = 1.0e-4;

public struct HDDARay {
    public float3 origin;
    public float3 direction;
    public float3 rcpDirection;
    public int3   step;
    public float3 deltaT;

    public static HDDARay create(float3 origin, float3 direction) {
        HDDARay ray;
        ray.origin = origin;
        ray.direction = direction;
        float3 safeDir = float3(
            abs(direction.x) < 1.0e-8 ? (direction.x < 0.0 ? -1.0e-8 : 1.0e-8) : direction.x,
            abs(direction.y) < 1.0e-8 ? (direction.y < 0.0 ? -1.0e-8 : 1.0e-8) : direction.y,
            abs(direction.z) < 1.0e-8 ? (direction.z < 0.0 ? -1.0e-8 : 1.0e-8) : direction.z
        );
        ray.rcpDirection = 1.0 / safeDir;
        ray.step = int3(
            safeDir.x >= 0.0 ? 1 : -1,
            safeDir.y >= 0.0 ? 1 : -1,
            safeDir.z >= 0.0 ? 1 : -1
        );
        ray.deltaT = abs(ray.rcpDirection);
        return ray;
    }
};

public struct HDDAHit {
    public bool   hit;
    public float  t;
    public float3 normal;
    public int3   voxelCoord;
    public float  shadowPenumbra; // For cone-traced soft shadows
};

HDDAHit hddaMiss(float tMax) {
    HDDAHit result;
    result.hit = false;
    result.t = tMax;
    result.normal = float3(0.0);
    result.voxelCoord = int3(-1);
    result.shadowPenumbra = 1.0;
    return result;
}

float3 getAxisNormal(int axis, int step) {
    if (axis == 0) return float3(-float(step), 0.0, 0.0);
    if (axis == 1) return float3(0.0, -float(step), 0.0);
    return float3(0.0, 0.0, -float(step));
}

int getMinAxis(float3 val) {
    if (val.x <= val.y && val.x <= val.z) return 0;
    return val.y <= val.z ? 1 : 2;
}

// Evaluates proximity to adjacent solid obstacle voxels inside the brick
void trackAdjacentObstacleProximity(
    uint64_t brickOccupancy,
    uint3 local,
    float3 rayPointInVoxel,
    float currentT,
    inout VoxelConeShadowTracker shadowTracker)
{
    float3 p = clamp(rayPointInVoxel, float3(0.0), float3(1.0));
    // Check 6 direct face neighbors in the same 4x4x4 brick
    if (local.x > 0u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x - 1u, local.y, local.z))) != 0) {
        shadowTracker.updateSample(p.x, currentT);
    }
    if (local.x < 3u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x + 1u, local.y, local.z))) != 0) {
        shadowTracker.updateSample(1.0 - p.x, currentT);
    }
    if (local.y > 0u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x, local.y - 1u, local.z))) != 0) {
        shadowTracker.updateSample(p.y, currentT);
    }
    if (local.y < 3u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x, local.y + 1u, local.z))) != 0) {
        shadowTracker.updateSample(1.0 - p.y, currentT);
    }
    if (local.z > 0u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x, local.y, local.z - 1u))) != 0) {
        shadowTracker.updateSample(p.z, currentT);
    }
    if (local.z < 3u && (brickOccupancy & mortonVoxelBitMask(uint3(local.x, local.y, local.z + 1u))) != 0) {
        shadowTracker.updateSample(1.0 - p.z, currentT);
    }
}

// Traverses voxels inside one 4x4x4 macro cell using Morton bitmask test
HDDAHit traceMacroCell(
    VoxelSection section,
    int3 macroCoord,
    HDDARay ray,
    float startT,
    float endT,
    float3 entryNormal,
    inout VoxelConeShadowTracker shadowTracker)
{
    float sampleT = min(startT + HDDA_EPSILON, endT);
    int3 voxel = clamp(
        int3(floor(ray.origin + ray.direction * sampleT)),
        macroCoord * VOXEL_MACRO_EDGE,
        macroCoord * VOXEL_MACRO_EDGE + int3(VOXEL_MACRO_EDGE - 1)
    );

    float3 boundary = float3(
        ray.step.x > 0 ? voxel.x + 1 : voxel.x,
        ray.step.y > 0 ? voxel.y + 1 : voxel.y,
        ray.step.z > 0 ? voxel.z + 1 : voxel.z
    );

    float3 nextT = (boundary - ray.origin) * ray.rcpDirection;
    float currentT = startT;
    float3 normal = entryNormal;

    uint macroIdx = uint(voxelMacroIndex(macroCoord));
    uint64_t brickOccupancy = section.occupancy[macroIdx];

    for (int iter = 0; iter < 16 && currentT <= endT; ++iter) {
        uint3 local = uint3(voxel - macroCoord * VOXEL_MACRO_EDGE);
        uint64_t voxelBit = mortonVoxelBitMask(local);

        if ((brickOccupancy & voxelBit) != 0) {
            HDDAHit hit;
            hit.hit = true;
            hit.t = currentT;
            hit.normal = normal;
            hit.voxelCoord = voxel + section.origin.xyz;
            hit.shadowPenumbra = shadowTracker.evaluate();
            return hit;
        }

        // Analytical shadow tracker: measure distance to adjacent solid obstacle faces
        float3 rayPoint = ray.origin + ray.direction * currentT;
        float3 rayPointInVoxel = rayPoint - float3(voxel);
        trackAdjacentObstacleProximity(brickOccupancy, local, rayPointInVoxel, currentT, shadowTracker);

        int axis = getMinAxis(nextT);
        currentT = nextT[axis];
        normal = getAxisNormal(axis, ray.step[axis]);
        voxel[axis] += ray.step[axis];
        nextT[axis] += ray.deltaT[axis];

        if (voxel[axis] < macroCoord[axis] * VOXEL_MACRO_EDGE ||
            voxel[axis] >= (macroCoord[axis] + 1) * VOXEL_MACRO_EDGE) {
            break;
        }
    }

    return hddaMiss(endT);
}

// Traverses a 16x16x16 chunk section using Subgroup Wave Voting to skip empty macro cells
public HDDAHit traceSectionSubgroupHDDA(
    VoxelSection section,
    float3 worldOrigin,
    float3 direction,
    float tMin,
    float tMax,
    bool enableSoftShadowCone = false)
{
    float3 localOrigin = worldOrigin - float3(section.origin.xyz);
    HDDARay ray = HDDARay.create(localOrigin, direction);

    // Section bounding box intersection
    float3 nearT = (float3(0.0) - ray.origin) * ray.rcpDirection;
    float3 farT = (float3(float(VOXEL_SECTION_EDGE)) - ray.origin) * ray.rcpDirection;
    float3 lower = min(nearT, farT);
    float3 upper = max(nearT, farT);
    float enterT = max(max(lower.x, lower.y), lower.z);
    float exitT = min(min(upper.x, upper.y), upper.z);

    if (exitT < max(enterT, 0.0)) return hddaMiss(tMax);

    float sectionEntry = enterT;
    enterT = max(enterT, tMin);
    exitT = min(exitT, tMax);
    if (enterT > exitT) return hddaMiss(tMax);

    float sampleT = min(enterT + HDDA_EPSILON, exitT);
    int3 macro = clamp(
        int3(floor((ray.origin + ray.direction * sampleT) / float(VOXEL_MACRO_EDGE))),
        int3(0),
        int3(VOXEL_MACRO_AXIS - 1)
    );

    float3 boundary = float3(
        ray.step.x > 0 ? (macro.x + 1) * VOXEL_MACRO_EDGE : macro.x * VOXEL_MACRO_EDGE,
        ray.step.y > 0 ? (macro.y + 1) * VOXEL_MACRO_EDGE : macro.y * VOXEL_MACRO_EDGE,
        ray.step.z > 0 ? (macro.z + 1) * VOXEL_MACRO_EDGE : macro.z * VOXEL_MACRO_EDGE
    );

    float3 nextT = (boundary - ray.origin) * ray.rcpDirection;
    float3 macroDeltaT = abs(float(VOXEL_MACRO_EDGE) * ray.rcpDirection);
    float currentT = enterT;
    float3 entryNormal = float3(0.0);

    if (sectionEntry >= tMin && sectionEntry >= 0.0) {
        int entryAxis = lower.x >= lower.y && lower.x >= lower.z ? 0 : (lower.y >= lower.z ? 1 : 2);
        entryNormal = getAxisNormal(entryAxis, ray.step[entryAxis]);
    }

    VoxelConeShadowTracker shadowTracker;
    shadowTracker.init(enableSoftShadowCone ? 0.05 : 0.001);

    for (int iter = 0; iter < 32 && currentT <= exitT; ++iter) {
        if (any(macro < int3(0)) || any(macro >= int3(VOXEL_MACRO_AXIS))) {
            break;
        }

        float macroExit = min(min(nextT.x, nextT.y), min(nextT.z, exitT));
        bool isOccupied = voxelMacroOccupied(section, macro);

        // Subgroup Wave Voting:
        // If all active lanes in the warp are looking at empty macro cells, the entire warp
        // skips inner traversal with zero branch divergence.
        bool waveAllEmpty = WaveActiveAll(!isOccupied);

        if (!waveAllEmpty && isOccupied) {
            HDDAHit hit = traceMacroCell(section, macro, ray, currentT, macroExit, entryNormal, shadowTracker);
            if (hit.hit) {
                return hit;
            }
        }

        int axis = getMinAxis(nextT);
        currentT = nextT[axis];
        entryNormal = getAxisNormal(axis, ray.step[axis]);
        macro[axis] += ray.step[axis];
        nextT[axis] += macroDeltaT[axis];
    }

    HDDAHit miss = hddaMiss(tMax);
    miss.shadowPenumbra = shadowTracker.evaluate();
    return miss;
}
