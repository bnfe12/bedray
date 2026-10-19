// Shared scene access of the ray tracing (rt_rays.comp) and path tracing (rt_path.comp) shaders: TLAS, section table, atlases,
// push constants, materials, sky, sampling and ray helpers. Included by RtShaderCompiler (textual include) after the
// version line and the defines. Ray queries: GL_EXT_ray_query (Khronos GLSL extension spec, GLSL_EXT_ray_query.txt).
#extension GL_EXT_ray_query : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_buffer_reference_uvec2 : require
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_ballot : require

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
#ifdef VKB_POST
// Post effects (rt_volumetric.comp): the final image is read and added to, the ray distance of the primary hit is read.
layout(set = 0, binding = 1, rgba8) uniform image2D outImage;
layout(set = 0, binding = 12, r32f) uniform readonly image2D outDepth;
#else
layout(set = 0, binding = 1, rgba8) uniform writeonly image2D outImage;
layout(set = 0, binding = 12, r32f) uniform writeonly image2D outDepth;
#endif

// 64 bytes per section slot, written by RtWorld.writeTableEntry
struct Section {
    uvec2 posAddr;    // float3 per vertex, 4 vertices per quad, section-local blocks
    uvec2 uvAddr;     // float2 per vertex (block atlas, 0..1, without Sodium's shrink bias)
    uvec2 colAddr;    // uint per vertex: R | G<<8 | B<<16 | A<<24 (biome tint * AO)
    uvec2 lightAddr;  // uint per vertex: blockLight | skyLight<<8 | material<<16, light bytes = clamp(level*16+8, 8, 248)
    uvec4 quadStart;  // x,y,z = first quad of geometry 0,1,2 ; w = geometry count
    ivec4 origin;     // section origin in blocks ; w = pass bits (2 bits per geometry: 0 solid, 1 cutout, 2 translucent)
};
layout(set = 0, binding = 2, std430) readonly buffer Sections { Section s[]; } sections;
layout(set = 0, binding = 3) uniform sampler2D atlas;
// Material atlas (RtMaterialAtlas, LabPbr): r linear roughness, g f0 (0..229/255) or 1.0 = metal, b emission, a source.
layout(set = 0, binding = 9) uniform sampler2D matAtlas;
// Normal atlas: LabPBR _n texel (rg DirectX normal xy, b AO, a height); flat = (0.5, 0.5, 1, 1).
layout(set = 0, binding = 10) uniform sampler2D nrmAtlas;

// Material-atlas source bit: redstone wire is force-translucent in the vanilla 26.3 block model,
// but its emissive pixels should not become filters/blockers in shadow / AO visibility queries.
const uint MATERIAL_SOURCE_REDSTONE = 4u;

// G-buffer for the NRD denoiser (RtPipeline compiles with "#define VKB_GBUFFER 1" when the denoiser is active).
#ifdef VKB_GBUFFER
layout(set = 0, binding = 4, r32f) uniform writeonly image2D gbViewZ;        // view-space Z (right-handed: negative in front), sky = +1e6
layout(set = 0, binding = 5, rgba16f) uniform writeonly image2D gbNormalRough; // xyz world normal facing the viewer, w linear roughness
layout(set = 0, binding = 6, rgba16f) uniform writeonly image2D gbDiffuse;     // rgb noisy radiance (no albedo), a hit distance in blocks
layout(set = 0, binding = 7, rgba8) uniform writeonly image2D gbAlbedo;        // albedo used by the lit view
layout(set = 0, binding = 8, rgba16f) uniform writeonly image2D gbDirect;      // rgb noise-free term added after the denoiser
#endif

// Accumulation image of the path tracer (RtPipeline accum variant, "#define VKB_ACCUM 1"): rgb running mean, a sample count.
#ifdef VKB_ACCUM
layout(set = 0, binding = 11, rgba32f) uniform image2D accumImage;
#endif

layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer FloatBuf { float v[]; };
layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer UIntBuf { uint v[]; };
// Per-frame slot written by RtFrameRenderer (statistics are zeroed on the GPU, the rest is written by the CPU before submit).
layout(buffer_reference, std430, buffer_reference_align = 16) buffer StatsBuf {
    uint hits;
    uint samples;
    uint lumLogSum;     // path tracing auto exposure: sum of (log2(luminance) + 16) * 64 over the counted pixels
    uint histAccepted;  // temporalAccumulate: counted hit pixels (1 in 16) whose reprojected history was accepted
    vec4 camRel;        // camera position relative to the emitter origin (blocks)
    uvec2 emitterAddr;  // EmitterBuf
    uint emitterCount;
    float exposure;     // auto exposure of the path tracer (CPU, from lumLogSum of earlier frames)
    float giDist;       // ray mode: GI ray length (blocks)
    float emisDist;     // ray mode: emissive NEE reach (blocks)
    float aoRadius;     // ray mode: AO radius (blocks)
    float autoNormalStrength;   // scale of the generated (auto) normal maps, both modes
    float sunRadius;            // angular radius of the sun/moon disk (radians), soft shadows of both modes
    float historyCap;           // longest temporal history (frames) usable this frame (RtAccumulation)
    mat4 prevViewProj;          // previous frame, camera-relative, unjittered
    vec4 camDelta;              // camera position now minus the previous frame (blocks)
    float fogDensity;           // fog density multiplier (0.0 .. 2.0, default 1.0)
    float fogInscattering;      // solar inscattering intensity (0.0 .. 2.0, default 1.0)
    float fogDistance;          // horizontal fog distance in blocks
    uint fogMode;               // 0 = OFF, 1 = DISTANCE, 2 = VOLUMETRIC
    float fogDistanceScale;     // horizontal fog distance scale (0.2 .. 2.0, default 1.0)
    uint entityTris;            // triangles of this frame's entity BLAS (RtEntityGeometryManager), 0 = none
    uvec2 entPosAddr;           // FloatBuf: float3 per vertex, camera-relative (same space as the TLAS)
    uvec2 entIdxAddr;           // UIntBuf: 3 indices per triangle (= BLAS primitive)
    uvec2 entAttrAddr;          // UIntBuf: per vertex colour ABGR, light (block | atlas slot << 8 | sky << 16), u, v (float bits)
    uvec2 entPrevAddr;          // FloatBuf: float3 per vertex, previous-frame position relative to the CURRENT camera
    uvec2 entAtlasAddr;         // UIntBuf: RT entity texture atlas, ABGR, entAtlasSize x entAtlasSize (EntityTextureAtlas)
    uvec2 entRectAddr;          // UIntBuf: x, y, w, h (texels) per atlas slot
    uvec2 motionAddr;           // Vec4Buf: per pixel (gbuffer variants): xyz = world motion prev - cur of the primary hit, w = 1 entity
    uint entAtlasSize;
    float skyCamY;              // 244 camera world Y (RtSkyState; height fog / god rays)
    vec2 cameraJitter;          // 248 camera subpixel jitter in [-0.5, 0.5] pixel units (0 = center)
    // ---- vanilla sky of the frame (RtSkyState.write, offset 256; rt_sky.glsl) ----
    vec4 skySunDir;             // 256 xyz towards the sun, w: SkyRenderState.sunAngle (rad)
    vec4 skyMoonDir;            // 272 xyz towards the moon, w: moonAngle (rad)
    vec4 skyFogColor;           // 288 rgb EnvironmentAttributes.FOG_COLOR, w: starBrightness
    vec4 skySunset;             // 304 SkyRenderState.sunriseAndSunsetColor (rgba)
    vec4 skyCloudColor;         // 320 LevelRenderState.cloudColor (rgba)
    vec4 skyCloudPos;           // 336 xyz camera in cloud space (blocks, x/z wrapped, y above the cloud bottom), w: cloud fog end
    vec4 skyMisc;               // 352 x starAngle, y sky fog end (blocks), z moon phase light 0..1, w rainBrightness
    uvec2 skyTexAddr;           // 368 UIntBuf of RtSkyTextures (0 = none)
    uint skyTexFlags;           // 376 moon phase (0-2) | has overworld sky (4) | sun flip x (5) | cloud status (8-9)
    float skyTilt;              // 380 tilt of the celestial arc about the east axis (rad), RtSky.tiltRad
    vec4 cameraBobOrigin;       // 384 camera origin offset in unbobbed-camera-relative world coordinates
    vec4 cameraForward;         // 400 xyz unbobbed world-space camera-forward axis for NRD's IN_VIEWZ guide; w: baked vanilla light fill 0..1 (rays)
    // ---- fog of the frame (RtFogState, offset 416) ----
    vec4 fogVanilla;            // 416 x environmental fog start, y end (blocks, spherical), z render distance fog start, w end (cylindrical): FogRenderer.setupFog
    vec4 fogMisc;               // 432 x rain 0..1, y medium (0 air, 1 water), z weight of the vanilla fog 0..1, w aerial perspective multiplier
    vec4 fogMedium;             // 448 rgb colour of the water fog (display space)
    vec4 fogFade;               // 464 x clock of the section fade-in (20 ms ticks), y fade length in ticks (0 = off)
};
layout(buffer_reference, std430, buffer_reference_align = 16) buffer Vec4Buf { vec4 v[]; };
// RtEmitters record: p0.xyz corner (relative to the emitter origin), p0.w area; e1, e2 edges; uvc.xy atlas UV of the
// emissive colour, uvc.z mean emission, uvc.w entity slot + 1; duv contains the UV edges; tint multiplies entity emitter RGB.
struct Emitter {
    vec4 p0;    // xyz corner, w area
    vec4 e1;    // xyz p1 - p0, w power boost of small sources
    vec4 e2;    // xyz p3 - p0, w: 1.0 = two-sided / open source (cutout, torches, lanterns), 0.0 = solid face
    vec4 uvc;   // xy uv(p0), z mean texel emission, w entity atlas slot + 1 (0 = block atlas; uv is then slot-local 0..1)
    vec4 duv;   // xy uv(p1) - uv(p0), zw uv(p3) - uv(p0)
    vec4 tint;  // entity emitter tint multiplier (RGB); block emitters use white
};
layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer EmitterBuf { Emitter e[]; };

// RtFrameParams (128 bytes)
layout(push_constant) uniform PC {
    mat4 invViewProj;   // clip -> camera-relative world (no jitter)
    uvec2 statsAddr;    // StatsBuf
    uint modeBits;      // view | aoSamples << 8 | flipY << 16 | tonemap << 17 | accumCount << 18
    uint frame;
    vec4 keyDir;        // xyz: towards the sun (day) or moon (night), w: day factor 0..1
    vec4 keyColor;      // rgb: key light colour (already scaled); w: flags (uint bits)
    vec4 skyColor;      // rgb: zenith colour from SkyRenderState; w: params2 (uint bits)
} pc;

const uint MODE_NORMALS = 0u;
const uint MODE_DEPTH = 1u;
const uint MODE_UV = 2u;
const uint MODE_SECTION = 3u;
const uint MODE_ALBEDO = 4u;
const uint MODE_LIT = 5u;
const uint MODE_SHADOW = 6u;
const uint MODE_AO = 7u;
const uint MODE_SKY = 8u;
const uint MODE_GI = 9u;
const uint MODE_EMISSION = 10u;
const uint MODE_BOUNCES = 11u;
const uint MODE_MATERIAL = 12u;
const uint MODE_REFLECTION = 13u;

const uint FLAG_SHADOWS = 1u;
const uint FLAG_SOFT_SHADOWS = 2u;
const uint FLAG_REFLECTIONS = 4u;
const uint FLAG_AO = 8u;
const uint FLAG_EMISSIVE = 16u;
const uint FLAG_MATERIALS = 32u;
const uint FLAG_ACCUMULATE = 64u;
const uint FLAG_NORMAL_MAPS = 128u;   // LabPBR _n maps are used
const uint FLAG_AUTO_NORMALS = 1024u; // generated normal maps of sprites without _n are used (strength in the frame slot)
const uint FLAG_AUTO_EXPOSURE = 256u;
const uint FLAG_GI = 512u;
const uint FLAG_GEOMETRIC_NORMALS = 2048u; // flat voxel geometric normal (ng) for G-buffer (DLSS-RR voxel sharpness)
const uint FLAG_VOLUMETRIC_FOG = 4096u;    // volumetric fog and atmospheric haze
const uint FLAG_WATER_REALISTIC = 8192u;
const uint FLAG_SUN_DENOISED = 65536u;     // first-hit sun NEE joins the denoised radiance signal when selected
const uint FLAG_DEMODULATE = 16777216u;
const uint FLAG_LOWDISC = 33554432u;      // low-discrepancy spatio-temporal sample sequence (rt_noise.glsl) instead of white noise   // ray mode: the diffuse irradiance is accumulated separately from the specular/emission/fog rest   // water style: 1 = Realistic, 0 = Vanilla

const uint WATER_STYLE_VANILLA = 0u;
const uint WATER_STYLE_REALISTIC = 1u;

const uint MASK_WORLD = 0x01u;
const uint MASK_ENTITIES = 0x02u;
const uint MASK_TRANSLUCENT = 0x04u;
const uint MASK_PARTICLES = 0x08u;
const uint MASK_FULL_SCENE = 0x0Fu;
const uint MASK_HERMETIC = MASK_WORLD | MASK_ENTITIES; // 0x03u

uint rtFlags() { return floatBitsToUint(pc.keyColor.w); }
bool hasFlag(uint f) { return (rtFlags() & f) != 0u; }
const float EMISSIVE_GAIN = 3.0;      // emission 1.0 -> radiance 3 x albedo (pre-exposure; RtLighting.EXPOSURE 1.1, ACES)
float sunRadius() { return StatsBuf(pc.statsAddr).sunRadius; }   // RtSettings.sunRadiusRad (real sun ~0.0046)

float waterTime() { return float(pc.frame) * 0.03; }
uint getWaterStyle() { return hasFlag(FLAG_WATER_REALISTIC) ? WATER_STYLE_REALISTIC : WATER_STYLE_VANILLA; }
uint viewMode() { return pc.modeBits & 255u; }
uint aoSampleCount() { return (pc.modeBits >> 8u) & 255u; }
uint accumCount() { return pc.modeBits >> 18u; }
uint params2() { return floatBitsToUint(pc.skyColor.w); }
uint maxBounces() { return (params2() >> 8u) & 255u; }
uint sppCount() { return max(1u, (params2() >> 16u) & 255u); }
float rayDistance() { return float((params2() >> 24u) & 255u) * 4.0; }

vec3 hashColor(uint x) {
    x = x * 747796405u + 2891336453u;
    x = ((x >> ((x >> 28u) + 4u)) ^ x) * 277803737u;
    x = (x >> 22u) ^ x;
    return vec3(float(x & 255u), float((x >> 8u) & 255u), float((x >> 16u) & 255u)) / 255.0 * 0.8 + 0.1;
}

vec3 loadPos(FloatBuf b, uint i) {
    return vec3(b.v[i * 3u], b.v[i * 3u + 1u], b.v[i * 3u + 2u]);
}

vec2 loadUv(FloatBuf b, uint i) {
    return vec2(b.v[i * 2u], b.v[i * 2u + 1u]);
}

vec3 unpackColor(uint c) {
    return vec3(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u)) / 255.0;
}

// (blockLight, skyLight) in 0..1 from the encoded light word
vec2 unpackLight(uint l) {
    vec2 raw = vec2(float(l & 255u), float((l >> 8u) & 255u));
    return clamp((raw - 8.0) / 16.0 / 15.0, 0.0, 1.0);
}

// Vertex indices of the triangle: primitive 2k is (0,1,2) of quad k, primitive 2k+1 is (2,3,0)
uvec3 triangleVertices(uint quad, uint prim) {
    uint vBase = quad * 4u;
    bool second = (prim & 1u) != 0u;
    return uvec3(vBase + (second ? 2u : 0u), vBase + (second ? 3u : 1u), vBase + (second ? 0u : 2u));
}

const uint INSTANCE_TYPE_ENTITY = 0x800000u;

bool isEntityHit(uint customIndex) {
    return (customIndex & INSTANCE_TYPE_ENTITY) != 0u;
}

uint getEntityRecordIndex(uint customIndex) {
    return customIndex & 0x7FFFFFu;
}

// Alpha of an entity atlas texel: transparent texels (< 26/255) keep their alpha, every other texel is opaque and carries a 5 bit
// emission code, alpha = (224 + code) / 255 (EntityTextureAtlas). Fallback texels are opaque white with code 0.
const vec4 ENTITY_TEXEL_FALLBACK = vec4(1.0, 1.0, 1.0, 224.0 / 255.0);

float entityEmission(vec4 texel) {
    float a = texel.a * 255.0 + 0.5;
    return a >= 224.0 ? float(uint(a) & 31u) / 31.0 : 0.0;
}

// True for a translucent texel of the entity atlas (stained glass / ice / slime item): its alpha is the real alpha 0.1 .. 0.875; the
// opaque texels (alpha >= 224/255) carry the emission code instead.
bool entityTexelTranslucent(vec4 texel) {
    float a = texel.a * 255.0 + 0.5;
    return a >= 26.0 && a < 224.0;
}

