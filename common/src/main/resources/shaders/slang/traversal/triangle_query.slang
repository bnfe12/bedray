// Shared nearest-triangle query for both legacy and native scene-table decoders.
public module triangle_query;
import "contracts/triangle_intersection.slang";
import "contracts/hit.slang";
import "traversal/entity_geometry.slang";

TriangleIntersection traceTriangleIntersection(RaytracingAccelerationStructure tlas, Ray ray,
        uint instanceMask, uint selfStart, uint selfCount, uint selfShadowMode) {
    TriangleIntersection result = missIntersection(ray.tMax);
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
        uint primitive = query.CandidatePrimitiveIndex();
        if (selfOccluderSkipped(selfShadowMode, entityInstance(query.CandidateInstanceID()),
            primitive, selfStart, selfCount)) continue;
        query.CommitNonOpaqueTriangleHit();
    }
    if (query.CommittedStatus() == COMMITTED_NOTHING) return result;
    result.hit = true;
    result.t = query.CommittedRayT();
    result.instanceId = query.CommittedInstanceID();
    result.geometryIndex = query.CommittedGeometryIndex();
    result.primitiveId = query.CommittedPrimitiveIndex();
    result.barycentrics = query.CommittedTriangleBarycentrics();
    return result;
}
