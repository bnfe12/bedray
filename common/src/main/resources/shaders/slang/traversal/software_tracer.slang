// Bedray 2.0 — Software Voxel Tracer (Tier 1 ISceneTracer implementation).
//
// Implements Section 1 and Section 3 of the Architectural Manifesto:
//  Zero-overhead specialization of ISceneTracer for Compute-only / Voxel Fast mode.
//  Uses Bitmask HDDA world traversal without any hardware RT cores.

public module software_tracer;

import "contracts/scene_tracer.slang";
import "contracts/hit.slang";
import "contracts/geometry.slang";
import "traversal/voxel_traversal.slang";

public struct SoftwareVoxelTracer : ISceneTracer {
    public VoxelWorld world;

    public bool traceShadowRay(Ray ray, float maxDist) {
        Ray r = ray;
        r.tMax = min(r.tMax, maxDist);
        VoxelWorldHit worldHit = traceVoxelWorld(world, r);
        return worldHit.hit.hit;
    }

    public ReflectionHit traceReflectionRay(Ray ray, float maxDist) {
        Ray r = ray;
        r.tMax = min(r.tMax, maxDist);
        VoxelWorldHit worldHit = traceVoxelWorld(world, r);
        ReflectionHit refl;

        if (worldHit.hit.hit) {
            refl.hit = true;
            refl.hitDistance = worldHit.hit.t;
            refl.position = ray.origin + ray.direction * worldHit.hit.t;
            refl.normal = worldHit.hit.normal;

            // Fetch block metadata and biome/light tint
            VoxelSection section = world.sections[worldHit.sectionIndex];
            int3 local = worldHit.hit.voxel - section.origin.xyz;
            VoxelBlock block = voxelBlockAt(world.blocks, section, local);
            refl.albedo = voxelTint(block);
            refl.roughness = 0.5; // Baseline voxel roughness
        } else {
            refl.hit = false;
            refl.hitDistance = maxDist;
            refl.position = float3(0.0);
            refl.normal = float3(0.0);
            refl.albedo = float3(0.0);
            refl.roughness = 1.0;
        }

        return refl;
    }

    public float traceAmbientOcclusion(Ray ray, float radius) {
        Ray r = ray;
        r.tMax = radius;
        VoxelWorldHit worldHit = traceVoxelWorld(world, r);
        // Returns 0.0 if occluded, 1.0 if clear
        return worldHit.hit.hit ? 0.0 : 1.0;
    }
};