vec4 entityTexel(uint slot, vec2 uv) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    if (fb.entAtlasSize == 0u) {
        return ENTITY_TEXEL_FALLBACK;
    }
    UIntBuf rb = UIntBuf(fb.entRectAddr);
    uvec4 r = uvec4(rb.v[slot * 4u], rb.v[slot * 4u + 1u], rb.v[slot * 4u + 2u], rb.v[slot * 4u + 3u]);
    uvec2 size = uvec2(r.z & 0xFFFFu, r.w & 0xFFFFu);
    if (size.x == 0u || size.y == 0u) {
        return ENTITY_TEXEL_FALLBACK;
    }
    uvec2 t = uvec2(clamp(uv, vec2(0.0), vec2(0.99999)) * vec2(size));
    return unpackUnorm4x8(UIntBuf(fb.entAtlasAddr).v[(r.y + t.y) * fb.entAtlasSize + r.x + t.x]);
}

// Luminance of entity texel with clamped integer pixel offset (for Sobel auto-normal).
// Returns -1.0 when the offset texel is transparent (< 0.1 alpha) so UV island borders do not produce false cliffs.
float entityLumOffset(uint slot, vec2 uv, int dx, int dy) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    if (fb.entAtlasSize == 0u) return -1.0;
    UIntBuf rb = UIntBuf(fb.entRectAddr);
    uvec4 r = uvec4(rb.v[slot * 4u], rb.v[slot * 4u + 1u], rb.v[slot * 4u + 2u], rb.v[slot * 4u + 3u]);
    uvec2 size = uvec2(r.z & 0xFFFFu, r.w & 0xFFFFu);
    if (size.x == 0u || size.y == 0u) return -1.0;
    ivec2 t = ivec2(clamp(uv, vec2(0.0), vec2(0.99999)) * vec2(size));
    t = clamp(t + ivec2(dx, dy), ivec2(0), ivec2(size) - ivec2(1));
    vec4 c = unpackUnorm4x8(UIntBuf(fb.entAtlasAddr).v[(r.y + uint(t.y)) * fb.entAtlasSize + r.x + uint(t.x)]);
    if (c.a < 0.1) return -1.0;
    return dot(c.rgb, vec3(0.299, 0.587, 0.114));
}


uvec3 entityTriangle(uint prim) {
    UIntBuf ib = UIntBuf(StatsBuf(pc.statsAddr).entIdxAddr);
    return uvec3(ib.v[prim * 3u], ib.v[prim * 3u + 1u], ib.v[prim * 3u + 2u]);
}

vec2 entityUv(uvec3 vi, vec3 b3) {
    UIntBuf ab = UIntBuf(StatsBuf(pc.statsAddr).entAttrAddr);
    return vec2(uintBitsToFloat(ab.v[vi.x * 4u + 2u]), uintBitsToFloat(ab.v[vi.x * 4u + 3u])) * b3.x
         + vec2(uintBitsToFloat(ab.v[vi.y * 4u + 2u]), uintBitsToFloat(ab.v[vi.y * 4u + 3u])) * b3.y
         + vec2(uintBitsToFloat(ab.v[vi.z * 4u + 2u]), uintBitsToFloat(ab.v[vi.z * 4u + 3u])) * b3.z;
}

uint entitySlot(uvec3 vi) {
    return (UIntBuf(StatsBuf(pc.statsAddr).entAttrAddr).v[vi.x * 4u + 1u] >> 8u) & 0xFFu;
}

// Set by candidateAccepted when a skipTranslucent ray let a surface pass that a non-skipping ray would have taken (glass, water, ice,
// translucent texels). traceTransmittance reads it: when a visibility ray reached its end without ever skipping such a surface,
// the light is unfiltered and the (much more expensive) layer walk can be skipped.
bool gSawTranslucent = false;

// Alpha cutout of entity / item triangles (the entity BLAS is not opaque): texel alpha >= 0.1 like vanilla entity_cutout. Texels
// between 0.1 and 0.95 (stained glass / ice / slime items) are glass-like: visibility and shadow rays treat them as filters.
bool entityCandidateAccepted(uint prim, vec2 bary, bool skipTranslucent) {
    if (prim >= StatsBuf(pc.statsAddr).entityTris) {
        return true;
    }
    uvec3 vi = entityTriangle(prim);
    uint l = UIntBuf(StatsBuf(pc.statsAddr).entAttrAddr).v[vi.x * 4u + 1u];
    if (((l >> 24u) & 0x08u) != 0u) {
        // Selection outline wireframe: never blocks shadow, AO, or secondary/visibility rays
        return !skipTranslucent;
    }
    vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    vec4 et = entityTexel(entitySlot(vi), entityUv(vi, b3));
    float a = et.a;
    if (a < 0.1) {
        return false;
    }
    // atlas alpha >= 224/255 = opaque texel with an emission code (EntityTextureAtlas); below it the texel keeps its true alpha
    if (skipTranslucent && entityTexelTranslucent(et) && max(et.r, max(et.g, et.b)) > 0.08) {
        gSawTranslucent = true;
        return false;
    }
    return true;
}

// Set by candidateAccepted when a skipTranslucent ray let a surface pass that a non-skipping ray would have taken (glass, water, ice,
// translucent texels). traceTransmittance reads it: when a visibility ray reached its end without ever skipping such a surface,
// the light is unfiltered and the (much more expensive) layer walk can be skipped.

// Distance (blocks) of the candidate hit being classified by candidateAccepted (set by CANDIDATE_ACCEPTED), the dust coverage needs it.
float gHitT = 0.0;

// Size of one pixel of the 3D view on a surface at distance t, in texels of a 16x16 block sprite (about 0.0016 rad per pixel).
float dustFootprintTexels(float t) {
    return t * 0.0016 * 16.0;
}

// Coverage 0..1 of a redstone dust texel neighbourhood. The 26.3 dust sprite is a thin, sparse line (about 20 of 256 texels) and the game
// shows it MIP-MAPPED: far away the line stays a continuous soft line. The ray tracer sampled one texel (mip 0), so a line further away
// than a few blocks fell apart into dots with holes between them (connections, crossings and corners lost) and the lone texels
// flickered. Close up (a pixel covers at most one texel) the texel itself decides (alpha >= 0.5); farther the alpha is averaged over
// 3 x 3 taps spread over the pixel footprint (as a mip level would), and any coverage makes the dust visible.
float dustCoverage(vec2 uv, float t) {
    float f = dustFootprintTexels(t);
    float a0 = textureLod(atlas, uv, 0.0).a;
    if (f <= 1.0) {
        return a0 >= 0.5 ? 1.0 : 0.0;
    }
    vec2 texel = 1.0 / vec2(textureSize(atlas, 0));
    vec2 r = texel * (0.5 * min(f, 8.0));
    float sum = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            sum += textureLod(atlas, uv + vec2(float(x), float(y)) * r, 0.0).a;
        }
    }
    return sum / 9.0;
}



// Alpha test of a non-opaque candidate: cutout needs atlas alpha >= 0.5; translucent candidates are confirmed
// unless the caller skips them (shadow/visibility/AO rays let translucent surfaces and cutout glass pass).
bool candidateAccepted(uint slot, uint geom, uint prim, vec2 bary, bool skipTranslucent) {
    if (isEntityHit(slot)) {
        return entityCandidateAccepted(prim, bary, skipTranslucent);
    }
    Section sec = sections.s[slot];
    uint pass = (uint(sec.origin.w) >> (2u * geom)) & 3u;
    if (pass == 2u && hasFlag(FLAG_MATERIALS)) {
        FloatBuf uvb = FloatBuf(sec.uvAddr);
        uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
        vec2 uv = (1.0 - bary.x - bary.y) * loadUv(uvb, v.x) + bary.x * loadUv(uvb, v.y) + bary.y * loadUv(uvb, v.z);
        uint sourceFlags = 255u - uint(textureLod(matAtlas, uv, 0.0).a * 255.0 + 0.5);
        if ((sourceFlags & MATERIAL_SOURCE_REDSTONE) != 0u && !skipTranslucent) {
            // Dust is built of coplanar quads (dot, side halves, up): a closest-hit that took the transparent texel of one of them
            // hid the opaque texel of the quad beside it (the ray continues past the plane), so the lines of the wire fell apart
            // into fragments at the joints. The holes are decided here, per candidate: only a dust texel is a hit.
            float cov = dustCoverage(uv, gHitT);
            return cov >= (dustFootprintTexels(gHitT) <= 1.0 ? 0.5 : 0.04);
        }
        if ((sourceFlags & MATERIAL_SOURCE_REDSTONE) != 0u) {
            // redstone_dust_dot/line models force the dust and overlay textures into TRANSLUCENT
            // (pass 2). The generic pass-2 path marks gSawTranslucent, causing traceTransmittance
            // to shade the thin dust quad as an alpha filter and produce block-shaped shadows.
            // Visibility-style rays ignore only its tagged texels; camera/reflection hits and the
            // emitter sampler remain unchanged.
            return false;
        }
    }
    if (pass == 2u) {
        gSawTranslucent = gSawTranslucent || skipTranslucent;
        return !skipTranslucent;
    }
    FloatBuf uvb = FloatBuf(sec.uvAddr);
    uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
    vec2 uv = (1.0 - bary.x - bary.y) * loadUv(uvb, v.x) + bary.x * loadUv(uvb, v.y) + bary.y * loadUv(uvb, v.z);
    float a = textureLod(atlas, uv, 0.0).a;
    if (skipTranslucent && a < 0.99) {
        // a texel below the cutout threshold is simply a hole (a non-skipping ray ignores it too), anything else is a filter
        if (pass == 0u || a >= 0.5) {
            gSawTranslucent = true;
        }
        return false;
    }
    if (skipTranslucent && hasFlag(FLAG_MATERIALS)) {
        vec4 m = textureLod(matAtlas, uv, 0.0);
        // Ice / blue_ice dielectric material (f0 <= 0.025, roughness <= 0.08)
        if (m.g <= 0.025 && m.r <= 0.08 && m.g > 0.005) {
            gSawTranslucent = true;
            return false;
        }
    }
    if (pass == 0u) {
        return true;
    }
    return a >= 0.5;
}

// A rayQueryEXT cannot be passed to a function, so the candidate is decoded at the call site by this macro.
#define CANDIDATE_ACCEPTED(q, skip) (gHitT = rayQueryGetIntersectionTEXT(q, false), candidateAccepted(     uint(rayQueryGetIntersectionInstanceCustomIndexEXT(q, false)),     uint(rayQueryGetIntersectionGeometryIndexEXT(q, false)),     uint(rayQueryGetIntersectionPrimitiveIndexEXT(q, false)),     rayQueryGetIntersectionBarycentricsEXT(q, false), skip))

#include "rt_tonemap.glsl"
#include "rt_sky.glsl"

// Vanilla face shade baked into the Sodium vertex colour (RtLighting.cardinalShade), for a unit normal.
float cardinalShade(vec3 n) {
    return n.y * n.y * (n.y > 0.0 ? 1.0 : 0.5) + n.x * n.x * 0.6 + n.z * n.z * 0.8;
}

// ---- Sky light model (RtSky.skyLight mirrors it) ----------------------------------------------------------------------------
// The sky is the second light of every surface next to the sun/moon key light. Its colour follows the real position of the sun
// in the world (StatsBuf.skySunDir.y, not the key light: at night the key is the moon):
//   noon        clean neutral-cool sky dome, no blue wash (0.25 of the vanilla zenith colour is mixed in, like before)
//   golden hour horizon turns warm orange, zenith keeps a soft violet-blue, the whole dome gets dimmer
//   twilight    short violet-blue afterglow below the horizon
//   night       dark blue moon/star light whose strength follows the moon phase (never fully black)
// Overworld only: dimensions without a skybox keep the neutral constant ambient.
struct SkyLight {
    vec3 zenith;    // irradiance colour from straight above
    vec3 horizon;   // irradiance colour from the horizon band
    vec3 ground;    // bounce colour from below
};

SkyLight skyLight() {
    SkyLight r;
    StatsBuf sb = StatsBuf(pc.statsAddr);
    float day = pc.keyDir.w;
    vec3 skyTint = 0.25 * pc.skyColor.rgb + 0.75;
    vec3 groundAlbedo = vec3(0.45, 0.43, 0.40);                                   // RtSky.GROUND_COLOR
    if (((sb.skyTexFlags >> 4u) & 1u) == 0u) {
        if (skyIsNether()) {
            vec3 biomeFog = sb.skyFogColor.rgb;
            float netherAmbient = 0.45;
            r.zenith = (biomeFog * 0.75 + 0.05) * netherAmbient;
            r.horizon = (biomeFog * 0.90 + 0.05) * netherAmbient;
            r.ground = mix(groundAlbedo, biomeFog, 0.5) * (netherAmbient * 0.85);
            return r;
        }
        if (skyIsEnd()) {
            vec3 endFog = sb.skyFogColor.rgb;
            vec3 cosmicTint = vec3(0.09, 0.06, 0.14);
            vec3 endAmbient = mix(cosmicTint, endFog, 0.35);
            r.zenith = endAmbient * 0.42;
            r.horizon = endAmbient * 0.32;
            r.ground = mix(groundAlbedo, endAmbient, 0.4) * 0.25;
            return r;
        }
        float scale = 0.65 * (pow(max(0.0, day), 1.5) * 0.90 + 0.01);
        r.zenith = skyTint * scale;
        r.horizon = skyTint * scale;
        r.ground = groundAlbedo * scale;
        return r;
    }
    float se = clamp(sb.skySunDir.y, -1.0, 1.0);                                  // sun elevation, sin(angle)
    float rain = clamp(sb.skyMisc.w, 0.0, 1.0);                                   // 1 clear .. 0 full rain
    float moonUp = smoothstep(-0.05, 0.35, sb.skyMoonDir.y);
    float moonPhase = clamp(sb.skyMisc.z, 0.0, 1.0);

    // daylight dome
    float golden = 1.0 - smoothstep(0.05, 0.50, se);                              // 1 at the horizon .. 0 above ~30 degrees
    vec3 zenithDay = mix(vec3(0.90, 0.94, 1.00), skyTint, 0.5);                   // soft blue-white (RtSky.ZENITH_DAY)
    vec3 horizonDay = vec3(1.00, 0.97, 0.92);                                     // warm white haze at noon
    vec3 zenithGold = vec3(0.70, 0.66, 0.90);                                     // violet-blue overhead at sunset
    vec3 horizonGold = vec3(1.00, 0.58, 0.32);                                    // orange glow at the horizon
    vec3 zenithD = mix(zenithDay, zenithGold, golden * 0.8);
    vec3 horizonD = mix(horizonDay, horizonGold, golden * 0.9);
    // overcast: grey, less contrast between dome and horizon
    float over = 1.0 - rain;
    float lum = dot(zenithD, vec3(0.299, 0.587, 0.114));
    zenithD = mix(zenithD, vec3(lum), 0.6 * over);
    horizonD = mix(horizonD, vec3(dot(horizonD, vec3(0.299, 0.587, 0.114))), 0.7 * over);
    float dayScale = 0.42 * pow(max(0.0, day), 1.5);                              // boosted daytime sky ambient so ground/shade stays readable

    // twilight afterglow and night (moon / stars)
    float twilight = smoothstep(-0.28, -0.04, se) * (1.0 - smoothstep(-0.04, 0.10, se));
    float night = (0.022 + 0.040 * moonPhase * moonUp) * (1.0 - day);             // RtSky.NIGHT_LEVEL
    vec3 nightZ = vec3(0.30, 0.40, 0.70);
    vec3 nightH = vec3(0.34, 0.42, 0.66);
    vec3 twZ = vec3(0.36, 0.34, 0.74) * 0.040 * twilight;
    vec3 twH = vec3(0.80, 0.38, 0.44) * 0.036 * twilight;

    r.zenith = zenithD * dayScale + nightZ * night + twZ;
    r.horizon = horizonD * dayScale + nightH * night + twH;
    // ground bounce: albedo lit by the same key light, so sunset sand glows warm and moonlit ground stays cool
    vec3 keyN = pc.keyColor.rgb / max(max(pc.keyColor.r, max(pc.keyColor.g, pc.keyColor.b)), 1.0e-3);
    vec3 groundTint = mix(vec3(1.0), keyN, 0.6);
    r.ground = groundAlbedo * groundTint * (dayScale * 1.15 + 0.5 * night) + 0.5 * (twZ + twH) * 0.5;
    return r;
}

// Hemisphere ambient for a surface normal with vertical component ny: ground bounce below, sky above (RtSky.ambient).
vec3 hemisphereAmbient(float ny, float day) {
    SkyLight l = skyLight();
    float k = 0.5 + 0.5 * clamp(ny, -1.0, 1.0);
    vec3 sky = mix(l.horizon, l.zenith, clamp(ny, 0.0, 1.0));
    return mix(l.ground, sky, k);
}

// Radiance of the sky seen by a secondary (bounce) ray, without the sun disk (the sun is sampled explicitly, NEE).
// Calibrated so that an unoccluded upward surface receives the same irradiance as hemisphereAmbient(1, day) * PI:
// a cosine-weighted estimate of sky radiance L gives albedo * mean(L), which equals albedo * hemisphereAmbient for an open sky.
vec3 skyRadiance(vec3 dir) {
    SkyLight l = skyLight();
    if (dir.y < 0.0) {
        return l.ground;
    }
    float h = clamp(dir.y, 0.0, 1.0);
    return mix(l.horizon, l.zenith, pow(h, 0.6));
}

