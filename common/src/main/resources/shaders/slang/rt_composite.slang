// Slang Tier 1 port of rt_composite.comp: screen-space depth composite of rasterized foreground entities over
// the ray-traced world. Reverse-Z raster depth (sky<=1e-6 -> RT sky); reconstruct camera-relative distance via
// invViewProj; adaptive threshold; entity alpha blend (mix->lerp); terrain replaced by RT.
// ABI preserved: bindings 0-3 sampler2D rasterColor/rasterDepth/rtColor/rtDepth, binding 4 rgba8 storage
// outComposite; push constant mat4 invViewProj + uvec2 size + float depthThreshold + float pad (80 bytes);
// entry main, numthreads 8x8x1. Matrix bytes column-major; GLSL M*v -> mul(M,v).
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    float4x4 invViewProj;
    uint2 size;
    float depthThreshold;
    float pad;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> rasterColor;
[[vk::binding(1, 0)]] Sampler2D<float4> rasterDepth;
[[vk::binding(2, 0)]] Sampler2D<float4> rtColor;
[[vk::binding(3, 0)]] Sampler2D<float4> rtDepth;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outComposite;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= int(pc.size.x) || p.y >= int(pc.size.y)) {
        return;
    }
    float2 uv = (float2(p) + 0.5) / float2(pc.size);
    float4 rColor = rasterColor.Load(int3(p, 0));
    float dRaster = rasterDepth.Load(int3(p, 0)).r;
    float4 rtCol = rtColor.Load(int3(p, 0));
    float dRay = rtDepth.Load(int3(p, 0)).r;

    if (dRaster <= 1.0e-6) {
        outComposite[p] = rtCol;
        return;
    }

    float4 clip = float4(uv * 2.0 - 1.0, dRaster, 1.0);
    float4 worldPosRel = mul(pc.invViewProj, clip);
    float distRaster = (abs(worldPosRel.w) > 1.0e-7) ? length(worldPosRel.xyz / worldPosRel.w) : 1.0e6;

    float thresh = max(pc.depthThreshold > 0.0 ? pc.depthThreshold : 0.20, dRay * 0.05);

    bool isEntity = false;
    if (dRay >= 1.0e5) {
        if (distRaster <= 256.0) {
            isEntity = true;
        }
    } else {
        if (distRaster < dRay - thresh) {
            isEntity = true;
        } else if (distRaster < 1.2 && distRaster < dRay - 0.05) {
            isEntity = true;
        }
    }

    if (isEntity && rColor.a > 0.0) {
        float a = clamp(rColor.a, 0.0, 1.0);
        float3 blended = lerp(rtCol.rgb, rColor.rgb, a);
        outComposite[p] = float4(blended, max(rtCol.a, a));
        return;
    }

    outComposite[p] = rtCol;
}