// Optional ray-generation scheduling adapter. Geometry, materials and random streams remain owned by the wrapped tracer.
public module reordering_traversal;
import "contracts/hit.slang";
import "traversal/traversal.slang";
import "contracts/triangle_intersection.slang";
import "traversal/entity_geometry.slang";

uint intersectionCoherenceHint(TriangleIntersection hit) {
    return triangleScheduleClass(hit, entityInstance(hit.instanceId));
}

struct ReorderingTraversal<T : ITriangleTracer> : ISceneTracer {
    T base;

    SurfaceHit traceClosest(Ray ray) {
        TriangleIntersection hit = base.traceIntersection(ray, base.secondarySelfShadowMode());
        ReorderThread(intersectionCoherenceHint(hit), 2u);
        return base.decodeIntersection(ray, hit);
    }

    override SurfaceHit tracePrimary(Ray ray) {
        TriangleIntersection hit = base.traceIntersection(ray, SELF_SHADOW_OFF);
        ReorderThread(intersectionCoherenceHint(hit), 2u);
        return base.decodeIntersection(ray, hit);
    }

    override SurfaceHit traceMirror(Ray ray) {
        TriangleIntersection hit = base.traceIntersection(ray, SELF_SHADOW_FULL);
        ReorderThread(intersectionCoherenceHint(hit), 2u);
        return base.decodeIntersection(ray, hit);
    }

    VisibilityResult traceVisibility(Ray ray) { return base.traceVisibility(ray); }
    float traceDistance(Ray ray) { return base.traceDistance(ray); }
    override float3 surfaceMotion(SurfaceHit hit) { return base.surfaceMotion(hit); }
};
