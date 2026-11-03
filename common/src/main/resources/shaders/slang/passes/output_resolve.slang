public module output_resolve_pass;
import "post/output_resolve.slang";
[[vk::image_format("rgba8")]] [[vk::binding(0, 0)]] RWTexture2D<float4> rawColor;
[[vk::image_format("r32f")]] [[vk::binding(1, 0)]] RWTexture2D<float> rayDepth;
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> outputColor;
[[vk::image_format("r32f")]] [[vk::binding(3, 0)]] RWTexture2D<float> outputDepth;
struct OutputResolvePush {
    float4 forward;
    float4 right;
    float4 up;
    float2 jitter;
    uint2 size;
    uint colorReady;
    uint reserved0;
    uint reserved1;
    uint reserved2;
};
[[vk::push_constant]] OutputResolvePush pc;
[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 p = tid.xy;
    if (any(p >= pc.size)) return;
    uint2 inputSize;
    rayDepth.GetDimensions(inputSize.x, inputSize.y);
    float2 uv = (float2(p) + 0.5) / float2(pc.size);
    uint2 tap = min(uint2(uv * float2(inputSize)), inputSize - 1u);
    float2 sourceNdc = (float2(tap) + 0.5 + pc.jitter) / float2(inputSize) * 2.0 - 1.0;
    outputDepth[p] = resolvedRayDistance(rayDepth[tap], sourceNdc, uv * 2.0 - 1.0,
        pc.forward, pc.right, pc.up);
    if (pc.colorReady != 0u) return;
    // A skipped/failed RR frame still fills every output pixel from the raw displayed image.
    float2 source = uv * float2(inputSize) - 0.5;
    int2 base = int2(floor(source));
    float2 blend = frac(source);
    int2 hi = int2(inputSize) - 1;
    float4 a = lerp(rawColor[clamp(base, int2(0), hi)], rawColor[clamp(base + int2(1,0), int2(0), hi)], blend.x);
    float4 b = lerp(rawColor[clamp(base + int2(0,1), int2(0), hi)], rawColor[clamp(base + int2(1,1), int2(0), hi)], blend.x);
    outputColor[p] = lerp(a, b, blend.y);
}
