// Hybrid traversal backend.
//
// Voxel geometry covers the streamed Minecraft world; hardware
// geometry covers entities, items and anything else that is not in the voxel
// provider.  The backend is generic over both halves so Slang can specialize it
// without a runtime switch and without a dynamic-dispatch cost.

public module hybrid_traversal;

import "contracts/hit.slang";
import "traversal/traversal.slang";

struct HybridTraversal<V : ISceneTracer, H : ISceneTracer> : ISceneTracer {
    V voxel;
    H hardware;
    // Budget measured from each ray origin (also for bounce/shadow rays), not
    // from the camera. The host must supply coverage for the desired world
    // range; hardware contains meshes, not an implicit far-world fallback.
    float voxelDistance;
    // Provenance invariant: V returns HitKind.Voxel and H returns mesh kinds.
    // surfaceMotion routes using this invariant, never a section/primitive ID.

    SurfaceHit traceClosest(Ray ray) {
        if (!(ray.tMax > ray.tMin)) return missHit(ray.tMax);
        Ray voxelRay = ray;
        voxelRay.tMax = min(ray.tMax, voxelDistance);

        SurfaceHit voxelHit = missHit(ray.tMax);
        if (voxelRay.tMax > voxelRay.tMin) voxelHit = voxel.traceClosest(voxelRay);
        SurfaceHit hardwareHit = hardware.traceClosest(ray);

        if (voxelHit.hit && (!hardwareHit.hit || voxelHit.t <= hardwareHit.t)) {
            return voxelHit;
        }
        return hardwareHit;
    }

    VisibilityResult traceVisibility(Ray ray) {
        SurfaceHit hit = traceClosest(ray);
        return hit.hit ? blockedResult(hit, float3(0.0)) : visibleResult(ray.tMax);
    }

    override SurfaceHit tracePrimary(Ray ray) {
        if (!(ray.tMax > ray.tMin)) return missHit(ray.tMax);
        Ray voxelRay = ray;
        voxelRay.tMax = min(ray.tMax, voxelDistance);
        SurfaceHit voxelHit = missHit(ray.tMax);
        if (voxelRay.tMax > voxelRay.tMin) voxelHit = voxel.tracePrimary(voxelRay);
        SurfaceHit hardwareHit = hardware.tracePrimary(ray);
        return voxelHit.hit && (!hardwareHit.hit || voxelHit.t <= hardwareHit.t)
            ? voxelHit : hardwareHit;
    }

    override float3 surfaceMotion(SurfaceHit hit) {
        if (!hit.hit) return float3(0.0);
        // This composite combines voxel-world geometry with hardware meshes.
        // Preserve the winning backend's motion for temporal reprojection.
        return hit.kind == HitKind.Voxel ? voxel.surfaceMotion(hit) : hardware.surfaceMotion(hit);
    }

    float traceDistance(Ray ray) {
        SurfaceHit hit = traceClosest(ray);
        return hit.hit ? hit.t : ray.tMax;
    }
};
