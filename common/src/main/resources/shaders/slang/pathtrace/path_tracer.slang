// Iterative path tracer for Bedray 2.0.
//
// The loop is written against ISceneTracer, ILightSampler, IEnvironmentSampler and IReuseSampler.
// It never names a backend, a GPU or a material atlas layout: surface decoding
// is delegated to surface.slang and lobe selection to shading/bsdf.slang.
//
// Direct lighting uses next-event estimation with the filtered visibility walk,
// so a cutout hole is skipped and stained glass tints the shadow.  The pixel's
// own first surface may instead be served from a cross-frame reservoir (the
// IReuseSampler branch); the loop then shades both branches it owns and reports
// the shadow outcome back to the record, without knowing what a reservoir is.
// Indirect lighting uses BSDF sampling.  Emission is added on every accepted
// hit, so a caller whose sampler also draws surface emitters must restore the
// "previous bounce was delta" gate and use MIS, or an emitter hit would be
// counted twice.
//
// A horizontal fluid face is also a medium boundary.  The path keeps the water
// body it is currently in: a refracted transmission through the face enters or
// leaves it, every segment inside is attenuated by the body's extinction, and
// each segment adds the light it scatters towards the eye.  The same state
// travels into the shadow rays, so an underwater receiver is lit through its
// own water column instead of through clear air - and the key light's beam is
// bent where it crosses the face, so the column that gets lit is the one the
// light actually came down.  The water surface itself is
// clear (`surface.slang`); all of its colour is this absorption.  A camera that
// starts inside a body has no entry surface either, so `primaryInWater` seeds the
// same state before the first segment and the optical lobe refracts from the
// dense side, which is what lets a swimmer see the surface mirror.

public module path_tracer;

import "contracts/contracts.slang";
// The distant block-light handover: the baked field of a receiver the emitter list can no longer serve.  A light module
// like light/sky.slang, not a backend: the tracer still samples every light through ILightSampler.
import "light/block_emitters.slang";
import "light/sky.slang";
import "sampling.slang";
import "shading/bsdf.slang";
import "shading/microfacet.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
import "surface/water.slang";
import "traversal/traversal.slang";
__exported import "pathtrace/diffuse_field.slang";

static const uint PATH_TRACE_FLAG_ENVIRONMENT      = 1u << 0u;
static const uint PATH_TRACE_FLAG_RUSSIAN_ROULETTE = 1u << 1u;
static const uint PATH_TRACE_FLAG_DEPTH_LIMIT      = 1u << 2u;
static const uint PATH_TRACE_FLAG_INVALID_SAMPLE   = 1u << 3u;
static const uint PATH_TRACE_FLAG_SURFACE_LIMIT    = 1u << 4u;
static const uint PATH_TRACE_FLAG_FILTERED_SHADOW  = 1u << 5u;
// The primary ray hit an accepted surface, so firstHitDistance is a real depth rather than the far plane.  A depth
// buffer cannot infer this from surfaceHits: a path that only crosses cutout holes never accepted a surface.
static const uint PATH_TRACE_FLAG_PRIMARY_HIT      = 1u << 6u;
// The two ways a path ends badly are not the same fault.  INVALID_SAMPLE means the arithmetic could not be trusted -
// a non-finite ray, hit, advance or throughput, or a hit outside the interval the caller gave.  TRUNCATED means the
// path stopped for a legitimate reason: the block decode refused the surface, the lobe had nothing to sample, cutout
// advances used up the interval, or the throughput reached the firefly guard.  The radiance gathered before a
// truncation is a short but honest sample of the integrand, so it stays in the mean; only a non-finite fault is worth
// dropping from a history that lives for thousands of frames.
static const uint PATH_TRACE_FLAG_TRUNCATED        = 1u << 7u;
// The path crossed a horizontal fluid face and is now tracing inside a water
// body.  Kept as a diagnostic: the medium changes what a segment means, and a
// frame that never sets it is a frame where the fluid was not recognised.
static const uint PATH_TRACE_FLAG_WATER_MEDIUM     = 1u << 8u;
static const uint PATH_TRACE_FLAG_RC_FINITE_WINDOW = 1u << 9u;
static const uint PATH_TRACE_FLAG_RC_OUTSIDE       = 1u << 10u;
static const uint PATH_TRACE_FLAG_RC_TERMINAL      = 1u << 11u;
static const uint PATH_TRACE_FLAG_RC_UNRESOLVED    = 1u << 12u;
// A shadow walk of this path ran out of its filter budget (SURFACE_VISIBILITY_FLAG_OVERFLOW).  The direct term already
// reads that walk as occluded; the flag only lets the F3 counters say how often it happened.  Not a rejection.
static const uint PATH_TRACE_FLAG_VISIBILITY_OVERFLOW = 1u << 13u;

// Bits of PathTraceOptions.flags.  These are pass options (what the host asks the
// pass to produce), not properties of a sample: the tracer ignores them and the
// entry pass reads them.
static const uint PATH_TRACE_OPTION_GBUFFER        = 1u << 0u;  // fill PathTraceResult.primary for the denoiser guides
static const uint PATH_TRACE_OPTION_NRD_MOTION     = 1u << 1u;  // also write the primary hit's world motion for NRD's IN_MV
// The effect set of the ray profile (Ray Tracing mode).  Absent bits are the full transport the path profile runs, so a
// host that sets none of them - Path Tracing, the RC probe pass - traces exactly what it always traced.
static const uint PATH_TRACE_OPTION_NO_SPECULAR_BOUNCE  = 1u << 2u;  // a specular continuation ends the path
static const uint PATH_TRACE_OPTION_NO_DIFFUSE_BOUNCE   = 1u << 3u;  // a diffuse continuation ends the path
// A diffuse continuation may only collect the environment: a geometry hit is an occluder (sky-light ambient occlusion).
static const uint PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE = 1u << 4u;
static const uint PATH_TRACE_OPTION_NO_KEY_SHADOWS      = 1u << 5u;  // key-light NEE skips the visibility walk
// The key-light sampler draws the disc axis instead of a point of the disc: the same flux, a hard shadow edge.
static const uint PATH_TRACE_OPTION_HARD_KEY_SHADOWS    = 1u << 6u;
// Pixel-shaped key-light shadows: the primary receiver is evaluated once per world cell instead of once per pixel, so the
// shadow edge becomes the staircase of the block texture the cell is measured in (the deleted rt_common_pixel_shadow.glsl).
// Bits 8..15 carry the cell size in texture pixels, one sixteenth of a block each; the flag set with a zero size is
// treated as off, and the flag clear keeps the exact per-pixel shadow of every earlier build.
static const uint PATH_TRACE_OPTION_PIXEL_SHADOWS       = 1u << 7u;
static const uint PATH_TRACE_PIXEL_SHADOW_SIZE_SHIFT    = 8u;
// The host enabled block emitters for this frame: the list is the frame's, possibly empty.  Read by the distant
// handover below - past the list's reach the receiver is lit from the vanilla block-light field, and it must then skip
// the emitter branch instead of paying an NEE batch whose every candidate is rejected.  A frame with the switch off
// keeps no baked field either, so the deleted renderer's "emissive lighting off" A/B stays exactly that.
static const uint PATH_TRACE_OPTION_BLOCK_EMITTERS      = 1u << 16u;
// ReSTIR PT (restir/pt_temporal.slang): record where this path's diffuse continuation of the primary vertex first
// scattered, so the entry can hand that suffix to the pixel's path reservoir.  Bit 17 is the first free bit (8..15 carry
// the pixel-shadow cell, 16 the block emitters).  The tracer only records; it never reads or writes a reservoir, and a
// host that leaves the bit clear traces exactly what it traced before.
static const uint PATH_TRACE_OPTION_RESTIR_PT        = 1u << 17u;

