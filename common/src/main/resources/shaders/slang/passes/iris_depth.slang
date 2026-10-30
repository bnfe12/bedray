// Iris depth bridge: the host's reverse-Z raster depth becomes the 0-near/1-far
// window depth an Iris shader pack expects. The bridge owns the output image and
// the sampler, writes a 128-byte push block (float4x4 + four float4) and
// dispatches 8x8 groups, so the two must stay in lockstep with IrisDepthConverter.
public module iris_depth_pass;
[[vk::binding(0, 0)]] Sampler2D rasterDepth;
[[vk::image_format("r32f")]] [[vk::binding(1, 0)]] RWTexture2D<float> irisDepth;
struct IrisDepthPush {
    float4x4 inverseProjection;
    float4 inverseView0;   // xyz = inverse model-view column, w = depth encoding
    float4 inverseView1;
    float4 inverseView2;
    float4 parameters;     // near, far, hardware-to-GL scale, bias
};
[[vk::push_constant]] IrisDepthPush pc;
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    irisDepth.GetDimensions(size.x, size.y);
    if (tid.x >= size.x || tid.y >= size.y) return;
    int2 pixel = int2(tid.xy);
    float depth = rasterDepth.Load(int3(pixel, 0)).r;
    float converted = 1.0;
    int encoding = int(pc.inverseView0.w);
    if (!isnan(depth) && !isinf(depth)) {
        if (encoding == 0) {
            converted = clamp(depth * pc.parameters.z + pc.parameters.w, 0.0, 1.0);
        } else if (encoding == 1) {
            converted = clamp(depth, 0.0, 1.0);
        } else if (depth > 0.0) {
            float rayDistance = depth;
            if (encoding == 3) {
                float2 ndc = (float2(pixel) + 0.5) / float2(size) * 2.0 - 1.0;
                float4 view = mul(pc.inverseProjection, float4(ndc, 0.0, 1.0));
                if (abs(view.w) > 1e-8) {
                    float3 ray = view.xyz / view.w;
                    // GLSL's mat3(a, b, c) builds columns; Slang's float3x3 builds rows, so the apply is spelled out.
                    float3 worldRay = pc.inverseView0.xyz * ray.x + pc.inverseView1.xyz * ray.y + pc.inverseView2.xyz * ray.z;
                    float worldLength = length(worldRay);
                    rayDistance = worldLength > 1e-8 ? depth * abs(ray.z) / worldLength : 1e30;
                } else {
                    rayDistance = 1e30;
                }
            }
            float nearPlane = pc.parameters.x;
            float farPlane = pc.parameters.y;
            converted = clamp((farPlane / (farPlane - nearPlane)) * (1.0 - nearPlane / max(rayDistance, nearPlane)),
                              0.0, 1.0);
        }
    }
    irisDepth[pixel] = converted;
}