const float PI = 3.14159265358979323846;

// Henyey-Greenstein anisotropic phase function for Mie in-scattering
// p(theta, g) = (1 - g^2) / (4 * pi * (1 + g^2 - 2g * cosTheta)^1.5)
float phaseHenyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * denom * sqrt(max(denom, 1.0e-5)));
}

float traceVisibility(vec3 origin, vec3 d, float tmax);   // defined below

// Height profile of the air fog density, after the Bedrock RTX fog definition canon (volumetric.density.air with
// uniform = false: full density below max_density_height, none above zero_density_height; NVIDIA texturing guide example
// 56 / 70, here 62 (sea level) / 128 for Java terrain) with a 15 % floor so that valleys and peaks both keep some haze.
float fogHeightProfile(float y) {
    return 0.15 + 0.85 * (1.0 - smoothstep(62.0, 128.0, y));
}

// ---- distance fog of a hit (vanilla fog + aerial perspective) --------------------------------------------------------------
// Vanilla (FogRenderer.setupFog, javap minecraft-26.3-client.jar; values collected by RtFogState): linear fog factor
//   max(linear(sphericalDistance, environmentalStart, environmentalEnd), linear(max(|xz|, |y|), renderDistanceStart, renderDistanceEnd))
// the second term hides the edge of the loaded world, the first one is the biome / weather / water fog. Weight fogMisc.z.
// Aerial perspective on top (air only): Beer-Lambert with a density tied to the render distance (so a short render distance does not
// get a thicker haze than a long one), the height profile of fogHeightProfile, rain up to 4x denser, a low sun 1.5x. The in-scattered
// colour is the vanilla horizon fog colour of the direction (the sky shows the same colour at the horizon: no seam) with a warm
// forward lobe of the key light, so the haze is not a flat blue veil.
const float AERIAL_K = 0.7;            // optical depth at the render distance edge, before density / height / weather: 0.7 -> 50 % transmittance
const float AERIAL_START = 32.0;       // blocks without any haze in front of the camera

// Fade-in of new world sections out of the haze: the primary hit of a section younger than fogFade.y ticks is blended towards the fog
// colour by (1 - smoothstep(age / length)); set by the shaders at the primary hit (gSectionFade), used by fogTransmittanceHit so
// the blend is part of the same linear fog (out = colour * T + A) as the rest of the distance fog. Section birth: Section.origin.w bits 8..31.
float gSectionFade = 0.0;

float sectionFade(uint slot) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (sb.fogFade.y < 1.0 || isEntityHit(slot)) {
        return 0.0;
    }
    uint birth = (uint(sections.s[slot].origin.w) >> 8u) & 0xFFFFFFu;
    uint now = uint(sb.fogFade.x) & 0xFFFFFFu;
    int age = int((now - birth) & 0xFFFFFFu);
    if (age >= 0x800000) {
        age = 0;   // stamped after this frame's clock was read: just born
    }
    return 1.0 - smoothstep(0.0, sb.fogFade.y, float(age));
}

float fogLinear(float d, float s, float e) {
    if (d <= s) {
        return 0.0;
    }
    if (d >= e) {
        return 1.0;
    }
    return (d - s) / max(e - s, 1.0e-3);
}

float fogWeatherDensity() {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    float lowSun = 1.0 - smoothstep(0.0, 0.45, pc.keyDir.y);
    return (1.0 + 3.0 * clamp(sb.fogMisc.x, 0.0, 1.0)) * (1.0 + 0.5 * lowSun * pc.keyDir.w);
}

float fogTransmittanceHit(vec3 origin, vec3 dir, float dist) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    vec3 rel = origin + dir * dist;
    float cyl = max(length(rel.xz), abs(rel.y));
    float fv = max(fogLinear(dist, sb.fogVanilla.x, sb.fogVanilla.y), fogLinear(cyl, sb.fogVanilla.z, sb.fogVanilla.w)) * sb.fogMisc.z;
    float T = 1.0 - clamp(fv, 0.0, 1.0);
    T *= 1.0 - gSectionFade;
    if (sb.fogMisc.y < 0.5 && sb.fogMisc.w > 0.0) {
        float R = max(sb.fogVanilla.w, 96.0) * max(sb.fogDistanceScale, 0.2);
        float yMid = sb.skyCamY + origin.y + dir.y * dist * 0.5;
        float tau = AERIAL_K * sb.fogMisc.w * sb.fogDensity * max(dist - AERIAL_START, 0.0) / R * fogHeightProfile(yMid) * fogWeatherDensity();
        T *= exp(-tau);
    }
    return clamp(T, 0.0, 1.0);
}

vec3 fogRadiance(vec3 dir) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (sb.fogMisc.y > 0.5) {
        return displayToRadiance(sb.fogMedium.rgb);
    }
    vec3 c = displayToRadiance(skyHasSky() ? skyFogColor(dir) : sb.skyFogColor.rgb);
    float forward = phaseHenyeyGreenstein(dot(dir, pc.keyDir.xyz), 0.55) * 4.0 * PI * 0.12;   // 0.12 at the sun direction .. ~0.005 away
    return c + pc.keyColor.rgb * forward * (1.0 - smoothstep(0.0, 0.05, -pc.keyDir.y));
}

// Atmosphere of a camera ray (origin = camera-relative ray origin, dist = hit distance):
//  1. aerial haze on geometry: Beer-Lambert extinction with the height profile, in-scattered colour = the vanilla fog /
//     horizon colour of this direction (so distant terrain fades into the sky horizon like vanilla);
//  2. VOLUMETRIC mode: god rays = single scattering of the key light (sun / moon) along the first 96 blocks of the view ray,
//     6 jittered steps with one shadow ray each (traceVisibility), Henyey-Greenstein phase g = 0.75 (Bedrock default for
//     air, Microsoft Learn "Fog in Resource Packs"), scattering 0.006 / block x density x height profile. The jitter changes
//     every frame; the temporal accumulation / denoiser averages it.
// The sky on a miss already holds the vanilla horizon fog (rt_sky.glsl), so only the god rays are added there.
vec3 applyVolumetricAtmosphere(vec3 color, vec3 origin, vec3 dir, float dist, bool hit) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    uint mode = sb.fogMode;
    if (mode == 0u || !hasFlag(FLAG_VOLUMETRIC_FOG)) {
        return color; // OFF
    }
    float densityFactor = sb.fogDensity;
    if (densityFactor <= 0.0) {
        return color;
    }
    float inscatterFactor = sb.fogInscattering;
    float camY = sb.skyCamY;

    if (hit) {
        float T = fogTransmittanceHit(origin, dir, dist);
        color = color * T + fogRadiance(dir) * (1.0 - T);
    }

    if (mode != 2u) {
        return color;
    }
    vec3 L = pc.keyDir.xyz;
    vec3 Lc = pc.keyColor.rgb;
    if (max(Lc.r, max(Lc.g, Lc.b)) < 1.0e-4 || L.y < -0.02) {
        return color;
    }
    const uint GOD_STEPS = 6u;
    float tEnd = min(hit ? dist : 1.0e6, 96.0);
    float dt = tEnd / float(GOD_STEPS);
    uint hsh = skyHash(uvec3(gl_GlobalInvocationID.xy, pc.frame));
    float jitter = float(hsh & 65535u) / 65536.0;
    float sigS = 0.006 * densityFactor;
    float acc = 0.0;
    for (uint i = 0u; i < GOD_STEPS; i++) {
        float t = (float(i) + jitter) * dt;
        vec3 p = origin + dir * t;
        float dens = sigS * fogHeightProfile(camY + p.y);
        float tr = exp(-dens * t);
        float vis = traceVisibility(p, L, 512.0);
        acc += dens * tr * vis * dt;
    }
    float phase = phaseHenyeyGreenstein(dot(dir, L), 0.75);
    return color + Lc * (acc * phase * 4.0 * PI * 0.25) * inscatterFactor;
}

// Transmittance of applyVolumetricAtmosphere for a surface at dist: it is linear, out = color * T + A, with A =
// applyVolumetricAtmosphere(vec3(0)) (distance fog inscatter + god rays). Used to move the fog attenuation onto the irradiance term.
float atmosphereTransmittance(vec3 origin, vec3 dir, float dist, bool hit) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (!hit || sb.fogMode == 0u || !hasFlag(FLAG_VOLUMETRIC_FOG) || sb.fogDensity <= 0.0) {
        return 1.0;
    }
    return fogTransmittanceHit(origin, dir, dist);
}

// Backwards-compatible wrapper
vec3 applyAtmosphericFog(vec3 surfaceColor, vec3 rayDir, float dist, bool isHit) {
    return applyVolumetricAtmosphere(surfaceColor, vec3(0.0), rayDir, dist, isHit);
}

// Tone mapping: rt_tonemap.glsl (peak-channel filmic curve, RtTonemap.java mirrors it).
vec3 finishColor(vec3 color) {
    if (((pc.modeBits >> 17u) & 1u) != 0u) {
        // RtLighting.EXPOSURE 1.1; the path tracer can use its auto exposure (never below 1.1)
        float exposure = StatsBuf(pc.statsAddr).exposure;   // CPU: auto or fixed 1.1, times the EV compensation
        if (isnan(exposure) || isinf(exposure) || exposure <= 0.0) {
            exposure = 1.1;
        }
        return tonemapColor(color * exposure);
    }
    return color;
}

// Visibility of a ray (1 = nothing hit): terminate on first hit, cutout alpha-tested, translucent surfaces let light pass.
float traceVisibility(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return 1.0;
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03, origin, 0.001, d, tmax);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(sq, true)) {
            rayQueryConfirmIntersectionEXT(sq);
        }
    }
    return rayQueryGetIntersectionTypeEXT(sq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}

// Forward declaration of RGB transmittance along a shadow ray
vec3 traceTransmittance(vec3 origin, vec3 d, float tmax);

// Distance to the nearest opaque/cutout hit along d, or tmax when nothing is hit (translucent surfaces let the ray pass).
float traceDist(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return 0.0;
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, d, tmax);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(sq, true)) {
            rayQueryConfirmIntersectionEXT(sq);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(sq, true) == gl_RayQueryCommittedIntersectionNoneEXT) {
        return tmax;
    }
    return rayQueryGetIntersectionTEXT(sq, true);
}

uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rand01(inout uint seed) {
    seed = pcgHash(seed);
    return float(seed) * (1.0 / 4294967296.0);
}

// Cosine-weighted hemisphere sample around +Z (RtSampling.cosineHemisphere)
vec3 cosineHemisphere(vec2 u) {
    float r = sqrt(u.x);
    float phi = 6.28318530718 * u.y;
    return vec3(r * cos(phi), r * sin(phi), sqrt(max(1.0 - u.x, 0.0)));
}

// Orthonormal basis around n (Duff et al. 2017)
void basisAround(vec3 n, out vec3 t, out vec3 b) {
    float sgn = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (sgn + n.z);
    float c = n.x * n.y * a;
    t = vec3(1.0 + sgn * n.x * n.x * a, sgn * c, -sgn * n.x);
    b = vec3(c, sgn + n.y * n.y * a, -n.y);
}

vec3 cosineAround(vec3 n, inout uint seed) {
    vec3 t;
    vec3 b;
    basisAround(n, t, b);
    vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
    return normalize(t * h.x + b * h.y + n * h.z);
}

// Uniform direction inside a cone of half-angle `radius` (radians) around d.
vec3 coneAround(vec3 d, float radius, inout uint seed) {
    vec3 t;
    vec3 b;
    basisAround(d, t, b);
    float cosMax = cos(radius);
    float cosT = mix(1.0, cosMax, rand01(seed));
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi = 6.28318530718 * rand01(seed);
    return normalize(t * (cos(phi) * sinT) + b * (sin(phi) * sinT) + d * cosT);
}

#include "rt_noise.glsl"

// ---- Low-discrepancy sampling of the sun / moon disk (shadow rays) ---------------------------------------------------------
// Independent white-noise directions need many samples to cover a penumbra, and a temporal accumulation of them leaves a
// grain along every shadow edge. The shadow rays use the R2 sequence (Roberts 2018, additive recurrence of the plastic number)
// instead: sample n of a pixel is fract(offset + n * (1/g, 1/g^2)), so any run of consecutive frames covers the disk evenly and
// the penumbra converges in a handful of samples. `offset` rotates the sequence per pixel (Cranley-Patterson); pixelOffset()
// is the spatial interleaved-gradient-noise rotation of rt_noise.glsl (noise2D at frame 0, sample 0 leaves only the IGN term).
vec2 pixelOffset(ivec2 p) {
    return noise2D(p, 0u, 0u, 1u, 0u);
}

vec2 r2Point(vec2 offset, uint n) {
    return fract(offset + float(n) * vec2(0.75487766624669276, 0.56984029099805327));
}

// Point of the unit disk by the concentric (Shirley-Chiu) map: area preserving, so strata stay strata.
vec2 concentricDisk(vec2 u) {
    vec2 a = 2.0 * u - 1.0;
    if (a.x == 0.0 && a.y == 0.0) {
        return vec2(0.0);
    }
    float r;
    float theta;
    if (abs(a.x) > abs(a.y)) {
        r = a.x;
        theta = 0.78539816339 * (a.y / a.x);
    } else {
        r = a.y;
        theta = 1.57079632679 - 0.78539816339 * (a.x / a.y);
    }
    return r * vec2(cos(theta), sin(theta));
}

// Direction inside the sun / moon disk of angular radius `radius` around d for the sample point u in [0,1)^2.
vec3 sunDiskDirection(vec3 d, float radius, vec2 u) {
    vec3 t;
    vec3 b;
    basisAround(d, t, b);
    vec2 q = concentricDisk(u) * tan(radius);
    return normalize(d + t * q.x + b * q.y);
}

// Pixel and sample counter of the shadow sequence of the current thread (set by main()).
ivec2 gSunPixel = ivec2(0);
uint gSunIndex = 0u;

// Shadow direction of sample `i` of this frame for the primary hit (stratified in time and over the disk).
vec3 sunShadowDirection(vec3 d, float radius, uint i, uint perFrame) {
    return sunDiskDirection(d, radius, r2Point(pixelOffset(gSunPixel), gSunIndex * perFrame + i));
}

// Schlick Fresnel
vec3 fresnelSchlick(vec3 f0, float cosTheta) {
    return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
}

// Dielectric F0 is the material value (AutoPbr: water/ice 0.02, glass and default 0.04; LabPBR _s green). The earlier
// dielectricF0() boost to ~0.22 for smooth surfaces is gone: it was 5-10x the physical reflectance and compensated a
// double Fresnel weighting of glass reflections in the stochastic transmission code (see interface* below).

// PBR Horizon Bending: smoothly bends a reflection vector above the geometric horizon ng
// to prevent black termination or reflection flipping on normal-mapped surfaces (water ripples, rough metals).
vec3 bendHorizon(vec3 r, vec3 ng) {
    float ndotr = dot(r, ng);
    if (ndotr < 0.015) {
        return normalize(r + ng * (0.015 - ndotr));
    }
    return r;
}

#include "rt_caustics.glsl"

// Water surface normal facing the ray (side of ng) from the analytic wave field (rt_caustics.glsl). footprint = blocks per pixel
// on the surface; lostVar the slope variance of the faded-out waves (roughness of the far sheen). Only horizontal faces get waves:
// the vertical sides of flowing water stay flat.
vec3 waterWaveNormal(vec3 pos, vec3 ng, float time, float footprint, out float lostVar) {
    lostVar = 0.0;
    if (abs(ng.y) < 0.7) {
        return ng;
    }
    // Flowing water (sloped top quads of Minecraft fluids): the wave pattern is carried downhill, the face tilt stays in the normal.
    vec2 xz = pos.xz;
    vec2 downhill = ng.xz * sign(ng.y);
    float flow = length(downhill);
    if (flow > 0.02) {
        xz -= downhill / flow * (time * min(flow * 10.0, 1.6));
    }
    vec2 slope = waterSlope(xz, time, footprint, lostVar);
    // perturb the face normal; for the underside (ng.y < 0) the slope enters with the opposite sign
    vec3 n = normalize(ng + vec3(-slope.x, 0.0, -slope.y) * sign(ng.y));
    return dot(n, ng) > 0.0 ? n : ng;
}

// Everything a shader needs about a hit point.
struct Surface {
    vec3 pos;          // camera-relative
    vec3 worldPos;     // world-anchored (modulo 1024 blocks) for procedural waves, caustics, and lava
    float t;
    vec3 ng;           // geometric normal facing the incoming ray
    vec3 ns;           // shading normal (normal map), same side as ng
    vec3 albedoTex;    // atlas texel
    vec3 albedo;       // atlas * vertex colour (tint * baked AO), as the raster image
    vec3 albedoLit;    // albedo without the baked vanilla face shade
    vec2 light;        // vanilla (blockLight, skyLight) 0..1
    float roughness;   // linear
    float f0;          // dielectric reflectance
    float metal;       // 0 or 1
    float emission;    // 0..1
    float mapAo;       // LabPBR AO, 1 = none
    vec2 uv;
    uint slot;
    bool translucent;
    float alpha;
    bool isWater;
    bool isLava;
    bool isIce;
    bool isPortal;
    bool isRedstone;
    bool isFoliage;
    bool isOutline;
    bool backFace;     // the ray left the block through this face (glass: the tint was applied by the entry face)
    vec3 motion;       // previous-frame position minus current (world, blocks); non-zero only for moving entities
};

