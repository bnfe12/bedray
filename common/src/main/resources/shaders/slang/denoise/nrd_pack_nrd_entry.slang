// NRD-only resources must not enter RR's RG16F motion contract.
public module nrd_pack_nrd_entry;
__exported import "denoise/nrd_pack_entry.slang";
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> gbAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(12, 0)]] RWTexture2D<float4> nrdInMotion;

void nrdPackPixel(uint2 pixel, NrdPackPush pc, RWTexture2D<float4> normalOutput) {
    if (any(pixel >= pc.size)) return;
    NrdPackInput input = {};
    input.viewZ = gbViewZ[pixel];
    if (!nrdIgnored(input.viewZ, pc)) {
        input.normalRoughness = gbNormalRough[pixel];
        input.diffuse = gbDiffuse[pixel];
        input.specular = gbSpecular[pixel];
        input.albedo = gbAlbedo[pixel].rgb;
        if (pc.motionAddress != 0) {
            float4* values = (float4*)pc.motionAddress;
            input.motion = values[pixel.y * pc.size.x + pixel.x];
        }
    }
    NrdPackedSample sample = nrdPackSample(input, pc);
    normalOutput[pixel] = sample.normalRoughness;
    nrdInDiffuse[pixel] = sample.diffuse;
    nrdInSpecular[pixel] = sample.specular;
    nrdInMotion[pixel] = sample.motion;
}
