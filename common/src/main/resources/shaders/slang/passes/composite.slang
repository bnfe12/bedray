// Screen-space depth composite, Slang entry point.
//
// The Java host binds the same five resources as the legacy GLSL pass:
//   0 raster colour (combined image sampler)
//   1 raster depth  (combined image sampler, reverse-Z)
//   2 ray-traced colour (combined image sampler)
//   3 ray hit distance (combined image sampler)
//   4 composite output (storage image)
//
// Push constants are byte-for-byte the legacy 80-byte block.

public module composite_pass;

struct CompositePush {
    float4x4 invViewProj;
    uint2 size;
    float depthThreshold;
    float pad;
};

[[vk::binding(0, 0)]] Sampler2D rasterColor;
[[vk::binding(1, 0)]] Sampler2D rasterDepth;
[[vk::binding(2, 0)]] Sampler2D rtColor;
[[vk::binding(3, 0)]] Sampler2D rtDepth;
// The legacy GLSL pass wrote this storage image as rgba8 (rt_composite.comp); the format must match the bound view.
[[vk::image_format("rgba8")]] [[vk::binding(4, 0)]] RWTexture2D<float4> outComposite;

[[vk::push_constant]] CompositePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 threadId : SV_DispatchThreadID) {
    int2 p = int2(threadId.xy);
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
    float distRaster = (abs(worldPosRel.w) > 1.0e-7)
        ? length(worldPosRel.xyz / worldPosRel.w)
        : 1.0e6;

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
        float a = saturate(rColor.a);
        float3 blended = lerp(rtCol.rgb, rColor.rgb, a);
        outComposite[p] = float4(blended, max(rtCol.a, a));
        return;
    }

    outComposite[p] = rtCol;
}
