// Export the recording's accumulated mean, never its sample count or a single noisy trace.
public module path_hdr_export_pass;
[[vk::image_format("rgba32f")]] [[vk::binding(0, 0)]] RWTexture2D<float4> accumulatedHdr;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outputHdr;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    outputHdr.GetDimensions(size.x, size.y);
    if (any(tid.xy >= size)) return;
    uint2 inputSize;
    accumulatedHdr.GetDimensions(inputSize.x, inputSize.y);
    if (any(tid.xy >= inputSize)) {
        outputHdr[tid.xy] = float4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float3 color = accumulatedHdr[tid.xy].rgb;
    color = select(isfinite(color), clamp(color, float3(0.0), float3(65504.0)), float3(0.0));
    outputHdr[tid.xy] = float4(color, 1.0);
}
