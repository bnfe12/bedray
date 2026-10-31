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
__exported import "restir/pt_temporal.slang";
__exported import "restir/pt_spatial.slang";
import "sampling.slang";
__exported import "surface/surface.slang";
import "surface/water.slang";
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
    float4 diCameraDelta;           // xyz raw camera position minus the previous frame's, w emitter collection range
    uint4  diState;                 // x: PATH_TRACE_DI_* bits, y: parity half this frame writes, zw diffuse/specular
                                    // bounce far planes as floats (0 = the ray far plane)
    uint4  entityState;             // x: entity atlas texels per side (0 = no atlas), yz: self-avatar primitive range,
                                    // w: auto-normal strength as float bits (0 = off)
};

// The ceiling of "Rays per Pixel": the host writes the player's own value in the frame word, the adaptive governor
// writes a smaller one, and a host that never wrote it asks for zero. All three are answered inside the largest ladder
// the settings screen can reach, so one dispatch can never cost more than the player chose.
static const uint PATH_TRACE_MAX_SPP = 8u;

uint pathTraceSampleCount(FrameConstants f) {
    return clamp(f.frame.z, 1u, PATH_TRACE_MAX_SPP);
}

// Camera-relative ray through the centre of a jittered pixel.  The camera basis
// is explicit; `origin` is the view-bob eye offset in raw-camera-relative
// world space, matching the TLAS anchor the host builds around the raw camera
// position.  The jittered inverse view-projection is for reprojection, not for
// primary rays.
Ray pathTracePrimaryRay(FrameConstants f, int2 pixel, float maxDistance, float2 jitter) {
    float2 jittered = float2(pixel) + 0.5 + jitter;
    float2 ndc = jittered * f.camera.jitter.zw * 2.0 - 1.0;
    float tanHalf = f.camera.forward.w;
    float aspect = f.camera.right.w;
    float3 direction = normalize(f.camera.forward.xyz
        + f.camera.right.xyz * (ndc.x * tanHalf * aspect)
        - f.camera.up.xyz * (ndc.y * tanHalf));
    return makeRay(f.camera.origin.xyz, direction, 0.0, max(maxDistance, 1.0));
}

// One cutoff for the primary path, material decoding and the filtered shadow walk; otherwise a near-threshold texel can
// be solid to the camera and a hole to the shadow ray.  The ReSTIR PT spatial entry reads the same options, so a
// neighbour's first edge is re-traced through exactly the walk that traced it.
MaterialSamplingOptions pathTraceMaterialOptions(FrameConstants f, PathTracePush pc) {
    MaterialSamplingOptions materialOptions = defaultMaterialSamplingOptions();
    materialOptions.alphaCutoff = pc.alphaCutoff;
    materialOptions.portalTime = f.camera.origin.w;
    materialOptions.autoNormalStrength = asfloat(pc.entityState.w);
    // The player's style switch, and it only ever touches the wave field: the realistic style keeps every tilt the
    // analytic field has, the other keeps the legacy damping of the same tilts.  Absorption, IOR and the bent beam are
    // identical with either, so both styles are the same body of water.
    materialOptions.waterWaveMix = frameWaterRealisticWaves(f) ? 1.0 : WATER_WAVE_MIX_VANILLA;
    return materialOptions;
}

uint pathTraceSeed(int2 pixel, uint frameIndex, uint sampleIndex) {
    uint seed = uint(pixel.x) * 1973u + uint(pixel.y) * 9277u;
    seed = pcgHash(seed + frameIndex * 26699u);
    return pcgHash(seed + sampleIndex * 6971u);
}

// Sub-pixel offset of sample `s` of `n`. The pixel is cut into an n x n grid and each sample takes one stratum per axis
// - a Latin hypercube, so the n rays of a frame cover every stratum of both axes while their pairing stays a diagonal.
// The diagonal rotates with the sample's seed, which already carries the frame and the host's sample index, so a still
// camera walks around the pixel instead of hammering one spot. n == 1 returns the host's own jitter untouched: with one
// ray per pixel the frame has to be exactly the frame that ran before there was a choice.
float2 pathTracePixelJitter(FrameConstants f, int2 pixel, uint s, uint n) {
    if (n <= 1u) {
        return f.camera.jitter.xy;
    }
    uint seed = pathTraceSeed(pixel, f.frame.x, f.frame.y);
    float2 stratum = float2(float((seed % n + s) % n), float(((seed / n) % n + s) % n));
    return (stratum + noise2D(pixel, f.frame.x, s, n, 7u)) / float(n) - 0.5;
}

