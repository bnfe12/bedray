public module output_resolve;

// Preserve the selected sample's view depth when moving its ray distance onto an unjittered output ray.
float resolvedRayDistance(float distance, float2 sourceNdc, float2 outputNdc,
        float4 forward, float4 right, float4 up) {
    if (!isfinite(distance) || distance <= 0.0) return 1.0e6;
    if (distance >= 1.0e5) return distance;
    float3 a = normalize(forward.xyz + right.xyz * sourceNdc.x * forward.w * right.w
        - up.xyz * sourceNdc.y * forward.w);
    float3 b = normalize(forward.xyz + right.xyz * outputNdc.x * forward.w * right.w
        - up.xyz * outputNdc.y * forward.w);
    float denominator = dot(b, forward.xyz);
    return denominator > 1.0e-6 ? distance * max(dot(a, forward.xyz), 0.0) / denominator : distance;
}
