// Minimal GLSL portability layer for the fp32 GPU paths of AMD FidelityFX ffx_fsr1.h (FidelityFX SDK v2.3.0).
// Written for this mod (LGPL-3.0-or-later). The SDK v2.3.0 ships only the HLSL core (ffx_core_hlsl.h); this file maps
// the names that ffx_fsr1.h uses to GLSL. The bit-trick approximations below reproduce
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
#define FfxFloat32x2 vec2
#define FfxFloat32x3 vec3
#define FfxFloat32x4 vec4
#define FfxInt32 int
#define FfxInt32x2 ivec2
#define FfxUInt32 uint
#define FfxUInt32x2 uvec2
#define FfxUInt32x3 uvec3
#define FfxUInt32x4 uvec4

#define ffxAsFloat(x) uintBitsToFloat(x)
#define ffxAsUInt32(x) floatBitsToUint(x)
#define ffxPackHalf2x16(x) packHalf2x16(x)
#define ffxBroadcast2(x) vec2(x)
#define ffxBroadcast3(x) vec3(x)
#define ffxBroadcast4(x) vec4(x)
#define ffxReciprocal(x) (1.0 / (x))
#define ffxSaturate(x) clamp((x), 0.0, 1.0)
#define ffxMin(x, y) min((x), (y))
#define ffxMin3(x, y, z) min((x), min((y), (z)))
#define ffxMax3(x, y, z) max((x), max((y), (z)))
#define ffxFract(x) fract(x)
#define ffxSqrt(x) sqrt(x)
#define ffxIsGreaterThanZero(m) ffxSaturate((m) * uintBitsToFloat(0x7f800000u))

float ffxApproximateReciprocal(float value) {
    return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(value));
}

float ffxApproximateReciprocalMedium(float value) {
    float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(value));
    return b * (-b * value + 2.0);
}

vec3 ffxApproximateReciprocalMedium(vec3 value) {
    vec3 b = uintBitsToFloat(uvec3(0x7ef19fffu) - floatBitsToUint(value));
    return b * (-b * value + vec3(2.0));
}

float ffxApproximateReciprocalSquareRoot(float value) {
    return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(value) >> 1u));
}
