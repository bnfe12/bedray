// Path-tracing entry over the native bedray_scene section table.
//
// This is the native-scene twin of path_trace.slang: the algorithm, bindings,
// push block and outputs are identical, but binding 2 is the 64-byte
// GeometrySection record owned by bedray_scene and the traversal decodes the
// GeometryVertex stream through its device address.  The host selects this
// program only when it can supply a native scene table; the legacy entry stays
// available for the current Java scene.  The shared integration body lives in
// pathtrace/path_trace_entry.slang, so the two entries cannot drift apart.
//
//   0  tlas             acceleration structure
//   1  outImage         RGBA16F storage image (scene-referred linear radiance)
//   2  sections         StructuredBuffer<GeometrySection> (native scene)
//   3  albedoAtlas      combined image sampler
//   9  materialAtlas    combined image sampler (LabPBR _s)
//   10 normalAtlas      combined image sampler (LabPBR _n)
//   5  outMotion        RGBA16F storage image
//   12 outDepth         R32F storage image (first accepted surface distance, 1e6 on a miss)
//   16 frame            ConstantBuffer<FrameConstants> (608 B)
//   17 blockMaterials   StructuredBuffer<BlockMaterial>
//   18 emitters         StructuredBuffer<BlockEmitterRecord>
//   19 reservoirs       RWStructuredBuffer<DIReservoir>
//   20 entityPositions  StructuredBuffer<uint>
//   21 entityIndices    StructuredBuffer<uint>
//   22 entityAttrs      StructuredBuffer<uint4>
//   23 entityRects      StructuredBuffer<uint4>
//   24 entityTexels     StructuredBuffer<uint>
//   25 entityPrevious   StructuredBuffer<uint>
//   26 outViewZ         R32F denoiser guide (view depth)
//   27 outNormalRough   RGBA16F guide (normal + roughness)
//   28 outSpecular      RGBA16F guide (zero: the path tracer sums its lobes)
//   29 outDiffuse       RGBA16F guide (residual radiance + first hit distance)
//   30 outAlbedo        RGBA8 guide
//   31 outDirect        RGBA16F guide (the primary vertex's own NEE)
//   32 outSpecAlbedo    RGBA8 guide (metal albedo or dielectric F0)
//   33 outMotionBuffer  RWStructuredBuffer<float4> NRD IN_MV (one vec4 per pixel)
//   36 ptReservoirs     RWStructuredBuffer<RestirPtReservoir> (ReSTIR PT: two parity halves, 144 B per pixel)
//
// The section record's vertexAddress is a PhysicalStorageBuffer address of a
// GeometryVertex array (64 B stride), so the manifest keeps Int64 and
// PhysicalStorageBufferAddresses alongside RayQueryKHR.

public module path_trace_scene_pass;

import "contracts/contracts.slang";
import "pathtrace/path_trace_entry.slang";
import "traversal/hardware_traversal.slang";
import "traversal/scene_traversal.slang";

// Terrain and the entity ring are one scene: the same TLAS holds both, and the
// instance's custom-index bit is what tells a mob quad from a section quad.
static const uint PATH_TRACE_INSTANCE_MASK_SCENE = 0x03u;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::binding(2, 0)]] StructuredBuffer<GeometrySection> sections;
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
// ReSTIR PT: two parity halves of one 144-byte RestirPtReservoir per pixel (restir/pt_temporal.slang).  A host without the
// ring binds a one-record stand-in and leaves PATH_TRACE_OPTION_RESTIR_PT clear; the stage refuses a ring that short.
[[vk::binding(36, 0)]] RWStructuredBuffer<RestirPtReservoir> ptReservoirs;

[[vk::push_constant]] PathTracePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    // This frame's captured models, shared with the legacy entry.
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

    // The native scene table carries the section origin, the pass ranges and the
    // device address of the GeometryVertex stream.  Entities stay in the same
    // TLAS; the instance bit routes a candidate to the entity decoder before the
    // section table is indexed.
    GeometryProvider geometry;
    geometry.sections = sections;

    SceneTraversal traversal;
    traversal.tlas = tlas;
    traversal.geometry = geometry;
    traversal.instanceMask = PATH_TRACE_INSTANCE_MASK_SCENE;
    traversal.entities = entities;
    traversal.cameraAnchor = pc.emitterCameraRelative.xyz;
    traversal.selfAvatarTriStart = pc.entityState.y;
    traversal.selfAvatarTriCount = pc.entityState.z;
    pathTraceEntry(traversal, entities, tid, outImage, albedoAtlas, outMotion, materialAtlas, normalAtlas,
        outDepth, outViewZ, outNormalRough, outSpecular, outDiffuse, outAlbedo, outDirect, outSpecAlbedo, outMotionBuffer,
        frame, blockMaterials, emitters, reservoirs, ptReservoirs, pc);
}
