// Particle layer composite for Bedray 2.0.
//
// Lays the Minecraft particle/weather layer over the final ray-traced image.
// Pipeline/descriptors are native-owned; the host supplies game layer views.
//
// The layer is cleared to (0,0,0,0) and the game's particle pipelines blend
// into it with their own blend functions, so a pixel holds premultiplied
// colour (translucent particles: src.rgb * src.a, opaque / cutout particles:
// rgb with alpha 1) and the coverage in alpha. The ray traced image is tone
// mapped RGBA8 in the same display space as the vanilla colour, so the "over"
// operator needs no conversion:
//
//     out.rgb = layer.rgb * strength + rt.rgb * (1 - layer.a)
//
// rt_output keeps its alpha. Runs in place on the display image between the
// denoiser and bloom / god rays (flames and magic bloom), so an untouched
// pixel is skipped without reading or writing the image.
//
// The pass is deliberately thin (one binding pair, one 16-byte push): there is
// no algorithm here to share, so no separate module exists — one file, one
// entry, the Bedray 2.0 pass convention. GPU execution remains unverified.

public module particles_composite_pass;

[[vk::binding(0, 0)]] Texture2D<float4> particleLayer;
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> rtImage;

struct ParticlesCompositePush {
    uint2 size;      // display resolution (the layer is the same size)
    float strength;  // 1.0 = vanilla particle colour; < 1 dims the layer (debug / future lighting hook)
    float pad;
};

[[vk::push_constant]] ParticlesCompositePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    uint2 p = dispatchThreadID.xy;
    if (p.x >= pc.size.x || p.y >= pc.size.y) return;
    uint2 layerSize;
    uint2 outputSize;
    particleLayer.GetDimensions(layerSize.x, layerSize.y);
    rtImage.GetDimensions(outputSize.x, outputSize.y);
    if (any(p >= layerSize) || any(p >= outputSize) || isnan(pc.strength) ||
        isinf(pc.strength) || pc.strength < 0.0) return;

    float4 layer = particleLayer.Load(int3(p, 0));
    if (any(isnan(layer)) || any(isinf(layer))) return;
    if (layer.a <= 0.0 && dot(layer.rgb, float3(1.0)) <= 0.0) {
        return; // untouched pixel: no read / write of the RT image
    }
    float4 rt = rtImage[p];
    if (any(isnan(rt)) || any(isinf(rt))) return;
    float coverage = clamp(layer.a, 0.0, 1.0);
    float3 outRgb = layer.rgb * pc.strength + rt.rgb * (1.0 - coverage);
    rtImage[p] = float4(clamp(outRgb, float3(0.0), float3(1.0)), rt.a);
}