// Fills s from a committed triangle hit.
void fetchSurface(uint slot, uint geom, uint prim, vec2 bary, float t, vec3 origin, vec3 dir, out Surface s) {
    s.motion = vec3(0.0);
    s.isOutline = false;
    vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    Section sec = sections.s[slot];
    uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
    FloatBuf pos = FloatBuf(sec.posAddr);
    vec3 p0 = loadPos(pos, v.x);
    vec3 p1 = loadPos(pos, v.y);
    vec3 p2 = loadPos(pos, v.z);
    vec3 n = normalize(cross(p1 - p0, p2 - p0));
    FloatBuf uvb = FloatBuf(sec.uvAddr);
    vec2 uv0 = loadUv(uvb, v.x);
    vec2 uv1 = loadUv(uvb, v.y);
    vec2 uv2 = loadUv(uvb, v.z);
    vec2 uv = b3.x * uv0 + b3.y * uv1 + b3.z * uv2;
    UIntBuf cols = UIntBuf(sec.colAddr);
    vec3 vcol = b3.x * unpackColor(cols.v[v.x]) + b3.y * unpackColor(cols.v[v.y]) + b3.z * unpackColor(cols.v[v.z]);
    uint geomPass = (uint(sec.origin.w) >> (2u * geom)) & 3u;
    UIntBuf lights = UIntBuf(sec.lightAddr);
    s.light = b3.x * unpackLight(lights.v[v.x]) + b3.y * unpackLight(lights.v[v.y]) + b3.z * unpackLight(lights.v[v.z]);
    vec4 tex = textureLod(atlas, uv, 0.0);
    s.albedoTex = tex.rgb;
    s.alpha = tex.a;
    s.albedo = s.albedoTex * vcol;
    // Lit shading removes the baked vanilla face shade (the hemisphere ambient / traced light takes its place).
    // Clamped to 1.0 so unshaded foliage/cross quads do not blow up, preserving full biome tint hue and saturation.
    s.albedoLit = s.albedoTex * clamp(vcol / max(cardinalShade(n), 0.5), 0.0, 1.0);
    s.uv = uv;
    s.slot = slot;
    s.t = t;
    s.pos = origin + dir * t;
    s.worldPos = vec3(sec.origin.xyz & 1023) + b3.x * p0 + b3.y * p1 + b3.z * p2;
    s.translucent = ((uint(sec.origin.w) >> (2u * geom)) & 3u) == 2u;
    s.backFace = dot(n, dir) > 0.0;
    s.ng = dot(n, dir) > 0.0 ? -n : n;
    s.ns = s.ng;
    s.roughness = s.translucent ? 0.02 : 0.9;
    s.f0 = 0.04;
    s.metal = 0.0;
    s.emission = 0.0;
    s.mapAo = 1.0;
    vec4 m = textureLod(matAtlas, uv, 0.0);
    uint matSourceFlags = 255u - uint(m.a * 255.0 + 0.5);
    bool taggedRedstone = (matSourceFlags & 4u) != 0u;
    bool redstoneColorHeuristic = abs(s.albedoTex.r - s.albedoTex.g) < 0.15 && abs(s.albedoTex.g - s.albedoTex.b) < 0.15
                               && vcol.r > 0.15 && vcol.r > vcol.g * 1.8 && vcol.r > vcol.b * 1.8;
    // The 26.3 dust models are forced into the TRANSLUCENT pass (see MATERIAL_SOURCE_REDSTONE). Tagged dust texels are not glass:
    // treating the pass as translucent made the transparent plate an interface (grey sheet, false mirror, washed-out pink). The
    // tag (material atlas) overrides the pass: texels are cutout (alpha < 0.5 is a hole) and shaded as an emissive solid.
    bool isRedstoneWire = hasFlag(FLAG_MATERIALS) ? taggedRedstone : (!s.translucent && redstoneColorHeuristic);
    float dustCov = 1.0;
    if (isRedstoneWire) {
        s.translucent = false;
        s.roughness = 0.9;
        float sig = clamp((vcol.r - 0.30) / 0.70, 0.0, 1.0);
        if (dustFootprintTexels(t) > 1.0) {
            // far dust: the coverage of the pixel footprint (see dustCoverage) decides, a texel between two dust texels still shows the dust
            dustCov = dustCoverage(uv, t);
            s.alpha = dustCov >= 0.04 ? 1.0 : 0.0;
            if (tex.a < 0.5) {
                s.albedoTex = vec3(0.9);
            }
        }
        s.albedoLit = s.albedoTex * vcol;
        s.emission = sig;
    }
    s.isRedstone = isRedstoneWire;
    if (hasFlag(FLAG_MATERIALS)) {
        s.roughness = m.r;
        s.metal = m.g > 229.5 / 255.0 ? 1.0 : 0.0;
        s.f0 = s.metal > 0.5 ? 0.04 : m.g;
        s.emission = m.b;
        // Redstone wire / dust: emission proportional to signal power (0..15 from vcol.r)
        if (isRedstoneWire) {
            float sig = clamp((vcol.r - 0.30) / 0.70, 0.0, 1.0);
            // far dust fades with its coverage instead of popping texel by texel
            s.emission = sig * (dustCov >= 1.0 ? 1.0 : clamp(0.25 + 2.5 * dustCov, 0.0, 1.0));
        }
        bool labN = (matSourceFlags & 2u) != 0u;   // material alpha bit 1 cleared: LabPBR _n
        if ((labN && hasFlag(FLAG_NORMAL_MAPS)) || (!labN && hasFlag(FLAG_AUTO_NORMALS))) {
            vec4 nm = textureLod(nrmAtlas, uv, 0.0);
            s.mapAo = nm.b;
            vec2 xy = (nm.rg - vec2(128.0 / 255.0)) * (255.0 / 127.0);   // DirectX: x right, y down (= +v of the atlas)
            if (!labN) {
                // auto-normal: smooth deadzone on low-contrast noise + scale the slope; keep it a valid unit-disc xy
                xy *= smoothstep(0.01, 0.04, length(xy)) * StatsBuf(pc.statsAddr).autoNormalStrength;
                float l2 = dot(xy, xy);
                if (l2 > 0.81) {
                    xy *= 0.9 * inversesqrt(l2);
                }
            }
            if (dot(xy, xy) > 1.0e-4) {
                vec3 e1 = p1 - p0;
                vec3 e2 = p2 - p0;
                vec2 d1 = uv1 - uv0;
                vec2 d2 = uv2 - uv0;
                float det = d1.x * d2.y - d2.x * d1.y;
                if (abs(det) > 1.0e-12) {
                    float r = 1.0 / det;
                    vec3 T = normalize((e1 * d2.y - e2 * d1.y) * r);   // dP/du
                    vec3 B = normalize((e2 * d1.x - e1 * d2.x) * r);   // dP/dv (down in the texture)
                    float z = sqrt(max(0.0, 1.0 - dot(xy, xy)));
                    vec3 nsObj = normalize(T * xy.x + B * xy.y + n * z);
                    vec3 cand = dot(n, s.ng) > 0.0 ? nsObj : reflect(nsObj, n);   // keep it on the side facing the ray
                    float ndot = dot(cand, s.ng);
                    // Smooth blending towards geometric normal to eliminate normal popping and seam tearing
                    s.ns = normalize(mix(s.ng, cand, smoothstep(0.05, 0.35, ndot)));
                }
            }
        }
    }

    if ((!s.translucent && s.alpha < 0.5) || s.alpha < 0.05) {
        s.roughness = 1.0;
        s.metal = 0.0;
        s.f0 = 0.0;
        s.emission = 0.0;
    }
    // Identify water, lava, and ice (including blue_ice)
    s.isIce = (s.translucent && (s.albedo.b > s.albedo.r && s.albedo.b > 0.4 && s.roughness < 0.2 && s.roughness > 0.035))
           || (s.f0 <= 0.025 && s.roughness <= 0.20 && s.roughness > 0.035 && s.metal < 0.5 && s.albedo.b > s.albedo.r * 1.1 && s.albedo.b > 0.35);
    // Water detection: translucent pass, water material f0 <= 0.025 (water f0 is 0.02; glass f0 is 0.04), not ice.
    // Glass (regular and stained) has f0 ~0.04 and is strictly s.isWater = false with flat normal s.ns = s.ng!
    // The alpha of the atlas texel is NOT a criterion: the 26.3 water sprite is opaque (the translucency lives in the render pass), so
    // with `alpha < 0.99` no water was ever recognised and it was shaded as a texel-tinted glass sheet (the blocky 16x16 pattern).
    s.isWater = s.translucent && s.f0 <= 0.025 && s.roughness <= 0.035 && !s.isIce;
    // Lava detection: requires material-atlas emission >= 0.5 (real lava/magma), orange-warm hue
    // (r dominant over both g and b), and never translucent.
    // Intentionally NOT using s.light.x — nearby torches/campfires raised block-light on warm-toned
    // wood/oak-planks/sign-posts above 0.8, falsely classifying them as lava.
    s.isLava = !s.translucent && s.emission >= 0.5
            && s.albedoTex.r > 0.55 && s.albedoTex.r > s.albedoTex.g * 1.05
            && s.albedoTex.g > s.albedoTex.b * 1.5;
    if (s.isLava && s.emission <= 0.0) {
        s.emission = 1.0;
    }
    if (s.isWater) {
        s.roughness = 0.02;
        s.metal = 0.0;
        s.f0 = 0.02;
        float footprint = t * WATER_PIXEL_ANGLE / max(abs(dot(dir, s.ng)), 0.06);
        float lostVar;
        vec3 waveN = waterWaveNormal(s.worldPos, s.ng, waterTime(), footprint, lostVar);
        if (getWaterStyle() == WATER_STYLE_REALISTIC) {
            s.ns = waveN;
            s.roughness = clamp(sqrt(lostVar) * 1.6, 0.02, 0.30);
        } else {
            // Subtle, gentle wave ripples for vanilla water (breaks up flat sheet reflections into natural water specular shimmer)
            s.ns = normalize(mix(s.ng, waveN, 0.40));
            s.roughness = clamp(0.02 + sqrt(lostVar) * 0.8, 0.02, 0.16);
        }
    }
    if (s.isIce) {
        s.roughness = 0.05;
        s.ns = s.ng;
    }
    // Nether portal detection: emissive translucent/semi-transparent surface
    s.isPortal = (s.translucent || s.alpha < 0.99) && s.emission >= 0.5;

    // Foliage classification: cutout render pass (geomPass 1) strictly (leaves, tall grass, crops, flowers)
    s.isFoliage = (geomPass == 1u) && !s.translucent && !s.isRedstone && !s.isLava && !s.isWater && !s.isIce && !s.isPortal;
}

// Fills s from a committed entity triangle hit: real triangle (positions, vertex colour, vanilla light), texture from the RT
// entity atlas (EntityTextureAtlas), per-vertex previous position for the motion vectors.
void fetchEntitySurface(uint customIndex, uint geom, uint prim, vec2 bary, float t, vec3 origin, vec3 dir, out Surface s) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    s.slot = customIndex;
    s.t = t;
    s.pos = origin + dir * t;
    s.worldPos = s.pos + StatsBuf(pc.statsAddr).camRel.xyz;
    s.backFace = false;
    s.motion = vec3(0.0);
    vec3 n = -dir;
    vec3 col = vec3(1.0);
    float alpha = 1.0;
    vec2 lt = vec2(0.5, 1.0);
    vec2 uv = bary;
    float texelEmission = 0.0;
    uint entSlot = 0u;
    bool isEndPortalEnt = false;
    bool isOutline = false;
    if (prim < fb.entityTris) {
        FloatBuf pb = FloatBuf(fb.entPosAddr);
        UIntBuf ab = UIntBuf(fb.entAttrAddr);
        uvec3 vi = entityTriangle(prim);
        entSlot = entitySlot(vi);
        vec3 p0 = vec3(pb.v[vi.x * 3u], pb.v[vi.x * 3u + 1u], pb.v[vi.x * 3u + 2u]);
        vec3 p1 = vec3(pb.v[vi.y * 3u], pb.v[vi.y * 3u + 1u], pb.v[vi.y * 3u + 2u]);
        vec3 p2 = vec3(pb.v[vi.z * 3u], pb.v[vi.z * 3u + 1u], pb.v[vi.z * 3u + 2u]);
        vec3 g = cross(p1 - p0, p2 - p0);
        if (dot(g, g) > 1.0e-20) {
            n = normalize(g);
        }
        vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
        vec4 c0 = unpackUnorm4x8(ab.v[vi.x * 4u]);
        vec4 c1 = unpackUnorm4x8(ab.v[vi.y * 4u]);
        vec4 c2 = unpackUnorm4x8(ab.v[vi.z * 4u]);
        uint l = ab.v[vi.x * 4u + 1u];
        isOutline = ((l >> 24u) & 0x08u) != 0u;
        isEndPortalEnt = ((l >> 24u) & 0x80u) != 0u;
        lt = clamp(vec2(float(l & 0xFFu), float((l >> 16u) & 0xFFu)) / 240.0, 0.0, 1.0);
        uv = entityUv(vi, b3);
        vec4 tex = entityTexel(entSlot, uv);
        col = tex.rgb * (c0.rgb * b3.x + c1.rgb * b3.y + c2.rgb * b3.z);
        if (isOutline) {
            col = (c0.rgb * b3.x + c1.rgb * b3.y + c2.rgb * b3.z);
            alpha = (c0.a * b3.x + c1.a * b3.y + c2.a * b3.z);
            texelEmission = 0.35;
        } else if (((l >> 24u) & 0x40u) != 0u) {
            col = mix(col, vec3(1.0, 0.05, 0.05), 0.65);
            alpha = tex.a;
            texelEmission = entityEmission(tex);
        } else {
            alpha = tex.a;
            texelEmission = entityEmission(tex);
        }
        if (isEndPortalEnt) {
            // Multi-layer cosmic End Portal starfield with view-parallax depth
            float pt = float(pc.frame) * 0.015;
            vec3 wp = s.worldPos;
            vec3 parallax = dir / max(abs(dir.y), 0.25);
            vec3 portalCol = vec3(0.015, 0.035, 0.065);
            const vec3 layerColors[6] = vec3[6](
                vec3(0.22, 0.58, 0.72),
                vec3(0.45, 0.28, 0.85),
                vec3(0.18, 0.82, 0.75),
                vec3(0.75, 0.42, 0.95),
                vec3(0.35, 0.92, 0.88),
                vec3(0.95, 0.88, 1.00)
            );
            for (int layer = 0; layer < 6; layer++) {
                float fl = float(layer + 1);
                vec2 puv = wp.xz * (1.8 + fl * 0.85) + parallax.xz * (0.12 * fl)
                         + vec2(sin(pt * (0.7 + 0.2 * fl) + fl * 1.7), cos(pt * (0.6 + 0.15 * fl) - fl * 2.3)) * 0.45;
                vec2 cell = floor(puv * 8.0);
                vec2 local = fract(puv * 8.0) - 0.5;
                uint h = uint(int(cell.x) * 73856093 ^ int(cell.y) * 19349663 ^ (layer * 83492791));
                h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u;
                vec2 starOff = (vec2(float(h & 0xFFu), float((h >> 8u) & 0xFFu)) / 255.0 - 0.5) * 0.55;
                float prob = float((h >> 16u) & 0xFFu) / 255.0;
                if (prob > 0.72) {
                    float d2 = dot(local - starOff, local - starOff);
                    float star = exp(-d2 * (90.0 + fl * 25.0)) * (0.6 + 0.4 * sin(pt * 3.0 + float(h & 63u)));
                    portalCol += layerColors[layer] * star * (1.25 - 0.10 * fl);
                }
            }
            col = clamp(portalCol, vec3(0.01), vec3(1.0));
            alpha = 1.0;
            texelEmission = 0.95;
        } else if (lt.x > 0.995 && lt.y > 0.995 && alpha >= 0.1) {
            // vanilla draws this part at full light, unaffected by the surroundings (the item of a glow item frame, blaze, magma
            // cube): it shows its own pixels instead of the scene light
            texelEmission = max(texelEmission, 0.4);
        }
        if (fb.entPrevAddr.x != 0u || fb.entPrevAddr.y != 0u) {
            FloatBuf qb = FloatBuf(fb.entPrevAddr);
            vec3 q0 = vec3(qb.v[vi.x * 3u], qb.v[vi.x * 3u + 1u], qb.v[vi.x * 3u + 2u]);
            vec3 q1 = vec3(qb.v[vi.y * 3u], qb.v[vi.y * 3u + 1u], qb.v[vi.y * 3u + 2u]);
            vec3 q2 = vec3(qb.v[vi.z * 3u], qb.v[vi.z * 3u + 1u], qb.v[vi.z * 3u + 2u]);
            s.motion = q0.x > 1.0e29 ? vec3(1.0e30) : (q0 - p0) * b3.x + (q1 - p1) * b3.y + (q2 - p2) * b3.z;
        }
        if (dot(n, dir) > 0.0) {
            n = -n;   // face the incoming ray (entity models are not consistently wound, culling is off)
        }
        vec3 ng = n;
        vec3 ns = ng;
        // Auto-normal: subtle Sobel gradient over entity atlas luminance, strictly keeping s.ng unperturbed and ignoring transparent UV borders.
        if (!isEndPortalEnt && hasFlag(FLAG_AUTO_NORMALS) && dot(g, g) > 1.0e-20 && alpha >= 0.1) {
            uint slot = entitySlot(vi);
            float lc = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
            float lxn = entityLumOffset(slot, uv, -1, 0);
            float lxp = entityLumOffset(slot, uv,  1, 0);
            float lyn = entityLumOffset(slot, uv,  0,-1);
            float lyp = entityLumOffset(slot, uv,  0, 1);
            if (lxn < 0.0) lxn = lc;
            if (lxp < 0.0) lxp = lc;
            if (lyn < 0.0) lyn = lc;
            if (lyp < 0.0) lyp = lc;
            vec2 grad = vec2(lxn - lxp, lyp - lyn); // du, dv in texture space
            float gradLen = length(grad);
            // Smooth deadzone and gentle strength so mob skin stays organic without harsh embossed seams
            grad *= smoothstep(0.025, 0.08, gradLen) * (0.28 * StatsBuf(pc.statsAddr).autoNormalStrength);
            float gl2 = dot(grad, grad);
            if (gl2 > 0.25) grad *= 0.5 * inversesqrt(gl2);
            if (dot(grad, grad) > 1.0e-4) {
                vec3 e1 = p1 - p0;
                vec3 e2 = p2 - p0;
                vec2 d1 = entityUv(vi, vec3(0.0, 1.0, 0.0)) - entityUv(vi, vec3(1.0, 0.0, 0.0));
                vec2 d2 = entityUv(vi, vec3(0.0, 0.0, 1.0)) - entityUv(vi, vec3(1.0, 0.0, 0.0));
                float det = d1.x * d2.y - d2.x * d1.y;
                if (abs(det) > 1.0e-12) {
                    float rDet = 1.0 / det;
                    vec3 T = normalize((e1 * d2.y - e2 * d1.y) * rDet);
                    vec3 B = normalize((e2 * d1.x - e1 * d2.x) * rDet);
                    T = normalize(T - ng * dot(ng, T));
                    B = normalize(B - ng * dot(ng, B));
                    float z = sqrt(max(0.25, 1.0 - dot(grad, grad)));
                    vec3 nsObj = normalize(T * grad.x + B * grad.y + ng * z);
                    vec3 cand = dot(nsObj, ng) > 0.0 ? nsObj : reflect(nsObj, ng);
                    float ndot = dot(cand, ng);
                    ns = normalize(mix(ng, cand, smoothstep(0.20, 0.65, ndot)));
                }
            }
        }
        s.ng = ng;
        s.ns = ns;
    } else {
        if (dot(n, dir) > 0.0) {
            n = -n;
        }
        s.ng = n;
        s.ns = n;
    }
    // Entity surfaces default to matte dielectric (roughness 0.85). Decodes PBR properties of held items
    // (iron/gold/netherite/copper = metal 1.0; diamond = IOR 2.42, roughness 0.05) packed into entity_tex_rects.
    float roughEst = isEndPortalEnt ? 0.05 : 0.85;
    float metalEst = 0.0;
    float f0Est = 0.04;
    if (!isEndPortalEnt && (fb.entRectAddr.x != 0u || fb.entRectAddr.y != 0u)) {
        UIntBuf rb = UIntBuf(fb.entRectAddr);
        uvec4 r = uvec4(rb.v[entSlot * 4u], rb.v[entSlot * 4u + 1u], rb.v[entSlot * 4u + 2u], rb.v[entSlot * 4u + 3u]);
        if (((r.w >> 24u) & 1u) != 0u) {
            roughEst = float((r.z >> 16u) & 0xFFu) / 255.0;
            metalEst = float((r.z >> 24u) & 0xFFu) / 255.0;
            f0Est = float((r.w >> 16u) & 0xFFu) / 255.0;
        }
    }
    // Tool/sword wooden handle (dark brown texel) remains matte dielectric
    if (metalEst > 0.5 && col.r > col.b * 1.8 && col.r < 0.45 && col.g < 0.30) {
        metalEst = 0.0;
        roughEst = 0.85;
    }
    s.albedoTex = col;
    s.albedo = col;
    s.albedoLit = col;
    // texels of glass-like items (stained glass, ice, slime: alpha 0.1 .. 0.95) keep their alpha and are an interface like the block
    // (black texels are the shadow blob / decals of the entity, not glass: they stay a cutout)
    bool glassy = !isEndPortalEnt && entityTexelTranslucent(vec4(0.0, 0.0, 0.0, alpha)) && max(col.r, max(col.g, col.b)) > 0.08;
    s.alpha = alpha >= 0.1 ? (glassy ? alpha : 1.0) : alpha;
    s.uv = uv;
    s.light = lt;
    s.roughness = roughEst;
    s.metal = metalEst;
    s.f0 = f0Est;
    s.emission = texelEmission;
    s.mapAo = 1.0;
    s.translucent = glassy;
    if (glassy) {
        s.roughness = min(s.roughness, 0.05);   // smooth dielectric like the block it was dropped from
    }
    s.isWater = false;
    s.isIce = false;
    s.isLava = false;
    s.isPortal = isEndPortalEnt;
    s.isRedstone = false;
    s.isFoliage = false;
    s.isOutline = isOutline;
}


