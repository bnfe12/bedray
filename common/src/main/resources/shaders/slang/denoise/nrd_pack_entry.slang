// Shared resource adapter; format-specific entries own binding 5.
public module nrd_pack_entry;
__exported import "denoise/nrd_pack.slang";

[[vk::image_format("r32f")]] [[vk::binding(0, 0)]] RWTexture2D<float> gbViewZ;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> gbNormalRough;
[[vk::image_format("rgba16f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> gbDiffuse;
[[vk::image_format("rgba16f")]] [[vk::binding(4, 0)]] RWTexture2D<float4> gbDirect;
[[vk::image_format("rgba16f")]] [[vk::binding(6, 0)]] RWTexture2D<float4> nrdInDiffuse;
[[vk::image_format("rgba16f")]] [[vk::binding(9, 0)]] RWTexture2D<float4> gbSpecular;
[[vk::image_format("rgba16f")]] [[vk::binding(10, 0)]] RWTexture2D<float4> nrdInSpecular;
[[vk::image_format("rgba8")]] [[vk::binding(13, 0)]] RWTexture2D<float4> gbSpecAlbedo;

// RR's motion target is RG16F. Keep it entirely outside this entry's resource
// contract rather than depending on an untaken RGBA16F write branch.
void nrdPackRrPixel(uint2 pixel, NrdPackPush pc, RWTexture2D<float4> normalOutput) {
    if (any(pixel >= pc.size)) return;
    NrdPackInput input = {};
    input.viewZ = gbViewZ[pixel];
    if (!nrdIgnored(input.viewZ, pc)) {
        input.normalRoughness = gbNormalRough[pixel];
        input.diffuse = gbDiffuse[pixel];
        input.specular = gbSpecular[pixel];
        input.direct = gbDirect[pixel].rgb;
        input.specularAlbedo = gbSpecAlbedo[pixel].rgb;
    }
    pc.denoiser = 2u;
    NrdPackedSample sample = nrdPackSample(input, pc);
    normalOutput[pixel] = sample.normalRoughness;
    nrdInDiffuse[pixel] = sample.diffuse;
    nrdInSpecular[pixel] = sample.specular;
}
