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
    uvec2 uvAddr;     // float2 per vertex (block atlas, 0..1, with Sodium's shrink bias applied)
    uvec2 colAddr;    // uint per vertex: R | G<<8 | B<<16 | A<<24 (biome tint * AO)
    uvec2 lightAddr;  // uint per vertex: blockLight | skyLight<<8 | material<<16, light bytes = clamp(level*16+8, 8, 248)
    uvec4 quadStart;  // x,y,z = first quad of geometry 0,1,2 ; w = geometry count
    ivec4 origin;     // section origin in blocks ; w = pass bits (2 bits per geometry: 0 solid, 1 cutout, 2 translucent)
};
layout(set = 0, binding = 2, std430) readonly buffer Sections { Section s[]; } sections;
layout(set = 0, binding = 3) uniform sampler2D atlas;

// The vanilla lightmap of the frame (RtPipeline BINDING_LIGHTMAP): 16x16, U = block light level, V = sky light level, the
// texture the raster path applies. Carries the real tint of the pack / dimension / effects for the baked fill and the
// vanilla water sheet (previously a hardcoded warm tint).
layout(set = 0, binding = 15) uniform sampler2D lightmap;

// Hue + brightness of the vanilla block light at full level in darkness: the real tint of the pack / dimension /
// effects, replacing the hardcoded warm tint (1.0, 0.82, 0.60). The (block, sky) lightmap texel combines both lights,
// so the per-surface texel cannot be used directly — the reference is the full-block-light-in-darkness texel
// (block 15, sky 0); intensity stays s.light.x.
vec3 blockLightColor() {
    return textureLod(lightmap, vec2(15.5 / 16.0, 0.5 / 16.0), 0.0).rgb;
}
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
layout(set = 0, binding = 6, rgba16f) uniform writeonly image2D gbDiffuse;     // rgb noisy diffuse radiance * albedo (raw), a hit distance in blocks
layout(set = 0, binding = 7, rgba8) uniform writeonly image2D gbAlbedo;        // rgb diffuse albedo guide (0 for metals), a = 1 on geometry
layout(set = 0, binding = 8, rgba16f) uniform writeonly image2D gbDirect;      // rgb noise-free term added after the denoiser
layout(set = 0, binding = 13, rgba16f) uniform writeonly image2D gbSpecular;   // rgb noisy specular radiance, a hit distance in blocks
layout(set = 0, binding = 14, rgba8) uniform writeonly image2D gbSpecAlbedo;   // rgb specular reflectance F0 guide (0 = no specular lobe)
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
    float shadowPixel;          // 480 edge of a pixelated-shadow cell in texture pixels (1 = one texel of a 16x16 block sprite; 0 = option off)
    uvec2 reservoirAddr;        // 488 ReservoirBuf: two parity halves of size.x * size.y records (0 = temporal reuse off)
    uint leafBacklit;           // 496 primary hits of this frame (1 in 16) whose key light came through a leaf card
    uint selfAvatarTriStart;    // 500 first triangle of the local player's captured body in this frame's entity BLAS
    uint selfAvatarTriCount;    // 504 0 = no capture (third person, option off): nothing is ever skipped
};
layout(buffer_reference, std430, buffer_reference_align = 16) buffer Vec4Buf { vec4 v[]; };
// ReSTIR DI reservoir: one chosen emitter sample per pixel, reused by the next frame.
struct Reservoir {
    vec4 yTexEm;   // xyz sample point, camera-relative when written; w emission of the emitter texel
    vec4 enPhat;   // xyz emitter face normal; w RIS target p^ of the sample at the previous receiver
    vec4 leWsum;   // xyz emitter radiance without visibility (texture * emission * gains); w cumulative RIS weight
    vec4 meta;     // x sample count, y open-source flag, z packed receiver normal, w receiver distance
};
layout(buffer_reference, std430, buffer_reference_align = 16) buffer ReservoirBuf { Reservoir r[]; };
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

// Bit 24 of a vertex light word (RtWorld.VEGETATION_BIT): the cutout quad belongs to a plant or a leaf, the only blocks
// that transmit the key light. Byte 3 of the Sodium light word is the region-local section index, dropped by the decoder.
const uint VERT_FLAG_VEGETATION = 0x01000000u;
// Bit 25 (RtWorld.LEAF_CUBE_BIT): that vegetation is a face of a full leaf cube, one block thick, not a thin card.
const uint VERT_FLAG_LEAF_CUBE = 0x02000000u;

const uint WATER_STYLE_VANILLA = 0u;
const uint WATER_STYLE_REALISTIC = 1u;

const uint MASK_WORLD = 0x01u;
const uint MASK_ENTITIES = 0x02u;
const uint MASK_HERMETIC = MASK_WORLD | MASK_ENTITIES; // 0x03u