// Closest hit along a ray; translucent surfaces are hit (opaque) unless skipTranslucent. Returns false on a miss.
bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, out Surface s) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, tmin, dir, tmax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(rq, skipTranslucent)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
        return false;
    }
    uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
    if (isEntityHit(customIndex)) {
        fetchEntitySurface(customIndex,
            uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)),
            rayQueryGetIntersectionBarycentricsEXT(rq, true), rayQueryGetIntersectionTEXT(rq, true), origin, dir, s);
    } else {
        fetchSurface(customIndex,
            uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)),
            rayQueryGetIntersectionBarycentricsEXT(rq, true), rayQueryGetIntersectionTEXT(rq, true), origin, dir, s);
    }
    return true;
}

// RGB transmittance along a shadow ray accounting for Beer-Lambert absorption through translucent surfaces (stained glass, water, ice).
// Entities and terrain cast colored shadows with up to 8 translucent layers.
// Light filtering of one translucent layer (stained glass, water, ice, translucent texels) hit by a shadow ray: Beer-Lambert style
// absorption by the tinted texture colour, weighted by the alpha; clear glass (alpha < 0.1) transmits without tint.
vec3 shadowLayerTransmittance(Surface s, vec3 d) {
    if (s.backFace && !s.isWater && !s.isIce) {
        return vec3(1.0 - s.f0);   // exit face: tinted at the entry face
    }
    vec3 c = clamp(s.albedoLit, vec3(0.001), vec3(1.0));
    vec3 tint = c;
    if (!s.isWater && !s.isIce) {
        float maxC = max(c.r, max(c.g, c.b));
        vec3 normC = c / max(maxC, 0.01);
        tint = max(pow(normC, vec3(2.2)), vec3(0.16)) * maxC;
    }
    float cosIncidence = max(abs(dot(s.ng, d)), 0.05);
    float effAlpha = (s.isWater || s.isIce) ? clamp(s.alpha < 0.99 ? s.alpha : 0.65, 0.5, 0.9) : clamp(s.alpha, 0.0, 1.0);
    float power = s.alpha < 0.1 ? 0.0 : max(1.0, effAlpha * 2.0) / cosIncidence;
    return (1.0 - s.f0) * pow(tint, vec3(power));
}

// RGB transmittance along a shadow ray in ONE traversal (was: a visibility ray plus up to 8 closest-hit walks, one traversal per
// glass face). Every non-opaque candidate triangle is classified by candidateAccepted(skip = true): an opaque one (solid, cutout
// texel >= 0.99, entity) blocks the light and ends the query; a filter (translucent pass, glass / water / ice texel) is fetched
// once and multiplies the transmittance. The product is order independent and a blocker makes the result 0 whatever order the
// candidates arrive in, so no sorting is needed. Same layer formula as the walk it replaces (shadowLayerTransmittance).
vec3 traceTransmittance(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return vec3(1.0);
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, d, tmax);
    vec3 T = vec3(1.0);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(sq, false));
        uint geom = uint(rayQueryGetIntersectionGeometryIndexEXT(sq, false));
        uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(sq, false));
        vec2 bary = rayQueryGetIntersectionBarycentricsEXT(sq, false);
        gSawTranslucent = false;
        gHitT = rayQueryGetIntersectionTEXT(sq, false);
        if (candidateAccepted(customIndex, geom, prim, bary, true)) {
            return vec3(0.0);
        }
        if (!gSawTranslucent) {
            continue;   // a hole of a cutout texel
        }
        Surface s;
        if (isEntityHit(customIndex)) {
            fetchEntitySurface(customIndex, geom, prim, bary, rayQueryGetIntersectionTEXT(sq, false), origin, d, s);
        } else {
            fetchSurface(customIndex, geom, prim, bary, rayQueryGetIntersectionTEXT(sq, false), origin, d, s);
        }
        T *= shadowLayerTransmittance(s, d);
        if (max(T.r, max(T.g, T.b)) < 0.001) {
            return vec3(0.0);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(sq, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
        return vec3(0.0);
    }
    return T;
}

// Display colour of a glowing texel: texel colour x emission mask, hue preserving. The brightest channel goes through a soft
// shoulder (max 0.96, slope ~2 at the dark end), the other channels are scaled with it, so a glowstone gradient, a flame or a
// lamp keeps the colours of its own pixels instead of clipping to white; dark pixels (mask 0) stay dark.
vec3 emissionDisplay(vec3 texel, float emission) {
    vec3 c = texel * emission;
    float m = max(c.r, max(c.g, c.b));
    if (m <= 1.0e-4) {
        return vec3(0.0);
    }
    float shoulder = 0.96 * (1.0 - exp(-2.2 * m));
    return c * (shoulder / m);
}

vec3 emitterLightColor(vec3 tex);

// Radiance emitted by a surface (only with emissive lights enabled and baked materials). Built in display space and
// converted with the inverse of the tone mapping (like the sky), so the camera sees emissionDisplay() whatever the exposure.
vec3 emitted(Surface s) {
    if (!hasFlag(FLAG_EMISSIVE) || s.emission <= 0.0) {
        return vec3(0.0);
    }
    if (s.isLava) {
        float t = float(pc.frame) * 0.06;
        vec3 p = s.worldPos;
        float turb = sin(p.x * 3.0 + t) * cos(p.z * 3.0 - t * 0.8)
                   + 0.5 * sin(p.x * 6.0 - t * 1.4 + p.y * 4.0) * cos(p.z * 6.0 + t * 1.2)
                   + 0.25 * sin((p.x + p.z) * 12.0 + t * 2.2);
        float pulse = clamp(1.0 + 0.35 * turb, 0.6, 1.8);
        float lum = dot(s.albedoTex, vec3(0.299, 0.587, 0.114));
        float texelHotspot = 0.8 + 1.4 * pow(clamp(lum, 0.0, 1.0), 1.6);
        vec3 fieryColor = mix(s.albedoTex * vec3(1.35, 1.08, 0.65), vec3(1.0, 0.86, 0.36), pow(clamp(lum, 0.0, 1.0), 2.0) * 0.65);
        return displayToRadiance(emissionDisplay(fieryColor, 1.15 * pulse * texelHotspot * max(s.emission, 1.0)));
    }
    // Redstone wire emits saturated red light even if the atlas texture is greyscale
    if (s.isRedstone && s.emission > 0.01) {
        // per pixel: the greyscale dust sprite modulates the glow (bright centre pixels glow more), the level follows signal / 15
        float texLum = clamp(dot(s.albedoTex, vec3(0.299, 0.587, 0.114)) * 1.5, 0.0, 1.0);
        vec3 redColor = vec3(1.0, 0.04, 0.02) * (0.8 + 0.4 * s.emission) * mix(0.6, 1.0, texLum);
        return displayToRadiance(emissionDisplay(redColor, s.emission * 1.2));
    }
    vec3 col = emitterLightColor(s.albedoTex);
    return displayToRadiance(emissionDisplay(col, s.emission));
}

const uint NEE_CANDIDATES = 8u;
const float EMITTER_RADIUS = 24.0;

