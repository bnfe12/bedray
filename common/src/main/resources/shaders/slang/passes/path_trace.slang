// Path-tracing entry for Bedray 2.0.
//
// The pass is a thin host boundary: it owns the bindings and the legacy
// traversal backend, then hands the shared body (pathtrace/path_trace_entry.slang)
// the pixel.  Geometry comes from LegacySectionTraversal, which reads the section
// table and the planar arena RtWorld already uploads; no geometry repack and no
// second BLAS.
//
// Binding numbers mirror the legacy rt_path.comp where the resource exists, so
// the host can reason about both paths side by side.  The two new resources
// (FrameConstants and the block-material table) live above the legacy range.
//
//   0  tlas             acceleration structure
//   1  outImage         RGBA16F storage image (scene-referred linear radiance, no exposure or tone map; a = 0 for a
//                       sample the tracer could not trust, which the display pass then leaves out of the mean)
//   2  sections         StructuredBuffer<LegacySection>
//   3  albedoAtlas      combined image sampler
//   9  materialAtlas    combined image sampler (LabPBR _s)
//   10 normalAtlas      combined image sampler (LabPBR _n)
//   5  outMotion        RGBA16F storage image (where the primary surface stood one frame ago minus where it is now, in
//                       world blocks; a = 1 when it is a captured model, so a static mob reads 0,1 and terrain 0,0)
//   12 outDepth         R32F storage image (first accepted surface distance, 1e6 on a miss)
//   16 frame            ConstantBuffer<FrameConstants> (608 B)
//   17 blockMaterials   StructuredBuffer<BlockMaterial> (dummy table until voxel materials exist)
//   18 emitters         StructuredBuffer<BlockEmitterRecord> (the RtEmitters list of the legacy path, 96 B per record)
//   19 reservoirs       RWStructuredBuffer<DIReservoir> (ReSTIR DI: two parity halves, 64 B per pixel)
//   20 entityPositions  StructuredBuffer<uint>  - float3 per captured vertex, as float bits
//   21 entityIndices    StructuredBuffer<uint>  - three per entity triangle
//   22 entityAttrs      StructuredBuffer<uint4> - colour, light, u, v per captured vertex
//   23 entityRects      StructuredBuffer<uint4> - one atlas slot's rect and authored PBR
//   24 entityTexels     StructuredBuffer<uint>  - the RT entity atlas, packed bytes
//   25 entityPrevious   StructuredBuffer<uint>  - the same float3 layout, one frame earlier
//   26 outViewZ         R32F denoiser guide (view depth)
//   27 outNormalRough   RGBA16F guide (normal + roughness)
//   28 outSpecular      RGBA16F guide (zero: the path tracer sums its lobes)
//   29 outDiffuse       RGBA16F guide (residual radiance + first hit distance)
//   30 outAlbedo        RGBA8 guide
//   31 outDirect        RGBA16F guide (the primary vertex's own NEE)
//   32 outSpecAlbedo    RGBA8 guide (metal albedo or dielectric F0)
//
// The six entity buffers are this frame's ring slot, bound at its own offset:
// the bound length is the triangle count, so a frame with nothing captured binds
// a one-element dummy and reads zero triangles rather than a stale model.
//
// The manifest records Int64, PhysicalStorageBufferAddresses and RayQueryKHR as
// required capabilities; the Java host must enable the matching ABI bits.

public module path_trace_pass;

import "contracts/contracts.slang";
import "pathtrace/path_trace_entry.slang";
import "traversal/legacy_section_traversal.slang";

