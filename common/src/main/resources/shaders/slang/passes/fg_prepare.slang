// Frame-generation input preparation (DLSS-G / Streamline), AOT Slang entry.
//
// The engine keeps every render target upside down (row 0 = bottom of the
// screen, the swapchain blit flips it back), but the back buffer that
// Streamline sees is upright. This pass writes vertically flipped depth and
// motion-vector copies:
//   depth: copied as is (reverse-Z NDC depth of the world raster pass),
//   motion vectors: rows flipped and y negated (pixels, previous minus
//   current, render resolution).
//
// Bindings and the 8-byte push block are byte-for-byte the old utility pass,
// so the host keeps its pipeline layout.

public module fg_prepare_pass;

[[vk::binding(0, 0)]] Sampler2D depthTexture;
[[vk::image_format("rg16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> inMotionVectors;
[[vk::image_format("r32f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> outDepth;
[[vk::image_format("rg16f")]] [[vk::binding(3, 0)]] RWTexture2D<float4> outMotionVectors;

struct FgPreparePush {
    int2 size;
};

[[vk::push_constant]] FgPreparePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 threadId : SV_DispatchThreadID)
{
    int2 p = int2(threadId.xy);
    if (p.x >= pc.size.x || p.y >= pc.size.y) {
        return;
    }
    int2 src = int2(p.x, pc.size.y - 1 - p.y);
    float d = depthTexture.Load(int3(src, 0)).r;
    float2 mv = inMotionVectors[src].rg;
    outDepth[p] = float4(d, 0.0, 0.0, 0.0);
    outMotionVectors[p] = float4(mv.x, -mv.y, 0.0, 0.0);
}