// Categorized and chromatic emission: classifies emitters by spectrum (warm flame, soul fire, redstone,
// sea lantern, glowstone, amethyst/portal, end rod, bioluminescent) so illumination matches real RTX light sources.
vec3 emitterLightColor(vec3 tex) {
    float m = max(tex.r, max(tex.g, tex.b));
    if (m <= 1.0e-4) {
        return vec3(0.0);
    }
    vec3 c = tex / m;

    // Category 1: Pure Redstone (Torch, wire, lamp, repeater, ore)
    if (c.r > 0.65 && c.g < 0.22 && c.b < 0.22) {
        return vec3(1.0, 0.05, 0.02) * mix(m, 1.0, 0.35);
    }

    // Category 2: Warm Flames & Firelight (Torch, Campfire, Lantern, Lava, Fire, Candle)
    // Rich, soft, golden-amber 2200-2400K illumination matching RTX screenshots
    if (c.r >= 0.55 && c.r > c.b * 1.25 && c.g >= 0.18) {
        float warmFactor = clamp((c.g - 0.20) / 0.60, 0.0, 1.0);
        vec3 warmLight = mix(vec3(1.0, 0.52, 0.10), vec3(1.0, 0.76, 0.26), warmFactor);
        return warmLight * 1.35 * mix(m, 1.0, 0.45);
    }

    // Category 3: Soul Fire (Soul Torch, Soul Lantern, Soul Campfire, Soul Fire)
    if (c.b > 0.45 && c.g > 0.35 && c.r < 0.45) {
        return vec3(0.18, 0.85, 1.0) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 4: Sea Lantern & Conduit (Pale Aquamarine / Cyan-White)
    if (c.b > 0.65 && c.g > 0.65 && c.r > 0.35 && c.b > c.r * 1.05) {
        return vec3(0.75, 0.94, 1.0) * 1.15 * mix(m, 1.0, 0.4);
    }

    // Category 5: Glowstone, Shroomlight, Ochre Froglight (Warm golden daylight)
    if (c.r > 0.7 && c.g > 0.65 && c.b < 0.45) {
        return vec3(1.0, 0.85, 0.42) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 6: Amethyst, Crying Obsidian, Nether Portal (Mystic Violet / Magenta)
    if (c.r > 0.45 && c.b > 0.55 && c.g < 0.45) {
        return vec3(0.85, 0.30, 1.0) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 7: End Rod & Purpur (Celestial Lilac White)
    if (c.r > 0.6 && c.b > 0.75 && c.g >= 0.45) {
        return vec3(0.92, 0.85, 1.0) * 1.25 * mix(m, 1.0, 0.4);
    }

    // Category 8: Bioluminescence (Glow Lichen, Cave Vines, Glow Berries, Glow Squid)
    if (c.g > 0.65 && c.b > 0.35 && c.r < 0.5) {
        return vec3(0.45, 1.0, 0.75) * 1.15 * mix(m, 1.0, 0.4);
    }

    // Default: retain vibrant chromaticity without severe desaturation
    vec3 hue = mix(c, vec3(1.0), 0.08);
    return hue * mix(m, 1.0, 0.5);
}
const float INV_PI = 0.31830988618;

// Physically-Based GGX / Trowbridge-Reitz Microfacet Distribution
float distributionGGX(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = max(a * a, 1.0e-5);
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

// Smith Joint Masking-Shadowing Function G2 (Heitz 2014)
float geometrySmith(float NdotV, float NdotL, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    float g1V = NdotV / (NdotV * (1.0 - k) + k);
    float g1L = NdotL / (NdotL * (1.0 - k) + k);
    return g1V * g1L;
}

vec3 brdfSpecularGGX(vec3 N, vec3 V, vec3 L, float roughness, vec3 F0) {
    vec3 H = normalize(V + L);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 1.0e-4);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    if (NdotL <= 0.0) return vec3(0.0);
    
    float D = distributionGGX(NdotH, roughness);
    float G = geometrySmith(NdotV, NdotL, roughness);
    vec3 F = fresnelSchlick(F0, VdotH);
    
    vec3 numerator = D * G * F;
    float denominator = 4.0 * NdotV * NdotL;
    return numerator / max(denominator, 1.0e-4);
}

uint emitterCount() {
    return StatsBuf(pc.statsAddr).emitterCount;
}

// Next event estimation of emissive surfaces: resampled importance sampling (RIS) over NEE_CANDIDATES emitters picked
// from the importance-sorted light list (target p^ = emission * cosS * cosR / d^2 without colour/visibility), one shadow ray to the
// chosen point. Returns the reflected radiance of a Lambert surface (albedo / PI * Le * G), unbiased for p^ > 0.
vec3 sampleEmitters(Surface s, vec3 albedo, float maxDist, inout uint seed) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    uint n = fb.emitterCount;
    if (n == 0u) {
        return vec3(0.0);
    }
    EmitterBuf eb = EmitterBuf(fb.emitterAddr);
    vec3 camRel = fb.camRel.xyz;
    float wsum = 0.0;
    float chosenPhat = 0.0;
    vec3 chosenY = vec3(0.0);
    vec2 chosenUv = vec2(0.0);
    uint chosenSlot = 0u;     // entity atlas slot + 1 of the chosen emitter, 0 = block atlas
    float chosenEm = 0.0;
    bool chosenRedstone = false;
    vec4 chosenTint = vec4(1.0);
    bool chosenOpenSource = false;   // two-sided emitter (torch, lantern, fire): its own body may stand between it and the receiver
    // stratified points on the emitter quads: a per-call random shift of the R2 low-discrepancy sequence over the candidates, so the
    // few quads of a torch / candle are covered evenly instead of by independent random points (the flicker of a torch light)
    vec2 r2shift = vec2(rand01(seed), rand01(seed));
    for (uint k = 0u; k < NEE_CANDIDATES; k++) {
        // the list is sorted by importance (RtEmitters): index = n * u^2 favours its front, P(i) = sqrt((i+1)/n) - sqrt(i/n)
        float u = rand01(seed);
        uint idx = min(uint(u * u * float(n)), n - 1u);
        float pIdx = sqrt(float(idx + 1u) / float(n)) - sqrt(float(idx) / float(n));
        Emitter e = eb.e[idx];
        vec2 ab = fract(r2shift + float(k) * vec2(0.7548776662, 0.5698402909));
        float a = ab.x;
        float b = ab.y;
        vec3 y = e.p0.xyz - camRel + e.e1.xyz * a + e.e2.xyz * b;
        vec2 yuv = e.uvc.xy + e.duv.xy * a + e.duv.zw * b;
        // light leaves only the emissive texels: the emission of the texel at the sampled point (the emitter rectangle is the
        // bounding box of the emissive texels, RtEmitters.buildQuadRecord; texels in it that do not glow emit nothing)
        uint slotPlus1 = uint(e.uvc.w + 0.5);
        vec4 matSample = slotPlus1 == 0u ? textureLod(matAtlas, yuv, 0.0) : vec4(0.0);
        bool redstoneEmitter = slotPlus1 == 0u && e.e2.w > 1.5;
        bool redstoneTexel = redstoneEmitter && ((255u - uint(matSample.a * 255.0 + 0.5)) & 4u) != 0u;
        float texEm = slotPlus1 != 0u ? entityEmission(entityTexel(slotPlus1 - 1u, yuv)) * e.e1.w
                                      : (redstoneTexel ? e.uvc.z : matSample.b * e.e1.w);
        if (texEm <= 0.0) {
            continue;
        }
        vec3 d = y - s.pos;
        float d2 = dot(d, d);
        if (d2 > maxDist * maxDist || d2 < 1.0e-6) {
            continue;
        }
        if (s.emission > 0.5 && d2 < 0.25) {
            continue; // eliminate emitter self-shadowing (end rod, torch, etc.)
        }
        vec3 dn = d * inversesqrt(d2);
        float cosR = max(dot(s.ns, dn), 0.0);
        if (cosR <= 0.0) {
            continue;
        }
        vec3 crossE = cross(e.e1.xyz, e.e2.xyz);
        float crossLen = length(crossE);
        vec3 en = crossLen > 1.0e-6 ? crossE / crossLen : vec3(0.0, 1.0, 0.0);
        float cosEmitter = dot(en, -dn);
        bool twoSided = e.e2.w > 0.5;
        // Two-sided / open sources (torches, lanterns, fire): emit in all directions with a floor so edge-on views don't cut off
        // Solid block faces (ceiling lamps, glowstone, sea lanterns): emit into the outward hemisphere (cosEmitter > 0)
        float cosS = twoSided ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);
        if (cosS <= 0.0) {
            continue;
        }
        // light radius: a source reaches EMITTER_RADIUS blocks (vanilla light level 15 = 15 blocks), not the whole ray distance: a lava
        // column 10 blocks away painted the walls orange in daylight
        float lightR = min(maxDist, EMITTER_RADIUS);
        float distFactor = clamp(1.0 - (d2 / (lightR * lightR)), 0.0, 1.0);
        // falloff of the light of a source: d^-1.5 (softer than the physical d^-2 of a point source, so that a torch or a held item
        // still reads at the other side of a room: the vanilla light level 14 reaches 14 blocks), windowed to zero at maxDist
        // d^-1.5 up to 2 blocks (a torch or a held item still reads across a small room), physical d^-2 beyond (continuous at 2 blocks)
        float dd = max(d2, 0.25);
        float att = dd <= 4.0 ? inversesqrt(dd * sqrt(dd)) : 1.41421356 / dd;
        float phat = texEm * cosS * cosR * distFactor * distFactor * att;
        float w = phat * e.p0.w / pIdx;   // p^ / p, p = P(idx) / area
        wsum += w;
        if (w > 0.0 && rand01(seed) * wsum < w) {
            chosenPhat = phat;
            chosenY = y;
            chosenUv = yuv;
            chosenSlot = slotPlus1;
            chosenEm = texEm;
            chosenRedstone = redstoneTexel;
            chosenTint = slotPlus1 != 0u ? e.tint : vec4(1.0);
            chosenOpenSource = twoSided;
        }
    }
    if (chosenPhat <= 0.0) {
        return vec3(0.0);
    }
    vec3 toEmitter = chosenY - s.pos;
    float dist = length(toEmitter);
    if (dist < 1.0e-5) {
        return vec3(0.0);
    }
    vec3 dn = toEmitter / dist;

    // Visibility test between receiver (s.pos) and sampled emitter point (chosenY).
    // Geometric normal s.ng prevents self-intersection / shadow acne on block boundaries and normal map seams.
    float endBias = clamp(0.02 * dist, 0.002, 0.03);
    // An open source (torch flame on its stick, lantern bulb in its cage, candle) is sampled on faces that may look away from the
    // receiver: the ray would then cross the body of the source itself and the stick would cast a shadow of its own light. The ray
    // ends outside that body (a stick is 2 / 16 block thick, 0.2 covers its diagonal); the surroundings (walls, floor) still block.
    if (chosenOpenSource) {
        endBias = max(endBias, min(0.2, 0.45 * dist));
    }
    vec3 shadowOrigin = s.pos + s.ng * 0.005;
    vec3 rayTarget = chosenY - dn * endBias;
    vec3 rayDir = rayTarget - shadowOrigin;
    float rayDist = length(rayDir);
    vec3 trans = vec3(1.0);
    if (rayDist > 0.002) {
        trans = traceTransmittance(shadowOrigin, rayDir / rayDist, rayDist);
    }
    if (max(trans.r, max(trans.g, trans.b)) <= 0.0) {
        return vec3(0.0);
    }
    const float EMISSIVE_FLUX_GAIN = 2.2;   // was 4.5, then 2.6: surfaces near torches / lanterns were flooded (owner screens 30-31, lighting-fix)
    vec3 texRgb = chosenSlot != 0u ? entityTexel(chosenSlot - 1u, chosenUv).rgb
                                  : (chosenRedstone ? vec3(1.0, 0.04, 0.02) : textureLod(atlas, chosenUv, 0.0).rgb);
    texRgb *= chosenTint.rgb * chosenTint.a;
    if (!chosenRedstone) {
        texRgb = emitterLightColor(texRgb);
    }
    vec3 le = texRgb * chosenEm * EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN * trans;
    // f * Le * G / p^(y) * (wsum / M); with p^ = em * G the geometry term cancels: albedo / PI * Le / em * wsum / M
    return albedo * INV_PI * le / max(chosenEm, 1.0e-4) * (wsum / float(NEE_CANDIDATES));
}

vec3 sampleEmitters(Surface s, vec3 albedo, vec3 origin, float maxDist, inout uint seed) {
    return sampleEmitters(s, albedo, maxDist, seed);
}

vec3 sampleEmitters(vec3 pos, vec3 ns, vec3 albedo, vec3 origin, float maxDist, inout uint seed) {
    Surface dummy;
    dummy.pos = pos;
    dummy.ng = ns;
    dummy.ns = ns;
    return sampleEmitters(dummy, albedo, maxDist, seed);
}

// Ray origin just above the surface (RtLighting.shadowBias)
vec3 offsetOrigin(Surface s) {
    float bias = clamp(0.003 + 0.0001 * s.t, 0.003, 0.015);
    return s.pos + s.ng * bias;
}

bool keyLightOn() {
    return max(pc.keyColor.r, max(pc.keyColor.g, pc.keyColor.b)) > 0.001;
}

// Subpixel camera jitter offset in [-0.5, 0.5] pixel units (0 = pixel center).
// CameraJitterManager adds +currentJitterNdcX to projMatrix.m20 and +currentJitterNdcY to projMatrix.m21
// (with Vulkan flipY m11 < 0), so pixel p samples at p + (-jitter.x, +jitter.y) in screen space.
vec2 getCameraJitter() {
    if (pc.statsAddr == uvec2(0u)) {
        return vec2(0.0);
    }
    vec2 j = StatsBuf(pc.statsAddr).cameraJitter;
    return vec2(-j.x, j.y);
}

vec3 cameraOrigin() {
    return pc.statsAddr == uvec2(0u) ? vec3(0.0) : StatsBuf(pc.statsAddr).cameraBobOrigin.xyz;
}

vec3 rawCameraForward() {
    return pc.statsAddr == uvec2(0u) ? vec3(0.0, 0.0, -1.0) : StatsBuf(pc.statsAddr).cameraForward.xyz;
}

// Camera forward derived from the current projection (including bob), matching the effective ray origin.
vec3 bobCameraForward() {
    vec4 centerW = pc.invViewProj * vec4(0.0, 0.0, 0.5, 1.0);
    return normalize(centerW.xyz / centerW.w - cameraOrigin());
}

// Camera ray of pixel p for the image size; sub-pixel offset jitter in 0..1.
vec3 cameraRay(ivec2 p, ivec2 size, vec2 jitter) {
    vec2 ndc = (vec2(p) + jitter) / vec2(size) * 2.0 - 1.0;
    if (((pc.modeBits >> 16u) & 1u) != 0u) {
        ndc.y = -ndc.y;
    }
    // Any depth strictly inside the frustum lies on the ray through the camera; 0.5 is inside for [0,1] and [-1,1] clip depth.
    vec4 w = pc.invViewProj * vec4(ndc, 0.5, 1.0);
    return normalize(w.xyz / w.w - cameraOrigin());
}

float gHistoryLimit = 1.0e9;   // longest temporal history for this pixel (frames): water keeps its animation alive

#ifdef VKB_ACCUM
// Temporal accumulation with reprojection (RtAccumImage layout, W x 9H: rows [0,H) / [H,2H) diffuse irradiance E + count of even /
// odd frames, [2H,3H) / [3H,4H) normal + hit distance, [4H,5H) albedo + filter flag of this frame, [5H,7H) scratch of rt_atrous.comp,
// [7H,8H) / [8H,9H) the rest R of even / odd frames). The final colour is albedo * E + R, accumulated as two signals:
//  - E: diffuse light (direct + indirect + emitter NEE, already times the fog transmittance), NOT multiplied by the albedo. It is
//    remodulated with the CURRENT albedo, so the point-sampled texture stays sharp and only the smooth lighting goes through the
//    bilinear history resample;
//  - R: everything that is not albedo * diffuse light: reflections, specular, emission, interface reflections, fog inscatter.
//    Nothing of it is divided by an albedo (no 1/albedo, no NaN on black textures), it keeps its own history.
// The current hit (camera-relative pos, normal, distance) is projected into the previous frame; the history there is used when its
// distance and normal agree (no disocclusion), its length capped by historyCap. Returns the new colour. Sky pixels keep no history.
vec3 temporalAccumulate(ivec2 p, ivec2 size, vec3 e, vec3 r, bool hit, vec3 pos, vec3 nrm, float dist, vec3 albedo, bool filterable) {
    int cur = int(pc.frame & 1u);
    int prv = cur ^ 1;
    float n = 0.0;
    vec3 histE = vec3(0.0);
    vec3 histR = vec3(0.0);
    // The stored and compared distance is |pos| (camera to the reprojected point). The caller's hit t is the length of the
    // LAST ray segment: behind water/glass it starts at the interface, so it never matched length(pPrev) of a moving camera
    // and every pixel seen through water or glass lost its history while walking (raw 1-spp noise, flicker).
    dist = hit ? length(pos) : 0.0;
    if (hasFlag(FLAG_ACCUMULATE) && accumCount() > 0u && hit) {
        StatsBuf fb = StatsBuf(pc.statsAddr);
        bool isStatic = (fb.camDelta.w == 0.0);
        vec2 pix = vec2(p);
        float prevDist = dist;
        bool valid = true;
        if (!isStatic) {
            // pos is relative to the raw current camera and includes this frame's bob origin. camDelta moves it to the prior
            // raw camera; prevViewProj includes the previous bob pose, so adding a separate bob delta here would double count it.
            vec3 pPrev = pos + fb.camDelta.xyz;
            vec4 clip = fb.prevViewProj * vec4(pPrev, 1.0);
            if (clip.w > 1.0e-4) {
                vec2 ndc = clip.xy / clip.w;
                if (((pc.modeBits >> 16u) & 1u) != 0u) {
                    ndc.y = -ndc.y;
                }
                pix = (ndc * 0.5 + 0.5) * vec2(size) - 0.5;   // continuous, pixel centres at integers
                prevDist = length(pPrev);
            } else {
                valid = false;
            }
        }
        if (valid) {
            // Distance tolerance of the disocclusion test: the stored distance belongs to the centre of the history pixel, up to
            // ~1 px away from the reprojected point. On a plane seen at incidence cos(i) one pixel of offset changes the distance
            // by about d * pixelAngle / cos(i): grazing floors failed the old fixed 3 % test while walking and lost their history
            // (noise crawling over the floor).
            vec4 c0 = pc.invViewProj * vec4(0.0, 0.0, 0.5, 1.0);
            vec4 c1 = pc.invViewProj * vec4(0.0, 2.0 / float(size.y), 0.5, 1.0);
            float pixAngle = length(normalize(c1.xyz / c1.w) - normalize(c0.xyz / c0.w));
            float cosInc = abs(dot(pos, nrm)) / max(dist, 1.0e-4);
            float tol = 0.02 * prevDist + 0.05 + 1.5 * prevDist * pixAngle / max(cosInc, 0.08);
            // bilinear history: 4 taps around the reprojected point, each validated (distance + normal), weights renormalised
            vec2 f = fract(pix);
            ivec2 base = ivec2(floor(pix));
            vec3 eSum = vec3(0.0);
            vec3 rSum = vec3(0.0);
            float nSum = 0.0;
            float wSum = 0.0;
            float normThr = isStatic ? 0.8 : 0.6;  // при движении нормали могут не совпадать идеально из-за субпиксельного смещения
            for (int i = 0; i < 4; i++) {
                ivec2 o = ivec2(i & 1, i >> 1);
                ivec2 q = base + o;
                if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                    continue;
                }
                vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                if (g.w > 0.0 && abs(g.w - prevDist) < tol && dot(g.xyz, nrm) > normThr) {
                    float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
                    vec4 h = imageLoad(accumImage, ivec2(q.x, q.y + prv * size.y));
                    eSum += h.rgb * w;
                    rSum += imageLoad(accumImage, ivec2(q.x, q.y + (7 + prv) * size.y)).rgb * w;
                    nSum += h.a * w;
                    wSum += w;
                }
            }
            if (wSum <= 1.0e-3) {
                // Silhouette fallback: the 4 bilinear taps all failed (jitter makes an edge pixel hit the other face, or the edge moved).
                // Take the best-matching tap of the 3x3 around the reprojected pixel (same distance / normal), with a short history so
                // the pixel is not left at 1 spp ("ants" along block outlines).
                ivec2 c = ivec2(floor(pix + 0.5));
                float bestScore = 1.0e9;
                ivec2 bq = c;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        ivec2 q = c + ivec2(i, j);
                        if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                            continue;
                        }
                        vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                        float dz = abs(g.w - prevDist);
                        if (g.w > 0.0 && dz < tol && dot(g.xyz, nrm) > 0.9) {
                            float sc = dz / tol + (1.0 - dot(g.xyz, nrm)) * 4.0 + 0.1 * float(abs(i) + abs(j));
                            if (sc < bestScore) {
                                bestScore = sc;
                                bq = q;
                            }
                        }
                    }
                }
                if (bestScore < 1.0e8) {
                    vec4 h = imageLoad(accumImage, ivec2(bq.x, bq.y + prv * size.y));
                    eSum = h.rgb;
                    rSum = imageLoad(accumImage, ivec2(bq.x, bq.y + (7 + prv) * size.y)).rgb;
                    nSum = min(h.a, 4.0);
                    wSum = 1.0;
                }
            }
            if (wSum > 1.0e-3) {
                histE = eSum / wSum;
                histR = rSum / wSum;
                n = min(min(nSum / wSum, fb.historyCap), gHistoryLimit);
            } else if (!isStatic) {
                // Relaxed reprojection fallback during camera motion to avoid 1 SPP noise flashing
                for (int i = 0; i < 4; i++) {
                    ivec2 o = ivec2(i & 1, i >> 1);
                    ivec2 q = base + o;
                    if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                        continue;
                    }
                    vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                    if (g.w > 0.0 && abs(g.w - prevDist) < tol * 3.0 && dot(g.xyz, nrm) > 0.3) {
                        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
                        vec4 h = imageLoad(accumImage, ivec2(q.x, q.y + prv * size.y));
                        eSum += h.rgb * w;
                        rSum += imageLoad(accumImage, ivec2(q.x, q.y + (7 + prv) * size.y)).rgb * w;
                        nSum += h.a * w;
                        wSum += w;
                    }
                }
                if (wSum > 1.0e-3) {
                    histE = eSum / wSum;
                    histR = rSum / wSum;
                    n = min(min(nSum / wSum, fb.historyCap), gHistoryLimit);
                }
            }
        }
        if (n > 0.0 && ((p.x | p.y) & 3) == 0) {
            atomicAdd(fb.histAccepted, 1u);    // history accepted (CPU: hist_pct of the counted hit pixels)
        }
    }
    vec3 meanE = (histE * n + e) / (n + 1.0);
    vec3 meanR = (histR * n + r) / (n + 1.0);
    if (isnan(meanE.r) || isinf(meanE.r)) meanE.r = e.r;
    if (isnan(meanE.g) || isinf(meanE.g)) meanE.g = e.g;
    if (isnan(meanE.b) || isinf(meanE.b)) meanE.b = e.b;
    if (isnan(meanR.r) || isinf(meanR.r)) meanR.r = r.r;
    if (isnan(meanR.g) || isinf(meanR.g)) meanR.g = r.g;
    if (isnan(meanR.b) || isinf(meanR.b)) meanR.b = r.b;
    meanE = max(meanE, vec3(0.0));
    meanR = max(meanR, vec3(0.0));
    imageStore(accumImage, ivec2(p.x, p.y + cur * size.y), vec4(meanE, n + 1.0));
    imageStore(accumImage, ivec2(p.x, p.y + (7 + cur) * size.y), vec4(meanR, 0.0));
    imageStore(accumImage, ivec2(p.x, p.y + (2 + cur) * size.y), vec4(nrm, hit ? dist : 0.0));
    imageStore(accumImage, ivec2(p.x, p.y + 4 * size.y), vec4(albedo, (hit && filterable) ? 1.0 : 0.0));
    return albedo * meanE + meanR;
}
#endif

