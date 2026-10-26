// Screen-space reflections: reflected colour (rgb) and confidence (a) per pixel.
// colorTex rgba16f lit scene (producer: shading); geomTex rgba16f view-space normal + linear depth, 0 = sky
// (producer: G-buffer); materialImg x = roughness 0..1; dstImg rgba16f, a = hit confidence 0..1, 0 = miss
// (consumer: reflection composite). A zero-confidence texel must fall back to sky/probe lighting.

import "screenspace/ssr.slang";

[[vk::binding(0, 0)]] Sampler2D colorTex;               // lit scene colour
[[vk::binding(1, 0)]] Sampler2D geomTex;                // xyz: view normal, w: linear view depth (0 = sky)
[[vk::binding(2, 0)]] Texture2D<float4> materialImg;    // x: roughness
[[vk::image_format("rgba16f")]] [[vk::binding(3, 0)]] RWTexture2D<float4> dstImg;
[[vk::binding(4, 0)]] ConstantBuffer<SsrCamera> camera;

[[vk::push_constant]] SsrPush pc;

struct SceneDepth : IDepthSource {
    float linearDepth(float2 uv) {
        return geomTex.SampleLevel(uv, 0.0).w;
    }
};

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 usize;
    dstImg.GetDimensions(usize.x, usize.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(usize.x) || p.y >= int(usize.y)) {
        return;
    }

    float2 uv = (float2(p) + 0.5) / float2(usize);
    float4 geom = geomTex.SampleLevel(uv, 0.0);
    if (geom.w <= 0.0) {
        dstImg[p] = float4(0.0);
        return;
    }

    float3 normal;
    float3 origin;
    if (!ssrFinite(geom.w) || !ssrUnitNormal(geom.xyz, normal) || !ssrViewPosition(uv, geom.w, camera, origin)) {
        dstImg[p] = float4(0.0);
        return;
    }
    float3 viewDir = reflect(normalize(origin), normal);

    SceneDepth depth;
    SsrHit hit = ssrTrace(depth, camera, origin, viewDir, materialImg.Load(int3(p, 0)).x, pc);
    float3 color = hit.confidence > 0.0 ? colorTex.SampleLevel(hit.uv, 0.0).rgb : float3(0.0);
    if (!ssrFinite3(color)) {
        color = float3(0.0);
        hit.confidence = 0.0;
    }
    dstImg[p] = float4(color, hit.confidence);
}
