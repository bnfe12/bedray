// Bitmask HDDA world traversal.
//
// The section directory is data, not architecture: a dense grid and a streamed
// hash directory use the same VoxelWorld value.  The traversal loop is a
// section-level DDA; inside a resident section the single-section Bitmask HDDA
// from voxel/voxel.slang runs unchanged.

public module voxel_traversal;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "traversal/traversal.slang";
import "voxel/voxel.slang";

static const uint VOXEL_MAX_SECTION_STEPS = 512u;

struct VoxelWorld {
    StructuredBuffer<VoxelSection> sections;
    StructuredBuffer<VoxelBlock> blocks;
    StructuredBuffer<SectionLookupEntry> lookup;
    VoxelGrid grid;
};

// Returns SECTION_INDEX_NONE when the section is not resident.  The dense path
// is used by the software provider; the hash path is used by streamed worlds.
int findVoxelSection(VoxelWorld world, int3 sectionCoordinate) {
    if (world.grid.dimensions.w == 0u) {
        int3 local = sectionCoordinate - world.grid.originSection.xyz;
        if (any(local < int3(0)) ||
            local.x >= int(world.grid.dimensions.x) ||
            local.y >= int(world.grid.dimensions.y) ||
            local.z >= int(world.grid.dimensions.z)) {
            return -1;
        }
        uint index = uint(local.x +
            int(world.grid.dimensions.x) * (local.y + int(world.grid.dimensions.y) * local.z));
        return index < world.grid.stats.x ? int(index) : -1;
    }

    uint mask = world.grid.originSection.w;
    uint probes = world.grid.dimensions.w;
    uint slot = voxelSectionHash(sectionCoordinate) & mask;
    for (uint probe = 0u; probe < probes; ++probe) {
        SectionLookupEntry entry = world.lookup[slot];
        if (entry.sectionIndex == SECTION_INDEX_NONE) return -1;
        if (all(entry.key == sectionCoordinate)) return int(entry.sectionIndex);
        slot = (slot + 1u) & mask;
    }
    return -1;
}

float3 safeRayDirection(float3 direction) {
    return float3(
        abs(direction.x) < 1.0e-8 ? (direction.x < 0.0 ? -1.0e-8 : 1.0e-8) : direction.x,
        abs(direction.y) < 1.0e-8 ? (direction.y < 0.0 ? -1.0e-8 : 1.0e-8) : direction.y,
        abs(direction.z) < 1.0e-8 ? (direction.z < 0.0 ? -1.0e-8 : 1.0e-8) : direction.z);
}

int sectionStepAxis(float3 tBoundary) {
    if (tBoundary.x <= tBoundary.y && tBoundary.x <= tBoundary.z) return 0;
    return tBoundary.y <= tBoundary.z ? 1 : 2;
}

struct VoxelWorldHit {
    VoxelHit hit;
    uint     sectionIndex;
};

// Shared section walk for closest/visibility/distance queries.  Sections are
// visited in ray order, so the first occupied voxel is also the nearest one.
VoxelWorldHit traceVoxelWorld(VoxelWorld world, Ray ray) {
    VoxelWorldHit result;
    result.hit = voxelMiss(ray.tMax);
    result.sectionIndex = SECTION_INDEX_NONE;

    float3 inverseDirection = 1.0 / safeRayDirection(ray.direction);
    int3 step = int3(ray.direction.x >= 0.0 ? 1 : -1,
                     ray.direction.y >= 0.0 ? 1 : -1,
                     ray.direction.z >= 0.0 ? 1 : -1);
    int3 sectionCoordinate = int3(floor(ray.origin / float(VOXEL_SECTION_EDGE)));

    for (uint stepIndex = 0u; stepIndex < VOXEL_MAX_SECTION_STEPS; ++stepIndex) {
        int sectionIndex = findVoxelSection(world, sectionCoordinate);
        if (sectionIndex >= 0) {
            VoxelSection section = world.sections[uint(sectionIndex)];
            float sectionTMax = ray.tMax;
            VoxelHit hit = traceBitmaskHDDA(section, ray.origin, ray.direction,
                ray.tMin, sectionTMax);
            if (hit.hit) {
                result.hit = hit;
                result.sectionIndex = uint(sectionIndex);
                return result;
            }
        }

        float3 boundary = float3(
            step.x > 0 ? (sectionCoordinate.x + 1) * VOXEL_SECTION_EDGE : sectionCoordinate.x * VOXEL_SECTION_EDGE,
            step.y > 0 ? (sectionCoordinate.y + 1) * VOXEL_SECTION_EDGE : sectionCoordinate.y * VOXEL_SECTION_EDGE,
            step.z > 0 ? (sectionCoordinate.z + 1) * VOXEL_SECTION_EDGE : sectionCoordinate.z * VOXEL_SECTION_EDGE);
        float3 tBoundary = (boundary - ray.origin) * inverseDirection;
        int axis = sectionStepAxis(tBoundary);
        float tNext = tBoundary[axis];
        if (tNext > ray.tMax) break;
        sectionCoordinate[axis] += step[axis];
    }

    return result;
}

