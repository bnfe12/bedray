// One dispatch per level, terminal -> finest, with native write/read barriers.  Each level is blended with the previous
// recording's field before the next finer level reads it (rcFieldBlendHistory).
public module rc_field_build_scene_pass;
import "contracts/contracts.slang";
import "light/block_emitters.slang";
import "light/sky.slang";
import "light/sun.slang";
import "radiance_cascades/field_transport.slang";
import "radiance_cascades/rc_trace_options.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/scene_traversal.slang";
import "traversal/entity_geometry.slang";
import "traversal/hardware_traversal.slang";

struct RCFieldBuildPush {
    uint level; uint count; uint frameIndex; uint materialCount;
    float rayBias; float alphaCutoff; float maxRadiance; float sunProbability;
    uint emitterCount; uint maxDepth; float emitterProbability; uint reserved;
    float4 emitterCameraRelative;
    uint4 entityState;
}; // 80 B
[[vk::binding(0,0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(2,0)]] StructuredBuffer<GeometrySection> sections;
[[vk::binding(3,0)]] Sampler2D albedoAtlas;
[[vk::binding(9,0)]] Sampler2D materialAtlas;
[[vk::binding(10,0)]] Sampler2D normalAtlas;
[[vk::binding(16,0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(17,0)]] StructuredBuffer<BlockMaterial> blockMaterials;
[[vk::binding(18,0)]] StructuredBuffer<BlockEmitterRecord> emitters;
[[vk::binding(20,0)]] StructuredBuffer<uint> entityPositions;
[[vk::binding(21,0)]] StructuredBuffer<uint> entityIndices;
[[vk::binding(22,0)]] StructuredBuffer<uint4> entityAttrs;
[[vk::binding(23,0)]] StructuredBuffer<uint4> entityRects;
[[vk::binding(24,0)]] StructuredBuffer<uint> entityTexels;
[[vk::binding(25,0)]] StructuredBuffer<uint> entityPrevious;
[[vk::binding(34,0)]] ConstantBuffer<RCFieldConstants> rcField;
[[vk::binding(35,0)]] RWStructuredBuffer<RCSegment> rcTails;
// The previous recording's tails, or this recording's own when rcField.history.w is 0 (then it is never read).
[[vk::binding(38,0)]] StructuredBuffer<RCSegment> rcHistory;
[[vk::push_constant]] RCFieldBuildPush pc;

[shader("compute")]
[numthreads(64,1,1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= pc.count || pc.level >= RC_FIELD_LEVELS) return;
    RCFieldConstants field = rcField;
    if (!rcFieldMetadataValid(field) || field.tag.x != pc.frameIndex) return;
    uint side = field.counts[pc.level].y;
    uint rays = 6u * side * side;
    uint count = field.counts[pc.level].z * rays;
    if (pc.count != count) return;

    EntityAtlas entityAtlas;
    entityAtlas.rects = entityRects; entityAtlas.texels = entityTexels;
    entityAtlas.atlasSize = pc.entityState.x;
    EntityGeometry entities;
    entities.positions = entityPositions; entities.previous = entityPrevious;
    entities.indices = entityIndices; entities.attrs = entityAttrs; entities.atlas = entityAtlas;
    GeometryProvider geometry; geometry.sections = sections;
    SceneTraversal traversal;
    traversal.tlas = tlas; traversal.geometry = geometry; traversal.instanceMask = 0x03u;
    traversal.entities = entities; traversal.cameraAnchor = pc.emitterCameraRelative.xyz;
    traversal.selfAvatarTriStart = pc.entityState.y; traversal.selfAvatarTriCount = pc.entityState.z;
    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas; atlas.material = materialAtlas; atlas.normal = normalAtlas;
    atlas.entities = entityAtlas;
    BlockMaterialTable table; table.materials = blockMaterials; table.count = pc.materialCount;
    SunMoonSampler key; key.sun = frame.sun; key.sunProbability = pc.sunProbability; key.hardShadows = false;
    BlockEmitterSampler block;
    block.records = emitters; block.atlas = atlas;
    block.candidates = uint(max(pc.emitterCameraRelative.w, 1.0));
    block.cameraRelative = pc.emitterCameraRelative.xyz; block.maxDistance = field.ranges[4].y;
    SunAndBlockEmitterSampler lights;
    lights.keyLight = key; lights.emitters = block;
    lights.emitterProbability = pc.emitterCount > 0u ? pc.emitterProbability : 0.0;
    lights.emittersReusedAtPrimary = false;
    SunMoonSky environment; environment.dome.atmosphere = frame.atmosphere; environment.sun = frame.sun;
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    materialOptions.alphaCutoff = pc.alphaCutoff;
    materialOptions.portalTime = frame.camera.origin.w;
    materialOptions.autoNormalStrength = asfloat(pc.entityState.w);
    RCTraceOptions options = defaultRCTraceOptions();
    options.rayBias = pc.rayBias; options.maxRadiance = pc.maxRadiance; options.maxDepth = pc.maxDepth;
    uint seed = pcgHash(tid.x * 747796405u + pc.frameIndex * 2891336453u + pc.level * 277803737u);
    uint3 probe = rcFieldProbeCoordinates(tid.x / rays, field.counts[pc.level].x);
    RCSegment current = rcFieldReconstruct(traversal, lights,
        environment, atlas, table, field, rcTails, pc.level, probe, tid.x % rays,
        materialOptions, options, seed);
    rcTails[field.counts[pc.level].w + tid.x] = rcFieldBlendHistory(field, rcHistory, pc.level, probe,
        tid.x % rays, current);
}
