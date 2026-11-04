// Typed native compute specialization; Vulkan 1.1 with no optional device features.
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/compose.slang";
[[vk::binding(0,0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(1,0)]] ByteAddressBuffer gridData;
[[vk::binding(2,0)]] StructuredBuffer<CubeFace> palette;
[[vk::binding(3,0)]] Sampler2D blockAtlas;
[[vk::binding(4,0)]] ByteAddressBuffer modelData;
[[vk::binding(5,0)]] StructuredBuffer<NoRtEnvironment> environment;
[[vk::push_constant]] ConstantBuffer<NoRtPush> diagnostic;
[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 id : SV_DispatchThreadID) {
    NoRtRenderer<StreamedNoRtScene,TexturedNoRtMaterials,NoRtRasterAbsent> renderer;
    renderer.scene=StreamedNoRtScene(gridData);
    renderer.textures=TexturedNoRtMaterials (palette,blockAtlas,modelData,environment);
    renderer.rasterInputs=NoRtRasterAbsent();
    renderer.diagnostic=diagnostic;
    renderer.outputImage=outputImage;
    renderer.render(id);
}
