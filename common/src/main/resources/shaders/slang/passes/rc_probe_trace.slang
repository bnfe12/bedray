// Typed Radiance Cascades producer pass (one dispatch item = one probe ray).
//
// This is the first real consumer of the typed RCSegment atlas: it decodes the
// flat dispatch index into (cascade, probe, ray), traces exactly that ray and
// writes both typed interval records through `traceCascadeDispatch`.  A resolve
// pass reads the atlas with `rcResolveProbe`; no float4 record is involved and
// no per-pixel colour is written here.
//
// Host contract
// -------------
// * `rcSegments` is RWStructuredBuffer<RCSegment>; its element stride must be
//   the reflected 32 B and its element count must be at least
//   `pc.segmentCapacity`.  `pc.segmentCapacity` must be the descriptor range,
//   not the allocation size; `radianceCascadeLayout().totalRecords` is the
//   canonical minimum.
// * `rcStatus` has one `RCTraceEntryStatus` per dispatched item.  The host must
//   bind at least `pc.rayCount` elements.  It is diagnostic output, not part of
//   the atlas: an entry with code != Ok still writes no record.
// * `pc.rayCount` is the number of flat ray items in this dispatch and must not
//   exceed the canonical ray count.  Indices beyond the canonical grid report
//   InvalidDispatch instead of touching the atlas.
// * The pass is deterministic for fixed inputs except for the per-item seed,
//   which mixes `pc.frameIndex` so block-emitter NEE decorrelates across
//   frames.  It does not accumulate or blend history; use the provisional
//   history entries explicitly if the resolve owner wants them.
//
// The descriptor numbers mirror passes/path_trace.slang for the shared scene
// inputs.  The two typed-RC resources live above that range:
//
//   0  tlas                  acceleration structure
//   2  sections              StructuredBuffer<LegacySection>
//   3  albedoAtlas           combined image sampler
//   9  materialAtlas         combined image sampler
//   10 normalAtlas           combined image sampler
//   16 frame                 ConstantBuffer<FrameConstants>
//   17 blockMaterials        StructuredBuffer<BlockMaterial>
//   18 emitters              StructuredBuffer<BlockEmitterRecord>
//   20 entityPositions       StructuredBuffer<uint>
//   21 entityIndices         StructuredBuffer<uint>
//   22 entityAttrs           StructuredBuffer<uint4>
//   23 entityRects           StructuredBuffer<uint4>
//   24 entityTexels          StructuredBuffer<uint>
//   25 entityPrevious        StructuredBuffer<uint>
//   26 rcSegments            RWStructuredBuffer<RCSegment> (typed atlas)
//   27 rcStatus              RWStructuredBuffer<RCTraceEntryStatus>
//
// The pass is host-agnostic: it does not create a binding set, a frame graph
// node or a Java descriptor.  Wiring it is a separate host change.

public module rc_probe_trace_pass;

import "contracts/frame.slang";
import "contracts/hit.slang";
import "light/block_emitters.slang";
import "light/sky.slang";
import "light/sun.slang";
import "radiance_cascades/rc_trace.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/entity_geometry.slang";
import "traversal/legacy_section_traversal.slang";
import "traversal/traversal.slang";

static const uint RC_PROBE_TRACE_INSTANCE_MASK_SCENE = 0x03u;

struct RCProbeTracePush {
    float rayBias;                 // 0
    float alphaCutoff;             // 4
    float emissionScale;           // 8
    float maxRadiance;             // 12
    float sunProbability;          // 16
    float emitterProbability;      // 20
    float nearDistance;            // 24, 0 = cascade default
    float farDistance;             // 28, 0 = cascade default
    float worldBoundaryDistance;   // 32, 0 = environment disabled
    uint  rayCount;                // 36, dispatch items / status elements
    uint  segmentCapacity;         // 40, RCSegment elements in the bound range
    uint  materialCount;           // 44
    uint  environmentPolicy;       // 48, RCEnvironmentPolicy
    uint  environmentCascade;      // 52
    uint  frameRotation;           // 56, cascade ray-set rotation
    uint  frameIndex;              // 60, seed decorrelation only
    uint  maxDepth;                // 64
    uint  emitterCount;            // 68
    float4 emitterCameraRelative;  // 80..95, xyz camera anchor, w candidates
    uint4  entityState;            // 96..111, x: atlas size, yz: self avatar, w: reserved
};                              // 112 B total; the pass manifest requires <= 128

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;
[[vk::binding(3, 0)]] Sampler2D albedoAtlas;
[[vk::binding(9, 0)]] Sampler2D materialAtlas;
[[vk::binding(10, 0)]] Sampler2D normalAtlas;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(17, 0)]] StructuredBuffer<BlockMaterial> blockMaterials;
[[vk::binding(18, 0)]] StructuredBuffer<BlockEmitterRecord> emitters;
[[vk::binding(20, 0)]] StructuredBuffer<uint> entityPositions;
[[vk::binding(21, 0)]] StructuredBuffer<uint> entityIndices;
[[vk::binding(22, 0)]] StructuredBuffer<uint4> entityAttrs;
[[vk::binding(23, 0)]] StructuredBuffer<uint4> entityRects;
[[vk::binding(24, 0)]] StructuredBuffer<uint> entityTexels;
[[vk::binding(25, 0)]] StructuredBuffer<uint> entityPrevious;
[[vk::binding(26, 0)]] RWStructuredBuffer<RCSegment> rcSegments;
[[vk::binding(27, 0)]] RWStructuredBuffer<RCTraceEntryStatus> rcStatus;