// PathPtRecord.status.  NONE: the bit was clear or the primary receiver is not one the reservoir may own (sky pixel, water,
// an RC field continuation, an optical interface, a ray profile without a full diffuse bounce) - the pixel publishes an
// empty record and the reservoir adds nothing.  ATTEMPT: an eligible receiver drew one sample that never reached a first
// scattered vertex (specular lobe, no lobe, truncation, firefly, depth limit) - still one draw from the first-edge
// density, so it counts in M with no contribution.  VERTEX: the continuation reached its first vertex or escaped.
static const uint PATH_PT_RECORD_NONE    = 0u;
static const uint PATH_PT_RECORD_ATTEMPT = 1u;
static const uint PATH_PT_RECORD_VERTEX  = 2u;
// PathPtRecord.vertexFlags.  The stage maps them onto RESTIR_PT_PATH_FLAG_*; the tracer keeps its own namespace so it
// never imports the reservoir module.
static const uint PATH_PT_VERTEX_ENVIRONMENT = 1u << 0u;   // the continuation escaped: no vertex, a sky lookup
static const uint PATH_PT_VERTEX_EMISSIVE    = 1u << 1u;   // the first vertex emits (decoded material, not a hit hint)
static const uint PATH_PT_VERTEX_CERTIFIED   = 1u << 2u;   // the first vertex carries the diffuse certificate

// The first edge of one path, as the reservoir needs it.  Positions are camera-relative like every hit.
struct PathPtRecord {
    uint   status;               // PATH_PT_RECORD_*
    float3 anchorPosition;       // the primary vertex
    float3 anchorNormal;         // its view-facing shading normal
    float3 diffuseReflectance;   // pi * f_d at the anchor for this view: bsdfDiffuseReflectanceAt
    float3 direction;            // the diffuse continuation (anchor -> first vertex)
    float  sourcePdf;            // its solid-angle density, the lobe probability included
    float3 continuationWeight;   // the throughput the continuation left with (bsdf.weight at the primary)
    float3 radianceBefore;       // result.radiance when it left
    float3 vertexPosition;
    float3 vertexNormal;         // view-facing (towards the anchor)
    float  reversePdf;           // the first vertex's density of sampling the edge back to the anchor
    uint   vertexFlags;          // PATH_PT_VERTEX_*
    float3 suffixContribution;   // what the path gathered after the continuation left: the term the stage takes out
    float3 suffix;               // the same divided by continuationWeight: radiance arriving at the anchor
};

PathPtRecord emptyPathPtRecord() {
    PathPtRecord record;
    record.status = PATH_PT_RECORD_NONE;
    record.anchorPosition = float3(0.0);
    record.anchorNormal = float3(0.0, 1.0, 0.0);
    record.diffuseReflectance = float3(0.0);
    record.direction = float3(0.0, 1.0, 0.0);
    record.sourcePdf = 0.0;
    record.continuationWeight = float3(0.0);
    record.radianceBefore = float3(0.0);
    record.vertexPosition = float3(0.0);
    record.vertexNormal = float3(0.0, 1.0, 0.0);
    record.reversePdf = 0.0;
    record.vertexFlags = 0u;
    record.suffixContribution = float3(0.0);
    record.suffix = float3(0.0);
    return record;
}

// The emitter decision of a first vertex, made on the decoded material - the twin of ptTerminalEmits in
// experimental/restir_pt.slang.  HIT_FLAG_EMISSIVE is a backend hint the hardware traversal never sets.
bool ptTerminalEmitsMaterial(MaterialSample material) {
    return material.emission.r > 0.0 || material.emission.g > 0.0 || material.emission.b > 0.0;
}

// The diffuse-first certificate (RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST): an opaque, non-fluid, non-foliage, non-metal surface
// rough enough to be read as Lambertian.  The BSDF still has a GGX lobe at roughness >= 0.6, so treating that share as
// direction-independent is a documented approximation (docs/RESTIR_GI_DERIVATION.md, "Live temporal stage"), not a
// claim; the threshold is the denoiser's specular-guide rule, so one notion of "glossy" governs both.  Foliage is out
// because its back-lit NEE term depends on the side the light arrives from.
bool pathPtCertifiedDiffuse(MaterialSample material) {
    return material.alphaMode == AlphaMode.Opaque
        && (material.sourceFlags & (MATERIAL_SOURCE_FLUID | MATERIAL_SOURCE_FOLIAGE)) == 0u
        && material.metallic <= 0.5 && material.roughness >= 0.6;
}

struct PathTraceOptions {
    uint  maxDepth;             // scattering events before the path stops
    uint  maxSurfaceSteps;      // geometric candidates, including cutout holes
    uint  minRussianDepth;      // first depth where Russian roulette is allowed
    float rayBias;
    float russianMinProbability;
    float maxDistance;          // secondary ray far plane
    float maxRadiance;          // firefly / overflow guard
    float alphaCutoff;
    uint  flags;
    // Per-lobe far planes of a continuation (0 = maxDistance).  The ray profile stops its bounce rays at the distances
    // its own sliders name, so "Reflection Distance" and "GI Distance" are what the traced rays actually use.
    float diffuseDistance;
    float specularDistance;
    // The host's verdict that the eye itself sits in water.  Geometry cannot
    // answer it: the surface of that body can be hidden by a block submerged in
    // the same column.  Never read from the push-constant flags, which mirror a
    // host ABI bit for bit; this is a shader-internal decision.
    bool  primaryInWater;
    // The player's caustics switch: the wave field spreads or gathers the bent beam along the receiver's depth.
    // The style switch is NOT here - it changes the wave field a face keeps, which is the surface decoder's option,
    // not a different path for the light.
    bool  waterCaustics;
    // Pixel-shadow cell in blocks (0 = the exact per-pixel shadow), and the frame/sample words the world-locked sun
    // rotation is hashed from.  Pass options like the flags above: the entry sets them, the tracer only reads them.
    float pixelShadowCell;
    uint  frameIndex;
    uint  sampleWord;
    // The range the host collected this frame's emitter list over, in blocks.  The list only holds emitters within this
    // reach of the camera, so past `emitterListRange - EMITTER_RADIUS` it is no longer complete for a receiver and the
    // distant handover starts.  0 is an older host and keeps the whole ray far plane.
    float emitterListRange;
};

PathTraceOptions defaultPathTraceOptions() {
    PathTraceOptions options;
    options.maxDepth = 8u;
    options.maxSurfaceSteps = 64u;
    options.minRussianDepth = 3u;
    options.rayBias = 1.0e-3;
    options.russianMinProbability = 0.05;
    options.maxDistance = 1.0e6;
    options.maxRadiance = 1.0e6;
    options.alphaCutoff = 0.5;
    options.flags = 0u;
    options.diffuseDistance = 0.0;
    options.specularDistance = 0.0;
    options.primaryInWater = false;
    options.waterCaustics = false;
    options.pixelShadowCell = 0.0;
    options.frameIndex = 0u;
    options.sampleWord = 0u;
    options.emitterListRange = 0.0;
    return options;
}

