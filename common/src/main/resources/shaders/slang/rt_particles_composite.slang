// Slang Tier 1 port of rt_particles_composite.comp: the vanilla particle layer (premultiplied colour + coverage)
// over the ray traced image, in place. out.rgb = layer.rgb*strength + rt.rgb*(1-layer.a); rt alpha kept.
// Untouched pixels (a<=0 and rgb==0) early-out with no read/write of the RT image.
// ABI preserved: binding 0 sampler2D particleLayer, binding 1 rgba8 storage rtImage (read+write);
// push constant uvec2 size + float strength + float pad (16 bytes); entry main, numthreads 8x8x1.
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    uint2 size;
    float strength;
    float pad;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> particleLayer;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> rtImage;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= int(pc.size.x) || p.y >= int(pc.size.y)) {
        return;
    }
    float4 layer = particleLayer.Load(int3(p, 0));
    if (layer.a <= 0.0 && dot(layer.rgb, float3(1.0)) <= 0.0) {
        return;
    }
    float4 rt = rtImage[p];
    float a = clamp(layer.a, 0.0, 1.0);
    float3 outRgb = layer.rgb * pc.strength + rt.rgb * (1.0 - a);
    rtImage[p] = float4(clamp(outRgb, 0.0, 1.0), rt.a);
}