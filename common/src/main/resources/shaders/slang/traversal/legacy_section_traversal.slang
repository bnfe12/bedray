// Legacy geometry adapter: VK_KHR_ray_query over the TLAS and the section table
// RtWorld already builds, without repacking geometry into a new BLAS.
//
// The legacy table stores raw device addresses, so a shader that reads it needs
// Int64 and PhysicalStorageBufferAddresses.  The host must enable
// BEDRAY_CAP_SHADER_INT64 and BEDRAY_CAP_BUFFER_DEVICE_ADDRESS on the runtime
// (the generated manifest records exactly that requirement).
//
// Layout, written by RtWorld.writeTableEntry (64 bytes per slot):
//
//   uint64_t posAddr    float3 per vertex, 48 B per quad, section-local blocks
//   uint64_t uvAddr     float2 per vertex, 32 B per quad
//   uint64_t colAddr    uint per vertex,   16 B per quad (R | G<<8 | B<<16 | A<<24)
//   uint64_t lightAddr  uint per vertex,   16 B per quad
//   uint4    quadStart  xyz = first quad of geometry 0..2, w = geometry count
//   int4     origin     xyz = section origin in blocks, w = 2-bit pass per geometry
//
// The light word is block | sky<<8 | material<<16, with light bytes stored as
// clamp(level * 16 + 8, 8, 248).  This is the same decoding as
// rt_common_core.glsl (triangleVertices, unpackColor, unpackLight).

public module legacy_section_traversal;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "traversal/entity_geometry.slang";
import "traversal/traversal.slang";

static const uint LEGACY_INSTANCE_MASK_WORLD    = 0x01u;
static const uint LEGACY_INSTANCE_MASK_ENTITIES = 0x02u;
static const uint LEGACY_INSTANCE_MASK_ALL      = 0x03u;

static const uint LEGACY_VERT_FLAG_VEGETATION   = 0x01000000u;
static const uint LEGACY_VERT_FLAG_LEAF_CUBE    = 0x02000000u;

struct LegacySection {
    uint64_t posAddr;
    uint64_t uvAddr;
    uint64_t colAddr;
    uint64_t lightAddr;
    uint4    quadStart;
    int4     origin;
};

// Primitive 2k is (0,1,2) of quad k, primitive 2k+1 is (2,3,0).  Kept in the
// same order as triangleVertices in the legacy GLSL, so a hit is bit-identical.
uint3 legacyTriangleVertices(uint quad, uint primitive) {
    uint base = quad * 4u;
    bool second = (primitive & 1u) != 0u;
    return uint3(
        base + (second ? 2u : 0u),
        base + (second ? 3u : 1u),
        base + (second ? 0u : 2u));
}

float3 legacyLoadPosition(LegacySection section, uint vertex) {
    float* base = (float*)section.posAddr;
    return float3(base[vertex * 3u], base[vertex * 3u + 1u], base[vertex * 3u + 2u]);
}

float2 legacyLoadUv(LegacySection section, uint vertex) {
    float* base = (float*)section.uvAddr;
    return float2(base[vertex * 2u], base[vertex * 2u + 1u]);
}

uint legacyLoadColor(LegacySection section, uint vertex) {
    uint* base = (uint*)section.colAddr;
    return base[vertex];
}

uint legacyLoadLight(LegacySection section, uint vertex) {
    uint* base = (uint*)section.lightAddr;
    return base[vertex];
}

float3 legacyUnpackColor(uint color) {
    return float3(
        float(color & 255u),
        float((color >> 8u) & 255u),
        float((color >> 16u) & 255u)) / 255.0;
}

// The legacy bytes are level * 16 + 8, clamped to 8..248; level 15 maps to 1.0.
float2 legacyUnpackLight(uint light) {
    float2 raw = float2(float(light & 255u), float((light >> 8u) & 255u));
    return clamp((raw - 8.0) / 16.0 / 15.0, 0.0, 1.0);
}

