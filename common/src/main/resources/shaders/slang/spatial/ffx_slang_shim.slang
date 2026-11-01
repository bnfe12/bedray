// Slang portability layer preserving the live GLSL fp32 shim for the fp32 GPU paths of AMD FidelityFX ffx_fsr1.h (FidelityFX SDK v2.3.0).
// Written for this mod (LGPL-3.0-or-later). The SDK v2.3.0 ships only the HLSL core (ffx_core_hlsl.h); this file maps
// the names that ffx_fsr1.h uses to Slang. The bit-trick approximations below reproduce
// Kits/FidelityFX/api/internal/gpu/ffx_core_gpu_common.h (ffxApproximateReciprocal 0x7ef07ebb,
// ffxApproximateReciprocalMedium 0x7ef19fff + one Newton step, ffxApproximateReciprocalSquareRoot 0x5f347d74), MIT.
// Half precision paths of ffx_fsr1.h are disabled (FFX_HALF 0).

#define FFX_GPU 1
#define FFX_GLSL 1
#define FFX_HALF 0
#define FFX_STATIC
#define FFX_PARAMETER_IN in
#define FFX_PARAMETER_OUT out
#define FFX_PARAMETER_INOUT inout
#define FFX_TRUE true
#define FFX_FALSE false

#define FfxBoolean bool
#define FfxFloat32 float
#define FfxFloat32x2 float2
#define FfxFloat32x3 float3
#define FfxFloat32x4 float4
#define FfxInt32 int
#define FfxInt32x2 int2
#define FfxUInt32 uint
#define FfxUInt32x2 uint2
#define FfxUInt32x3 uint3
#define FfxUInt32x4 uint4

#define ffxAsFloat(x) asfloat(x)
#define ffxAsUInt32(x) asuint(x)
uint ffxPackHalf2x16(float2 value) {
    return spirv_asm { result: $$uint = OpExtInst glsl450 PackHalf2x16 $value };
}
#define ffxBroadcast2(x) float2(x)
#define ffxBroadcast3(x) float3(x)
#define ffxBroadcast4(x) float4(x)
#define ffxReciprocal(x) (1.0 / (x))
#define ffxSaturate(x) clamp((x), 0.0, 1.0)
#define ffxMin(x, y) min((x), (y))
#define ffxMin3(x, y, z) min((x), min((y), (z)))
#define ffxMax3(x, y, z) max((x), max((y), (z)))
#define ffxFract(x) frac(x)
#define ffxSqrt(x) sqrt(x)
#define ffxIsGreaterThanZero(m) ffxSaturate((m) * asfloat(0x7f800000u))

float ffxApproximateReciprocal(float value) {
    return asfloat(0x7ef07ebbu - asuint(value));
}

float ffxApproximateReciprocalMedium(float value) {
    float b = asfloat(0x7ef19fffu - asuint(value));
    return b * (-b * value + 2.0);
}

float3 ffxApproximateReciprocalMedium(float3 value) {
    float3 b = asfloat(uint3(0x7ef19fffu) - asuint(value));
    return b * (-b * value + float3(2.0));
}

float ffxApproximateReciprocalSquareRoot(float value) {
    return asfloat(0x5f347d74u - (asuint(value) >> 1u));
}