// Guide channels for the NRD / DLSS-RR input pack, laid out as rt_path.comp writes them: the pack, the denoiser and the
// compose are unchanged, so the Slang path can be denoised by the same chain as the legacy one.  A sky pixel gets the
// legacy pattern (view depth 1e6, a flat normal, zero signal, albedo 0) so the pack skips it and the denoiser keeps its
// range finite.
void pathTraceWriteGuides(int2 p, FrameConstants f, PathTraceResult result, bool sampleTrusted,
        RWTexture2D<float> outViewZ,
        RWTexture2D<float4> outNormalRough,
        RWTexture2D<float4> outSpecular,
        RWTexture2D<float4> outDiffuse,
        RWTexture2D<float4> outAlbedo,
        RWTexture2D<float4> outDirect,
        RWTexture2D<float4> outSpecAlbedo) {
    // A sample the tracer rejected carries no geometry the denoiser may consume: `sampleTrusted` is
    // false for PATH_TRACE_FLAG_INVALID_SAMPLE and PATH_TRACE_FLAG_RC_UNRESOLVED, and the neutral set
    // below is the same one a miss gets, so the guides never disagree with the zero weight in the
    // radiance lane.  A truncated path is still trusted: it stopped short, its primary vertex is real.
    if (!sampleTrusted || !result.primary.valid) {
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
float4 pathTraceNrdMotion(PathTraceResult result, bool sampleTrusted) {
    if (!sampleTrusted || !result.primary.valid) {
        return float4(0.0);
    }
    float3 motion = result.primaryMotion.xyz;
    float trusted = (result.primaryMotion.w > 0.5 || dot(motion, motion) > 1.0e-8) ? 1.0 : 0.0;
    return float4(motion, trusted);
}

// The running mean of the samples of one pixel, kept as sums and counts: the registers do not grow with the sample
// count, and the writers below read one PathTraceResult instead of a second copy of themselves. Radiance is summed
// twice over - once for the paths the tracer believed, once for the ones it refused - because a refused path is exactly
// the non-finite value that must never enter the mean the accumulator stores for thousands of frames.
struct PathTraceMean {
    float3 acceptedRadiance;
    float3 refusedRadiance;
    float4 motion;
    float3 position;
    float3 normal;
    float3 albedo;
    float3 specularF0;
    float3 direct;
    float  roughness;
    float  metallic;
    float  firstHit;
    uint   entityHits;   // believed samples whose primary landed on a captured model
    uint   accepted;
    uint   hits;         // believed samples whose primary decoded a surface
    uint   traced;
};

PathTraceMean emptyPathTraceMean() {
    PathTraceMean mean;
    mean.acceptedRadiance = float3(0.0);
    mean.refusedRadiance = float3(0.0);
    mean.motion = float4(0.0);
    mean.position = float3(0.0);
    mean.normal = float3(0.0);
    mean.albedo = float3(0.0);
    mean.specularF0 = float3(0.0);
    mean.direct = float3(0.0);
    mean.roughness = 0.0;
    mean.metallic = 0.0;
    mean.firstHit = 0.0;
    mean.entityHits = 0u;
    mean.accepted = 0u;
    mean.hits = 0u;
    mean.traced = 0u;
    return mean;
}

void accumulatePathTraceSample(inout PathTraceMean mean, PathTraceResult result) {
    mean.traced += 1u;
    if (pathTraceSampleRejected(result.flags)) {
        mean.refusedRadiance += result.radiance;
        return;
    }
    mean.accepted += 1u;
    mean.acceptedRadiance += result.radiance;
    if (!result.primary.valid) {
        return;
    }
    mean.hits += 1u;
    mean.position += result.primary.position;
    mean.normal += result.primary.normal;
    mean.albedo += result.primary.albedo;
    mean.specularF0 += result.primary.specularF0;
    mean.direct += result.primary.direct;
    mean.roughness += result.primary.roughness;
    mean.metallic += result.primary.metallic;
    mean.firstHit += result.firstHitDistance;
    mean.motion += result.primaryMotion;
    mean.entityHits += result.primaryMotion.w > 0.5 ? 1u : 0u;
}

// The one sample the writers already know how to read: a mean radiance for the integrand and a supersampled
// representative vertex for the guides. Averaging the vertex across the n sub-pixel positions is what a partly covered
// edge needs - a normal taken from a single sample would keep the aliasing the extra rays were asked to remove.
PathTraceResult pathTraceMeanResult(PathTraceMean mean, out bool sampleTrusted) {
    PathTraceResult result = emptyPathTraceResult();
    if (mean.accepted == 0u) {
        // Every path refused. The pixel keeps the legacy verdict - zero weight for the accumulator - and the mean of
        // what those paths produced, which is what a single refusing sample used to write.
        result.radiance = mean.refusedRadiance / max(float(mean.traced), 1.0);
        sampleTrusted = false;
        return result;
    }
    result.radiance = mean.acceptedRadiance / float(mean.accepted);
    sampleTrusted = true;
    if (mean.hits == 0u) {
        return result;   // every ray reached the sky: no vertex to average, the writers take the sky pattern
    }
    float hits = float(mean.hits);
    result.primary.valid = true;
    result.primary.position = mean.position / hits;
    float3 normal = mean.normal / hits;
    result.primary.normal = length(normal) > 1.0e-4 ? normalize(normal) : float3(0.0, 0.0, 1.0);
    result.primary.albedo = mean.albedo / hits;
    result.primary.specularF0 = mean.specularF0 / hits;
    result.primary.direct = mean.direct / hits;
    result.primary.roughness = mean.roughness / hits;
    result.primary.metallic = mean.metallic / hits;
    result.firstHitDistance = mean.firstHit / hits;
    // Whether this pixel belongs to a captured model or to terrain is a verdict, not a mean: the majority of the
    // samples decides, and a tie reads as terrain because a blurred world vector still reprojects a camera move.
    result.primaryMotion = float4(mean.motion.xyz / hits, mean.entityHits * 2u > mean.hits ? 1.0 : 0.0);
    result.flags = PATH_TRACE_FLAG_PRIMARY_HIT;
    return result;
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
    RWStructuredBuffer<RestirPtReservoir> ptReservoirs,
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
    // The ray profile's "Soft Shadows" switch decides whether the key light is sampled across its disc or at its axis.
    keyLight.hardShadows = (pc.flags & PATH_TRACE_OPTION_HARD_KEY_SHADOWS) != 0u;

    // Block emitters of this frame, in the list RtEmitters uploads for the legacy path as well. The receiver's own
    // emission reaches the sampler through LightReceiver, so a glowing face is not lit by its own quad.
    uint emitterCandidates = uint(max(pc.emitterCameraRelative.w, 1.0));
    BlockEmitterSampler emitterSampler;
    emitterSampler.records = emitters;
    emitterSampler.atlas = atlas;
    emitterSampler.candidates = emitterCandidates;
    emitterSampler.cameraRelative = pc.emitterCameraRelative.xyz;
    // The word carries the range the host collected emitters over; a host that leaves it 0 (the path profile's older
    // default) keeps the whole ray far plane.
    emitterSampler.maxDistance = asfloat(pc.diCameraDelta.w) > 0.0
        ? asfloat(pc.diCameraDelta.w) : max(pc.maxDistance, 1.0);

    // ReSTIR DI over the same list: one reservoir of this pixel, the reprojected history of the same surface.  The pass
    // always builds it and the push flag decides whether it is consulted, so one instantiation covers both branches.
    BlockEmitterDiSampler directReuse;
    directReuse.records = emitters;
    directReuse.reservoirs = reservoirs;
    directReuse.atlas = atlas;
    directReuse.candidates = emitterCandidates;
    directReuse.cameraRelative = pc.emitterCameraRelative.xyz;
    directReuse.maxDistance = emitterSampler.maxDistance;
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
    // Pixel-shaped key-light shadows: bits 8..15 of the same word carry the cell size in texture pixels (1/16 block
    // each), so the 112-byte push block did not have to grow a word.  A flag without a size is off, and the flag clear is
    // the exact per-pixel shadow of every earlier build.
    options.pixelShadowCell = (pc.flags & PATH_TRACE_OPTION_PIXEL_SHADOWS) != 0u
        ? float((pc.flags >> PATH_TRACE_PIXEL_SHADOW_SIZE_SHIFT) & 0xFFu) / 16.0 : 0.0;
    options.frameIndex = f.frame.x;
    // The reach this frame's emitter list was collected over: the distant block-light handover starts where the list
    // stops being complete, so the tracer reads the same range the sampler windows its candidates with.
    options.emitterListRange = emitterSampler.maxDistance;
    // The ray profile's own bounce distances.  A 0 word is the path profile's older host and keeps the ray far plane,
    // so the two words change nothing for a dispatch that does not fill them.
    options.diffuseDistance = asfloat(pc.diState.z) > 0.0 ? asfloat(pc.diState.z) : options.maxDistance;
    options.specularDistance = asfloat(pc.diState.w) > 0.0 ? asfloat(pc.diState.w) : options.maxDistance;
    // Whether the eye's own block is water, which only the host can answer: the
    // surface of that body can hide behind a block submerged in the same column.
    options.primaryInWater = frameCameraInWater(f);
    // The player's caustics switch, from the frame word rather than the push-constant mirror: it says whether the
    // waves focus the beam that got through.  The style switch is the surface decoder's wave mix below, because a
    // style changes how much the face tilts and never what the medium does to the light.
    options.waterCaustics = frameWaterCaustics(f);

    MaterialSamplingOptions materialOptions = pathTraceMaterialOptions(f, pc);

    // ReSTIR PT: one path reservoir per pixel, fed by the first ray's diffuse continuation (restir/pt_temporal.slang).
    // Its parity and history travel in the flag word because the ring does not depend on emitters, and DI's own state
    // is only written while DI runs.  A host without a ring binds one record, which ptTemporalActive refuses.
    PtTemporalState ptState;
    ptState.enabled = (pc.flags & PATH_TRACE_OPTION_RESTIR_PT) != 0u ? 1u : 0u;
    ptState.historyValid = (pc.flags & PATH_TRACE_PT_HISTORY) != 0u ? 1u : 0u;
    ptState.cameraStatic = (pc.flags & PATH_TRACE_PT_STATIC) != 0u ? 1u : 0u;
    ptState.flipY = (pc.flags & PATH_TRACE_PT_FLIP_Y) != 0u ? 1u : 0u;
    ptState.parity = (pc.flags & PATH_TRACE_PT_PARITY) != 0u ? 1u : 0u;
    ptState.pixel = p;
    ptState.size = size;
    ptState.frameIndex = f.frame.x;
    ptState.cameraDelta = pc.diCameraDelta.xyz;
    ptState.previousViewProjection = f.camera.prevViewProjection;

    uint sampleCount = pathTraceSampleCount(f);
    uint diRequested = directReuse.enabled;
    PathTraceMean total = emptyPathTraceMean();
    for (uint s = 0u; s < sampleCount; ++s) {
        // Each ray gets its own point in the pixel and its own path. With one ray per pixel both are exactly what the
        // host prepared - the frame's own jitter, the host's own sample index - so the cheapest setting traces the very
        // paths it traced before this loop existed.
        Ray primary = pathTracePrimaryRay(f, p, pc.maxDistance, pathTracePixelJitter(f, p, s, sampleCount));
        // One word identifies the sample across the whole frame: the path seed and the world-locked sun rotation of a
        // pixel-shadow cell both hash it, so the strata a cell shares are the strata every pixel of the frame drew.
        uint sampleWord = pc.sampleIndex * sampleCount + s;
        uint seed = pathTraceSeed(p, f.frame.x, sampleWord);
        options.sampleWord = sampleWord;
        // The reservoir is one record per pixel per frame, so only the first ray reads, merges and publishes it. The
        // rest shade the same emitter list with plain next-event estimation, and every write of the reservoir - the
        // publish, the occlusion decay, the empty record - is gated on reuseActive(), so turning it off here cannot
        // blank what the first ray published. emittersReusedAtPrimary follows the same value: a ray that did not draw
        // on the reservoir has to pay for the emissive branch itself, or the extra rays would darken the picture.
        directReuse.enabled = s == 0u ? diRequested : 0u;
        lights.emittersReusedAtPrimary = directReuse.enabled != 0u;
        // The path reservoir is one record per pixel as well: only the first ray records its first edge and hands it over,
        // the others keep their own suffixes and trace exactly what they traced before.
        options.flags = s == 0u ? pc.flags : (pc.flags & ~PATH_TRACE_OPTION_RESTIR_PT);
        uint pathSeed = seed;
        PathTraceResult traced = tracePathWithField(traversal, lights, environment, directReuse, atlas, table,
            primary, materialOptions, options, seed, diffuseField);
        if (s == 0u && ptState.enabled != 0u) {
            float3 swapped = ptTemporalStage(traversal, atlas, table, materialOptions, options.rayBias, traced, ptState,
                ptReservoirs, pathSeed);
            // The same guards the tracer applies to its own sum: a finite, non-negative radiance under the firefly cap.
            traced.radiance = isFinite(swapped)
                ? clampRadiance(max(swapped, float3(0.0)), options.maxRadiance) : traced.radiance;
        }
        accumulatePathTraceSample(total, traced);
    }
    // The frame the writers below read is the mean of those rays, not the last one: a pixel that traced eight of them
    // would otherwise throw seven away and keep only their noise.
    bool sampleTrusted;
    PathTraceResult result = pathTraceMeanResult(total, sampleTrusted);

    // Scene-referred linear radiance: no exposure and no tone map here.  Exposure lives in FrameConstants.exposure and
    // is applied once by the display/tonemap pass, so accumulation and denoise keep working in linear HDR.
    // The alpha lane is the pixel's verdict, not coverage: rgb has no room left to say "none of these paths could be
    // trusted", and a non-finite fault must not dilute a running mean that lives for thousands of frames.
    // The verdict is computed once: the guides and both motion lanes read the same bit, so no output can
    // contradict the alpha lane.
    outImage[p] = float4(result.radiance, sampleTrusted ? 1.0 : 0.0);
    // Legacy depth contract (rt_path.comp): a miss writes 1e6, because bloom.slang treats anything at or beyond
    // BLOOM_SKY_DISTANCE (1e5) as sky and the composite pass uses the same buffer.  firstHitDistance alone defaults
    // to the ray far plane on a miss, which is far below that threshold and would make the sky glow.
    outDepth[p] = (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u ? result.firstHitDistance : 1.0e6;
    // The velocity lane belongs to the accumulator, not to the integrand: it says where the surface this pixel
    // showed had itself been one frame ago.  A miss and terrain both say "nowhere", which is what a camera-only
    // reprojection already assumes, so no flag and no branch is needed downstream for them.  A rejected sample
    // says "nowhere" too: its vector was produced by a path the tracer refused, and the accumulator must not
    // reproject through it.
    outMotion[p] = sampleTrusted && (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u
        ? result.primaryMotion : float4(0.0);
    if ((options.flags & PATH_TRACE_OPTION_GBUFFER) != 0u) {
        pathTraceWriteGuides(p, f, result, sampleTrusted, outViewZ, outNormalRough, outSpecular, outDiffuse,
            outAlbedo, outDirect, outSpecAlbedo);
    }
    if ((options.flags & PATH_TRACE_OPTION_NRD_MOTION) != 0u) {
        outMotionBuffer[uint(p.x) + uint(p.y) * f.camera.size.x] = pathTraceNrdMotion(result, sampleTrusted);
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
    RWStructuredBuffer<RestirPtReservoir> ptReservoirs, PathTracePush pc) {
    NoPathDiffuseField field;
    pathTraceEntryWithField(traversal, entities, tid, outImage, albedoAtlas, outMotion,
        materialAtlas, normalAtlas, outDepth, outViewZ, outNormalRough, outSpecular,
        outDiffuse, outAlbedo, outDirect, outSpecAlbedo, outMotionBuffer, frame,
        blockMaterials, emitters, reservoirs, ptReservoirs, pc, field);
}

// The ReSTIR PT spatial entry (passes/restir_pt_spatial*.slang): the trace pass's own push block, atlases, block table and
// traversal, so the first edges it re-traces are walked exactly as the trace walked them.  It runs only on a frame whose
// trace ran the temporal stage; the parity is the half that trace wrote.
public void pathTracePtSpatialEntry<T : ISceneTracer>(T traversal, EntityGeometry entities, uint3 tid,
    Sampler2D albedoAtlas,
    Sampler2D materialAtlas,
    Sampler2D normalAtlas,
    FrameConstants frame,
    StructuredBuffer<BlockMaterial> blockMaterials,
    RWStructuredBuffer<RestirPtReservoir> ptReservoirs,
    PathTracePush pc) {
    FrameConstants f = frame;
    if ((pc.flags & PATH_TRACE_OPTION_RESTIR_PT) == 0u || f.camera.size.x == 0u || f.camera.size.y == 0u) {
        return;
    }
    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas;
    atlas.material = materialAtlas;
    atlas.normal = normalAtlas;
    atlas.entities = entities.atlas;

    BlockMaterialTable table;
    table.materials = blockMaterials;
    table.count = pc.materialCount;

    MaterialSamplingOptions materialOptions = pathTraceMaterialOptions(f, pc);
    ptSpatialStage(traversal, atlas, table, materialOptions, max(pc.rayBias, 0.0), ptReservoirs, int2(tid.xy),
        f.camera.size.xy, (pc.flags & PATH_TRACE_PT_PARITY) != 0u ? 1u : 0u, f.frame.x);
}