SurfaceHit legacyHitToSurface(StructuredBuffer<LegacySection> sections, Ray ray,
        uint instanceId, uint geometryIndex, uint primitiveId, float2 bary, float t) {
    SurfaceHit result = missHit(ray.tMax);
    // Safety net only: decodeHit routes an entity instance to its own decoder, so
    // an instance id with the entity bit must never index the section table.
    if (entityInstance(instanceId)) return result;

    LegacySection section = sections[instanceId];
    if (geometryIndex >= section.quadStart.w) return result;

    uint quad = section.quadStart[geometryIndex] + (primitiveId >> 1u);
    uint3 v = legacyTriangleVertices(quad, primitiveId);

    float3 p0 = legacyLoadPosition(section, v.x);
    float3 p1 = legacyLoadPosition(section, v.y);
    float3 p2 = legacyLoadPosition(section, v.z);

    float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float3 localPosition = p0 * weights.x + p1 * weights.y + p2 * weights.z;

    float3 geometricNormal = safeNormalize(cross(p1 - p0, p2 - p0), -ray.direction);
    bool frontFace = dot(geometricNormal, ray.direction) < 0.0;
    if (!frontFace) geometricNormal = -geometricNormal;

    float2 uv0 = legacyLoadUv(section, v.x);
    float2 uv1 = legacyLoadUv(section, v.y);
    float2 uv2 = legacyLoadUv(section, v.z);
    float2 uv = uv0 * weights.x + uv1 * weights.y + uv2 * weights.z;

    uint light0 = legacyLoadLight(section, v.x);
    uint light1 = legacyLoadLight(section, v.y);
    uint light2 = legacyLoadLight(section, v.z);
    float2 light = legacyUnpackLight(light0) * weights.x +
                   legacyUnpackLight(light1) * weights.y +
                   legacyUnpackLight(light2) * weights.z;

    float3 tint = legacyUnpackColor(legacyLoadColor(section, v.x)) * weights.x +
                  legacyUnpackColor(legacyLoadColor(section, v.y)) * weights.y +
                  legacyUnpackColor(legacyLoadColor(section, v.z)) * weights.z;

    result.hit = true;
    result.kind = HitKind.Triangle;
    result.t = t;
    result.position = rayAt(ray, t);
    result.worldPosition = float3(section.origin.xyz & 1023) + localPosition;
    result.geometricNormal = geometricNormal;
    result.shadingNormal = geometricNormal;
    result.uv = uv;
    result.barycentrics = bary;
    result.light = light;
    result.tint = tint;
    result.instanceId = instanceId;
    result.primitiveId = primitiveId;
    // The slot is the stable section identity in the legacy table; origin.w is
    // the packed pass mask, not an id.
    result.sectionId = instanceId;
    result.materialId = (light0 >> 16u) & 0xFFu;
    result.flags = frontFace ? HIT_FLAG_FRONT_FACE : 0u;

    uint geometryPass = (uint(section.origin.w) >> (2u * geometryIndex)) & 3u;
    if (geometryPass == 1u) result.flags |= HIT_FLAG_CUTOUT;
    if (geometryPass == 2u) result.flags |= HIT_FLAG_TRANSLUCENT;
    // The legacy light word carries the same two tags the native GeometryVertex
    // stores in meta.y, so both backends report the same hit flags.
    if ((light0 & LEGACY_VERT_FLAG_VEGETATION) != 0u) result.flags |= HIT_FLAG_FOLIAGE;
    if ((light0 & LEGACY_VERT_FLAG_LEAF_CUBE) != 0u) result.flags |= HIT_FLAG_LEAF_CUBE;

    float3 tangent;
    float3 bitangent;
    triangleTangentFrame(p0, p1, p2, uv0, uv1, uv2, geometricNormal, tangent, bitangent);
    result.tangent = tangent;
    result.bitangent = bitangent;
    result.flags |= HIT_FLAG_TANGENT_VALID;
    return result;
}

struct LegacySectionTraversal : ISceneTracer {
    RaytracingAccelerationStructure tlas;
    StructuredBuffer<LegacySection> sections;
    uint instanceMask;

    // The frame's captured entities, in the same TLAS and the same
    // camera-relative space as the sections.  `cameraAnchor` is the origin the
    // ring was written around, which is all an entity hit has for a world
    // position: entity geometry carries no section identity.
    EntityGeometry entities;
    float3 cameraAnchor;

    // The local player's own captured body, as a range of entity primitives.
    // First person uploads it so a reflection can show the avatar, and the eye
    // sits inside it, so only the ray that leaves the eye skips the range.  A
    // count of 0 means there is no capture (third person, or the option off).
    uint selfAvatarTriStart;
    uint selfAvatarTriCount;

