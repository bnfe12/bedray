// AMD FidelityFX FSR1 fp32 AOT host. The checked-in MIT vendor algorithm is included unchanged.
[[vk::binding(0, 0)]] Sampler2D spatialInput;
[[vk::image_format("rgba8")]] [[vk::binding(1, 0)]] RWTexture2D<float4> spatialOutput;
struct SpatialRcasPush { uint4 con; uint4 size; };
[[vk::push_constant]] SpatialRcasPush pc;
#define FSR_RCAS_F 1
#include "../spatial/ffx_slang_shim.slang"
#include "../../third_party/fsr1/ffx_fsr1.h"
float4 FsrRcasLoadF(int2 p) {
    int2 q = clamp(p, int2(0), int2(pc.size.xy) - int2(1));
    return spatialInput.Load(int3(q, 0));
}
void FsrRcasInputF(inout float r, inout float g, inout float b) {}
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint2 ip = id.xy;
    if (any(ip >= pc.size.xy)) return;
    float r, g, b;
    FsrRcasF(r, g, b, ip, pc.con);
    spatialOutput[ip] = float4(r, g, b, 1.0);
}
