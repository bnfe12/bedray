public module world_motion;

// One render-grid record in the second half of the motion buffer. XYZ is previous-minus-current world motion.
// W contains two half-float fields, primary hit distance and compositing coverage. The NRD half stays independent.
float4 packWorldMotion(float3 delta, float hitDistance, float coverage) {
    uint packed = f32tof16(clamp(hitDistance, 0.0, 65504.0)) | (f32tof16(saturate(coverage)) << 16u);
    return float4(delta, asfloat(packed));
}

float worldMotionDistance(float4 value) {
    return f16tof32(asuint(value.w) & 0xffffu);
}

float worldMotionCoverage(float4 value) {
    return f16tof32(asuint(value.w) >> 16u);
}
