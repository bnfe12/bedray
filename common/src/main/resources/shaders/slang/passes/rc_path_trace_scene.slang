// Native scene primary path with opt-in finite-window RC diffuse continuation.
// Same path/guide ABI plus immutable per-recording field bindings34/35.
public module rc_path_trace_scene_pass;
import "radiance_cascades/field_gather.slang";

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

[[vk::binding(34, 0)]] ConstantBuffer<RCFieldConstants> rcField;
[[vk::binding(35, 0)]] StructuredBuffer<RCSegment> rcTails;
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
    RCPathDiffuseField field;
    field.field = rcField; field.tails = rcTails; field.frameIndex = frame.frame.x;
    pathTraceEntryWithField(traversal, entities, tid, outImage, albedoAtlas, outMotion, materialAtlas, normalAtlas,
        outDepth, outViewZ, outNormalRough, outSpecular, outDiffuse, outAlbedo, outDirect, outSpecAlbedo, outMotionBuffer,
        frame, blockMaterials, emitters, reservoirs, pc, field);
}
