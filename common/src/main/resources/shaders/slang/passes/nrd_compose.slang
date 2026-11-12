public module nrd_compose_pass;
import "denoise/nrd_pack.slang";
[[vk::image_format("r32f")]] [[vk::binding(0, 0)]] RWTexture2D<float> gbViewZ;
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> gbAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(4, 0)]] RWTexture2D<float4> gbDirect;
[[vk::image_format("rgba16f")]] [[vk::binding(7, 0)]] RWTexture2D<float4> nrdOutDiffuse;
[[vk::image_format("rgba8")]] [[vk::binding(8, 0)]] RWTexture2D<float4> rtOutput;
[[vk::image_format("rgba16f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> nrdOutSpecular;
[[vk::image_format("rgba8")]] [[vk::binding(13, 0)]] RWTexture2D<float4> gbSpecAlbedo;
[[vk::push_constant]] NrdPackPush pc;
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 pixel = tid.xy;
    if (any(pixel >= pc.size)) return;
    // RR's colour can be output-sized while all geometry guides remain input-sized.
    // Its complete radiance includes sky: never index input guides on this output grid.
    if (pc.denoiser == 2u) {
        rtOutput[pixel] = float4(nrdPostCinema(nrdComposeColor(nrdOutDiffuse[pixel].rgb, float3(0.0), float3(0.0),
            float4(0.0), float4(0.0), pc), pixel, pc), 1.0);
        return;
    }
    if (nrdIgnored(gbViewZ[pixel], pc)) return;
    if ((pc.flags & 2u) != 0u && pixel.x < pc.size.x / 2u) return;
    float3 specular = float3(0.0), albedo = float3(0.0);
    float4 direct = float4(0.0);
    float4 specularMaterial = float4(0.0);
    if (pc.denoiser != 2u) {
        specular = nrdOutSpecular[pixel].rgb;
        albedo = gbAlbedo[pixel].rgb;
        specularMaterial = gbSpecAlbedo[pixel];
        direct = gbDirect[pixel];
    }
    float3 color = nrdPostCinema(nrdComposeColor(nrdOutDiffuse[pixel].rgb, specular, albedo, specularMaterial, direct, pc), pixel, pc);
    rtOutput[pixel] = float4(color, 1.0);
}