SurfaceHit voxelHitToSurface(VoxelWorld world, Ray ray, VoxelWorldHit worldHit) {
    SurfaceHit result = missHit(ray.tMax);
    if (!worldHit.hit.hit) return result;

    VoxelSection section = world.sections[worldHit.sectionIndex];
    int3 local = worldHit.hit.voxel - section.origin.xyz;
    VoxelBlock block = voxelBlockAt(world.blocks, section, local);

    result.hit = true;
    result.kind = HitKind.Voxel;
    result.t = worldHit.hit.t;
    result.position = rayAt(ray, worldHit.hit.t);
    // Section origins are rebased by the host; the wrapped anchor is stable for
    // procedural effects.  Add FrameConstants.camera.origin for absolute world.
    result.worldPosition = float3(section.origin.xyz & 1023) + (result.position - float3(section.origin.xyz));
    result.geometricNormal = worldHit.hit.normal;
    result.shadingNormal = worldHit.hit.normal;
    result.uv = float2(0.0);   // voxel faces use the block material, not an atlas quad
    result.light = float2(float(voxelBlockLight(block)), float(voxelSkyLight(block))) * (1.0 / 15.0);
    result.tint = voxelTint(block);
    result.instanceId = worldHit.sectionIndex;
    result.primitiveId = uint(worldHit.hit.voxel.x & 15) +
                         uint(worldHit.hit.voxel.y & 15) * 16u +
                         uint(worldHit.hit.voxel.z & 15) * 256u;
    result.sectionId = section.meta.x;
    result.materialId = block.materialId;
    // An origin inside a solid voxel has no entry face. Preserve that fact:
    // downstream code must not interpret a zero normal as a valid tangent frame.
    bool hasEntryFace = dot(worldHit.hit.normal, worldHit.hit.normal) > 0.0;
    result.flags = hasEntryFace ? (HIT_FLAG_FRONT_FACE | HIT_FLAG_TANGENT_VALID) : 0u;
    if ((block.flags & VOXEL_BLOCK_FLAG_EMISSIVE) != 0u) result.flags |= HIT_FLAG_EMISSIVE;
    if ((block.flags & VOXEL_BLOCK_FLAG_FOLIAGE) != 0u) result.flags |= HIT_FLAG_CUTOUT;
    if ((block.flags & VOXEL_BLOCK_FLAG_FLUID) != 0u) result.flags |= HIT_FLAG_TRANSLUCENT;

    float3 tangent;
    float3 bitangent;
    if (hasEntryFace) {
        faceTangentFrame(worldHit.hit.normal, tangent, bitangent);
    } else {
        tangent = float3(0.0);
        bitangent = float3(0.0);
    }
    result.tangent = tangent;
    result.bitangent = bitangent;
    return result;
}

struct BitmaskVoxelTraversal : ITraversal {
    VoxelWorld world;

    SurfaceHit traceClosest(Ray ray) {
        return voxelHitToSurface(world, ray, traceVoxelWorld(world, ray));
    }

    VisibilityResult traceVisibility(Ray ray) {
        VoxelWorldHit hit = traceVoxelWorld(world, ray);
        if (hit.hit.hit) return blockedResult(voxelHitToSurface(world, ray, hit), float3(0.0));
        return visibleResult(ray.tMax);
    }

    float traceDistance(Ray ray) {
        VoxelWorldHit hit = traceVoxelWorld(world, ray);
        return hit.hit.hit ? hit.hit.t : ray.tMax;
    }
};
