// Path-tracing entry for Bedray 2.0.
//
// The pass is a thin host boundary: it owns the bindings, builds one camera ray
// per pixel from FrameConstants and hands the path to the generic tracePath.
// Geometry comes from LegacySectionTraversal, which reads the section table and
// the planar arena RtWorld already uploads; no geometry repack and no second BLAS.
//
// Binding numbers mirror the legacy rt_path.comp where the resource exists, so
// the host can reason about both paths side by side.  The two new resources
// (FrameConstants and the block-material table) live above the legacy range.
//
//   0  tlas             acceleration structure
//   1  outImage         RGBA16F storage image (scene-referred linear radiance, no exposure or tone map)
//   2  sections         StructuredBuffer<LegacySection>
//   3  albedoAtlas      combined image sampler
//   9  materialAtlas    combined image sampler (LabPBR _s)
//   10 normalAtlas      combined image sampler (LabPBR _n)
//   12 outDepth         R32F storage image (first accepted surface distance, 1e6 on a miss)
//   16 frame            ConstantBuffer<FrameConstants> (608 B)
//   17 blockMaterials   StructuredBuffer<BlockMaterial> (dummy table until voxel materials exist)
//
// The manifest records Int64, PhysicalStorageBufferAddresses and RayQueryKHR as
// required capabilities; the Java host must enable the matching ABI bits.

public module path_trace_pass;

import "contracts/contracts.slang";
import "light/sky.slang";
import "light/sun.slang";
import "pathtrace/path_tracer.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/legacy_section_traversal.slang";
import "traversal/traversal.slang";

static const uint PATH_TRACE_INSTANCE_MASK_WORLD = 0x01u;

struct PathTracePush {
    uint  maxDepth;
    uint  maxSurfaceSteps;
    uint  sampleIndex;
    uint  materialCount;
    uint  flags;
    float rayBias;
    float alphaCutoff;
    float maxDistance;
    float maxRadiance;
    float sunProbability;
};

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;
[[vk::binding(3, 0)]] Sampler2D albedoAtlas;
[[vk::binding(9, 0)]] Sampler2D materialAtlas;
[[vk::binding(10, 0)]] Sampler2D normalAtlas;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(17, 0)]] StructuredBuffer<BlockMaterial> blockMaterials;

[[vk::push_constant]] PathTracePush pc;

// Camera-relative ray through the centre of a jittered pixel.  The camera basis
// is explicit; `origin` is the view-bob eye offset in raw-camera-relative
// world space, matching the TLAS anchor the host builds around the raw camera
// position.  The jittered inverse view-projection is for reprojection, not for
// primary rays.
Ray pathTracePrimaryRay(FrameConstants f, int2 pixel) {
    float2 jittered = float2(pixel) + 0.5 + f.camera.jitter.xy;
    float2 ndc = jittered * f.camera.jitter.zw * 2.0 - 1.0;
    float tanHalf = f.camera.forward.w;
    float aspect = f.camera.right.w;
    float3 direction = normalize(f.camera.forward.xyz
        + f.camera.right.xyz * (ndc.x * tanHalf * aspect)
        - f.camera.up.xyz * (ndc.y * tanHalf));
    return makeRay(f.camera.origin.xyz, direction, 0.0, max(pc.maxDistance, 1.0));
}

uint pathTraceSeed(int2 pixel, uint frameIndex, uint sampleIndex) {
    uint seed = uint(pixel.x) * 1973u + uint(pixel.y) * 9277u;
    seed = pcgHash(seed + frameIndex * 26699u);
    return pcgHash(seed + sampleIndex * 6971u);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    outImage.GetDimensions(size.x, size.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }

    FrameConstants f = frame;

    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas;
    atlas.material = materialAtlas;
    atlas.normal = normalAtlas;

    // The legacy triangle path has no voxel hit, so the block-material table is
    // present only to satisfy the shared decodeSurface signature.  A host that
    // later resolves block states fills count and the buffer.
    BlockMaterialTable table;
    table.materials = blockMaterials;
    table.count = pc.materialCount;

    LegacySectionTraversal traversal;
    traversal.tlas = tlas;
    traversal.sections = sections;
    traversal.instanceMask = PATH_TRACE_INSTANCE_MASK_WORLD;

    SunMoonSampler lights;
    lights.sun = f.sun;
    lights.sunProbability = pc.sunProbability;

    GradientSky environment;
    environment.atmosphere = f.atmosphere;

    PathTraceOptions options = defaultPathTraceOptions();
    options.maxDepth = max(pc.maxDepth, 1u);
    options.maxSurfaceSteps = max(pc.maxSurfaceSteps, 1u);
    options.rayBias = max(pc.rayBias, 0.0);
    options.alphaCutoff = pc.alphaCutoff;
    options.maxDistance = max(pc.maxDistance, 1.0);
    options.maxRadiance = max(pc.maxRadiance, 1.0);
    options.flags = pc.flags;

    Ray primary = pathTracePrimaryRay(f, p);
    uint seed = pathTraceSeed(p, f.frame.x, pc.sampleIndex);
    // One cutoff for the primary path, material decoding and the filtered
    // shadow walk; otherwise a near-threshold texel can be solid to the camera
    // and a hole to the shadow ray.
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    materialOptions.alphaCutoff = options.alphaCutoff;
    PathTraceResult result = tracePath(traversal, lights, environment, atlas, table,
        primary, materialOptions, options, seed);

    // Scene-referred linear radiance: no exposure and no tone map here.  Exposure lives in FrameConstants.exposure and
    // is applied once by the display/tonemap pass, so accumulation and denoise keep working in linear HDR.
    outImage[p] = float4(result.radiance, 1.0);
    // Legacy depth contract (rt_path.comp): a miss writes 1e6, because bloom.slang treats anything at or beyond
    // BLOOM_SKY_DISTANCE (1e5) as sky and the composite pass uses the same buffer.  firstHitDistance alone defaults
    // to the ray far plane on a miss, which is far below that threshold and would make the sky glow.
    outDepth[p] = (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u ? result.firstHitDistance : 1.0e6;
}
