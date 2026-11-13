// Slang Tier 1 port of iris_depth.comp (host reverse-Z raster depth -> Iris/OpenGL 0-near/1-far depth).
// ABI preserved: binding 0 sampler2D rasterDepth, binding 1 r32f storage irisDepth; push constant 128 bytes
// (mat4 inverseProjection + 3 vec4 inverseView columns with encoding in inverseView0.w + vec4 parameters);
// entry main, numthreads 8x8x1. GLSL mat3(col0,col1,col2) builds COLUMNS; Slang float3x3(a,b,c) builds ROWS,
// so the column form is transpose(float3x3(...)). Matrix bytes are column-major; GLSL M*v -> mul(M,v).
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    float4x4 inverseProjection;
    float4 inverseView0;
    float4 inverseView1;
    float4 inverseView2;
    float4 parameters;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> rasterDepth;
[[vk::binding(1, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> irisDepth;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 pixel = int2(id.xy);
    uint w, h;
    irisDepth.GetDimensions(w, h);
    int2 size = int2(int(w), int(h));
    if (pixel.x >= size.x || pixel.y >= size.y) {
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
                if (abs(view.w) > 1e-8) {
                    float3 ray = view.xyz / view.w;
                    float3x3 inverseView = transpose(float3x3(pc.inverseView0.xyz, pc.inverseView1.xyz, pc.inverseView2.xyz));
                    float worldLength = length(mul(inverseView, ray));
                    distance = worldLength > 1e-8 ? depth * abs(ray.z) / worldLength : 1e30;
                } else {
                    distance = 1e30;
                }
            }
            float near = pc.parameters.x;
            float far = pc.parameters.y;
            converted = clamp((far / (far - near)) * (1.0 - near / max(distance, near)), 0.0, 1.0);
        }
    }
    irisDepth[pixel] = converted;
}