[[vk::push_constant]] RCProbeTracePush pc;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= pc.rayCount) return;

    FrameConstants f = frame;

    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas;
    atlas.material = materialAtlas;
    atlas.normal = normalAtlas;

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
    atlas.entities = entityAtlas;

    BlockMaterialTable table;
    table.materials = blockMaterials;
    table.count = pc.materialCount;

    LegacySectionTraversal traversal;
    traversal.tlas = tlas;
    traversal.sections = sections;
    traversal.instanceMask = RC_PROBE_TRACE_INSTANCE_MASK_SCENE;
    traversal.entities = entities;
    traversal.cameraAnchor = pc.emitterCameraRelative.xyz;
    traversal.selfAvatarTriStart = pc.entityState.y;
    traversal.selfAvatarTriCount = pc.entityState.z;

    SunMoonSampler keyLight;
    keyLight.sun = f.sun;
    keyLight.sunProbability = pc.sunProbability;

    BlockEmitterSampler emitterSampler;
    emitterSampler.records = emitters;
    emitterSampler.atlas = atlas;
    emitterSampler.candidates = uint(max(pc.emitterCameraRelative.w, 1.0));
    emitterSampler.cameraRelative = pc.emitterCameraRelative.xyz;
    emitterSampler.maxDistance = max(max(pc.nearDistance, pc.farDistance), 1.0);

    SunAndBlockEmitterSampler lights;
    lights.keyLight = keyLight;
    lights.emitters = emitterSampler;
    // No list, no share: the sun keeps the whole density when the emitter list
    // is empty, exactly as the path tracer does.
    lights.emitterProbability = pc.emitterCount > 0u
        ? clamp(pc.emitterProbability, 0.0, 1.0)
        : 0.0;
    lights.emittersReusedAtPrimary = false;

    SunMoonSky environment;
    environment.dome.atmosphere = f.atmosphere;
    environment.sun = f.sun;

    RCTraceOptions options = defaultRCTraceOptions();
    options.maxDepth = max(pc.maxDepth, 1u);
    options.rayBias = max(pc.rayBias, 0.0);
    options.nearDistance = max(pc.nearDistance, 0.0);
    options.farDistance = max(pc.farDistance, 0.0);
    options.maxRadiance = max(pc.maxRadiance, 1.0);
    options.environmentPolicy = pc.environmentPolicy;
    options.environmentCascade = min(pc.environmentCascade,
        RADIANCE_CASCADE_COUNT - 1u);
    options.worldBoundaryDistance = max(pc.worldBoundaryDistance, 0.0);
    options.flags = 0u;

    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    materialOptions.alphaCutoff = pc.alphaCutoff;
    materialOptions.emissionScale = max(pc.emissionScale, 0.0);

    RadianceCascadeLayout layout = radianceCascadeLayout();
    uint totalRayCount = layout.totalRecords / RC_INTERVALS_PER_RAY;
    if (index >= totalRayCount) {
        // A stale oversized dispatch is reported instead of silently touching
        // an out-of-range record address.
        RCTraceEntryStatus rejected = emptyRCTraceEntryStatus();
        rejected.code = uint(RCTraceEntryCode.InvalidDispatch);
        rcStatus[index] = rejected;
        return;
    }

    uint seed = pcgHash(index * 747796405u + pc.frameIndex * 2891336453u);
    RCTraceEntryStatus status = traceCascadeDispatch(traversal, lights,
        environment, atlas, table, layout, rcSegments, pc.segmentCapacity,
        index, f.camera.origin.xyz, pc.frameRotation, materialOptions, options,
        seed);
    rcStatus[index] = status;
}