uint rtFlags() { return floatBitsToUint(pc.keyColor.w); }
bool hasFlag(uint f) { return (rtFlags() & f) != 0u; }
// normalize() of a zero vector is NaN in GLSL, and a voxel mesh can carry one: a quad stored as (A, B, C, C) turns its second
// triangle into (C, C, A), a zero-area sliver nobody sees but every traversal hits. NaN then survives clamp() and denoising.
// Callers that derive a direction from geometry use this and pass a direction they already trust as the fallback.
vec3 normalizeOr(vec3 v, vec3 fallback) {
    float len2 = dot(v, v);
    return len2 > 1.0e-12 ? v * inversesqrt(len2) : fallback;
}
const float EMISSIVE_GAIN = 3.0;      // emission 1.0 -> radiance 3 x albedo (pre-exposure; RtLighting.EXPOSURE 1.1, ACES)
const float EMISSIVE_FLUX_GAIN = 2.2; // was 4.5, then 2.6: surfaces near torches / lanterns were flooded (owner screens 30-31, lighting-fix)
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

// Triangles of the local player's own body in this frame's entity BLAS. First person captures the avatar so that a reflection
// can show it, but the camera ray of that same frame starts inside the body: its inner faces are centimetres from the eye and
// would fill the screen. Only primary rays skip the range; reflections, shadows and the water probe still see the player.
bool isSelfAvatarPrim(uint customIndex, uint prim) {
    if (!isEntityHit(customIndex)) {
        return false;
    }
    StatsBuf fb = StatsBuf(pc.statsAddr);
    return fb.selfAvatarTriCount > 0u && prim >= fb.selfAvatarTriStart
        && prim < fb.selfAvatarTriStart + fb.selfAvatarTriCount;
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
// foliageFilter: only traceTransmittance sets it. A vegetation texel then becomes a coloured filter instead of a blocker,
// so grass and leaves transmit the key light; visibility / AO / sky rays keep the binary cutout (a canopy still occludes).
bool candidateAccepted(uint slot, uint geom, uint prim, vec2 bary, bool skipTranslucent, bool foliageFilter) {
    if (isEntityHit(slot)) {
        return entityCandidateAccepted(prim, bary, skipTranslucent);
    }
    Section sec = sections.s[slot];
    uint pass = (uint(sec.origin.w) >> (2u * geom)) & 3u;
    if (pass == 0u) {
        // The solid layer has no alpha test: the camera ray already treats it as opaque, so a shadow/visibility ray must too,
        // otherwise a solid texel with atlas alpha < 1 stops casting a shadow. Returning here also skips the UV load and the
        // atlas/material fetches for the most common candidate of every shadow ray.
        return true;
    }
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
    // Cutout alpha: the exact mip-0 texel while a pixel covers at most one texel (crisp close up, unchanged), the
    // footprint-averaged coverage farther (same model as the redstone dust): grass and leaves no longer flip the whole
    // quad between hit and hole at every Halton jitter phase, which read as flickering plant parts.
    float a = dustCoverage(uv, gHitT);
    if (foliageFilter && skipTranslucent && a >= 0.5) {
        // Vegetation must be classified before the generic alpha filter: an emissive plant blocks even when its texel alpha is
        // below 0.99, matching Surface.isFoliage. Other foliage becomes a coloured filter in traceTransmittance.
        UIntBuf lights = UIntBuf(sec.lightAddr);
        if ((lights.v[v.x] & VERT_FLAG_VEGETATION) != 0u) {
            float emission = hasFlag(FLAG_MATERIALS) ? textureLod(matAtlas, uv, 0.0).b : 0.0;
            if (emission >= 0.5) {
                return true;
            }
            gSawTranslucent = true;
            return false;
        }
    }
    if (skipTranslucent && a < 0.99) {
        // a texel below the cutout threshold is simply a hole (a non-skipping ray ignores it too), anything else is a filter
        if (a >= 0.5) {
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
    return a >= 0.5;
}

bool candidateAccepted(uint slot, uint geom, uint prim, vec2 bary, bool skipTranslucent) {
    return candidateAccepted(slot, geom, prim, bary, skipTranslucent, false);
}

// A rayQueryEXT cannot be passed to a function, so the candidate is decoded at the call site by this macro.
#define CANDIDATE_ACCEPTED(q, skip) (gHitT = rayQueryGetIntersectionTEXT(q, false), candidateAccepted(     uint(rayQueryGetIntersectionInstanceCustomIndexEXT(q, false)),     uint(rayQueryGetIntersectionGeometryIndexEXT(q, false)),     uint(rayQueryGetIntersectionPrimitiveIndexEXT(q, false)),     rayQueryGetIntersectionBarycentricsEXT(q, false), skip))

