// Bitmask HDDA software voxel traversal.
//
// A 16^3 Minecraft section is split into 4^3 macro cells. Each macro cell
// owns one 64-bit occupancy mask for its 4^3 voxels, so block edits update one
// bit and never require a triangle mesh or a distance field rebuild.
//
// This module owns a single section.  World-level section walking lives in
// traversal/voxel_traversal.slang, which keeps this file independent of the
// section directory and of any descriptor binding.

public module voxel;

import "contracts/geometry.slang";

static const float VOXEL_RAY_EPSILON = 1.0e-4;

struct VoxelHit {
    bool   hit;
    float  t;
    float3 normal;
    int3   voxel;
};

VoxelHit voxelMiss(float tMax) {
    VoxelHit result;
    result.hit = false;
    result.t = tMax;
    result.normal = float3(0.0);
    result.voxel = int3(-1);
    return result;
}

bool inRange(int3 cell, int edge) {
    return all(cell >= int3(0)) && all(cell < int3(edge));
}

// Same ray-box test as the old GLSL, but expressed with the contract constants
// so the section edge cannot drift between host and shader.
bool intersectSection(float3 origin, float3 direction, out float enterT, out float exitT) {
    float3 safeDirection = float3(
        abs(direction.x) < 1.0e-8 ? (direction.x < 0.0 ? -1.0e-8 : 1.0e-8) : direction.x,
        abs(direction.y) < 1.0e-8 ? (direction.y < 0.0 ? -1.0e-8 : 1.0e-8) : direction.y,
        abs(direction.z) < 1.0e-8 ? (direction.z < 0.0 ? -1.0e-8 : 1.0e-8) : direction.z);
    float3 nearT = (float3(0.0) - origin) / safeDirection;
    float3 farT = (float3(float(VOXEL_SECTION_EDGE)) - origin) / safeDirection;
    float3 lower = min(nearT, farT);
    float3 upper = max(nearT, farT);
    enterT = max(max(lower.x, lower.y), lower.z);
    exitT = min(min(upper.x, upper.y), upper.z);
    return exitT >= max(enterT, 0.0);
}

float3 axisNormal(int axis, int step) {
    if (axis == 0) return float3(-float(step), 0.0, 0.0);
    if (axis == 1) return float3(0.0, -float(step), 0.0);
    return float3(0.0, 0.0, -float(step));
}

int minAxis(float3 value) {
    if (value.x <= value.y && value.x <= value.z) return 0;
    return value.y <= value.z ? 1 : 2;
}

// Traverses the 4x4x4 voxels in one occupied macro cell.  The macro mask is
// consulted before this routine is entered; the inner DDA only visits occupied
// bits, keeping the common empty-cell case O(1).
VoxelHit traceMacro(VoxelSection section, int3 macro, float3 origin, float3 direction,
        float startT, float endT, float3 entryNormal) {
    float sampleT = min(startT + VOXEL_RAY_EPSILON, endT);
    int3 voxel = clamp(int3(floor(origin + direction * sampleT)), macro * VOXEL_MACRO_EDGE,
        macro * VOXEL_MACRO_EDGE + int3(VOXEL_MACRO_EDGE - 1));
    int3 step = int3(direction.x >= 0.0 ? 1 : -1, direction.y >= 0.0 ? 1 : -1, direction.z >= 0.0 ? 1 : -1);
    float3 inverseDirection = 1.0 / float3(
        abs(direction.x) < 1.0e-8 ? (direction.x < 0.0 ? -1.0e-8 : 1.0e-8) : direction.x,
        abs(direction.y) < 1.0e-8 ? (direction.y < 0.0 ? -1.0e-8 : 1.0e-8) : direction.y,
        abs(direction.z) < 1.0e-8 ? (direction.z < 0.0 ? -1.0e-8 : 1.0e-8) : direction.z);
    float3 boundary = float3(
        step.x > 0 ? voxel.x + 1 : voxel.x,
        step.y > 0 ? voxel.y + 1 : voxel.y,
        step.z > 0 ? voxel.z + 1 : voxel.z);
    float3 nextT = (boundary - origin) * inverseDirection;
    float3 deltaT = abs(inverseDirection);
    float currentT = startT;
    // Zero is intentional when the ray starts inside solid geometry: no
    // entrance face exists. Otherwise propagate the actual crossed face.
    float3 normal = entryNormal;

    for (int iteration = 0; iteration < 64 && currentT <= endT; ++iteration) {
        int3 local = voxel - macro * VOXEL_MACRO_EDGE;
        if (voxelOccupiedInMacro(section, macro, local)) {
            VoxelHit result;
            result.hit = true;
            result.t = currentT;
            result.normal = normal;
            result.voxel = voxel + section.origin.xyz;
            return result;
        }
        int axis = minAxis(nextT);
        currentT = nextT[axis];
        normal = axisNormal(axis, step[axis]);
        voxel[axis] += step[axis];
        nextT[axis] += deltaT[axis];
        if (voxel[axis] < macro[axis] * VOXEL_MACRO_EDGE ||
            voxel[axis] >= (macro[axis] + 1) * VOXEL_MACRO_EDGE) {
            break;
        }
    }
    return voxelMiss(endT);
}

