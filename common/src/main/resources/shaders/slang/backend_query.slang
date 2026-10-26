// backend_query.slang — Ray traversal backend using VK_KHR_ray_query (inline RT).
// Works on ALL GPUs with ray tracing support: NVIDIA RTX 20/30/40/50, AMD RDNA 2+, Intel Arc.
// This is the Tier 1 and Tier 2 backend.

module backend_query;

import types;
import bindings;
import ray_backend;

// Alpha-test helper: decodes the atlas UV from barycentric coordinates and
// tests the alpha channel. Returns true if the candidate should be accepted.
// This mirrors CANDIDATE_ACCEPTED() from the old GLSL.
bool candidateAccepted(RayQuery<RAY_FLAG_NONE> rq, bool shadow) {
    // Get the intersection attributes
    float2 bary = rq.CandidateTriangleBarycentrics();
    int    inst = rq.CandidateInstanceID();
    int    prim = rq.CandidatePrimitiveIndex();

    // Look up the section for this instance
    Section sec = sections[inst];
    uint geom   = cycleGeometry(sec, prim);
    uint quad   = cycleQuad(sec, prim, geom);

    // Interpolate UV from the quad's 4 vertices (triangle = half of a quad)
    float2 uv = interpolateQuadUV(sec, quad, prim, bary);

    // Sample the atlas alpha
    float a = atlas.SampleLevel(uv, 0).a;

    // Cutout geometry (pass 1): accept only if alpha > 0.5
    uint pass = (uint(sec.origin.w) >> (geom * 2u)) & 3u;
    if (pass == 1u) return a > 0.5;

    // Solid geometry (pass 0): always accept
    if (pass == 0u) return true;

    // Translucent (pass 2): let shadows pass through, otherwise accept
    return !shadow;
}

struct RayQueryBackend : IRayBackend {
    float traceVisibility(float3 origin, float3 dir, float tmax) {
        if (tmax <= 0.001) return 1.0;

        RayQuery<RAY_FLAG_NONE> rq;
        RayDesc ray;
        ray.Origin    = origin;
        ray.Direction = dir;
        ray.TMin      = 0.001;
        ray.TMax      = tmax;

        rq.TraceRayInline(tlas, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, MASK_ALL, ray);
        while (rq.Proceed()) {
            if (rq.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                if (candidateAccepted(rq, true)) {
                    rq.CommitNonOpaqueTriangleHit();
                }
            }
        }
        return rq.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.0;
    }

    float traceDist(float3 origin, float3 dir, float tmax) {
        if (tmax <= 0.001) return 0.0;

        RayQuery<RAY_FLAG_NONE> rq;
        RayDesc ray;
        ray.Origin    = origin;
        ray.Direction = dir;
        ray.TMin      = 0.001;
        ray.TMax      = tmax;

        rq.TraceRayInline(tlas, RAY_FLAG_NONE, MASK_ALL, ray);
        while (rq.Proceed()) {
            if (rq.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                if (candidateAccepted(rq, true)) {
                    rq.CommitNonOpaqueTriangleHit();
                }
            }
        }
        if (rq.CommittedStatus() == COMMITTED_NOTHING) return tmax;
        return rq.CommittedRayT();
    }

    HitInfo traceClosest(float3 origin, float3 dir, float tmax) {
        HitInfo info;
        info.hit         = false;
        info.t           = tmax;
        info.instanceId  = -1;
        info.primitiveId = -1;
        info.bary        = float2(0.0);

        if (tmax <= 0.001) return info;

        RayQuery<RAY_FLAG_NONE> rq;
        RayDesc ray;
        ray.Origin    = origin;
        ray.Direction = dir;
        ray.TMin      = 0.001;
        ray.TMax      = tmax;

        rq.TraceRayInline(tlas, RAY_FLAG_NONE, MASK_ALL, ray);
        while (rq.Proceed()) {
            if (rq.CandidateType() == CANDIDATE_NON_OPAQUE_TRIANGLE) {
                if (candidateAccepted(rq, false)) {
                    rq.CommitNonOpaqueTriangleHit();
                }
            }
        }

        if (rq.CommittedStatus() != COMMITTED_NOTHING) {
            info.hit         = true;
            info.t           = rq.CommittedRayT();
            info.instanceId  = rq.CommittedInstanceID();
            info.primitiveId = rq.CommittedPrimitiveIndex();
            info.bary        = rq.CommittedTriangleBarycentrics();
        }
        return info;
    }

    float3 traceTransmittance(float3 origin, float3 dir, float tmax) {
        // TODO: port colored shadow transmission from rt_common_surface.glsl
        // For now, binary visibility as RGB.
        float vis = traceVisibility(origin, dir, tmax);
        return float3(vis);
    }
};
