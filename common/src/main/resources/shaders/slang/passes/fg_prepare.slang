// Producer-only frame-generation preparation. Same raster convention as the
// existing fg_prepare.comp: flip rows, retain reverse-Z depth, negate MV.y.
public module fg_prepare_pass;
[[vk::binding(0, 0)]] Sampler2D depthTexture;
[[vk::image_format("rg16f")]] [[vk::binding(1, 0)]] RWTexture2D<float2> inMotionVectors;
[[vk::image_format("r32f")]] [[vk::binding(2, 0)]] RWTexture2D<float> outDepth;
[[vk::image_format("rg16f")]] [[vk::binding(3, 0)]] RWTexture2D<float2> outMotionVectors;
struct FgPreparePush { uint2 size; };
[[vk::push_constant]] FgPreparePush pc;
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 pixel = tid.xy;
    if (any(pixel >= pc.size)) return;
    uint2 source = uint2(pixel.x, pc.size.y - 1u - pixel.y);
    float depth = depthTexture.Load(int3(source, 0)).r;
    float2 motion = inMotionVectors[source];
    outDepth[pixel] = depth;
    outMotionVectors[pixel] = float2(motion.x, -motion.y);
}
