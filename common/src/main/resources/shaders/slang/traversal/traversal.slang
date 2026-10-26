// Traversal interface for Bedray 2.0.
//
// A pass is written against ITraversal, never against a concrete backend.  The
// host picks a backend at pipeline-build time:
//
//   BitmaskVoxelTraversal   — software Bitmask HDDA, no RT cores
//   HardwareTraversal       — TLAS + VK_KHR_ray_query
//   HybridTraversal         — voxel world near, hardware entities far
//
// Keeping the interface this small is intentional.  Material rules, alpha
// testing and light sampling belong to later stages, so adding a backend does
// not multiply shader variants.

public module traversal;

import "contracts/hit.slang";

interface ITraversal {
    // Nearest surface along the ray.  Implementations must return
    // missHit(ray.tMax) when nothing is hit.
    SurfaceHit traceClosest(Ray ray);

    // Shadow/visibility query.  Translucent surfaces are handled by the
    // material stage; a backend only reports geometric blockers.
    VisibilityResult traceVisibility(Ray ray);

    // Distance to the nearest blocker, or ray.tMax on a miss.  This is the
    // cheap form used by AO and short-range GI.
    float traceDistance(Ray ray);
}