void countSample(ivec2 p, bool hit) {
    if (((p.x | p.y) & 3) == 0) {
        StatsBuf st = StatsBuf(pc.statsAddr);
        atomicAdd(st.samples, 1u);
        if (hit) {
            atomicAdd(st.hits, 1u);
        }
    }
}

// ---- Thin dielectric interfaces: glass, stained glass, water surface, ice -----------------------------------------------
// A texel with alpha < 0.99 is a thin slab: Schlick reflectance with the physical material F0 (clamped to 0.02..0.08 so a
// missing material cannot make it a mirror), transmission (1 - F) without a refraction offset (panes and block faces are
// thin; water keeps its straight view as in vanilla), absorption by the tinted texture colour weighted by its alpha (stained
// glass and water tint, the clear centre of vanilla glass passes unchanged). Alpha is absorption here, not stochastic coverage:
// coverage sampling made every stained-glass pixel randomly opaque or transparent each frame (the user-visible glass noise).
bool isInterface(Surface s) {
    if (isEntityHit(s.slot)) {
        return false;
    }
    // Nether portal is an emissive volume/quad, not a thin dielectric glass sheet
    if (s.isPortal || s.emission > 0.1) {
        return false;
    }
    // Transparent cutout holes (alpha < 0.5 on solid/cutout geometry) are empty space, not a dielectric interface
    if ((!s.translucent && s.alpha < 0.5) || s.alpha < 0.05) {
        return false;
    }
    return s.translucent || s.isIce || (s.f0 <= 0.08 && s.roughness <= 0.15 && s.alpha < 0.99);
}

// Water the realistic style models as a volume (absorption, in-scattering, Snell refraction, caustics); the vanilla style keeps
// the thin straight-through sheet.
bool waterVolumeStyle() {
    return getWaterStyle() == WATER_STYLE_REALISTIC;
}

// Vanilla style: the water is the (animated) water_still / water_flow sprite tinted with the biome colour, drawn over the scene at
// the vanilla translucency.
const float VANILLA_WATER_ALPHA = 0.24;

// Lit colour of the textured vanilla sheet: sky ambient, sun diffuse, sun specular glint trail across the water surface, and block light.
vec3 vanillaWaterSurface(Surface s, vec3 dir) {
    vec3 L = pc.keyDir.xyz;
    float sunVis = smoothstep(0.3, 0.9, s.light.y);
    vec3 light = hemisphereAmbient(1.0, pc.keyDir.w) * max(s.light.y, 0.03)
               + pc.keyColor.rgb * (max(dot(s.ns, L), 0.0) * sunVis)
               + vec3(1.0, 0.82, 0.60) * s.light.x;
    // Sun specular reflection trail across the water surface towards the camera (RenderDragon / RTX style)
    vec3 V = -dir;
    vec3 H = normalize(L + V);
    float NdotH = max(dot(s.ns, H), 0.0);
    float spec = (pow(NdotH, 128.0) * 2.4 + pow(NdotH, 24.0) * 0.5) * sunVis;
    vec3 sunGlint = pc.keyColor.rgb * spec;
    return s.albedoLit * light + sunGlint;
}

vec3 vanillaWaterSurface(Surface s) {
    return vanillaWaterSurface(s, vec3(0.0, -1.0, 0.0));
}

// A horizontal water face; only those separate air from the water body in a way that is tracked (flowing sides stay thin sheets).
bool isWaterBoundary(Surface s) {
    return s.isWater && abs(s.ng.y) > 0.5;
}

float interfaceReflectance(Surface s, vec3 d) {
    float cosV = clamp(abs(dot(d, s.ns)), 0.0, 1.0);
    if (s.isWater && waterVolumeStyle() && isWaterBoundary(s)) {
        // exact dielectric Fresnel; from below (ray going up) with total internal reflection beyond the 48 degree Snell window
        return fresnelDielectric(cosV, d.y < 0.0 ? WATER_IOR : 1.0 / WATER_IOR);
    }
    float f0 = clamp(s.f0, 0.02, 0.08);
    float R = f0 + (1.0 - f0) * pow(1.0 - cosV, 5.0);
    if (!s.isWater && !s.isIce) {
        R = min(R, 0.60);
    }
    return R;
}

vec3 interfaceTransmittance(Surface s, float F) {
    // Beer-Lambert style absorption: colour^(2 alpha). Clear glass centre (alpha 0) passes unchanged, stained glass (alpha ~0.5)
    // filters by its full colour, water (alpha ~0.7, grey texture * biome tint from the vertex colour) gets its biome blue.
    // Ice / blue_ice transmits tinted with its ice albedo.
    if (s.isWater && waterVolumeStyle() && isWaterBoundary(s)) {
        return vec3(1.0 - F);   // the colour of the water comes from the depth absorption of the medium (waterSegment)
    }
    if (s.isWater) {
        return vec3((1.0 - F) * (1.0 - VANILLA_WATER_ALPHA));   // vanilla style: the textured sheet is blended in, see vanillaWaterSurface
    }
    if (s.backFace && !s.isWater && !s.isIce) {
        // exit face of a glass block: the entry face already filtered the light (a block is one slab of coloured glass, not two)
        return vec3(1.0 - F);
    }
    vec3 c = clamp(s.albedoLit, vec3(0.001), vec3(1.0));
    if (s.isWater && waterVolumeStyle()) {
        // Realistic style: water is a continuous fluid, filtered by the biome tint rather than 16x16 texture pixel noise
        // which creates blocky texel patterns under realistic waves.
        vec3 tint = s.albedoLit / max(s.albedoTex, vec3(0.05));
        c = clamp(tint * 0.85, vec3(0.001), vec3(1.0));
    } else if (s.isWater) {
        // Vanilla style: the vanilla water sprite (water_still / water_flow of the live, animated block atlas) x biome tint is the
        // look of this style, as in the game; c stays the texel colour s.albedoLit
    } else if (!s.isIce) {
        float maxC = max(c.r, max(c.g, c.b));
        vec3 normC = c / max(maxC, 0.01);
        // saturate the hue but keep every channel above 0.16: deep blue / red glass tinted down to (0.03, 0.07, 1) and a few layers
        // of it turned the room behind into black voids (owner screenshot 37, PT Quality)
        c = max(pow(normC, vec3(2.2)), vec3(0.16)) * maxC;
    }
    float effAlpha = s.isIce ? clamp(s.alpha < 0.99 ? s.alpha : 0.65, 0.0, 1.0) : clamp(s.alpha, 0.0, 1.0);
    float power = s.alpha < 0.1 ? 0.0 : (s.isIce ? 1.3 : max(1.0, effAlpha * 2.0));
    return (1.0 - F) * pow(c, vec3(power));
}

// Beer-Lambert depth absorption for water column of travel distance dist (blocks); the vanilla style's fixed coefficients.
vec3 waterBeerLambert(float dist) {
    return clamp(exp(-vec3(0.18, 0.06, 0.02) * max(dist, 0.0)), vec3(0.001), vec3(1.0));
}

// ---- water medium -----------------------------------------------------------------------------------------------------------
// A ray (camera, reflection, bounce) that crosses a horizontal water face toggles the medium. Inside, every segment is attenuated
// by exp(-sigma * length) and adds in-scattered light (the water body glowing with the sky light that reaches that depth), so the
// colour of water depends on the depth of the water column, not on a fixed tint. sigma = clear water (red absorbed first) plus a
// share of the biome tint from the vertex colour (swamp green, warm ocean turquoise).
struct WaterMedium {
    bool inside;
    vec3 sigma;     // 1/block
    float surfY;    // camera-relative world Y of the water surface
};

WaterMedium noWater() {
    WaterMedium m;
    m.inside = false;
    m.sigma = vec3(0.0);
    m.surfY = 0.0;
    return m;
}

vec3 waterSigma(Surface s) {
    vec3 tint = clamp(s.albedoLit / max(s.albedoTex, vec3(0.05)), vec3(0.02), vec3(1.0));
    return vec3(0.12, 0.045, 0.015) + 0.06 * (-log(tint)) + vec3(0.035);   // + turbidity (grey murk, deeper water gets hazy)
}

WaterMedium enterWater(Surface s) {
    WaterMedium m;
    m.inside = true;
    m.sigma = waterSigma(s);
    m.surfY = s.pos.y;
    return m;
}

// Medium of a ray that crosses the boundary s: water entered when going down, left when going up (horizontal faces only).
void crossWaterBoundary(inout WaterMedium m, Surface s, vec3 d) {
    if (!isWaterBoundary(s)) {
        return;
    }
    if (d.y < 0.0 && !m.inside) {
        m = enterWater(s);
    } else if (d.y > 0.0 && m.inside) {
        m.inside = false;
    }
}

// Segment o -> o + d * len inside the medium: transmittance T and in-scattered radiance ins (to be multiplied by the throughput).
void waterSegment(WaterMedium m, vec3 o, vec3 d, float len, out vec3 T, out vec3 ins) {
    T = exp(-m.sigma * len);
    float depth = max(m.surfY - (o.y + d.y * len * 0.5), 0.0);
    // light reaching that depth: sky ambient plus a little scattered sun, dimmed by the column above; the scatter colour is what
    // survives about 6 blocks of the water (blue green), single scattering albedo ~0.2
    vec3 down = hemisphereAmbient(1.0, pc.keyDir.w) + pc.keyColor.rgb * (0.25 * max(pc.keyDir.y, 0.0));
    vec3 scatterC = exp(-m.sigma * 6.0) * 0.28;
    ins = down * scatterC * exp(-m.sigma * depth * 0.8) * (vec3(1.0) - T);
}

// Light-weight closest hit for the water queries (camera medium, refracted sun ray): same traversal and alpha test as traceSurface,
// but only the triangle, the material of the hit texel and the vertex colour are read (fetchSurface decodes ~15 more attributes and
// is inlined at every call site, which made the whole shader slower even in scenes without water).
// Returns 0 = miss, 1 = water boundary face (horizontal water; tint = biome tint of the water), 2 = blocker (solid, entity, cutout),
// 3 = other translucent layer (glass, ice, stained glass: the caller lets the ray pass).
uint traceWaterBoundary(vec3 origin, vec3 dir, float tmax, out float hitT, out vec3 tint) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, dir, tmax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(rq, false)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    hitT = 0.0;
    tint = vec3(1.0);
    if (rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
        return 0u;
    }
    hitT = rayQueryGetIntersectionTEXT(rq, true);
    uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
    if (isEntityHit(customIndex)) {
        return 2u;
    }
    uint geom = uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true));
    uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
    Section sec = sections.s[customIndex];
    if (((uint(sec.origin.w) >> (2u * geom)) & 3u) != 2u) {
        return 2u;
    }
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
    vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
    FloatBuf pos = FloatBuf(sec.posAddr);
    vec3 p0 = loadPos(pos, v.x);
    vec3 p1 = loadPos(pos, v.y);
    vec3 p2 = loadPos(pos, v.z);
    vec3 n = normalize(cross(p1 - p0, p2 - p0));
    FloatBuf uvb = FloatBuf(sec.uvAddr);
    vec2 uv = b3.x * loadUv(uvb, v.x) + b3.y * loadUv(uvb, v.y) + b3.z * loadUv(uvb, v.z);
    vec4 m = textureLod(matAtlas, uv, 0.0);
    // water material of the auto PBR (rough 0.02, f0 0.02), see fetchSurface; ice has roughness 0.05
    if (!(m.g <= 0.025 && m.r <= 0.035) || abs(n.y) < 0.5) {
        return 3u;
    }
    UIntBuf cols = UIntBuf(sec.colAddr);
    vec3 vcol = b3.x * unpackColor(cols.v[v.x]) + b3.y * unpackColor(cols.v[v.y]) + b3.z * unpackColor(cols.v[v.z]);
    tint = clamp(vcol / max(cardinalShade(n), 0.5), vec3(0.0), vec3(1.0));
    return 1u;
}

vec3 waterSigmaFromTint(vec3 tintIn) {
    vec3 tint = clamp(tintIn, vec3(0.02), vec3(1.0));
    return vec3(0.12, 0.045, 0.015) + 0.06 * (-log(tint)) + vec3(0.035);
}

// Medium of the camera: a ray straight up from the eye ends on a water face when the camera is under water.
WaterMedium cameraWaterMedium(vec3 origin) {
    WaterMedium m = noWater();
    vec3 o = origin;
    for (uint i = 0u; i < 3u; i++) {
        float t;
        vec3 tint;
        uint k = traceWaterBoundary(o, vec3(0.0, 1.0, 0.0), 160.0, t, tint);
        if (k == 3u) {
            o += vec3(0.0, t + 0.002, 0.0);   // glass / ice above the eye: look through it
            continue;
        }
        if (k == 1u) {
            m.inside = true;
            m.sigma = waterSigmaFromTint(tint);
            m.surfY = origin.y + t;
        }
        return m;
    }
    return m;
}

// The camera origin is the same for every pixel, so one lane per subgroup asks the scene and broadcasts the answer (one ray per
// 32 pixels instead of one per pixel). Call it where every lane of the subgroup is active (top of main, before the bounds return).
WaterMedium cameraWaterMediumShared(vec3 origin) {
    WaterMedium m = noWater();
    if (subgroupElect()) {
        m = cameraWaterMedium(origin);
    }
    m.inside = subgroupBroadcastFirst(m.inside);
    m.sigma = subgroupBroadcastFirst(m.sigma);
    m.surfY = subgroupBroadcastFirst(m.surfY);
    return m;
}

