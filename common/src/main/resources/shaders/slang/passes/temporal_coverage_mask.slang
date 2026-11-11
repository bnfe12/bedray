import "history/world_motion.slang";

// Coverage from the particles/weather replay and the current primary RT material.
struct CoveragePush {
    uint2 worldMotion;
    uint hasParticles;
    uint pad;
};
[[vk::push_constant]] ConstantBuffer<CoveragePush> pc;
[[vk::binding(0, 0)]] Texture2D<float4> particleLayer;
[[vk::image_format("r8")]] [[vk::binding(1, 0)]] RWTexture2D<float> coverageMask;
[[vk::image_format("r8")]] [[vk::binding(2, 0)]] RWTexture2D<float> reactiveMask;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint width;
    uint height;
    coverageMask.GetDimensions(width, height);
    if (id.x >= width || id.y >= height) return;
    int2 pixel = int2(id.xy);
    float coverage = pc.hasParticles != 0u ? saturate(particleLayer.Load(int3(pixel, 0)).a) : 0.0;
    uint64_t address = uint64_t(pc.worldMotion.x) | (uint64_t(pc.worldMotion.y) << 32u);
    if (address != 0u) {
        float4 hit = ((float4*)address)[id.y * width + id.x];
        coverage = max(coverage, worldMotionCoverage(hit));
        // Newly spawned or changed geometry has no valid previous-frame position.
        if (hit.x > 1.0e29 || any(isnan(hit.xyz)) || any(isinf(hit.xyz))) coverage = 1.0;
    }
    coverageMask[pixel] = saturate(coverage);
    reactiveMask[pixel] = min(saturate(coverage), 0.9);
}
