// Internal scheduling boundary, not a descriptor/push/public ABI layout.
public module triangle_intersection;
import "contracts/scene_tracer.slang";
import "contracts/hit.slang";

// No normal/tangent/UV/tint/material state and no RayQuery survives this boundary.
struct TriangleIntersection {
    bool hit;
    float t;
    uint instanceId;
    uint geometryIndex;
    uint primitiveId;
    float2 barycentrics;
};

TriangleIntersection missIntersection(float tMax) {
    TriangleIntersection result;
    result.hit = false;
    result.t = tMax;
    result.instanceId = 0u;
    result.geometryIndex = 0u;
    result.primitiveId = 0u;
    result.barycentrics = float2(0.0);
    return result;
}

interface ITriangleTracer : ISceneTracer {
    TriangleIntersection traceIntersection(Ray ray, bool excludeSelfAvatar);
    SurfaceHit decodeIntersection(Ray ray, TriangleIntersection intersection);
};

bool excludedSelfAvatar(bool primary, bool entity, uint primitive, uint start, uint count) {
    return primary && entity && count != 0u && primitive >= start && primitive - start < count;
}

uint triangleScheduleClass(TriangleIntersection hit, bool entity) {
    return !hit.hit ? 0u : entity ? 2u : 1u;
}
