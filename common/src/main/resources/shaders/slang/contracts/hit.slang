// Ray and hit contract for Bedray 2.0.
//
// Traversal backends return SurfaceHit, never a backend-specific structure.
// Material decoding happens afterwards, so a hit produced by Bitmask HDDA and
// a hit produced by a hardware TLAS follow the same shading path.

public module hit_contract;

import "contracts/geometry.slang";

enum HitKind : uint {
    Miss      = 0u,
    Voxel     = 1u,
    Triangle  = 2u,
    Entity    = 3u,
    Interface = 4u,
};

// Ray in camera-relative world space.  tMin is kept separate from tMax so
// shadow rays can start at an epsilon without rebuilding the ray.
struct Ray {
    float3 origin;
    float  tMin;
    float3 direction;
    float  tMax;
};

struct SurfaceHit {
    bool      hit;
    HitKind   kind;
    float     t;
    float3    position;        // camera-relative position
    // Current voxel/hardware backends wrap the section origin modulo 1024.
    // This is a procedural anchor, not a globally unique world position and
    // must not by itself key history reprojection or emitter identity.
    float3    worldPosition;
    float3    geometricNormal;
    float3    shadingNormal;   // equals geometricNormal until a normal map is decoded
    float3    tangent;         // optional orthonormal tangent frame; zero when unavailable
    float3    bitangent;
    float2    uv;
    float2    barycentrics;
    float2    light;           // x: block light 0..1, y: sky light 0..1
    float3    tint;            // biome tint * baked AO, already linear
    uint      instanceId;      // TLAS instance or voxel section slot
    uint      primitiveId;     // triangle index or voxel index
    uint      sectionId;       // stable section identity
    uint      materialId;      // dense block-material table index
    uint      flags;
};

// Hit flags are additive; unknown bits are ignored by consumers.
static const uint HIT_FLAG_FRONT_FACE   = 1u << 0u;
static const uint HIT_FLAG_CUTOUT       = 1u << 1u;
static const uint HIT_FLAG_TRANSLUCENT  = 1u << 2u;
// A backend may provide this hint, but its absence does not mean non-emissive:
// the hardware traversal is material-agnostic. Resolve emission after material
// decoding until a scene-wide authoritative emitter-identity contract exists.
static const uint HIT_FLAG_EMISSIVE     = 1u << 3u;
static const uint HIT_FLAG_ENTITY       = 1u << 4u;
static const uint HIT_FLAG_SELF         = 1u << 5u;
static const uint HIT_FLAG_TANGENT_VALID = 1u << 6u;

SurfaceHit missHit(float tMax) {
    SurfaceHit result;
    result.hit = false;
    result.kind = HitKind.Miss;
    result.t = tMax;
    result.position = float3(0.0);
    result.worldPosition = float3(0.0);
    result.geometricNormal = float3(0.0);
    result.shadingNormal = float3(0.0);
    result.tangent = float3(0.0);
    result.bitangent = float3(0.0);
    result.uv = float2(0.0);
    result.barycentrics = float2(0.0);
    result.light = float2(0.0);
    result.tint = float3(1.0);
    result.instanceId = SECTION_INDEX_NONE;
    result.primitiveId = SECTION_INDEX_NONE;
    result.sectionId = SECTION_INDEX_NONE;
    result.materialId = 0u;
    result.flags = 0u;
    return result;
}

Ray makeRay(float3 origin, float3 direction, float tMin, float tMax) {
    Ray ray;
    ray.origin = origin;
    ray.tMin = tMin;
    ray.direction = direction;
    ray.tMax = tMax;
    return ray;
}

float3 rayAt(Ray ray, float t) {
    return ray.origin + ray.direction * t;
}

// A visibility query is not a boolean because transmittance through coloured
// glass and water is part of the contract.  The first geometric blocker is
// returned so the material layer can decide whether it is a cutout hole, a
// translucent filter or an opaque wall; traversal itself stays material-free.
struct VisibilityResult {
    bool       visible;
    float      distance;
    float3     transmittance;
    SurfaceHit blocker;
};

VisibilityResult visibleResult(float distance) {
    VisibilityResult result;
    result.visible = true;
    result.distance = distance;
    result.transmittance = float3(1.0);
    result.blocker = missHit(distance);
    return result;
}

VisibilityResult blockedResult(SurfaceHit blocker, float3 transmittance) {
    VisibilityResult result;
    result.visible = false;
    result.distance = blocker.t;
    result.transmittance = transmittance;
    result.blocker = blocker;
    return result;
}

// One vertex of a path.  ReSTIR PT and the path tracer share this record; the
// traversal backend is deliberately absent from it.
struct PathVertex {
    float3 position;
    float3 normal;
    float3 throughput;
    float  pdf;
    float  bsdfPdf;
    float  eta;
    uint   depth;
    uint   flags;
};

// Payload carried through recursive or iterative path sampling.  Keeping the
// seed here makes temporal reproducibility a property of the path, not of the
// dispatch index.
struct RayPayload {
    float3 radiance;
    float3 throughput;
    uint   depth;
    uint   seed;
    uint   flags;
    float  eta;
};

RayPayload emptyPayload(uint seed) {
    RayPayload payload;
    payload.radiance = float3(0.0);
    payload.throughput = float3(1.0);
    payload.depth = 0u;
    payload.seed = seed;
    payload.flags = 0u;
    payload.eta = 1.0;
    return payload;
}
