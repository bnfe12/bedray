// Traversal interface for Bedray 2.0.
//
// A pass is written against ISceneTracer, never against a concrete backend.  The
// host picks a backend at pipeline-build time:
//
//   BitmaskVoxelTraversal   — software Bitmask HDDA, no RT cores
//   HardwareTraversal       — TLAS + VK_KHR_ray_query
//   HybridTraversal         — voxel world near, hardware entities far
//
// Keeping the interface this small is intentional.  Material rules, alpha
// testing and light sampling belong to later stages, so adding a backend does
// not multiply shader variants.

public module scene_tracer;

import "contracts/hit.slang";

interface ISceneTracer {
    // Nearest surface along the ray.  Implementations must return
    // missHit(ray.tMax) when nothing is hit.
    SurfaceHit traceClosest(Ray ray);

    // The ray that leaves the eye.  It is the one ray a backend may answer with
    // less geometry than a bounce: a first-person capture puts the local
    // player's own body around the camera, and the centimetres of inner face
    // between the eye and that body would otherwise fill the screen.  Shadow and
    // visibility rays keep calling traceClosest, so the body still casts its
    // shadow and still blocks a light.
    SurfaceHit tracePrimary(Ray ray) {
        return traceClosest(ray);
    }

    // Shadow/visibility query.  Translucent surfaces are handled by the
    // material stage; a backend only reports geometric blockers.
    VisibilityResult traceVisibility(Ray ray);

    // Distance to the nearest blocker, or ray.tMax on a miss.  This is the
    // cheap form used by AO and short-range GI.
    float traceDistance(Ray ray);

    // Where the surface of a hit had itself one frame ago, minus where it is now,
    // in world blocks.  A backend whose geometry does not move answers zero, and
    // zero is exactly what a consumer wants: added to a camera-only reprojection
    // it changes nothing.  Only the primary hit is ever asked, because the rest
    // of the path is a sample of this frame, not a surface on a screen.
    float3 surfaceMotion(SurfaceHit hit) {
        return float3(0.0);
    }
}
