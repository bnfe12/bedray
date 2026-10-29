// Shared body of the path-tracing entry points.
//
// `path_trace_pass` (LegacySection table) and `path_trace_scene_pass`
// (native GeometrySection table) differ only in binding 2 and in the traversal
// backend; the integration, material decode, ReSTIR and output contract are
// identical.  Keeping the body here means a fix cannot land in one entry and
// silently miss the other.
public module path_trace_entry;

__exported import "contracts/contracts.slang";
__exported import "light/block_emitters.slang";
import "light/sky.slang";
import "light/sun.slang";
import "pathtrace/path_tracer.slang";
import "restir/di_block_emitters.slang";
__exported import "restir/restir_di.slang";
import "sampling.slang";
__exported import "surface/surface.slang";
__exported import "traversal/entity_geometry.slang";
import "traversal/traversal.slang";

// Bits of PathTracePush.diState.x.  The reservoir branch is one code path gated by a flag, not a second shader: the host
// can A/B ReSTIR DI against plain per-frame RIS in the same frame graph.
static const uint PATH_TRACE_DI_ENABLED      = 1u << 0u;
static const uint PATH_TRACE_DI_HISTORY      = 1u << 1u;   // the previous dispatch wrote records of this convention
static const uint PATH_TRACE_DI_STATIC       = 1u << 2u;   // camera did not move: the history is the same pixel
static const uint PATH_TRACE_DI_FLIP_Y       = 1u << 3u;   // the reprojection has to mirror the previous-frame y
public struct PathTracePush {
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

// Camera-relative ray through the centre of a jittered pixel.  The camera basis
// is explicit; `origin` is the view-bob eye offset in raw-camera-relative
// world space, matching the TLAS anchor the host builds around the raw camera
// position.  The jittered inverse view-projection is for reprojection, not for
// primary rays.
Ray pathTracePrimaryRay(FrameConstants f, int2 pixel, float maxDistance) {
    float2 jittered = float2(pixel) + 0.5 + f.camera.jitter.xy;
    float2 ndc = jittered * f.camera.jitter.zw * 2.0 - 1.0;
    float tanHalf = f.camera.forward.w;
    float aspect = f.camera.right.w;
    float3 direction = normalize(f.camera.forward.xyz
        + f.camera.right.xyz * (ndc.x * tanHalf * aspect)
        - f.camera.up.xyz * (ndc.y * tanHalf));
    return makeRay(f.camera.origin.xyz, direction, 0.0, max(maxDistance, 1.0));
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
void pathTraceWriteGuides(int2 p, FrameConstants f, PathTraceResult result,
        RWTexture2D<float> outViewZ,
        RWTexture2D<float4> outNormalRough,
        RWTexture2D<float4> outSpecular,
        RWTexture2D<float4> outDiffuse,
        RWTexture2D<float4> outAlbedo,
        RWTexture2D<float4> outDirect,
        RWTexture2D<float4> outSpecAlbedo) {
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

// `traversal` arrives with its backend-specific fields set (TLAS and the section/geometry provider); everything that
// is shared between the two backends is filled here, so adding a field cannot be forgotten in one entry point.
float4 pathTraceNrdMotion(PathTraceResult result) {
    if (!result.primary.valid) {
        return float4(0.0);
    }
    float3 motion = result.primaryMotion.xyz;
    float trusted = (result.primaryMotion.w > 0.5 || dot(motion, motion) > 1.0e-8) ? 1.0 : 0.0;
    return float4(motion, trusted);
}

public void pathTraceEntryWithField<T : ISceneTracer, F : IPathDiffuseField>(
    T traversal,
    EntityGeometry entities,
    uint3 tid,
    RWTexture2D<float4> outImage,
    Sampler2D albedoAtlas,
    RWTexture2D<float4> outMotion,
    Sampler2D materialAtlas,
    Sampler2D normalAtlas,
    RWTexture2D<float> outDepth,
    RWTexture2D<float> outViewZ,
    RWTexture2D<float4> outNormalRough,
    RWTexture2D<float4> outSpecular,
    RWTexture2D<float4> outDiffuse,
    RWTexture2D<float4> outAlbedo,
    RWTexture2D<float4> outDirect,
    RWTexture2D<float4> outSpecAlbedo,
    RWStructuredBuffer<float4> outMotionBuffer,
    FrameConstants frame,
    StructuredBuffer<BlockMaterial> blockMaterials,
    StructuredBuffer<BlockEmitterRecord> emitters,
    RWStructuredBuffer<DIReservoir> reservoirs,
    PathTracePush pc, F diffuseField) {
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

    // A mob part is decoded through the same entry as a block face, so the atlas
    // has to name the entity grid even for a pass that only ever hits terrain.
    atlas.entities = entities.atlas;

    // The legacy triangle path has no voxel hit, so the block-material table is
    // present only to satisfy the shared decodeSurface signature.  A host that
    // later resolves block states fills count and the buffer.
    BlockMaterialTable table;
    table.materials = blockMaterials;
    table.count = pc.materialCount;

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

    Ray primary = pathTracePrimaryRay(f, p, pc.maxDistance);
    uint seed = pathTraceSeed(p, f.frame.x, pc.sampleIndex);
    // One cutoff for the primary path, material decoding and the filtered
    // shadow walk; otherwise a near-threshold texel can be solid to the camera
    // and a hole to the shadow ray.
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    materialOptions.alphaCutoff = options.alphaCutoff;
    materialOptions.portalTime = f.camera.origin.w;
    materialOptions.autoNormalStrength = asfloat(pc.entityState.w);
    PathTraceResult result = tracePathWithField(traversal, lights, environment, directReuse, atlas, table,
        primary, materialOptions, options, seed, diffuseField);

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
        pathTraceWriteGuides(p, f, result, outViewZ, outNormalRough, outSpecular, outDiffuse,
            outAlbedo, outDirect, outSpecAlbedo);
    }
    if ((options.flags & PATH_TRACE_OPTION_NRD_MOTION) != 0u) {
        outMotionBuffer[uint(p.x) + uint(p.y) * f.camera.size.x] = pathTraceNrdMotion(result);
    }
}

public void pathTraceEntry<T : ISceneTracer>(
    T traversal, EntityGeometry entities, uint3 tid,
    RWTexture2D<float4> outImage, Sampler2D albedoAtlas, RWTexture2D<float4> outMotion,
    Sampler2D materialAtlas, Sampler2D normalAtlas, RWTexture2D<float> outDepth,
    RWTexture2D<float> outViewZ, RWTexture2D<float4> outNormalRough,
    RWTexture2D<float4> outSpecular, RWTexture2D<float4> outDiffuse,
    RWTexture2D<float4> outAlbedo, RWTexture2D<float4> outDirect,
    RWTexture2D<float4> outSpecAlbedo, RWStructuredBuffer<float4> outMotionBuffer,
    FrameConstants frame, StructuredBuffer<BlockMaterial> blockMaterials,
    StructuredBuffer<BlockEmitterRecord> emitters, RWStructuredBuffer<DIReservoir> reservoirs,
    PathTracePush pc) {
    NoPathDiffuseField field;
    pathTraceEntryWithField(traversal, entities, tid, outImage, albedoAtlas, outMotion,
        materialAtlas, normalAtlas, outDepth, outViewZ, outNormalRough, outSpecular,
        outDiffuse, outAlbedo, outDirect, outSpecAlbedo, outMotionBuffer, frame,
        blockMaterials, emitters, reservoirs, pc, field);
}
