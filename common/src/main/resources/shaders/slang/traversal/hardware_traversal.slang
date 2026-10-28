// Hardware traversal backend: VK_KHR_ray_query against a TLAS.
//
// This backend is deliberately material-agnostic.  It reports the geometric
// candidate; cutout/translucency rules are applied by the material layer.  The
// old backend mixed atlas alpha tests into traversal, which made the voxel and
// hardware paths disagree on foliage.

public module hardware_traversal;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "traversal/traversal.slang";

static const uint HARDWARE_INSTANCE_MASK_WORLD    = 0x01u;
static const uint HARDWARE_INSTANCE_MASK_ENTITIES = 0x02u;
static const uint HARDWARE_INSTANCE_MASK_ALL      = 0x03u;

// The provider owns the triangle stream, not the traversal loop.  A hardware
// backend can therefore shade a hit without importing the legacy Section
// records or any descriptor binding.  The TLAS instanceCustomIndex of a world
// instance is the section table slot; entity instances are routed by
// SceneTraversal before this decoder is reached.
struct GeometryProvider {
    StructuredBuffer<GeometrySection> sections;
};

SurfaceHit hardwareHitToSurface(GeometryProvider geometry, Ray ray,
        uint instanceId, uint geometryIndex, uint primitiveId, float2 bary, float t) {
    SurfaceHit result = missHit(ray.tMax);
    // Safety net only: a world instance's custom index is the section table slot.
    // An entity instance (or a stale slot after a section was removed) must not
    // index the table, so the bound length decides.
    uint sectionCount;
    uint sectionStride;
    geometry.sections.GetDimensions(sectionCount, sectionStride);
    if (instanceId >= sectionCount) return result;
    GeometrySection section = geometry.sections[instanceId];
    // The scene builds one BLAS geometry per non-empty pass range, so the hit's
    // geometry index is the range index and the primitive id is local to it.
    int rangeIndex = geometryRangeForPrimitive(section, geometryIndex);
    if (rangeIndex < 0) return result;
    GeometryRange range = section.ranges[uint(rangeIndex)];
    if ((primitiveId >> 1u) >= geometryRangeQuadCount(range)) return result;

    GeometryVertex v0 = geometryVertexAt(section, geometryIndex, primitiveId, 0u);
    GeometryVertex v1 = geometryVertexAt(section, geometryIndex, primitiveId, 1u);
    GeometryVertex v2 = geometryVertexAt(section, geometryIndex, primitiveId, 2u);

    float3 p0 = v0.position.xyz;
    float3 p1 = v1.position.xyz;
    float3 p2 = v2.position.xyz;
    float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float3 localPosition = p0 * weights.x + p1 * weights.y + p2 * weights.z;

    float3 geometricNormal = safeNormalize(cross(p1 - p0, p2 - p0), -ray.direction);
    bool frontFace = dot(geometricNormal, ray.direction) < 0.0;
    if (!frontFace) geometricNormal = -geometricNormal;

    float2 uv0 = v0.uvLight.xy;
    float2 uv1 = v1.uvLight.xy;
    float2 uv2 = v2.uvLight.xy;
    float2 uv = uv0 * weights.x + uv1 * weights.y + uv2 * weights.z;

    float2 light = (v0.uvLight.zw * weights.x +
                    v1.uvLight.zw * weights.y +
                    v2.uvLight.zw * weights.z);
    float3 tint = v0.tint.rgb * weights.x + v1.tint.rgb * weights.y + v2.tint.rgb * weights.z;

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
    result.sectionId = uint(section.origin.w);
    result.materialId = v0.meta.x;
    result.flags = frontFace ? HIT_FLAG_FRONT_FACE : 0u;

    GeometryPass pass = geometryRangePass(range);
    if (pass == GeometryPass.Cutout) result.flags |= HIT_FLAG_CUTOUT;
    if (pass == GeometryPass.Translucent) result.flags |= HIT_FLAG_TRANSLUCENT;
    // The host's per-quad tags: every vertex of a quad carries the same word, so
    // reading the first one is exact and does not depend on the barycentrics.
    uint vertexFlags = v0.meta.y;
    if ((vertexFlags & GEOMETRY_VERTEX_FLAG_VEGETATION) != 0u) result.flags |= HIT_FLAG_FOLIAGE;
    if ((vertexFlags & GEOMETRY_VERTEX_FLAG_LEAF_CUBE) != 0u) result.flags |= HIT_FLAG_LEAF_CUBE;

    float3 tangent;
    float3 bitangent;
    float3 e1 = p1 - p0;
    float3 e2 = p2 - p0;
    float2 d1 = uv1 - uv0;
    float2 d2 = uv2 - uv0;
    float det = d1.x * d2.y - d2.x * d1.y;
    if (abs(det) > 1.0e-12) {
        tangent = safeNormalize((e1 * d2.y - e2 * d1.y) / det, geometricNormal);
        bitangent = safeNormalize((e2 * d1.x - e1 * d2.x) / det, geometricNormal);
    } else {
        faceTangentFrame(geometricNormal, tangent, bitangent);
    }
    result.tangent = tangent;
    result.bitangent = bitangent;
    result.flags |= HIT_FLAG_TANGENT_VALID;
    return result;
}

struct HardwareTraversal : ISceneTracer {
    RaytracingAccelerationStructure tlas;
    GeometryProvider geometry;
    uint instanceMask;

    SurfaceHit traceClosest(Ray ray) {
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
            if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                query.CommitNonOpaqueTriangleHit();
            }
        }

        if (query.CommittedStatus() == COMMITTED_NOTHING) return result;
        return hardwareHitToSurface(geometry, ray, query.CommittedInstanceID(),
            query.CommittedGeometryIndex(), query.CommittedPrimitiveIndex(),
            query.CommittedTriangleBarycentrics(), query.CommittedRayT());
    }

    VisibilityResult traceVisibility(Ray ray) {
        if (ray.tMax <= ray.tMin) return visibleResult(ray.tMax);

        RayDesc desc;
        desc.Origin = ray.origin;
        desc.Direction = ray.direction;
        desc.TMin = ray.tMin;
        desc.TMax = ray.tMax;

        RayQuery<RAY_FLAG_NONE> query;
        query.TraceRayInline(tlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, instanceMask, desc);
        while (query.Proceed()) {
            if (query.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                query.CommitNonOpaqueTriangleHit();
            }
        }

        if (query.CommittedStatus() == COMMITTED_NOTHING) return visibleResult(ray.tMax);
        SurfaceHit blocker = hardwareHitToSurface(geometry, ray, query.CommittedInstanceID(),
            query.CommittedGeometryIndex(), query.CommittedPrimitiveIndex(),
            query.CommittedTriangleBarycentrics(), query.CommittedRayT());
        return blockedResult(blocker, float3(0.0));
    }

    float traceDistance(Ray ray) {
        SurfaceHit hit = traceClosest(ray);
        return hit.hit ? hit.t : ray.tMax;
    }
};
