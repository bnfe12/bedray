// Shared by the HDR output passes (passes/hdr_scene.slang, passes/hdr_output.slang): the SDR transfer curve both of them
// must decode the same way, because the output pass compares the frame with the scene pass's SDR copy.

float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
    return select(c <= float3(0.04045), lo, hi);
}
