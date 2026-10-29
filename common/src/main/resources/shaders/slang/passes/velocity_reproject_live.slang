// AOT replacement preserving the live RG16F upscaler contract.
import "history/velocity_live.slang";

[[vk::binding(0, 0)]] Sampler2D depthTexture;
[[vk::image_format("rg16f")]] [[vk::binding(1, 0)]] RWTexture2D<float2> outMotionVectors;
[[vk::binding(2, 0)]] ConstantBuffer<VelocityLiveUniforms> frame;

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID) {
    int2 pixelCoord = int2(dispatchThreadID.xy);
    int2 size = int2(frame.renderResolution.xy);
    if (pixelCoord.x >= size.x || pixelCoord.y >= size.y) return;
    outMotionVectors[pixelCoord] = reprojectLiveVelocity(pixelCoord, size, depthTexture, frame).xy;
}
