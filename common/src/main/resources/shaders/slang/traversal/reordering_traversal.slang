// Optional ray-generation scheduling adapter. Geometry, materials and random streams remain owned by the wrapped tracer.
public module reordering_traversal;
import "contracts/hit.slang";
import "traversal/traversal.slang";

uint surfaceCoherenceHint(SurfaceHit hit) {
    if (!hit.hit) return 0u;
    uint alphaClass = (hit.flags & HIT_FLAG_TRANSLUCENT) != 0u ? 2u
        : (hit.flags & HIT_FLAG_CUTOUT) != 0u ? 1u : 0u;
    return 1u + alphaClass + (hit.kind == HitKind.Entity ? 4u : 0u);
}

struct ReorderingTraversal<T : ISceneTracer> : ISceneTracer {
    T base;

    SurfaceHit traceClosest(Ray ray) {
        // The query has finished inside base: only ordinary hit data crosses the repack instruction.
        SurfaceHit hit = base.traceClosest(ray);
        ReorderThread(surfaceCoherenceHint(hit), 3u);
        return hit;
    }

    override SurfaceHit tracePrimary(Ray ray) {
        SurfaceHit hit = base.tracePrimary(ray);
        ReorderThread(surfaceCoherenceHint(hit), 3u);
        return hit;
    }

    VisibilityResult traceVisibility(Ray ray) { return base.traceVisibility(ray); }
    float traceDistance(Ray ray) { return base.traceDistance(ray); }
    override float3 surfaceMotion(SurfaceHit hit) { return base.surfaceMotion(hit); }
};
