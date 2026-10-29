public module nrd_compose_pass;
import "denoise/nrd_pack.slang";
[[vk::image_format("r32f")]] [[vk::binding(0, 0)]] RWTexture2D<float> gbViewZ;
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> gbAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(4, 0)]] RWTexture2D<float4> gbDirect;
[[vk::image_format("rgba16f")]] [[vk::binding(7, 0)]] RWTexture2D<float4> nrdOutDiffuse;
[[vk::image_format("rgba8")]] [[vk::binding(8, 0)]] RWTexture2D<float4> rtOutput;
[[vk::image_format("rgba16f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> nrdOutSpecular;
[[vk::push_constant]] NrdPackPush pc;
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 pixel = tid.xy;
    if (any(pixel >= pc.size) || nrdIgnored(gbViewZ[pixel], pc)) return;
    if ((pc.flags & 2u) != 0u && pixel.x < pc.size.x / 2u) return;
    float3 specular = float3(0.0), albedo = float3(0.0), direct = float3(0.0);
    if (pc.denoiser != 2u) {
        specular = nrdOutSpecular[pixel].rgb;
        albedo = gbAlbedo[pixel].rgb;
        direct = gbDirect[pixel].rgb;
    }
    float3 color = nrdComposeColor(nrdOutDiffuse[pixel].rgb, specular, albedo, direct, pc);
    rtOutput[pixel] = float4(color, 1.0);
}