// The primary surface as a denoiser sees it: the guide channels an NRD / DLSS-RR input pack needs, captured at the
// vertex the camera actually sees rather than re-derived by the pass.  A pass cannot recompute these without a second
// primary trace whose hit could disagree with the one the radiance came from.
struct PathPrimaryGuide {
    float3 position;      // camera-relative world position of the primary surface
    float3 normal;        // view-facing shading normal
    float3 albedo;        // linear diffuse albedo, floored for the demodulation
    float3 specularF0;    // interface reflectance at normal incidence
    float3 direct;        // this sample's next-event estimate at the primary vertex
    float  roughness;     // linear roughness (0..1)
    float  metallic;      // 0 = dielectric, 1 = metal
    bool   valid;
};

struct PathTraceResult {
    float3 radiance;
    float3 throughput;
    float  firstHitDistance;    // parameter from primary origin; distance when primary direction is unit length
    float4 primaryMotion;       // xyz: where the primary surface stood one frame ago minus where it is now, in
                                // world blocks; w: 1 when that surface is a captured model rather than terrain
    PathPrimaryGuide primary;   // guide channels, filled only when the primary ray hit a decodable surface
    PathPtRecord pt;            // ReSTIR PT first edge, recorded only under PATH_TRACE_OPTION_RESTIR_PT
    uint   depth;
    uint   surfaceHits;
    uint   flags;
};

PathTraceResult emptyPathTraceResult() {
    PathTraceResult result;
    result.radiance = float3(0.0);
    result.throughput = float3(1.0);
    result.firstHitDistance = 0.0;
    result.primaryMotion = float4(0.0);
    result.primary.position = float3(0.0);
    result.primary.normal = float3(0.0, 0.0, 1.0);
    result.primary.albedo = float3(0.0);
    result.primary.specularF0 = float3(0.0);
    result.primary.direct = float3(0.0);
    result.primary.roughness = 1.0;
    result.primary.metallic = 0.0;
    result.primary.valid = false;
    result.pt = emptyPathPtRecord();
    result.depth = 0u;
    result.surfaceHits = 0u;
    result.flags = 0u;
    return result;
}

float pathMaxComponent(float3 value) {
    return max(value.r, max(value.g, value.b));
}

// The one place "do not show this sample to the accumulator" is spelled out, so a pass cannot invent its own rule and
// silently start dropping truncated paths.
bool pathTraceSampleRejected(uint flags) {
    return (flags & (PATH_TRACE_FLAG_INVALID_SAMPLE | PATH_TRACE_FLAG_RC_UNRESOLVED)) != 0u;
}

// ---- Pixel-shaped shadows (the deleted rt_common_pixel_shadow.glsl) ----
// The option evaluates the shadow once per world cell: the receiver is snapped horizontally to the centre of its cell,
// so a whole cell shares one shadow ray and the edge becomes the staircase of the block texture the cell is measured in.
// Three rules keep the look from becoming a cheat, and all three live here: only the receiver moves (never the caster),
// a cell that crosses geometry keeps the exact shadow, and geometry that is not part of the block grid (a mob, a leaf)
// is never snapped.  The cell is world-locked: `SurfaceHit.worldPosition` is the world anchor modulo 1024 blocks, and
// every cell size the host can send (1/16 .. 1 block) divides the 1024-block wrap exactly.

// Cell index that rotates the sun/moon disc sequence: each fragment of a pixel-shadow cell draws the same disc point, so
// the quantised penumbra is one value instead of per-pixel speckle.
int3 pathTraceShadowCell(float3 worldPosition, float cell) {
    return int3(int(floor(worldPosition.x / cell)), int(floor(worldPosition.z / cell)), 0);
}

// World-locked rotation for a fragment that keeps its exact origin: the penumbra rays stay still in the world while the
// accumulating sub-pixel jitter moves the hit, instead of boiling.  The y shear decorrelates stacked surfaces, so a floor
// under a roof does not repeat the roof's sample pattern; the 1/16-block cell is the legacy one.
int3 pathTraceStableSunCell(float3 worldPosition) {
    float2 sheared = (worldPosition.xz + worldPosition.y * 0.5) * 16.0;
    return int3(int(floor(sheared.x)), int(floor(sheared.y)), 0);
}

// A leaf's rotation is its own block, frame term included in the caller: a leaf card never leaves that block, and hashing
// a 1/16-block cell would restart the whole disc sequence as jitter moves the hit across those cell borders.
int3 pathTraceFoliageSunCell(float3 worldPosition) {
    return int3(floor(worldPosition));
}

// The cell-stable seed of the sun/moon sample: same cell, same frame and same sample stratum draw the same disc point.
uint pathTraceSunSeed(int3 cell, uint frame, uint sampleWord) {
    uint seed = uint(cell.x) * 1973u + uint(cell.y) * 9277u + uint(cell.z) * 4093u;
    return pcgHash(pcgHash(seed + frame * 26699u) + sampleWord * 6971u);
}

// Origin of the shared shadow ray of a fragment's cell.  Returns `position` unchanged whenever sharing is not possible
// (the option is off or the cell crosses geometry), so a caller can use the result unconditionally.  The displacement
// stays horizontal and is projected into the surface plane - a naive world snap pushes wall fragments sideways into the
// wall - and the validation ray is the whole test: blocks are convex, so "the cell centre is inside geometry or behind a
// step" is exactly "the short segment from the fragment to that centre is blocked".  A fragment whose snap is rejected
// keeps its exact origin, which is what keeps shadows attached to their casters.
float3 pathPixelShadowOrigin<T : ISceneTracer>(T traversal, float3 position, float3 normal, float bias,
        float3 worldPosition, float cell) {
    float2 w = worldPosition.xz;
    float3 lateral = float3((floor(w.x / cell) + 0.5) * cell - w.x, 0.0,
                            (floor(w.y / cell) + 0.5) * cell - w.y);
    lateral -= normal * dot(lateral, normal);
    float len = length(lateral);
    if (len <= 1.0e-5) {
        return position;   // the fragment already sits on the cell centre
    }
    // The probe starts where the shadow ray will start, not on the face itself: a ray lying in its own triangle would
    // count that triangle as a blocker and reject every snap.
    Ray probe = makeRay(position + normal * bias, lateral / len, 0.0, len + 0.05);
    return traversal.traceVisibility(probe).visible ? position + lateral : position;
}

// The baked emitter term of the distant handover: the receiver's vanilla block light, tinted and gained, weighted by
// the same diffuse factors the emitter NEE would have carried - the albedo of the reflecting lobe, the energy the
// interface did not take ((1 - F) at the view angle, the legacy `Fsc`) and the LabPBR AO.  The legacy look has no
// cosine and no 1/PI in it: the field stands in for the whole estimate at a tuned scale (`BLOCK_LIGHT_FIELD_GAIN`),
// not for a second estimator, so these are the numbers the owner tuned rather than a re-derivation.
float3 pathBakedBlockLight(MaterialSample material, float3 view, float3 normal, float2 light) {
    float3 fresnelView = fresnelSchlick(max(dot(normal, view), 0.0), bsdfF0(material));
    float specularShare = max(fresnelView.r, max(fresnelView.g, fresnelView.b));
    return bsdfReflectionAlbedo(material) * (1.0 - specularShare) * material.ambientOcclusion
        * vanillaBlockIrradiance(light);
}