// Bitmask HDDA: first DDA walks 4x4x4 macro cells, second DDA visits voxels
// only inside a macro cell whose 64-bit occupancy mask is non-zero.
VoxelHit traceBitmaskHDDA(VoxelSection section, float3 worldOrigin, float3 direction,
        float tMin, float tMax) {
    float3 origin = worldOrigin - float3(section.origin.xyz);
    float enterT;
    float exitT;
    if (!intersectSection(origin, direction, enterT, exitT)) return voxelMiss(tMax);
    float sectionEntry = enterT;
    enterT = max(enterT, tMin);
    exitT = min(exitT, tMax);
    if (enterT > exitT) return voxelMiss(tMax);

    float sampleT = min(enterT + VOXEL_RAY_EPSILON, exitT);
    int3 macro = clamp(int3(floor((origin + direction * sampleT) / float(VOXEL_MACRO_EDGE))), int3(0),
        int3(VOXEL_MACRO_AXIS - 1));
    int3 step = int3(direction.x >= 0.0 ? 1 : -1, direction.y >= 0.0 ? 1 : -1, direction.z >= 0.0 ? 1 : -1);
    float3 safeDirection = float3(
        abs(direction.x) < 1.0e-8 ? (direction.x < 0.0 ? -1.0e-8 : 1.0e-8) : direction.x,
        abs(direction.y) < 1.0e-8 ? (direction.y < 0.0 ? -1.0e-8 : 1.0e-8) : direction.y,
        abs(direction.z) < 1.0e-8 ? (direction.z < 0.0 ? -1.0e-8 : 1.0e-8) : direction.z);
    float3 inverseDirection = 1.0 / safeDirection;
    float3 boundary = float3(
        step.x > 0 ? (macro.x + 1) * VOXEL_MACRO_EDGE : macro.x * VOXEL_MACRO_EDGE,
        step.y > 0 ? (macro.y + 1) * VOXEL_MACRO_EDGE : macro.y * VOXEL_MACRO_EDGE,
        step.z > 0 ? (macro.z + 1) * VOXEL_MACRO_EDGE : macro.z * VOXEL_MACRO_EDGE);
    float3 nextT = (boundary - origin) * inverseDirection;
    float3 deltaT = abs(float(VOXEL_MACRO_EDGE) * inverseDirection);
    float currentT = enterT;
    float3 entryNormal = float3(0.0);
    if (sectionEntry >= tMin && sectionEntry >= 0.0) {
        float3 lower = min((float3(0.0) - origin) * inverseDirection,
            (float3(float(VOXEL_SECTION_EDGE)) - origin) * inverseDirection);
        int entryAxis = lower.x >= lower.y && lower.x >= lower.z ? 0 :
            (lower.y >= lower.z ? 1 : 2);
        entryNormal = axisNormal(entryAxis, step[entryAxis]);
    }

    for (int iteration = 0; iteration < 64 && inRange(macro, VOXEL_MACRO_AXIS) && currentT <= exitT; ++iteration) {
        float macroExit = min(min(nextT.x, nextT.y), min(nextT.z, exitT));
        if (voxelMacroOccupied(section, macro)) {
            VoxelHit hit = traceMacro(section, macro, origin, direction, currentT, macroExit, entryNormal);
            if (hit.hit) return hit;
        }
        int axis = minAxis(nextT);
        currentT = nextT[axis];
        entryNormal = axisNormal(axis, step[axis]);
        macro[axis] += step[axis];
        nextT[axis] += deltaT[axis];
    }
    return voxelMiss(tMax);
}

// Convenience overload for callers that do not need a tMin clip.
VoxelHit traceBitmaskHDDA(VoxelSection section, float3 worldOrigin, float3 direction, float tMax) {
    return traceBitmaskHDDA(section, worldOrigin, direction, 0.0, tMax);
}
