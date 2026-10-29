public module nrd_pack_rr_pass;
import "denoise/nrd_pack_entry.slang";
[[vk::image_format("rgba16f")]] [[vk::binding(5, 0)]] RWTexture2D<float4> nrdInNormalRough;
[[vk::push_constant]] NrdPackPush pc;
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) { nrdPackRrPixel(tid.xy, pc, nrdInNormalRough); }
