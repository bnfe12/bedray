public module rr_depth;

// Exact primary-ray metadata of the trace that produced the linear guide.
public struct RrDepthPush {
    float4 forward;
    float4 right;
    float4 up;
    float4 origin;
    float4 clipZ;
    float4 clipW;
    float4 jitter;
    uint2 size;
    uint2 reserved;
};

public bool rrHasSurfaceDepth(float viewDepth) {
    return isfinite(viewDepth) && viewDepth > 0.0 && viewDepth < 1.0e5;
}

public float rrHardwareDepth(float viewDepth, float2 pixel, RrDepthPush pc) {
    if (!rrHasSurfaceDepth(viewDepth)) return 0.0;
    float2 ndc = (pixel + 0.5 + pc.jitter.xy) * pc.jitter.zw * 2.0 - 1.0;
    float3 direction = normalize(pc.forward.xyz
        + pc.right.xyz * (ndc.x * pc.forward.w * pc.right.w)
        - pc.up.xyz * (ndc.y * pc.forward.w));
    float cosine = dot(direction, pc.forward.xyz);
    if (!isfinite(cosine) || cosine <= 1.0e-6) return 0.0;
    float4 point = float4(pc.origin.xyz + direction * (viewDepth / cosine), 1.0);
    float z = dot(pc.clipZ, point), w = dot(pc.clipW, point);
    return isfinite(z) && isfinite(w) && w > 1.0e-6 ? clamp(z / w, 0.0, 1.0) : 0.0;
}
