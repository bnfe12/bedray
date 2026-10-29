// Path-tracing entry over the native bedray_scene section table.
//
// This is the native-scene twin of path_trace.slang: the algorithm, bindings,
// push block and outputs are identical, but binding 2 is the 64-byte
// GeometrySection record owned by bedray_scene and the traversal decodes the
// GeometryVertex stream through its device address.  The host selects this
// program only when it can supply a native scene table; the legacy entry stays
// available for the current Java scene.
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
//
// The section record's vertexAddress is a PhysicalStorageBuffer address of a
// GeometryVertex array (64 B stride), so the manifest keeps Int64 and
// PhysicalStorageBufferAddresses alongside RayQueryKHR.

public module path_trace_scene_pass;

import "contracts/contracts.slang";
import "light/block_emitters.slang";
import "light/sky.slang";
import "light/sun.slang";
import "pathtrace/path_tracer.slang";
import "restir/di_block_emitters.slang";
import "restir/restir_di.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/entity_geometry.slang";
import "traversal/hardware_traversal.slang";
import "traversal/scene_traversal.slang";
import "traversal/traversal.slang";

// Terrain and the entity ring are one scene: the same TLAS holds both, and the
// instance's custom-index bit is what tells a mob quad from a section quad.
// Masking the entity instance out would make the path tracer blind to every
// creature, item and armour stand in the world.
static const uint PATH_TRACE_INSTANCE_MASK_SCENE = 0x03u;

// Bits of PathTracePush.diState.x.  The reservoir branch is one code path gated by a flag, not a second shader: the host
// can A/B ReSTIR DI against plain per-frame RIS in the same frame graph.
static const uint PATH_TRACE_DI_ENABLED      = 1u << 0u;
static const uint PATH_TRACE_DI_HISTORY      = 1u << 1u;   // the previous dispatch wrote records of this convention
static const uint PATH_TRACE_DI_STATIC       = 1u << 2u;   // camera did not move: the history is the same pixel
static const uint PATH_TRACE_DI_FLIP_Y       = 1u << 3u;   // the reprojection has to mirror the previous-frame y

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
    uint  emitterCount;
    float emitterProbability;
    float4 emitterCameraRelative;   // xyz camera minus the origin the list was rebuilt at, w candidates per call
    float4 diCameraDelta;           // xyz raw camera position minus the previous frame's, w reserved
    uint4  diState;                 // x: PATH_TRACE_DI_* bits, y: parity half this frame writes, zw reserved
    uint4  entityState;             // x: entity atlas texels per side (0 = no atlas), yz: self-avatar primitive range,
                                    // w: auto-normal strength as float bits (0 = off)
};

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

// Guide channels for the NRD / DLSS-RR input pack, laid out as rt_path.comp writes them: the pack, the denoiser and the
// compose are unchanged, so the Slang path can be denoised by the same chain as the legacy one.  A sky pixel gets the
// legacy pattern (view depth 1e6, a flat normal, zero signal, albedo 0) so the pack skips it and the denoiser keeps its
// range finite.
void pathTraceWriteGuides(int2 p, FrameConstants f, PathTraceResult result) {
    if (!result.primary.valid) {
        outViewZ[p] = 1.0e6;
        outNormalRough[p] = float4(0.0, 0.0, 1.0, 1.0);
        outSpecular[p] = float4(0.0);
        outDiffuse[p] = float4(0.0);
        outAlbedo[p] = float4(0.0);
        outDirect[p] = float4(0.0);
        outSpecAlbedo[p] = float4(0.0);
        return;
    }
    // NRD's view depth is measured backwards from the eye along the view axis, as in rt_path.comp.
    float3 toSurface = result.primary.position - f.camera.origin.xyz;
    float viewZ = -dot(toSurface, f.camera.forward.xyz);
    float3 n = result.primary.normal;
    n = length(n) > 1.0e-4 ? normalize(n) : float3(0.0, 0.0, 1.0);
    outViewZ[p] = viewZ;
    outNormalRough[p] = float4(n, clamp(result.primary.roughness, 0.0, 1.0));
    // The path tracer sums diffuse and specular per sample: the split channel stays empty and the whole signal goes
    // through the colour input as raw radiance (specular albedo is a material guide only), exactly as in rt_path.comp.
    outSpecular[p] = float4(0.0);
    outDiffuse[p] = float4(result.radiance - result.primary.direct, result.firstHitDistance);
    outAlbedo[p] = float4(result.primary.albedo, 1.0);
    outDirect[p] = float4(result.primary.direct, 0.0);
    bool specularGuide = result.primary.metallic > 0.5 || result.primary.roughness < 0.6;
    float3 specAlbedo = !specularGuide ? float3(0.0)
        : (result.primary.metallic > 0.5 ? result.primary.albedo : result.primary.specularF0);
    outSpecAlbedo[p] = float4(clamp(specAlbedo, float3(0.0), float3(1.0)), 1.0);
}

