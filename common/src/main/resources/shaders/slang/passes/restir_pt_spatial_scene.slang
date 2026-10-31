// ReSTIR PT spatial-reuse entry, over the native bedray_scene section table.
//
// One thread per render pixel, one dispatch per frame, after passes/path_trace_scene.slang on a frame whose trace ran the ReSTIR PT
// temporal stage.  It pools four neighbours' path records into each pixel's own and writes them into the other half of
// the ring, which the next frame's temporal import reads; the body is restir/pt_spatial.slang behind
// pathTracePtSpatialEntry in pathtrace/path_trace_entry.slang.
//
// Bindings and push block are passes/path_trace_scene.slang's own, number for number: the host binds the views it bound for the trace and
// pushes the same 112 bytes (PATH_TRACE_PT_PARITY names the half the trace wrote).  Only the resources the first-edge
// visibility walk reads are declared:
//
//   0  tlas             acceleration structure
//   2  sections         StructuredBuffer<GeometrySection>
//   3  albedoAtlas      combined image sampler
//   9  materialAtlas    combined image sampler (LabPBR _s)
//  10  normalAtlas      combined image sampler (LabPBR _n)
//  16  frame            ConstantBuffer<FrameConstants> (608 B) - the trace pass's own upload
//  17  blockMaterials   StructuredBuffer<BlockMaterial>
//  20  entityPositions  StructuredBuffer<uint>
//  21  entityIndices    StructuredBuffer<uint>
//  22  entityAttrs      StructuredBuffer<uint4>
//  23  entityRects      StructuredBuffer<uint4>
//  24  entityTexels     StructuredBuffer<uint>
//  25  entityPrevious   StructuredBuffer<uint>
//  36  ptReservoirs     RWStructuredBuffer<RestirPtReservoir> (two parity halves, 144 B per pixel)

public module restir_pt_spatial_scene_pass;

import "contracts/contracts.slang";
import "pathtrace/path_trace_entry.slang";
import "traversal/hardware_traversal.slang";
import "traversal/scene_traversal.slang";

// The trace pass's own mask: a neighbour's first edge is blocked by exactly what blocked the trace.
static const uint PATH_TRACE_INSTANCE_MASK_SCENE = 0x03u;

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(2, 0)]] StructuredBuffer<GeometrySection> sections;
[[vk::binding(3, 0)]] Sampler2D albedoAtlas;
[[vk::binding(9, 0)]] Sampler2D materialAtlas;
[[vk::binding(10, 0)]] Sampler2D normalAtlas;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(17, 0)]] StructuredBuffer<BlockMaterial> blockMaterials;
[[vk::binding(20, 0)]] StructuredBuffer<uint> entityPositions;
[[vk::binding(21, 0)]] StructuredBuffer<uint> entityIndices;
[[vk::binding(22, 0)]] StructuredBuffer<uint4> entityAttrs;
[[vk::binding(23, 0)]] StructuredBuffer<uint4> entityRects;
[[vk::binding(24, 0)]] StructuredBuffer<uint> entityTexels;
[[vk::binding(25, 0)]] StructuredBuffer<uint> entityPrevious;
[[vk::binding(36, 0)]] RWStructuredBuffer<RestirPtReservoir> ptReservoirs;

[[vk::push_constant]] PathTracePush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
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
    pathTracePtSpatialEntry(traversal, entities, tid, albedoAtlas, materialAtlas, normalAtlas, frame, blockMaterials,
        ptReservoirs, pc);
}
