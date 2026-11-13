// Slang Tier 1 port of fg_prepare.comp (frame generation input preparation, DLSS-G / Streamline).
// The engine keeps render targets upside down; the Streamline back buffer is upright, so depth and motion
// vectors are copied vertically flipped (motion y negated because the row index direction changes sign).
// ABI preserved: binding 0 sampler2D depth, 1 rg16f storage inMotionVectors, 2 r32f storage outDepth,
// 3 rg16f storage outMotionVectors; push constant int2 size (8 bytes); entry main, numthreads 8x8x1.
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    int2 size;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> depthTexture;
[[vk::binding(1, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> inMotionVectors;
[[vk::binding(2, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> outDepth;
[[vk::binding(3, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> outMotionVectors;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= pc.size.x || p.y >= pc.size.y) {
        return;
    }
    int2 src = int2(p.x, pc.size.y - 1 - p.y);
    float d = depthTexture.Load(int3(src, 0)).r;
    float2 mv = inMotionVectors[src];
    outDepth[p] = d;
    outMotionVectors[p] = float2(mv.x, -mv.y);
}