// Terrain and the entity ring are one scene: the same TLAS holds both, and the
// instance's custom-index bit is what tells a mob quad from a section quad.
static const uint PATH_TRACE_INSTANCE_MASK_SCENE = 0x03u;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;
[[vk::binding(3, 0)]] Sampler2D albedoAtlas;
[[vk::image_format("rgba16f")]] [[vk::binding(5, 0)]] RWTexture2D<float4> outMotion;
[[vk::binding(9, 0)]] Sampler2D materialAtlas;
[[vk::binding(10, 0)]] Sampler2D normalAtlas;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(17, 0)]] StructuredBuffer<BlockMaterial> blockMaterials;
[[vk::binding(18, 0)]] StructuredBuffer<BlockEmitterRecord> emitters;
[[vk::binding(19, 0)]] RWStructuredBuffer<DIReservoir> reservoirs;
[[vk::binding(20, 0)]] StructuredBuffer<uint> entityPositions;
[[vk::binding(21, 0)]] StructuredBuffer<uint> entityIndices;
[[vk::binding(22, 0)]] StructuredBuffer<uint4> entityAttrs;
[[vk::binding(23, 0)]] StructuredBuffer<uint4> entityRects;
[[vk::binding(24, 0)]] StructuredBuffer<uint> entityTexels;
[[vk::binding(25, 0)]] StructuredBuffer<uint> entityPrevious;
// Denoiser guides, the same seven channels rt_path.comp writes for rt_pack_nrd.comp.  The host binds one-pixel
// stand-ins and leaves PATH_TRACE_OPTION_GBUFFER clear when no denoiser is selected.
[[vk::image_format("r32f")]]    [[vk::binding(26, 0)]] RWTexture2D<float> outViewZ;
[[vk::image_format("rgba16f")]] [[vk::binding(27, 0)]] RWTexture2D<float4> outNormalRough;
[[vk::image_format("rgba16f")]] [[vk::binding(28, 0)]] RWTexture2D<float4> outSpecular;
[[vk::image_format("rgba16f")]] [[vk::binding(29, 0)]] RWTexture2D<float4> outDiffuse;
[[vk::image_format("rgba8")]]   [[vk::binding(30, 0)]] RWTexture2D<float4> outAlbedo;
[[vk::image_format("rgba16f")]] [[vk::binding(31, 0)]] RWTexture2D<float4> outDirect;
[[vk::image_format("rgba8")]]   [[vk::binding(32, 0)]] RWTexture2D<float4> outSpecAlbedo;
// NRD's IN_MV channel: the same per-pixel Vec4 rt_path.comp writes into StatsBuf.motionAddr.  The host binds a
// one-element stand-in and leaves PATH_TRACE_OPTION_NRD_MOTION clear unless the NRD layout is selected.
[[vk::binding(33, 0)]] RWStructuredBuffer<float4> outMotionBuffer;

[[vk::push_constant]] PathTracePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    // This frame's captured models.  The buffers are bound at the ring slot's
    // own offset, so the bound length is the frame's vertex and triangle count:
    // a frame with nothing captured binds one-element dummies and decodes zero
    // triangles rather than a stale model.
    EntityAtlas entityAtlas;
    entityAtlas.rects = entityRects;
    entityAtlas.texels = entityTexels;
    entityAtlas.atlasSize = pc.entityState.x;

    EntityGeometry entities;
    entities.positions = entityPositions;
    entities.previous = entityPrevious;
    entities.indices = entityIndices;
    entities.attrs = entityAttrs;
    entities.atlas = entityAtlas;

    LegacySectionTraversal traversal;
    traversal.tlas = tlas;
    traversal.sections = sections;
    traversal.instanceMask = PATH_TRACE_INSTANCE_MASK_SCENE;
    traversal.entities = entities;
    // An entity hit has no section to anchor to, so its world position is
    // `hit + camRel`, where camRel is the camera against the origin the frame
    // was anchored at.  worldPosition is a wrapped neighbourhood proxy, not a
    // unique identity; this is the whole of what an entity hit can carry.
    traversal.cameraAnchor = pc.emitterCameraRelative.xyz;
    // The first-person body sits around the eye, so only the camera ray looks
    // past it; every other ray of the path crosses it like any other geometry.
    traversal.selfAvatarTriStart = pc.entityState.y;
    traversal.selfAvatarTriCount = pc.entityState.z;
    pathTraceEntry(traversal, entities, tid, outImage, albedoAtlas, outMotion, materialAtlas, normalAtlas,
        outDepth, outViewZ, outNormalRough, outSpecular, outDiffuse, outAlbedo, outDirect, outSpecAlbedo, outMotionBuffer,
        frame, blockMaterials, emitters, reservoirs, pc);
}
