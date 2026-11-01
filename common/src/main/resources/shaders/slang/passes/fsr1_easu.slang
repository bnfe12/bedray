// AMD FidelityFX FSR1 fp32 AOT host. The checked-in MIT vendor algorithm is included unchanged.
[[vk::binding(0, 0)]] Sampler2D spatialInput;
[[vk::image_format("rgba8")]] [[vk::binding(1, 0)]] RWTexture2D<float4> spatialOutput;
struct SpatialEasuPush { uint4 con0; uint4 con1; uint4 con2; uint4 con3; uint4 outSize; };
[[vk::push_constant]] SpatialEasuPush pc;
#define FFX_FSR_EASU_FLOAT 1
#include "../spatial/ffx_slang_shim.slang"
#include "../../third_party/fsr1/ffx_fsr1.h"
float4 FsrEasuRF(float2 p) { return spatialInput.GatherRed(p); }
float4 FsrEasuGF(float2 p) { return spatialInput.GatherGreen(p); }
float4 FsrEasuBF(float2 p) { return spatialInput.GatherBlue(p); }
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint2 ip = id.xy;
    if (any(ip >= pc.outSize.xy)) return;
    float3 color;
    ffxFsrEasuFloat(color, ip, pc.con0, pc.con1, pc.con2, pc.con3);
    spatialOutput[ip] = float4(color, 1.0);
}
