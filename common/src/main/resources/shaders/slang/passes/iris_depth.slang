// Iris/OpenGL depth conversion, AOT Slang entry.
//
// Converts the host's reverse-Z raster depth into Iris/OpenGL 0-near/1-far
// depth. Bindings and the 128-byte push block mirror the old utility pass:
//   binding 0: sampled raster depth (R32F)
//   binding 1: R32F output
//   push: inverseProjection, inverseView0..2 (xyz = columns, w = encoding),
//         parameters = near, far, hardware-to-GL scale, bias.

public module iris_depth_pass;

[[vk::binding(0, 0)]] Sampler2D rasterDepth;
[[vk::image_format("r32f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> irisDepth;

struct IrisDepthPush {
    float4x4 inverseProjection;
    float4 inverseView0;
    float4 inverseView1;
    float4 inverseView2;
    float4 parameters;
};

[[vk::push_constant]] IrisDepthPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 threadId : SV_DispatchThreadID)
{
    int2 pixel = int2(threadId.xy);
    uint2 size;
    irisDepth.GetDimensions(size.x, size.y);
    if (pixel.x >= int(size.x) || pixel.y >= int(size.y)) {
        return;
    }

    float depth = rasterDepth.Load(int3(pixel, 0)).r;
    float converted = 1.0;
    int encoding = int(pc.inverseView0.w);
    if (!isnan(depth) && !isinf(depth)) {
        if (encoding == 0) {
            converted = clamp(depth * pc.parameters.z + pc.parameters.w, 0.0, 1.0);
        } else if (encoding == 1) {
            converted = clamp(depth, 0.0, 1.0);
        } else if (depth > 0.0) {
            float distance = depth;
            if (encoding == 3) {
                float2 ndc = (float2(pixel) + 0.5) / float2(size) * 2.0 - 1.0;
                float4 view = mul(pc.inverseProjection, float4(ndc, 0.0, 1.0));
                if (abs(view.w) > 1.0e-8) {
                    float3 ray = view.xyz / view.w;
                    // The three vectors are the columns of the inverse view
                    // matrix, so the product is three dot products.
                    float3 world = float3(dot(pc.inverseView0.xyz, ray),
                        dot(pc.inverseView1.xyz, ray), dot(pc.inverseView2.xyz, ray));
                    float worldLength = length(world);
                    distance = worldLength > 1.0e-8 ? depth * abs(ray.z) / worldLength : 1.0e30;
                } else {
                    distance = 1.0e30;
                }
            }
            float near = pc.parameters.x;
            float far = pc.parameters.y;
            converted = clamp((far / (far - near)) * (1.0 - near / max(distance, near)), 0.0, 1.0);
        }
    }
    irisDepth[pixel] = float4(converted, 0.0, 0.0, 0.0);
}