    bool selfAvatarPrimitive(uint instanceId, uint primitive) {
        if (selfAvatarTriCount == 0u || !entityInstance(instanceId)) return false;
        return primitive >= selfAvatarTriStart && primitive < selfAvatarTriStart + selfAvatarTriCount;
    }

    // One committed hit named: the instance bit says whether the triangle came
    // out of the entity ring or out of a section, and nothing else distinguishes
    // them because both live in the same acceleration structure.
    SurfaceHit decodeHit(Ray ray, uint instanceId, uint geometryIndex, uint primitiveId,
            float2 bary, float t) {
        if (entityInstance(instanceId)) {
            return entityHitToSurface(entities, cameraAnchor, ray, instanceId, primitiveId, bary, t);
        }
        return legacyHitToSurface(sections, ray, instanceId, geometryIndex, primitiveId, bary, t);
    }

    // The candidate loop commits selectively for one reason: a primitive the
    // primary ray is not allowed to see must never become a hit at all, and the
    // entity ring is the only geometry that has such primitives.  Cutout texels
    // are not filtered here - they are a material question, and the surface
    // layer answers it.
    SurfaceHit traceNearest(Ray ray, bool excludeSelfAvatar) {
        SurfaceHit result = missHit(ray.tMax);
        if (ray.tMax <= ray.tMin) return result;

        RayDesc desc;
        desc.Origin = ray.origin;
        desc.Direction = ray.direction;
        desc.TMin = ray.tMin;
        desc.TMax = ray.tMax;

        RayQuery<RAY_FLAG_NONE> query;
        query.TraceRayInline(tlas, RAY_FLAG_NONE, instanceMask, desc);
        while (query.Proceed()) {
            if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE) continue;
            if (excludeSelfAvatar && selfAvatarPrimitive(query.CandidateInstanceID(),
                    query.CandidatePrimitiveIndex())) continue;
            query.CommitNonOpaqueTriangleHit();
        }

        if (query.CommittedStatus() == COMMITTED_NOTHING) return result;
        return decodeHit(ray, query.CommittedInstanceID(), query.CommittedGeometryIndex(),
            query.CommittedPrimitiveIndex(), query.CommittedTriangleBarycentrics(),
            query.CommittedRayT());
    }

    SurfaceHit traceClosest(Ray ray) {
        return traceNearest(ray, false);
    }

    override SurfaceHit tracePrimary(Ray ray) {
        return traceNearest(ray, true);
    }

    // A section cannot move: its geometry is uploaded once and the world around a
    // player is static within a frame.  A captured model can, and the ring says
    // where each of its vertices stood one frame ago.
    override float3 surfaceMotion(SurfaceHit hit) {
        if (!hit.hit || hit.kind != HitKind.Entity) return float3(0.0);
        return entityMotion(entities, hit.primitiveId, hit.barycentrics);
    }

    VisibilityResult traceVisibility(Ray ray) {
        if (ray.tMax <= ray.tMin) return visibleResult(ray.tMax);

        RayDesc desc;
        desc.Origin = ray.origin;
        desc.Direction = ray.direction;
        desc.TMin = ray.tMin;
        desc.TMax = ray.tMax;

        // The body of the local player is geometry a light has to cross, so a
        // shadow ray keeps it: only the primary ray may look past it.
        RayQuery<RAY_FLAG_NONE> query;
        query.TraceRayInline(tlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, instanceMask, desc);
        while (query.Proceed()) {
            if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                query.CommitNonOpaqueTriangleHit();
            }
        }

        if (query.CommittedStatus() == COMMITTED_NOTHING) return visibleResult(ray.tMax);
        SurfaceHit blocker = decodeHit(ray, query.CommittedInstanceID(),
            query.CommittedGeometryIndex(), query.CommittedPrimitiveIndex(),
            query.CommittedTriangleBarycentrics(), query.CommittedRayT());
        return blockedResult(blocker, float3(0.0));
    }

    float traceDistance(Ray ray) {
        SurfaceHit hit = traceClosest(ray);
        return hit.hit ? hit.t : ray.tMax;
    }
}
