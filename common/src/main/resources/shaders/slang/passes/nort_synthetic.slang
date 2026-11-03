// Typed native compute specialization; Vulkan 1.1 with no optional device features.
import "nort/types.slang";
import "nort/inputs.slang";
import "nort/compose.slang";
[[vk::binding(0,0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(1,0)]] ByteAddressBuffer gridData;
[[vk::push_constant]] ConstantBuffer<NoRtPush> diagnostic;
[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 id : SV_DispatchThreadID) {
    NoRtRenderer<DenseNoRtScene,SyntheticNoRtMaterials,NoRtRasterAbsent> renderer;
    renderer.scene=DenseNoRtScene(gridData);
    renderer.textures=SyntheticNoRtMaterials();
    renderer.rasterInputs=NoRtRasterAbsent();
    renderer.diagnostic=diagnostic;
    renderer.outputImage=outputImage;
    renderer.render(id);
}
