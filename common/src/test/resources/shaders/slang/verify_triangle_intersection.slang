// Production predicate/miss/scheduling contracts, executed by the native CPU harness.
import "contracts/triangle_intersection.slang";
[[vk::binding(0, 0)]] RWStructuredBuffer<uint> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    sink[0] = excludedSelfAvatar(true, true, 40u, 40u, 10u);
    sink[1] = excludedSelfAvatar(true, true, 49u, 40u, 10u);
    sink[2] = !excludedSelfAvatar(true, true, 39u, 40u, 10u);
    sink[3] = !excludedSelfAvatar(true, true, 50u, 40u, 10u);
    sink[4] = !excludedSelfAvatar(true, true, 40u, 40u, 0u);
    sink[5] = !excludedSelfAvatar(true, false, 40u, 40u, 10u); // world triangle shares the primitive number
    sink[6] = !excludedSelfAvatar(false, true, 40u, 40u, 10u); // reflection/shadow keeps avatar
    sink[7] = excludedSelfAvatar(true, true, 0xffffffffu, 0xfffffffeu, 2u); // no end-range overflow
    TriangleIntersection hit = missIntersection(900.0);
    sink[8] = !hit.hit && hit.t == 900.0 && hit.instanceId == 0u && hit.geometryIndex == 0u
        && hit.primitiveId == 0u && all(hit.barycentrics == float2(0.0));
    sink[9] = triangleScheduleClass(hit, true) == 0u;
    hit.hit = true;
    sink[10] = triangleScheduleClass(hit, false) == 1u;
    sink[11] = triangleScheduleClass(hit, true) == 2u;
}
