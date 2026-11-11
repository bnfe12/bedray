public module world_motion;

// Half-float bits of a non-negative value, by integer arithmetic.  f32tof16 / f16tof32 make slangc declare the SPIR-V
// Float16 capability, which the core ABI has no bit for (the AOT build refuses the pass: "capability 9 has no ABI
// mapping"), and the two fields below need neither a sign nor rounding to nearest.  Values past the largest finite
// half clamp to it; values below the smallest subnormal become zero.
uint worldMotionHalfBits(float value) {
    uint bits = asuint(max(value, 0.0));
    uint exponent = (bits >> 23u) & 0xffu;
    uint mantissa = bits & 0x7fffffu;
    if (exponent < 103u) return 0u;
    if (exponent < 113u) return (mantissa | 0x800000u) >> (126u - exponent);
    if (exponent > 142u) return 0x7bffu;
    return ((exponent - 112u) << 10u) | (mantissa >> 13u);
}

float worldMotionHalfValue(uint half) {
    uint exponent = (half >> 10u) & 0x1fu;
    uint mantissa = half & 0x3ffu;
    if (exponent == 0u) return float(mantissa) * 5.9604644775390625e-8;
    return asfloat(((exponent + 112u) << 23u) | (mantissa << 13u));
}

// One render-grid record in the second half of the motion buffer. XYZ is previous-minus-current world motion.
// W contains two half-float fields, primary hit distance and compositing coverage. The NRD half stays independent.
float4 packWorldMotion(float3 delta, float hitDistance, float coverage) {
    uint packed = worldMotionHalfBits(clamp(hitDistance, 0.0, 65504.0)) | (worldMotionHalfBits(saturate(coverage)) << 16u);
    return float4(delta, asfloat(packed));
}

float worldMotionDistance(float4 value) {
    return worldMotionHalfValue(asuint(value.w) & 0xffffu);
}

float worldMotionCoverage(float4 value) {
    return worldMotionHalfValue(asuint(value.w) >> 16u);
}