// NRD's IN_MV channel, written where rt_path.comp writes Vec4Buf(StatsBuf.motionAddr): the primary surface's own
// world motion, prev minus cur, and the same trust flag (a captured model always counts, a static surface's near-zero
// motion is left out).  A sky pixel has no surface to reproject, so it writes zero and the pack keeps its static miss.
float4 pathTraceNrdMotion(PathTraceResult result) {
    if (!result.primary.valid) {
        return float4(0.0);
    }
    float3 motion = result.primaryMotion.xyz;
    float trusted = (result.primaryMotion.w > 0.5 || dot(motion, motion) > 1.0e-8) ? 1.0 : 0.0;
    return float4(motion, trusted);
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

    // This frame's captured models.  The buffers are bound at the ring slot's own
    // offset, so the bound length is the frame's vertex and triangle count: a
    // frame with nothing captured binds one-element dummies and decodes zero
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
    // A mob part is decoded through the same entry as a block face, so the atlas
    // has to name the entity grid even for a pass that only ever hits terrain.
    atlas.entities = entities.atlas;

    // The legacy triangle path has no voxel hit, so the block-material table is
    // present only to satisfy the shared decodeSurface signature.  A host that
    // later resolves block states fills count and the buffer.
    BlockMaterialTable table;
    table.materials = blockMaterials;
    table.count = pc.materialCount;

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
    // The legacy path rebuilt an entity surface's world position the same way:
    // `hit + camRel`, where camRel is the camera against the origin the frame was
    // anchored at.  worldPosition is a wrapped neighbourhood proxy, not a unique
    // identity, and an entity hit has no section to anchor to, so this is the
    // whole of what it can be.
    traversal.cameraAnchor = pc.emitterCameraRelative.xyz;
    // The first-person body sits around the eye, so only the camera ray looks
    // past it; every other ray of the path crosses it like any other geometry.
    traversal.selfAvatarTriStart = pc.entityState.y;
    traversal.selfAvatarTriCount = pc.entityState.z;

    SunMoonSampler keyLight;
    keyLight.sun = f.sun;
    keyLight.sunProbability = pc.sunProbability;

    // Block emitters of this frame, in the list RtEmitters uploads for the legacy path as well. The receiver's own
    // emission reaches the sampler through LightReceiver, so a glowing face is not lit by its own quad.
    uint emitterCandidates = uint(max(pc.emitterCameraRelative.w, 1.0));
    BlockEmitterSampler emitterSampler;
    emitterSampler.records = emitters;
    emitterSampler.atlas = atlas;
    emitterSampler.candidates = emitterCandidates;
    emitterSampler.cameraRelative = pc.emitterCameraRelative.xyz;
    emitterSampler.maxDistance = max(pc.maxDistance, 1.0);

    // ReSTIR DI over the same list: one reservoir of this pixel, the reprojected history of the same surface.  The pass
    // always builds it and the push flag decides whether it is consulted, so one instantiation covers both branches.
    BlockEmitterDiSampler directReuse;
    directReuse.records = emitters;
    directReuse.reservoirs = reservoirs;
    directReuse.atlas = atlas;
    directReuse.candidates = emitterCandidates;
    directReuse.cameraRelative = pc.emitterCameraRelative.xyz;
    directReuse.maxDistance = max(pc.maxDistance, 1.0);
    directReuse.enabled = (pc.diState.x & PATH_TRACE_DI_ENABLED) != 0u ? 1u : 0u;
    directReuse.historyValid = (pc.diState.x & PATH_TRACE_DI_HISTORY) != 0u ? 1u : 0u;
    directReuse.cameraStatic = (pc.diState.x & PATH_TRACE_DI_STATIC) != 0u ? 1u : 0u;
    directReuse.flipY = (pc.diState.x & PATH_TRACE_DI_FLIP_Y) != 0u ? 1u : 0u;
    directReuse.parity = pc.diState.y;
    directReuse.pixel = p;
    directReuse.size = size;
    directReuse.cameraDelta = pc.diCameraDelta.xyz;
    directReuse.previousViewProjection = f.camera.prevViewProjection;

    SunAndBlockEmitterSampler lights;
    lights.keyLight = keyLight;
    lights.emitters = emitterSampler;
    // No list, no share: the sun keeps the whole density instead of being renormalised by a branch that never wins.
    lights.emitterProbability = pc.emitterCount > 0u ? clamp(pc.emitterProbability, 0.0, 1.0) : 0.0;
    // With the reservoir on, the primary vertex is lit from it and the composite must not draw the same branch again.
    lights.emittersReusedAtPrimary = directReuse.enabled != 0u;

    // The dome the path leaves into, with the two bodies the key light already
    // describes drawn into it.  A reflection or an escaped ray that points at the
    // sun now shows it; the sampled environment stays the dome alone, because the
    // sun and moon are discrete entries in the MIS list above.
    SunMoonSky environment;
    environment.dome.atmosphere = f.atmosphere;
    environment.sun = f.sun;

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
    materialOptions.portalTime = f.camera.origin.w;
    materialOptions.autoNormalStrength = asfloat(pc.entityState.w);
    PathTraceResult result = tracePath(traversal, lights, environment, directReuse, atlas, table,
        primary, materialOptions, options, seed);

    // Scene-referred linear radiance: no exposure and no tone map here.  Exposure lives in FrameConstants.exposure and
    // is applied once by the display/tonemap pass, so accumulation and denoise keep working in linear HDR.
    // The alpha lane is the sample's verdict, not coverage: rgb has no room left to say "this path could not be
    // trusted", and a non-finite fault must not dilute a running mean that lives for thousands of frames.
    outImage[p] = float4(result.radiance, pathTraceSampleRejected(result.flags) ? 0.0 : 1.0);
    // Legacy depth contract (rt_path.comp): a miss writes 1e6, because bloom.slang treats anything at or beyond
    // BLOOM_SKY_DISTANCE (1e5) as sky and the composite pass uses the same buffer.  firstHitDistance alone defaults
    // to the ray far plane on a miss, which is far below that threshold and would make the sky glow.
    outDepth[p] = (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u ? result.firstHitDistance : 1.0e6;
    // The velocity lane belongs to the accumulator, not to the integrand: it says where the surface this pixel
    // showed had itself been one frame ago.  A miss and terrain both say "nowhere", which is what a camera-only
    // reprojection already assumes, so no flag and no branch is needed downstream for them.
    outMotion[p] = (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u ? result.primaryMotion : float4(0.0);
    if ((options.flags & PATH_TRACE_OPTION_GBUFFER) != 0u) {
        pathTraceWriteGuides(p, f, result);
    }
    if ((options.flags & PATH_TRACE_OPTION_NRD_MOTION) != 0u) {
        outMotionBuffer[uint(p.x) + uint(p.y) * f.camera.size.x] = pathTraceNrdMotion(result);
    }
}