// Shades one direct-light sample: the BSDF term of the legacy NEE times the filtered visibility walk.  The caller
// multiplies the result by its throughput.  `traced` says a shadow ray really ran, `occluded` that it came back
// blocked; a reservoir needs both, because a sample this receiver cannot even see must fade instead of persisting.
// `medium` is the water body the receiver stands in: an underwater receiver is
// shadowed through its own column, and a sun or moon sample is walked as the bent
// beam `waterBeamToLight` names rather than as the straight sight line, which is
// what puts the light where the surface let it through and gives the floor its
// caustics.
float3 pathTraceDirectTerm<T : ISceneTracer>(T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceSample surface, float3 view, float3 normal, float3 position, LightSample light,
        PathTraceOptions options, MaterialSamplingOptions materialOptions, WaterMedium medium, bool primary,
        out bool traced, out bool occluded, inout uint pathFlags) {
    float3 zero = float3(0.0);
    traced = false;
    occluded = false;
    if (!light.valid || !(light.sourcePdf > 0.0)) {
        return zero;
    }
    float3 lightDirection = light.direction;
    // Under water the key light does not arrive along the sight line to the disc:
    // the face bends every air direction into the Snell window, so the beam is
    // what carries the light, not the straight line.  A block emitter that never
    // crossed the surface is not that problem, which is why the beam is asked for
    // only of the two lights the sampler treats as directional.  Both water
    // styles get it: a body of water does not stop bending light because a slider
    // moved, so the switch owns the waves and never the sight line.
    WaterBeam beam = invalidWaterBeam();
    if (medium.inside && (light.kind == LightKind.Sun || light.kind == LightKind.Moon)) {
        beam = waterBeamToLight(lightDirection, light.distance, position, medium);
    } else if (medium.inside && light.kind == LightKind.Emitter) {
        beam = waterBeamToPoint(light.position, position, medium);
    }
    float3 arrival = beam.valid ? -beam.direction : lightDirection;
    // Two weights ride on the bent beam.  The sample was drawn from air solid
    // angles and is now evaluated on the water side, and the ratio of the two
    // cones is what leaves the flux the surface actually let through.  The focus
    // is the wave field gathering that flux onto this point or spreading it thin,
    // and it is the part the caustics switch owns: the étendue ratio is flux
    // conservation, not an effect, so it stays whatever that switch says.  The
    // damped style focuses less because the waves that bend the bundle are weaker.
    float beamWeight = beam.valid
        ? beam.solidAngle * (options.waterCaustics
            ? waterBeamFocus(beam, position, beam.air, materialOptions.portalTime,
                materialOptions.waterWaveMix) : 1.0)
        : 1.0;
    float cosine = max(dot(normal, arrival), 0.0);
    // Offset from the geometric face, not the normal-mapped
    // shading normal: a tilted normal can start the shadow ray
    // inside the surface and either leak light or self-shadow.
    float3 shadowNormal = safeNormalize(surface.hit.geometricNormal, normal);
    if (dot(shadowNormal, view) < 0.0) {
        shadowNormal = -shadowNormal;
    }
    bool keyLight = light.kind == LightKind.Sun || light.kind == LightKind.Moon;
    // Light through a thin leaf card.  A plant seen against the sun has its shaded face toward the camera, so this
    // cosine is zero and the BSDF below has no front lobe; the legacy path lit exactly that case with a transmission
    // term, and without it every back-lit plant is a dark dot on a sunlit slope.  The term reads the light-facing
    // normal, and an emissive plant is excluded the way the filter is: a flame drawn in the cutout pass transmits no
    // sunlight.
    bool foliage = (surface.material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u
        && (surface.material.flags & MATERIAL_FLAG_EMISSIVE) == 0u;
    float leafBack = foliage && keyLight ? max(dot(-shadowNormal, arrival), 0.0) : 0.0;
    if (cosine <= 0.0 && leafBack <= 0.0) {
        return zero;
    }
    float3 bsdf = zero;
    if (cosine > 0.0) {
        bsdf = bsdfEvaluate(surface.material, view, normal, arrival);
        if (bsdf.r + bsdf.g + bsdf.b <= 0.0) {
            return zero;
        }
    }
    // The transmission term carries no cosine of the shaded face; its own ramp does that job.  The energy split the
    // legacy term took from the specular lobe is the same (1 - F) at the leaf's dielectric F0.
    float3 leafTerm = zero;
    if (leafBack > 0.0) {
        float leafGain = leafBack * FOLIAGE_BACKLIT_GAIN * smoothstep(0.0, 0.08, leafBack)
            * (1.0 - clamp(luminance(surface.material.f0), 0.0, 1.0));
        leafTerm = surface.material.albedo * (leafGain * FOLIAGE_BACKLIT_TINT);
    }
    // The ray profile's "RT Shadows" switch: with it off the key light still lights the surface, it simply skips the
    // visibility walk - the sun arrives unshadowed, while every block emitter keeps its traced shadow.
    if ((options.flags & PATH_TRACE_OPTION_NO_KEY_SHADOWS) != 0u && keyLight) {
        float3 unshadowed = leafBack > 0.0 ? leafTerm : bsdf;
        return unshadowed * light.radiance * beamWeight / max(light.sourcePdf, 1.0e-6);
    }
    traced = true;
    // The back-lit shadow ray has to start on the sun side of the leaf or it re-hits the caster.  A full leaf cube is
    // one block thick: its sun-side face is exactly one block along -normal, so the step crosses its own cell.  A card
    // (or a back-face hit, already on the sun-side face) is zero thickness and is cleared by 1 cm along the sun.  An
    // underwater receiver keeps the beam origin: the bent column, not the straight sight line, carries its light.
    float3 shadowPosition = position;
    if (leafBack > 0.0 && !medium.inside) {
        bool leafCube = (surface.hit.flags & HIT_FLAG_LEAF_CUBE) != 0u;
        bool frontFace = (surface.hit.flags & HIT_FLAG_FRONT_FACE) != 0u;
        shadowPosition = leafCube && frontFace
            ? position - shadowNormal * 1.01
            : position + lightDirection * 0.01;
    }
    // Pixel-shaped shadows: the primary fragment is shaded from the centre of its world cell, so one whole cell shares
    // one shadow ray.  Only the primary vertex pays for this - a deeper vertex is 1-3 spp and the denoiser dissolves
    // its quantization, while each one would spend a validation ray.  A leaf keeps the sun-side origin its transmission
    // needs, an entity moves inside a world-locked cell, and an underwater receiver's cell above means nothing.  The
    // same rule gates both the key light and a block emitter, which is what makes a torch cast the same staircase.
    if (primary && options.pixelShadowCell > 0.0 && leafBack <= 0.0 && !medium.inside
            && (surface.hit.flags & HIT_FLAG_ENTITY) == 0u) {
        shadowPosition = pathPixelShadowOrigin(traversal, position, shadowNormal, options.rayBias,
            surface.hit.worldPosition, options.pixelShadowCell);
    }
    SurfaceVisibility shadow = beam.valid
        ? traceShadowBeam(traversal, atlas, blockMaterials, shadowPosition, shadowNormal, lightDirection,
            light.distance, options.rayBias, materialOptions, medium, beam)
        : traceShadowVisibilityMedium(traversal, atlas, blockMaterials, shadowPosition, shadowNormal,
            lightDirection, light.distance, options.rayBias, materialOptions, medium);
    if ((shadow.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) != 0u) {
        pathFlags |= PATH_TRACE_FLAG_VISIBILITY_OVERFLOW;
    }
    // Sixteen filters deep the walk gives up and keeps the energy it collected.  That is not a proven sight line:
    // calling it one would light this surface through an opaque blocker hidden behind the glass.  Reporting it as
    // occluded also lets a reused record fade instead of persisting.
    float3 transmittance = surfaceVisibilityProven(shadow) ? shadow.result.transmittance : float3(0.0);
    if (transmittance.r + transmittance.g + transmittance.b <= 0.0) {
        occluded = true;
        return zero;
    }
    float3 estimator = leafBack > 0.0 ? leafTerm : bsdf;
    return estimator * light.radiance * transmittance * beamWeight / max(light.sourcePdf, 1.0e-6);
}

// How far up, and past how many blockers, the search for the surface of the
// body the eye sits in goes.  An ocean column is at most ~64 blocks deep and a
// submerged block or a waterlogged slab hides the top face from the first probe
// only for a moment, so the walk stops on the first face that is not a fluid
// top rather than after the first hit.
static const float WATER_PROBE_RISE = 64.0;
static const uint WATER_PROBE_STEPS = 6u;

// The water body the camera itself stands in, found by walking straight up from
// the eye until the top face of the column appears.  The host says whether the
// eye is in water - geometry cannot, because a block submerged in the same body
// hides its surface - and this answers which body: its biome tint and the height
// its surface sits at, both read off the face the same decoder names for a
// refracted transmission.  `inside` stays false when the walk leaves without a
// face, which is the caller's cue to keep its own fallback.
WaterMedium primaryWaterMedium<T : ISceneTracer>(T traversal, MaterialAtlas atlas,
        BlockMaterialTable blockMaterials, MaterialSamplingOptions materialOptions,
        float3 eye, float rayBias, float alphaCutoff) {
    float3 up = float3(0.0, 1.0, 0.0);
    float3 origin = eye;
    float remaining = WATER_PROBE_RISE;
    for (uint probe = 0u; probe < WATER_PROBE_STEPS && remaining > 0.0; ++probe) {
        SurfaceHit face = traversal.traceClosest(makeRay(origin, up, 0.0, remaining));
        if (!face.hit || !isFinite(face.t) || !(face.t > 0.0)) {
            break;
        }
        float advance = face.t + rayBias;
        origin = origin + up * advance;
        remaining -= advance;
        SurfaceSample body = decodeSurface(atlas, blockMaterials, face, materialOptions, up, false);
        if (!body.valid || surfaceIsHole(body, alphaCutoff)) {
            continue;
        }
        // A fluid face whose outward normal points up is the top of the column:
        // the same face the entry test below crosses when a ray dives in.
        if ((body.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u
                && body.hit.geometricNormal.y > 0.5) {
            return waterMediumFrom(waterBiomeTint(face.tint, body.hit.geometricNormal),
                body.hit.position.y);
        }
    }
    return noWaterMedium();
}

// Traces one path.  `seed` is advanced in place so a caller can derive the next
// path's seed without a global random stream.
PathTraceResult tracePathWithField<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler, R : IReuseSampler, F : IPathDiffuseField>(
        T traversal, L lights, E environment, R reuse,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
    PathTraceOptions options, inout uint seed, F field) {
    PathTraceResult result = emptyPathTraceResult();
    result.firstHitDistance = options.maxDistance;

    // A path that never reaches a surface has no sample of its own to publish, and the pixel's record still has to be
    // rewritten: a stale history would resurrect a light that has left the screen.  `reuseHandled` is set by the branch
    // that wrote it.
    bool reuseHandled = false;

    // A primary direction is unit length by the caller's contract, so
    // firstHitDistance is a distance. Reject a degenerate interval or
    // non-finite primary data instead of letting traversal treat it as a miss.
    if (!isFinite(primaryRay.origin) || !isFinite(primaryRay.direction) ||
        !(dot(primaryRay.direction, primaryRay.direction) > 0.0) ||
        !isFinite(primaryRay.tMin) || !isFinite(primaryRay.tMax) ||
        primaryRay.tMin < 0.0 || !(primaryRay.tMax > primaryRay.tMin)) {
        result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
        if (reuse.reuseActive()) {
            reuse.reuseClear();
        }
        return result;
    }

    float3 throughput = float3(1.0);
    Ray ray = primaryRay;
    bool firstSurfaceAccepted = false;
    // The previous continuation was an ambient-only (AO) bounce: the next opaque surface it reaches blocks the sky
    // light this ray carries, so the path ends there without shading that surface.
    bool ambientOnlyPending = false;
    // Cutout holes move the origin along the current ray. Keep the displacement
    // of the primary ray only, so a later hit is measured from the camera.
    float primaryAdvance = 0.0;
    uint step = 0u;
    // The diffuse continuation of the primary vertex is in flight and its first vertex is not recorded yet.
    bool ptArmed = false;

    // The water body the current ray runs in and the ray coordinate where its
    // current segment began.  A hole advance moves the origin, so the same
    // physical point sits at -rayBias on the new ray; tracking the coordinate
    // rather than an accumulated length keeps the next segment exact.
    WaterMedium medium = noWaterMedium();
    float mediumStart = 0.0;
    // Light arriving at the water body from above.  Only a path that entered
    // the water needs it, so it is fetched on the first entry instead of once
    // per path.
    float3 waterDown = float3(0.0);
    bool waterDownReady = false;

    if (options.primaryInWater) {
        // The eye's own body: the primary ray has no entry surface to cross, so
        // without this every segment under water would run through clear air.
        // A walk that found no face above still absorbs - the host named the
        // medium, and putting the surface at the eye is the shallowest reading
        // that cannot invent depth the column does not have.
        medium = primaryWaterMedium(traversal, atlas, blockMaterials, materialOptions,
            primaryRay.origin, options.rayBias, options.alphaCutoff);
        if (!medium.inside) {
            medium = waterMediumFrom(float3(1.0), primaryRay.origin.y);
        }
        waterDown = environment.radiance(float3(0.0, 1.0, 0.0));
        waterDownReady = true;
        result.flags |= PATH_TRACE_FLAG_WATER_MEDIUM;
    }

    for (; step < options.maxSurfaceSteps; ++step) {
        // A non-finite interval cannot be traced at all; an empty one is a path cut short by the cutout advances.
        if (!isFinite(ray.tMax)) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }
        if (!(ray.tMax > ray.tMin)) {
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }
        // The eye's own ray is the one a backend may answer with less geometry:
        // a first-person capture puts the local player's body around the camera.
        // A cutout hole does not increment surfaceHits, so a ray that walked
        // through the body's own skin keeps looking past it, and only the first
        // accepted surface starts the bounces that must see the body - it is
        // geometry a light has to cross.
        SurfaceHit hit = result.surfaceHits == 0u ? traversal.tracePrimary(ray)
                                                  : traversal.traceClosest(ray);
        if (!hit.hit) {
            // A ray inside the body that finds no surface still crossed water:
            // the tail is absorbed like every other segment (the legacy bound
            // of 48 blocks keeps an open ray from going black at the far plane).
            if (medium.inside) {
                float tail = clamp(ray.tMax - mediumStart, 0.0, 48.0);
                float3 tailMidpoint = ray.origin + ray.direction * (mediumStart + tail * 0.5);
                result.radiance += throughput * waterInScatter(medium, tailMidpoint.y, waterDown, tail);
                throughput *= waterSegmentTransmittance(medium, tail);
            }
            if (ptArmed) {
                // An escaped continuation has no vertex: the sky lookup is direction-dependent radiance, which the path
                // keeps (the stage never takes an ENVIRONMENT first edge into the reservoir).
                ptArmed = false;
                result.pt.status = PATH_PT_RECORD_VERTEX;
                result.pt.vertexPosition = ray.origin + ray.direction * ray.tMax;
                result.pt.vertexNormal = -ray.direction;
                result.pt.vertexFlags = PATH_PT_VERTEX_ENVIRONMENT;
            }
            // A ray that leaves the body upward without meeting a face (the walk up from the eye found none, or the sheet
            // is not in the scene) still crosses the plane the body was entered through: it leaves the dense side, so it
            // bends away from the vertical with the sheet's transmission and eta^2, exactly as the optical lobe weights a
            // crossing, or it is totally reflected and sees no sky.  The reflected half is not traced on.
            float3 escape = ray.direction;
            float3 escapeWeight = float3(1.0);
            if (medium.inside && ray.direction.y > 0.0) {
                float3 bent = refract(ray.direction, float3(0.0, -1.0, 0.0), WATER_IOR);
                if (dot(bent, bent) <= 1.0e-8) {
                    escapeWeight = float3(0.0);   // total internal reflection: this ray sees no sky
                } else {
                    escape = safeNormalize(bent, ray.direction);
                    escapeWeight = waterBeamTransmittance(escape.y) * (WATER_IOR * WATER_IOR);
                }
            }
            result.radiance += throughput * escapeWeight * environment.radiance(escape);
            result.flags |= PATH_TRACE_FLAG_ENVIRONMENT;
            break;
        }

        if (!isFinite(hit.t) || hit.t < ray.tMin || hit.t > ray.tMax) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }

        // Distance absorption of the segment that ends on this candidate.  The
        // in-scattered light is added at the throughput the segment started
        // with; the transmitted part continues through the extinction.
        if (medium.inside) {
            float segment = max(hit.t - mediumStart, 0.0);
            float3 midpoint = ray.origin + ray.direction * (mediumStart + segment * 0.5);
            result.radiance += throughput * waterInScatter(medium, midpoint.y, waterDown, segment);
            throughput *= waterSegmentTransmittance(medium, segment);
        }
        mediumStart = hit.t;

        SurfaceSample surface = decodeSurface(atlas, blockMaterials, hit, materialOptions, ray.direction);
        if (!surface.valid) {
            // A hit this decoder cannot name.  Nothing produced a bad number, so the radiance gathered up to here is
            // still a sample; the path simply stops short.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        if (surfaceIsHole(surface, options.alphaCutoff)) {
            // A cutout hole is not a surface: continue the same ray just past
            // the candidate without spending a scattering event.
            float advance = hit.t + options.rayBias;
            if (!isFinite(advance) || advance <= 0.0 ||
                (!firstSurfaceAccepted && !isFinite(primaryAdvance + advance))) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            if (!firstSurfaceAccepted) primaryAdvance += advance;
            float3 advancedOrigin = ray.origin + ray.direction * advance;
            if (!isFinite(advancedOrigin)) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            ray.origin = advancedOrigin;
            ray.tMax = max(ray.tMax - advance, 0.0);
            ray.tMin = 0.0;
            // The candidate now sits rayBias behind the new origin, so the
            // medium segment must continue from there, not from zero.
            mediumStart = -options.rayBias;
            continue;
        }

        if (ambientOnlyPending) {
            // An ambient-only bounce that found geometry collects nothing: the sky it would have seen is occluded.
            break;
        }

        if (!firstSurfaceAccepted) {
            if (!isFinite(primaryAdvance + hit.t)) {
                result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
                break;
            }
            firstSurfaceAccepted = true;
            result.firstHitDistance = primaryAdvance + hit.t;
            // What the accumulator needs is not this frame's surface but the same
            // surface as the previous frame saw it, so the question goes to the
            // backend that owns the geometry and stops here for every other ray.
            result.primaryMotion = float4(traversal.surfaceMotion(hit),
                hit.kind == HitKind.Entity ? 1.0 : 0.0);
            result.flags |= PATH_TRACE_FLAG_PRIMARY_HIT;
        }
        result.surfaceHits++;

        float3 normal = surface.shadingNormal;
        float3 view = -ray.direction;
        if (dot(normal, view) < 0.0) normal = -normal;

        if (ptArmed) {
            // The first scattered vertex of the primary diffuse continuation.  `view` points back along the edge to the
            // anchor, so bsdfPdf(material, view, normal, view) is this vertex's density of sampling that edge in reverse
            // - the module's `bsdfPdf` field.  (L = +direction faces into the surface and would always read 0.)
            ptArmed = false;
            result.pt.status = PATH_PT_RECORD_VERTEX;
            result.pt.vertexPosition = hit.position;
            result.pt.vertexNormal = normal;
            result.pt.reversePdf = bsdfPdf(surface.material, view, normal, view);
            result.pt.vertexFlags = (ptTerminalEmitsMaterial(surface.material) ? PATH_PT_VERTEX_EMISSIVE : 0u)
                | (pathPtCertifiedDiffuse(surface.material) ? PATH_PT_VERTEX_CERTIFIED : 0u);
        }

        // The face of a glowing block is seen as its own emission, and the light it casts on other surfaces comes from
        // NEE; a sampler that reads the emitter list rejects the receiver's own quad through LightReceiver.emission.
        result.radiance += throughput * surface.material.emission;

        // ---- Next-event estimation ----
        LightReceiver receiver = lightReceiver(hit.position, normal, surface.material.emissionMask);
        // Only the pixel's own first surface owns the screen-space record; a deeper vertex would write someone else's
        // receiver into it and drag that light across the screen.
        receiver.primary = result.surfaceHits == 1u;
        // The reservoir belongs to this surface, so its history has to be looked up where this surface was - which for
        // a mob part is not the pixel the camera alone points at.
        receiver.motion = result.primaryMotion.xyz;
        // The leaf transmission term below needs a key-light sample on the sun side of the card; the samplers read the
        // tag from the same place the estimator does, emissive exclusion included, so a glowing cutout plant is not
        // handed a key-light sample the estimator would discard.
        receiver.foliage = (surface.material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u
            && (surface.material.flags & MATERIAL_FLAG_EMISSIVE) == 0u;
        // The world-locked rotation of the sun/moon disc for this vertex.  A leaf freezes to its own block AND to a fixed
        // frame term: its transmission is a 0.6-per-layer staircase, so re-rolling the disc point every frame flips whole
        // texels - the ripple the owner kept reporting.  With pixel shadows on, a cell shares one disc point and every
        // vertex that keeps its exact origin still gets a world-locked one, so accumulating jitter cannot make the
        // penumbra boil.  With the option off nothing changes: the sample stays the exact per-path one.
        receiver.stableSun = false;
        receiver.sunSeed = 0u;
        if (receiver.foliage) {
            receiver.stableSun = true;
            receiver.sunSeed = pathTraceSunSeed(pathTraceFoliageSunCell(surface.hit.worldPosition), 0u,
                options.sampleWord);
        } else if (options.pixelShadowCell > 0.0) {
            bool cellShadow = receiver.primary && !medium.inside
                && (surface.hit.flags & HIT_FLAG_ENTITY) == 0u;
            int3 sunCell = cellShadow
                ? pathTraceShadowCell(surface.hit.worldPosition, options.pixelShadowCell)
                : pathTraceStableSunCell(surface.hit.worldPosition);
            receiver.stableSun = true;
            receiver.sunSeed = pathTraceSunSeed(sunCell, options.frameIndex, options.sampleWord);
        }
        float3 direct = float3(0.0);
        // ---- Distant block light ----
        // Past the list's reach the camera-sorted emitter list cannot serve this receiver any more, so it is lit from
        // the vanilla block-light field instead (the deleted rt_common_shade.glsl handover).  Only the primary vertex
        // has a camera distance - a deeper vertex is shaded where the previous one sent it and keeps the traced
        // estimate - and a face with no vanilla block light (a LabPBR glow, an emissive item) has nothing to fall back
        // to, so it keeps tracing.  The legacy branch was diffuse-only for the same reason the field is.
        float emitterFar = 0.0;
        bool emitterBaked = false;
        if (receiver.primary && (options.flags & PATH_TRACE_OPTION_BLOCK_EMITTERS) != 0u
                && !materialIsMetal(surface.material) && hit.light.x > 0.0) {
            float listRange = options.emitterListRange > 0.0 ? options.emitterListRange : options.maxDistance;
            // `hit.position` is camera-relative: the distance is measured from the camera, the way the list was sorted.
            emitterFar = blockLightFarMix(length(hit.position), listRange);
            // In the far end of the band the traced batch would spend its candidates on draws that are all rejected:
            // the emitter branch is skipped and the baked field is the whole term, while the key light keeps sampling.
            emitterBaked = emitterFar >= BLOCK_LIGHT_FAR_FULL;
        }
        receiver.bakedBlockLight = emitterBaked;
        if (receiver.primary && reuse.reuseActive() && !emitterBaked) {
            // The reservoir carries the emissive branch across frames, so the composite must not also draw it; the key
            // light is still sampled the plain way.  One extra shadow ray per pixel, no duplicated emitter batch.
            bool reusedTraced;
            bool reusedOccluded;
            LightSample reused = reuse.reuseSample(receiver, seed);
            reuseHandled = true;
            // The handover fades the traced estimate into the baked field across the band.  Mix, not max: the estimate
            // reads zero on most pixels of the band, so max() would put the whole field on top of an average that is
            // already lower and leave a bright ring around the player.
            direct += pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                reused, options, materialOptions, medium, receiver.primary, reusedTraced, reusedOccluded, result.flags)
                * (1.0 - emitterFar);
            // A sample this receiver could not even shade has not been proven visible either: decaying it lets a record
            // that lost its light fade out instead of sitting at full weight until the surface changes.
            reuse.reuseConfirm(!reusedTraced || reusedOccluded);
            bool keyTraced;
            bool keyOccluded;
            direct += pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                lights.sample(receiver, seed), options, materialOptions, medium, receiver.primary, keyTraced, keyOccluded, result.flags);
        } else {
            bool lightTraced;
            bool lightOccluded;
            LightSample lightSample = lights.sample(receiver, seed);
            float3 term = pathTraceDirectTerm(traversal, atlas, blockMaterials, surface, view, normal, hit.position,
                lightSample, options, materialOptions, medium, receiver.primary, lightTraced, lightOccluded, result.flags);
            // Only the emitter share of the composite draw fades: the key light is not part of the handover.
            direct += lightSample.kind == LightKind.Emitter ? term * (1.0 - emitterFar) : term;
        }
        // The baked field carries the band's outer share: the part of the term the list can no longer serve.  Added
        // after the branches, so a reused, a fresh emitter and a key-light draw all see the same field.
        if (emitterFar > 0.0) {
            direct += pathBakedBlockLight(surface.material, view, normal, hit.light) * emitterFar;
        }
        if (direct.r + direct.g + direct.b > 0.0) {
            result.flags |= PATH_TRACE_FLAG_FILTERED_SHADOW;
        }
        // The guide channels of the surface the camera sees, captured here because this is where its material, its
        // view-facing normal and its own next-event estimate exist.  `receiver.primary` is true exactly once per path,
        // at the first accepted surface; the throughput there is still 1, so `direct` is the primary vertex's own term.
        if (receiver.primary) {
            result.primary.valid = true;
            result.primary.position = hit.position;
            result.primary.normal = normal;
            result.primary.albedo = clamp(surface.material.albedo, float3(0.02), float3(1.0));
            result.primary.specularF0 = surface.material.f0;
            result.primary.roughness = surface.material.roughness;
            result.primary.metallic = surface.material.metallic;
            result.primary.direct = direct;
        }
        result.radiance += throughput * direct;

        // ReSTIR PT owns the diffuse continuation of an eligible primary vertex.  Eligibility is a property of the receiver,
        // decided before the lobe is drawn: whatever this sample then does, it is one attempt of that receiver's reservoir.
        // A receiver the reservoir must never add to - in water (the first edge is attenuated by the medium), an RC field
        // continuation, an optical interface (its sampler has no diffuse branch), a ray profile without a full diffuse
        // bounce - stays NONE and keeps every term in the path.
        if (receiver.primary && (options.flags & PATH_TRACE_OPTION_RESTIR_PT) != 0u && !medium.inside
                && !field.active() && surface.material.alphaMode != AlphaMode.Optical
                && (surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) == 0u
                && (options.flags & (PATH_TRACE_OPTION_NO_DIFFUSE_BOUNCE | PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE)) == 0u) {
            result.pt.status = PATH_PT_RECORD_ATTEMPT;
            result.pt.anchorPosition = hit.position;
            result.pt.anchorNormal = normal;
            // The same N.V floor bsdfSample uses, so the resolved f_d and the sampler's diffuse weight are one value.
            result.pt.diffuseReflectance = bsdfDiffuseReflectanceAt(surface.material,
                max(dot(normal, view), 1.0e-4));
        }

        // ---- BSDF sampling ----
        // The lobe refracts from the medium this ray travels in, not from air:
        // under water the surface is the dense side leaving, which bends away
        // from the normal and mirrors at grazing angles.
        BSDFSample bsdf = bsdfSample(surface.material, view, normal,
            medium.inside ? WATER_IOR : 1.0, seed);
        if (!bsdf.valid || bsdf.pdf <= 0.0) {
            // No lobe left to sample (an albedo that reflects nothing, or a
            // VNDF reflection below the shading horizon with zero BRDF).  A
            // short path, not a broken one: direct light and emission at this
            // vertex are already in `result.radiance`.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        // RC replaces only the selected primary diffuse continuation. Its
        // cosine mean excludes albedo/pi: bsdf.weight already contains the
        // reflection albedo and the lobe selection probability. Specular,
        // transmission, and water continuations retain the ordinary path.
        if (field.active() && receiver.primary && result.depth + 1u < options.maxDepth
            && bsdf.lobe == BSDF_LOBE_DIFFUSE && !medium.inside
            && (surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) == 0u) {
            float3 fieldOffsetNormal = safeNormalize(surface.hit.geometricNormal, normal);
            if (dot(fieldOffsetNormal, view) < 0.0) fieldOffsetNormal = -fieldOffsetNormal;
            DiffuseFieldSample incoming = field.evaluate(traversal, lights, environment,
                atlas, blockMaterials, hit.position, normal, fieldOffsetNormal, materialOptions,
                options.rayBias, options.maxRadiance, seed);
            if (incoming.status == DIFFUSE_FIELD_INVALID) {
                result.flags |= PATH_TRACE_FLAG_RC_UNRESOLVED;
                break;
            }
            if (incoming.status == DIFFUSE_FIELD_VALID) {
                result.radiance += throughput * bsdf.weight * incoming.cosineMean;
                result.flags |= PATH_TRACE_FLAG_RC_FINITE_WINDOW;
                if (incoming.terminalKnown) result.flags |= PATH_TRACE_FLAG_RC_TERMINAL;
                result.depth++;
                break;
            }
            result.flags |= PATH_TRACE_FLAG_RC_OUTSIDE;
        }

        // Which continuations this pass may trace.  The ray profile switches whole effects off (reflections, GI, AO)
        // and bounds the ones that stay with their own distances; the path profile leaves every bit clear and keeps the
        // full transport.  Transmission is never gated this way: looking through water or glass is not a ray effect.
        float continuationDistance = options.maxDistance;
        bool continuationAmbientOnly = false;
        if (bsdf.lobe == BSDF_LOBE_SPECULAR) {
            if ((options.flags & PATH_TRACE_OPTION_NO_SPECULAR_BOUNCE) != 0u) {
                result.flags |= PATH_TRACE_FLAG_TRUNCATED;
                break;
            }
            if (options.specularDistance > 0.0) {
                continuationDistance = options.specularDistance;
            }
        } else if (bsdf.lobe == BSDF_LOBE_DIFFUSE) {
            if ((options.flags & PATH_TRACE_OPTION_NO_DIFFUSE_BOUNCE) != 0u) {
                result.flags |= PATH_TRACE_FLAG_TRUNCATED;
                break;
            }
            continuationAmbientOnly = (options.flags & PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE) != 0u;
            if (options.diffuseDistance > 0.0) {
                continuationDistance = options.diffuseDistance;
            }
        }

        // A refracted transmission through a horizontal fluid face crosses the
        // water boundary; the transmitted direction says which side the next
        // segment runs on.  A reflection (including total internal reflection)
        // stays on its own side and toggles nothing.
        if ((surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u
                && bsdf.lobe == BSDF_LOBE_TRANSMISSION) {
            float faceY = surface.hit.geometricNormal.y;
            if (waterEntersMedium(medium.inside, faceY, bsdf.direction.y)) {
                if (!waterDownReady) {
                    waterDown = environment.radiance(float3(0.0, 1.0, 0.0));
                    waterDownReady = true;
                }
                medium = waterMediumFrom(waterBiomeTint(hit.tint, surface.hit.geometricNormal),
                    hit.position.y);
                result.flags |= PATH_TRACE_FLAG_WATER_MEDIUM;
            } else if (waterLeavesMedium(medium.inside, faceY, bsdf.direction.y)) {
                medium.inside = false;
            }
        }

        throughput *= bsdf.weight;
        result.depth++;

        if (pathMaxComponent(throughput) <= 0.0) {
            break;
        }

        if (result.depth >= options.maxDepth) {
            result.flags |= PATH_TRACE_FLAG_DEPTH_LIMIT;
            break;
        }

        if (result.depth >= options.minRussianDepth) {
            float survival = clamp(pathMaxComponent(throughput),
                options.russianMinProbability, 0.95);
            if (rand01(seed) >= survival) {
                result.flags |= PATH_TRACE_FLAG_RUSSIAN_ROULETTE;
                break;
            }
            throughput /= max(survival, 1.0e-4);
        }

        if (!isFinite(throughput)) {
            result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
            break;
        }
        if (pathMaxComponent(throughput) > options.maxRadiance) {
            // The firefly guard.  The path would blow up, so it ends here, but the tail clamps what came before to a
            // finite value: a bright bounce is a real sample, not a broken one.
            result.flags |= PATH_TRACE_FLAG_TRUNCATED;
            break;
        }

        // Offset off the geometric face, not along the outgoing ray: at grazing angles a ray-aligned bias leaves the
        // bounce inside the triangle it just left, and a tilted shading normal can point past the face.
        float3 bounceNormal = safeNormalize(surface.hit.geometricNormal, normal);
        float3 origin = hit.position
            + bounceNormal * (dot(bsdf.direction, bounceNormal) >= 0.0 ? options.rayBias : -options.rayBias);
        if (result.pt.status == PATH_PT_RECORD_ATTEMPT && result.surfaceHits == 1u
                && bsdf.lobe == BSDF_LOBE_DIFFUSE && !continuationAmbientOnly && !medium.inside) {
            // The continuation the reservoir may own leaves now.  Everything the path adds from here on is its suffix.
            ptArmed = true;
            result.pt.direction = bsdf.direction;
            result.pt.sourcePdf = bsdf.pdf;
            result.pt.continuationWeight = throughput;
            result.pt.radianceBefore = result.radiance;
        }
        ray = makeRay(origin, bsdf.direction, 0.0, continuationDistance);
        ambientOnlyPending = continuationAmbientOnly;
        // The new origin sits one bias off the interaction along the face; the segment starts there (the legacy path
        // ignored this offset too).
        mediumStart = 0.0;
    }

    if (step == options.maxSurfaceSteps) {
        result.flags |= PATH_TRACE_FLAG_SURFACE_LIMIT;
    }

    // Nothing wrote this pixel's record (a sky pixel, a truncated interval, a surface that stopped the path): publish an
    // empty one.
    if (!reuseHandled && reuse.reuseActive()) {
        reuse.reuseClear();
    }

    result.throughput = throughput;
    if (!isFinite(result.radiance)) {
        result.radiance = float3(0.0);
        result.flags |= PATH_TRACE_FLAG_INVALID_SAMPLE;
    }
    if (result.pt.status == PATH_PT_RECORD_VERTEX) {
        // Taken before the clamp below, so subtracting it removes exactly what the suffix added.  A rejected sample keeps
        // a zero suffix, and the stage refuses it as a candidate anyway.
        float3 radianceSuffix = (result.flags & PATH_TRACE_FLAG_INVALID_SAMPLE) != 0u
            ? float3(0.0) : max(result.radiance - result.pt.radianceBefore, float3(0.0));
        result.pt.suffixContribution = radianceSuffix;
        float3 weight = result.pt.continuationWeight;
        result.pt.suffix = float3(weight.r > 0.0 ? radianceSuffix.r / weight.r : 0.0,
            weight.g > 0.0 ? radianceSuffix.g / weight.g : 0.0,
            weight.b > 0.0 ? radianceSuffix.b / weight.b : 0.0);
    }
    result.radiance = clampRadiance(result.radiance, options.maxRadiance);
    return result;
}

PathTraceResult tracePath<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler, R : IReuseSampler>(
        T traversal, L lights, E environment, R reuse,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    NoPathDiffuseField field;
    return tracePathWithField(traversal, lights, environment, reuse, atlas,
        blockMaterials, primaryRay, materialOptions, options, seed, field);
}

// Radiance-only convenience for passes that do not need the diagnostics.
float3 tracePathRadiance<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler, R : IReuseSampler>(
        T traversal, L lights, E environment, R reuse,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        Ray primaryRay, MaterialSamplingOptions materialOptions,
        PathTraceOptions options, inout uint seed) {
    return tracePath(traversal, lights, environment, reuse, atlas, blockMaterials,
        primaryRay, materialOptions, options, seed).radiance;
}
