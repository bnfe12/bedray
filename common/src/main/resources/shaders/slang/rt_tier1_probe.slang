// Tier 1 toolchain-validation probe (NOT a production shader port). Exercises the full host-RT
// capability set: ray query, subgroup ballot, device-address pointer (uvec2 -> uint64_t -> T*),
// storage image and structured buffer. Compiled by :common:compileSlangShaders; audited by
// RtSlangCapabilityTest. Removed in Stage 2.3 once real Slang RT variants exist.
struct Stats {
    float4 camRel;
    uint entityTris;
    uint pad0;
    uint pad1;
    uint pad2;
};

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> scratch;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint2> addrBuf;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID)
{
    RayQuery<RAY_FLAG_NONE> q;
    RayDesc ray;
    ray.Origin = float3(0, 0, 0);
    ray.Direction = float3(0, 0, 1);
    ray.TMin = 0.0;
    ray.TMax = 100.0;
    q.TraceRayInline(tlas, RAY_FLAG_NONE, 0xFF, ray);
    while (q.Proceed()) { }
    float t = 0.0;
    if (q.CommittedStatus() != COMMITTED_NOTHING) {
        t = q.CommittedRayT();
    }
    uint2 a = addrBuf[0];
    uint64_t addr = ((uint64_t)a.y << 32) | (uint64_t)a.x;
    Stats* p = (Stats*)addr;
    uint4 ballot = WaveActiveBallot(t > 0.0);
    scratch[tid.x] = ballot.x;
    outImage[tid.xy] = float4(t, p[0].camRel.x, float(ballot.x), float(WaveGetLaneCount()));
}