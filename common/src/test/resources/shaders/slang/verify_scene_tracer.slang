import "contracts/scene_tracer.slang";
import "contracts/hit.slang";
import "traversal/hybrid_traversal.slang";
import "traversal/software_tracer.slang";

struct MockTracer : ISceneTracer {
    float distance;
    uint kind;
    uint primary;
    SurfaceHit traceClosest(Ray ray) {
        SurfaceHit hit = missHit(ray.tMax);
        if (distance >= ray.tMin && distance <= ray.tMax) {
            hit.hit = true;
            hit.kind = HitKind(kind);
            hit.t = distance;
        }
        return hit;
    }
    override SurfaceHit tracePrimary(Ray ray) {
        return primary != 0u ? traceClosest(ray) : missHit(ray.tMax);
    }
    VisibilityResult traceVisibility(Ray ray) {
        SurfaceHit hit = traceClosest(ray);
        return hit.hit ? blockedResult(hit, float3(0.0)) : visibleResult(ray.tMax);
    }
    float traceDistance(Ray ray) { return traceClosest(ray).t; }
    override float3 surfaceMotion(SurfaceHit hit) { return float3(distance, 0.0, 0.0); }
}

[[vk::binding(0, 0)]] RWStructuredBuffer<float4> results;
[[vk::push_constant]] ConstantBuffer<float4> distances;
[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    MockTracer voxel;
    voxel.distance = distances.x;
    voxel.kind = uint(HitKind.Voxel);
    voxel.primary = 1u;
    MockTracer hardware;
    hardware.distance = distances.y;
    hardware.kind = uint(HitKind.Entity);
    hardware.primary = 0u;
    HybridTraversal<MockTracer, MockTracer> hybrid;
    hybrid.voxel = voxel;
    hybrid.hardware = hardware;
    hybrid.voxelDistance = distances.z;
    Ray ray = makeRay(float3(0.0), float3(0.0, 0.0, 1.0), 0.0, distances.w);
    SurfaceHit closest = hybrid.traceClosest(ray);
    SurfaceHit primary = hybrid.tracePrimary(ray);
    VisibilityResult shadow = hybrid.traceVisibility(ray);
    results[0] = float4(closest.t, primary.t, shadow.distance, hybrid.traceDistance(ray));
    results[1] = float4(hybrid.surfaceMotion(closest), closest.hit ? 1.0 : 0.0);
}
