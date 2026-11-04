import "denoise/rr_depth.slang";
[[vk::binding(0,0)]] [[vk::image_format("r32f")]] RWTexture2D<float> linearDepth;
[[vk::binding(1,0)]] [[vk::image_format("r32f")]] RWTexture2D<float> hardwareDepth;
[[vk::push_constant]] ConstantBuffer<RrDepthPush> pc;

[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.size.x || id.y >= pc.size.y) return;
    int2 pixel = int2(id.xy);
    hardwareDepth[pixel] = rrHardwareDepth(linearDepth[pixel], float2(pixel), pc);
}
