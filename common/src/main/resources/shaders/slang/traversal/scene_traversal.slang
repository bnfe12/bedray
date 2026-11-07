// Native scene traversal: the TLAS and the 64-byte section records owned by
// bedray_scene, decoded through the shader-facing GeometryVertex stream.
//
// This is the replacement for LegacySectionTraversal once the host binds the
// native scene table.  It keeps the same ISceneTracer contract and the same
// entity handling as the legacy adapter:
//
//   * a world instance's instanceCustomIndex is the section table slot;
//   * an entity instance is routed to traversal/entity_geometry.slang by the
//     instance bit, before the section table is touched;
//   * the first-person body is skipped only by the primary ray.
//
// The section record carries a device address of its GeometryVertex stream
// (64 B stride), so this module needs Int64/PhysicalStorageBufferAddresses in
// addition to RayQueryKHR, exactly like the legacy table did.

public module scene_traversal;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "traversal/entity_geometry.slang";
import "traversal/hardware_traversal.slang";
import "traversal/traversal.slang";
import "contracts/triangle_intersection.slang";
import "traversal/triangle_query.slang";

static const uint SCENE_INSTANCE_MASK_WORLD    = 0x01u;
static const uint SCENE_INSTANCE_MASK_ENTITIES = 0x02u;
static const uint SCENE_INSTANCE_MASK_ALL      = 0x03u;

struct SceneTraversal : ITriangleTracer {
    RaytracingAccelerationStructure tlas;
    GeometryProvider geometry;
    uint instanceMask;

    // The frame's captured entities, in the same TLAS and the same
    // camera-relative space as the sections.  `cameraAnchor` is the origin the
    // ring was written around, which is all an entity hit has for a world
    // position: entity geometry carries no section identity.
    EntityGeometry entities;
    float3 cameraAnchor;

    // The local player's own captured body, as a range of entity primitives.
    uint selfAvatarTriStart;
    uint selfAvatarTriCount;
    // SELF_SHADOW_* for shadow, visibility and diffuse rays (setting self_shadow).
    uint selfShadowMode;

    // One committed hit named: the instance bit says whether the triangle came
    // out of the entity ring or out of a section.  The section decoder reads the
    // native record and the GeometryVertex stream at its device address.
    SurfaceHit decodeHit(Ray ray, uint instanceId, uint geometryIndex, uint primitiveId,
            float2 bary, float t) {
        if (entityInstance(instanceId)) {
            return entityHitToSurface(entities, cameraAnchor, ray, instanceId, primitiveId, bary, t);
        }
        return hardwareHitToSurface(geometry, ray, instanceId, geometryIndex, primitiveId, bary, t);
    }

    // The candidate loop commits selectively for one reason: a primitive the
    // primary ray is not allowed to see must never become a hit at all, and the
    // entity ring is the only geometry that has such primitives.  Cutout texels
    // are not filtered here - they are a material question, and the surface
    // layer answers it.
    uint secondarySelfShadowMode() {
        return selfShadowMode;
    }

    SurfaceHit traceNearest(Ray ray, uint selfMode) {
        return decodeIntersection(ray, traceIntersection(ray, selfMode));
    }

    TriangleIntersection traceIntersection(Ray ray, uint selfMode) {
        return traceTriangleIntersection(tlas, ray, instanceMask, selfAvatarTriStart, selfAvatarTriCount, selfMode);
    }

    SurfaceHit decodeIntersection(Ray ray, TriangleIntersection hit) {
        return hit.hit ? decodeHit(ray, hit.instanceId, hit.geometryIndex, hit.primitiveId, hit.barycentrics, hit.t)
            : missHit(ray.tMax);
    }

    SurfaceHit traceClosest(Ray ray) {
        return traceNearest(ray, selfShadowMode);
    }

    override SurfaceHit tracePrimary(Ray ray) {
        return traceNearest(ray, SELF_SHADOW_OFF);
    }

    override SurfaceHit traceMirror(Ray ray) {
        return traceNearest(ray, SELF_SHADOW_FULL);
    }

    // A section cannot move: its geometry is uploaded once and the world around
    // a player is static within a frame.  A captured model can, and the ring
    // says where each of its vertices stood one frame ago.
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

        // The first-person body casts its shadow per the self_shadow setting;
        // by default not onto the floor patch its own feet cover.
        RayQuery<RAY_FLAG_NONE> query;
        query.TraceRayInline(tlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, instanceMask, desc);
        while (query.Proceed()) {
            if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE) continue;
            if (selfOccluderSkipped(selfShadowMode, entityInstance(query.CandidateInstanceID()),
                query.CandidatePrimitiveIndex(), selfAvatarTriStart, selfAvatarTriCount, ray.origin)) continue;
            query.CommitNonOpaqueTriangleHit();
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
