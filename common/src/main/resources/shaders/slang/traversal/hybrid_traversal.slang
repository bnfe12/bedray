// Hybrid traversal backend.
//
// Voxel geometry covers the streamed Minecraft world near the camera; hardware
// geometry covers entities, items and anything else that is not in the voxel
// provider.  The backend is generic over both halves so Slang can specialize it
// without a runtime switch and without a dynamic-dispatch cost.

public module hybrid_traversal;

import "contracts/hit.slang";
import "traversal/traversal.slang";

struct HybridTraversal<V : ITraversal, H : ITraversal> : ITraversal {
    V voxel;
    H hardware;
    float voxelDistance;   // hardware-only beyond this distance

    SurfaceHit traceClosest(Ray ray) {
        Ray voxelRay = ray;
        voxelRay.tMax = min(ray.tMax, voxelDistance);

        SurfaceHit voxelHit = voxel.traceClosest(voxelRay);
        SurfaceHit hardwareHit = hardware.traceClosest(ray);

        if (voxelHit.hit && (!hardwareHit.hit || voxelHit.t <= hardwareHit.t)) {
            return voxelHit;
        }
        return hardwareHit;
    }

    VisibilityResult traceVisibility(Ray ray) {
        Ray voxelRay = ray;
        voxelRay.tMax = min(ray.tMax, voxelDistance);

        VisibilityResult voxelResult = voxel.traceVisibility(voxelRay);
        if (!voxelResult.visible) return voxelResult;
        return hardware.traceVisibility(ray);
    }

    float traceDistance(Ray ray) {
        Ray voxelRay = ray;
        voxelRay.tMax = min(ray.tMax, voxelDistance);

        float voxelDistanceToHit = voxel.traceDistance(voxelRay);
        float hardwareDistanceToHit = hardware.traceDistance(ray);
        return min(voxelDistanceToHit, hardwareDistanceToHit);
    }
};