// First non-interface surface along a ray; interfaces on the way only attenuate (deterministic, no random choice).
// Uses traceSurface (mask 0x03: MASK_WORLD | MASK_ENTITIES) so reflections and refractions see entities and terrain.
// The medium m (water body the ray starts in, if any) is updated at every water boundary; filterOut collects the transmittance
// of interfaces and of the water, inscatter the light scattered towards the ray inside the water (already weighted by the
// filter of the part of the ray before it; the caller multiplies it with its own throughput only).
bool traceThroughInterfacesM(vec3 origin, vec3 dir, float reach, inout WaterMedium m, out Surface hit, out vec3 filterOut, out vec3 inscatter) {
    vec3 o = origin;
    vec3 d = dir;
    filterOut = vec3(1.0);
    inscatter = vec3(0.0);
    bool legacyIn = false;      // vanilla style: legacy Beer-Lambert from the first water face to the hit
    vec3 legacyEntry = vec3(0.0);
    // a glass block has a front and a back face: 16 steps pass 8 blocks of glass; the walk ends early when nothing is left of the light
    for (uint i = 0u; i < 16u; i++) {
        if (i > 0u && max(filterOut.r, max(filterOut.g, filterOut.b)) < 0.004) {
            return false;
        }
        if (!traceSurface(o, d, 0.001, reach, false, hit)) {
            if (m.inside) {
                vec3 T;
                vec3 ins;
                waterSegment(m, o, d, min(reach, 48.0), T, ins);
                inscatter += filterOut * ins;
                filterOut *= T;
            }
            return false;
        }
        if (m.inside) {
            vec3 T;
            vec3 ins;
            waterSegment(m, o, d, hit.t, T, ins);
            inscatter += filterOut * ins;
            filterOut *= T;
        }
        if ((!hit.translucent && hit.alpha < 0.5) || hit.alpha < 0.05 || hit.isOutline) {
            o = hit.pos + d * 0.002;
            continue;
        }
        if (!isInterface(hit)) {
            if (legacyIn) {
                filterOut *= waterBeerLambert(length(hit.pos - legacyEntry));
            }
            return true;
        }
        filterOut *= interfaceTransmittance(hit, interfaceReflectance(hit, d));
        crossWaterBoundary(m, hit, d);
        o = hit.pos + d * 0.002;
    }
    return false;
}

bool traceThroughInterfaces(vec3 origin, vec3 dir, float reach, out Surface hit, out vec3 filterOut) {
    WaterMedium m = noWater();
    vec3 ins;
    return traceThroughInterfacesM(origin, dir, reach, m, hit, filterOut, ins);
}

// Sun / moon light that reaches a point under water: the straight line to the light is wrong there. The light bends at the surface
// (Snell), so the shadow ray first goes along the REFRACTED direction Lw up to the water surface (walls, floors and overhangs of
// the pool cast their shadows with the refracted angle), then continues along L to the sky. The light arriving on the surface is
// weighted by (1 - Fresnel), the absorption of the water column on the way and the analytic caustic of the wave field at that
// depth (rt_caustics.glsl). Returns false when the light is blocked. Lw is the direction to use for the cosine terms of the
// shaded point, tr the RGB gain including the beam narrowing cos(L)/cos(Lw) (irradiance on a horizontal floor stays E cos(L)).
// worldXZ: world position (the frame of Surface.worldPos) of the shaded point, the wave field is evaluated there.
bool waterSunBeam(vec3 so, vec2 worldXZ, vec3 L, WaterMedium m, out vec3 Lw, out vec3 shOrigin, out vec3 shDir, out vec3 gain) {
    Lw = L;
    shOrigin = so;
    shDir = L;
    gain = vec3(1.0);
    if (L.y < 0.03) {
        return false;
    }
    Lw = -refract(-L, vec3(0.0, 1.0, 0.0), 1.0 / WATER_IOR);
    vec3 o = so;
    bool found = false;
    float travel = 0.0;
    vec3 hpos = so;
    for (uint i = 0u; i < 4u; i++) {
        float t;
        vec3 tint;
        uint k = traceWaterBoundary(o, Lw, 200.0, t, tint);
        if (k == 0u) {
            break;
        }
        travel += t;
        hpos = o + Lw * t;
        if (k == 1u) {
            found = true;
            break;
        }
        if (k == 2u) {
            return false;   // the pool wall / an overhang blocks the refracted beam
        }
        o = hpos + Lw * 0.002;   // glass layer in the water: let the beam through
    }
    if (!found) {
        Lw = L;   // no water above (the medium state was stale): plain straight sun
        return true;
    }
    float depth = max(hpos.y - so.y, 0.0);
    float F = fresnelDielectric(L.y, WATER_IOR);
    float c = waterCaustic(worldXZ, L, depth, waterTime());
    // the rest of the beam (sky side) is the caller's single traceTransmittance(shOrigin, shDir)
    shOrigin = hpos + L * 0.003;
    gain = exp(-m.sigma * travel) * ((1.0 - F) * (L.y / max(Lw.y, 0.05)) * c);
    return true;
}

// Ambient occlusion query: cosine-weighted rays within radius around s.ng
float traceAO(Surface s, float radius, uint samples, inout uint seed) {
    if (radius <= 0.001 || samples == 0u) {
        return s.mapAo;
    }
    vec3 o = offsetOrigin(s);
    vec3 tangent;
    vec3 bitangent;
    basisAround(s.ng, tangent, bitangent);
    float open = 0.0;
    for (uint i = 0u; i < samples; i++) {
        vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
        vec3 d = tangent * h.x + bitangent * h.y + s.ng * h.z;
        open += traceVisibility(o, d, radius);
    }
    return (open / float(samples)) * s.mapAo;
}

// Sky light by ray tracing. Replaces the baked vanilla sky light (vertex light .y, which made blocks darken with the distance
// to the player and inside rooms depending on the vertex data): `samples` cosine-weighted rays around the normal, each one
// either escapes (radiance of the sky in its direction, skyRadiance: the same dome the path tracer sees on a miss) or is blocked.
// The mean is the sky irradiance / PI, equal to hemisphereAmbient for an open surface. Under a roof, in a cave or in a corridor the
// sky is really occluded. A blocked ray towards the ground counts as the warm ground bounce (explicit ambient floor, the path
// tracer gets it from real bounces); there is no vertex light in it.
const float SKY_REACH = 160.0;

// Optional baked vanilla light fill (RtSettings baked_light_fill_pct / -Dvkrtx.rt.bakedLightFill), default 0 = fully traced light.
// Rays mode only: the vanilla vertex light of the surface is mixed back with this strength (path mode never reads it).
float bakedLightFill() {
    return pc.statsAddr == uvec2(0u) ? 0.0 : StatsBuf(pc.statsAddr).cameraForward.w;
}
// sky term x mix(1, vanilla sky light, fill)
vec3 applyBakedSky(vec3 skyLight, Surface s) {
    float f = bakedLightFill();
    return f <= 0.0 ? skyLight : skyLight * mix(1.0, max(s.light.y, 0.02), f);
}
// warm vanilla block light fill (fill x light.x)
vec3 bakedBlockFill(Surface s) {
    float f = bakedLightFill();
    return f <= 0.0 ? vec3(0.0) : vec3(1.0, 0.82, 0.60) * s.light.x * f;
}
const float GROUND_BOUNCE_BLOCKED = 0.72;
vec3 tracedSkyLight(Surface s, uint samples, inout uint seed) {
    vec3 o = offsetOrigin(s);
    vec3 tangent;
    vec3 bitangent;
    basisAround(s.ng, tangent, bitangent);
    vec3 sum = vec3(0.0);
    float skyFill = clamp(s.light.y, 0.0, 1.0);
    float canopyTrans = 0.32 * skyFill * skyFill;
    for (uint i = 0u; i < max(samples, 1u); i++) {
        vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
        vec3 d = tangent * h.x + bitangent * h.y + s.ng * h.z;
        if (traceVisibility(o, d, SKY_REACH) > 0.5) {
            sum += skyRadiance(d);
        } else if (d.y < 0.0) {
            sum += skyRadiance(d) * GROUND_BOUNCE_BLOCKED;
        } else if (canopyTrans > 0.0) {
            sum += skyRadiance(d) * canopyTrans;
        }
    }
    return sum / float(max(samples, 1u));   // the caller applies AO / mapAo
}

// Shading of a surface seen by a secondary ray (reflection / GI): sun with one hard shadow ray (when shadowRay), traced
// sky light, emitters (NEE with visibility rays), emission, PBR reflection and specular highlight.
vec3 shadeSimple(Surface s, vec3 dir, bool shadowRay) {
    vec3 o = offsetOrigin(s);
    vec3 L = pc.keyDir.xyz;
    float ndotl = max(dot(s.ns, L), 0.0);
    float ngdotl = dot(s.ng, L);
    vec3 sunT = vec3(0.0);
    if (keyLightOn() && ndotl > 0.0 && ngdotl > 0.0) {
        sunT = shadowRay ? traceTransmittance(o, L, 2048.0) : vec3(1.0);
    }
    float terminator = smoothstep(0.0, 0.08, ngdotl);

    // Contact ambient occlusion for reflections: stable seed from world position. Respect AO-off; otherwise every
    // secondary reflection silently pays for two visibility rays even when the user disabled AO.
    float aoRad = max(StatsBuf(pc.statsAddr).aoRadius, 0.0);
    uint aoSeed = pcgHash(uint(int(floor(s.worldPos.x * 8.0)) * 73856093 ^ int(floor(s.worldPos.y * 8.0)) * 19349663 ^ int(floor(s.worldPos.z * 8.0)) * 83492791));
    float ao = aoRad <= 0.001 ? s.mapAo : traceAO(s, clamp(aoRad, 1.0, 3.0), 2u, aoSeed);

    uint skySeed = aoSeed ^ 0x9E3779B9u;
    vec3 ambient = applyBakedSky(tracedSkyLight(s, 1u, skySeed), s) * ao;
    vec3 directSun = pc.keyColor.rgb * ndotl * sunT * terminator;
    if (s.isFoliage && keyLightOn()) {
        float backScatter = max(dot(-s.ns, L), 0.0);
        if (backScatter > 0.0) {
            directSun += pc.keyColor.rgb * (backScatter * 0.42) * sunT * vec3(1.05, 1.25, 0.75);
        }
    }
    vec3 direct = directSun + bakedBlockFill(s) * ao;
    vec3 diffuseLight = direct + ambient;

    // Stratified emitter sampling with stable seed (3 samples) for reflection stability
    if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u) {
        uint emSeed = pcgHash(uint(int(floor(s.worldPos.x * 4.0)) * 73856093 ^ int(floor(s.worldPos.y * 4.0)) * 19349663 ^ int(floor(s.worldPos.z * 4.0)) * 83492791));
        const uint EM_SAMPLES = 3u;
        vec3 emLight = vec3(0.0);
        for (uint i = 0u; i < EM_SAMPLES; i++) {
            emLight += sampleEmitters(s, vec3(1.0), 32.0, emSeed);
        }
        diffuseLight += emLight / float(EM_SAMPLES);
    }
    vec3 diffuse = s.albedoLit * diffuseLight;

    // Specular / PBR reflection when incident ray direction dir is present
    if (dot(dir, dir) > 1.0e-4) {
        vec3 V = -dir;
        float cosV = max(dot(V, s.ns), 0.0);
        float baseF0 = s.metal > 0.5 ? 1.0 : s.f0;
        vec3 f0 = s.metal > 0.5 ? s.albedoTex : vec3(baseF0);
        vec3 F = fresnelSchlick(f0, cosV);
        float gloss = 1.0 - s.roughness;

        // Specular sun highlight with Cook-Torrance GGX microfacet BRDF
        vec3 sunSpec = vec3(0.0);
        if (keyLightOn() && ndotl > 0.0 && length(sunT) > 0.001) {
            vec3 specGGX = brdfSpecularGGX(s.ns, V, L, max(s.roughness, 0.04), f0);
            sunSpec = pc.keyColor.rgb * sunT * specGGX * ndotl;
        }

        // Environment reflection for reflective materials (metals and smooth dielectrics)
        if (s.metal > 0.5 || s.roughness < 0.6) {
            vec3 rdir = bendHorizon(reflect(dir, s.ns), s.ng);
            vec3 skyRefl = skyAt(rdir);
            vec3 refl = skyRefl * F * gloss;
            if (s.metal > 0.5 && s.emission <= 0.1) {
                return diffuse * 0.15 + refl + sunSpec + emitted(s);   // pure metals: preserve metal color and Fresnel
            } else {
                return diffuse * (1.0 - F * gloss) + refl + sunSpec + emitted(s);
            }
        } else {
            return diffuse + sunSpec + emitted(s);
        }
    }

    if (s.metal > 0.5 && s.emission <= 0.1) {
        return diffuse * 0.15 + emitted(s);
    }
    return diffuse + emitted(s);
}

vec3 shadeSimple(Surface s, bool shadowRay) {
    return shadeSimple(s, vec3(0.0), shadowRay);
}

// Reflected hit: direct sunlight with one shadow ray, ONE contact-AO ray and ONE emitter sample (was: none, to avoid a cost of up
// to six extra traversals; the single samples keep it at about three), vanilla ambient/block light, material Fresnel and emission.
vec3 shadeReflectionHit(Surface s, vec3 dir, bool shadowRay) {
    vec3 o = offsetOrigin(s);
    vec3 L = pc.keyDir.xyz;
    float ndotl = max(dot(s.ns, L), 0.0);
    float ngdotl = dot(s.ng, L);
    vec3 sunT = vec3(0.0);
    if (keyLightOn() && ndotl > 0.0 && ngdotl > 0.0) {
        sunT = shadowRay ? traceTransmittance(o, L, 2048.0) : vec3(1.0);
    }
    float terminator = smoothstep(0.0, 0.08, ngdotl);
    // One contact-AO ray and one emitter sample per reflected hit, random per pixel and frame (the temporal accumulation of the
    // mirror signal resolves it): the pillars in a reflection get their contact shadows and the lamps light the mirror world.
    // The old stable-seed shortcut left reflected geometry flat lit and dark next to lamps (owner: "lamps missing in the mirror").
    uint rs = pcgHash(gl_GlobalInvocationID.x * 1973u + gl_GlobalInvocationID.y * 9277u + pc.frame * 26699u + 99u);
    float ao = traceAO(s, 1.6, 1u, rs);
    // sky light and block light are traced (sky rays, emitter NEE with visibility rays); no baked vertex light
    vec3 ambient = applyBakedSky(shadowRay ? tracedSkyLight(s, 1u, rs) : hemisphereAmbient(s.ns.y, pc.keyDir.w), s) * ao;
    vec3 emLight = vec3(0.0);
    if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u) {
        emLight = sampleEmitters(s, vec3(1.0), 24.0, rs);
    }
    vec3 diffuse = s.albedoLit * (pc.keyColor.rgb * ndotl * sunT * terminator + ambient + emLight + bakedBlockFill(s) * ao);
    vec3 glow = emitted(s);

    if (dot(dir, dir) <= 1.0e-4) {
        return diffuse + glow;
    }
    vec3 V = -dir;
    float cosV = max(dot(V, s.ns), 0.0);
    float baseF0 = s.metal > 0.5 ? 1.0 : s.f0;
    vec3 f0 = s.metal > 0.5 ? s.albedoTex : vec3(baseF0);
    vec3 F = fresnelSchlick(f0, cosV);
    float gloss = 1.0 - s.roughness;
    vec3 sunSpec = vec3(0.0);
    if (keyLightOn() && ndotl > 0.0 && length(sunT) > 0.001) {
        vec3 H = normalize(L + V);
        float ndoth = max(dot(s.ns, H), 0.0);
        float specPower = mix(8.0, 512.0, gloss * gloss);
        sunSpec = pc.keyColor.rgb * sunT * pow(ndoth, specPower) * F * ndotl * (gloss + 0.1);
    }
    if (s.metal > 0.5) {
        vec3 rdir = bendHorizon(reflect(dir, s.ns), s.ng);
        vec3 reflected = skyAt(rdir) * F * gloss;
        return (s.emission <= 0.1 ? diffuse * 0.15 : diffuse * (1.0 - F * gloss)) + reflected + sunSpec + glow;
    }
    if (s.roughness < 0.6) {
        vec3 rdir = bendHorizon(reflect(dir, s.ns), s.ng);
        return diffuse * (1.0 - F * gloss) + skyAt(rdir) * F * gloss + sunSpec + glow;
    }
    return diffuse + sunSpec + glow;
}

// Deterministic radiance arriving along a mirror direction: sky with clouds and the sun/moon disk on a miss, else the lit hit.
// Ensures off-screen reflection support via hermetic TLAS traversal (mask 0x03: MASK_WORLD | MASK_ENTITIES).
// m: water body the mirror ray starts in (camera under water looking at the surface from below): the ray is attenuated and
// in-scatters like every other ray in the water.
vec3 mirrorRadianceM(vec3 origin, vec3 dir, float reach, bool shadowRay, WaterMedium m) {
    Surface r;
    vec3 f;
    vec3 ins;
    if (traceThroughInterfacesM(origin, dir, reach, m, r, f, ins)) {
        return f * shadeReflectionHit(r, dir, shadowRay) + ins;
    }
    vec3 sky = skyAt(dir);
    if (dir.y <= 0.0) {
        sky = skyRadiance(dir);   // no geometry below the horizon in range: the same ground dome the path tracer sees on a miss
    }
    return f * sky + ins;
}

vec3 mirrorRadiance(vec3 origin, vec3 dir, float reach, bool shadowRay) {
    return mirrorRadianceM(origin, dir, reach, shadowRay, noWater());
}

// Reflection of an interface: at F < 5 % of a water face (steep views) the reflected radiance is only the sky (plus the sun glint),
// no scene traversal; from under the surface such a reflection is dark water and is dropped. ONE mirrorRadianceM call site only
// (every inlined copy of the walk and the reflected-hit shading costs the whole shader performance).
vec3 interfaceMirror(bool skyOnly, vec3 origin, vec3 rdir, float reach, bool shadow, WaterMedium m) {
    if (skyOnly) {
        return m.inside ? vec3(0.0) : skyAt(rdir);
    }
    return mirrorRadianceM(origin, rdir, reach, shadow, m);
}
