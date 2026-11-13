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

// How a ray treats the first-person body (setting self_shadow, packed by the host
// into the top two bits of entityState.z; the low 30 bits are the triangle count).
//   FULL          the body is an occluder like any other geometry (mirror rays
//                 always use this: water and glass reflect the player)
//   NO_FOOTPRINT  the body still casts its shadow, but not onto the floor it
//                 stands on: a ray that starts inside the body's column ignores
//                 it.  The eye cannot see the feet that would cover that patch,
//                 so without this the player looked down at a black blot.
//   OFF           the body never occludes (the eye ray always uses this)
static const uint SELF_SHADOW_FULL = 0u;
static const uint SELF_SHADOW_NO_FOOTPRINT = 1u;
static const uint SELF_SHADOW_OFF = 2u;
// Horizontal radius of the player's model around the camera column, in blocks:
// the arms reach +-0.5, so 0.6 covers the whole footprint.
static const float SELF_SHADOW_FOOTPRINT_RADIUS = 0.6;

// Rays are traced in raw-camera-relative space, so the camera column is xz = 0.
bool selfOccluderSkipped(uint mode, bool entity, uint primitive, uint start, uint count, float3 origin) {
    if (!excludedSelfAvatar(true, entity, primitive, start, count)) return false;
    if (mode == SELF_SHADOW_FULL) return false;
    if (mode == SELF_SHADOW_OFF) return true;
    return dot(origin.xz, origin.xz) < SELF_SHADOW_FOOTPRINT_RADIUS * SELF_SHADOW_FOOTPRINT_RADIUS;
}

uint triangleScheduleClass(TriangleIntersection hit, bool entity) {
    return !hit.hit ? 0u : entity ? 2u : 1u;
}
