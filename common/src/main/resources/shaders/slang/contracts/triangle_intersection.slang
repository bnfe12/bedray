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
    TriangleIntersection traceIntersection(Ray ray, uint selfShadowMode);
    // SELF_SHADOW_* the backend applies to its own shadow and diffuse rays.
    uint secondarySelfShadowMode();
    SurfaceHit decodeIntersection(Ray ray, TriangleIntersection intersection);
};

bool excludedSelfAvatar(bool primary, bool entity, uint primitive, uint start, uint count) {
    return primary && entity && count != 0u && primitive >= start && primitive - start < count;
}

// The host packs self_shadow in the top two bits of entityState.z. Primary rays
// skip the first-person body. Mirror rays always see it, and visibility rays
// keep its complete silhouette in Full mode.
static const uint SELF_SHADOW_FULL = 0u;
static const uint SELF_SHADOW_OFF = 2u;

bool selfOccluderSkipped(uint mode, bool entity, uint primitive, uint start, uint count) {
    return mode == SELF_SHADOW_OFF && excludedSelfAvatar(true, entity, primitive, start, count);
}

uint triangleScheduleClass(TriangleIntersection hit, bool entity) {
    return !hit.hit ? 0u : entity ? 2u : 1u;
}
