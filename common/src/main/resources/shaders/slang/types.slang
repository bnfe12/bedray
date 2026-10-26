// types.slang — Core data structures shared across all Bedray shaders.
// Ported 1:1 from rt_common_core.glsl (lines 22-170).
// All field names, sizes and alignments match the Java side (RtWorld, RtFrameRenderer).

// Slang modules are internal by default.  Everything here forms the shared
// shader ABI, so importing modules must be able to see these declarations.
public module types;

import "contracts/math.slang";

// ---- Geometry ----

// 64 bytes per section slot, written by RtWorld.writeTableEntry.
// Maps to layout(set = 0, binding = 2) in the old GLSL.
struct Section {
    uint64_t posAddr;    // float3 per vertex, 4 vertices per quad, section-local blocks
    uint64_t uvAddr;     // float2 per vertex (block atlas UV, 0..1, with Sodium's shrink bias)
    uint64_t colAddr;    // uint per vertex: R | G<<8 | B<<16 | A<<24 (biome tint * AO)
    uint64_t lightAddr;  // uint per vertex: blockLight | skyLight<<8 | material<<16
    uint4    quadStart;  // xyz = first quad of geometry 0,1,2; w = geometry count
    int4     origin;     // section origin in blocks; w = pass bits (2 bits per geometry)
};

// ---- Surface hit ----

struct Surface {
    float3 pos;          // world position of the hit
    float3 ng;           // geometric normal (face normal)
    float3 ns;           // shading normal (LabPBR normal-mapped)
    float2 uv;           // atlas UV at the hit
    float3 albedo;       // diffuse albedo from the block atlas
    float  alpha;        // alpha channel of the atlas texel
    float  roughness;    // linear roughness from LabPBR _s channel
    float  f0;           // reflectance at normal incidence (0..0.9 dielectric, 1.0 metal)
    float  emission;     // emission intensity from LabPBR _s blue channel
    float  mapAo;        // ambient occlusion from LabPBR _n blue channel
    float2 light;        // x = block light (0..1), y = sky light (0..1)
    uint   materialBits; // material source flags (redstone etc.)
    float  dist;         // distance from ray origin to hit
    bool   isMetal;      // true when f0 == 1.0
    bool   isFoliage;    // true for leaves, grass, vines (cutout geometry pass 1)
    bool   isEntity;     // true when the hit is on an entity BLAS instance
};

// ---- Ray hit info ----

struct HitInfo {
    bool   hit;          // true if a surface was found
    float  t;            // distance along the ray
    int    instanceId;   // TLAS instance index (-1 for miss)
    int    primitiveId;  // triangle index within the BLAS
    float2 bary;         // barycentric coordinates
};

// ---- Emitter for ReSTIR DI ----

struct Emitter {
    float3 pos;          // center of the emitting face
    float3 normal;       // outward normal of the emitting face
    float  emission;     // emission intensity (LabPBR blue channel value)
    float  area;         // area of the emitting face in blocks^2
    bool   openSource;   // true for torches, lanterns, fire (emit all directions)
};

// ---- ReSTIR reservoir ----

struct Reservoir {
    float4 meta;         // x = M (sample count), y = flags, z = packed normal, w = wSum
    float4 sample_;      // xyz = light sample position, w = target PDF (p-hat)
};

// ---- Transparent interface candidate ----

static const uint INTERFACE_LAYER_CAPACITY = 16u;
static const uint MAX_PATH_INTERFACES      = 24u;

struct InterfaceCandidate {
    float  t;            // distance along the ray
    float3 normal;       // surface normal at the interface
    float3 albedo;       // tint color of the interface
    float  ior;          // index of refraction
    float  thickness;    // authored slab thickness (blocks)
    bool   isWater;      // true for water boundaries
    bool   entering;     // true when entering the medium
};

// ---- Constants ----

static const float EMITTER_RADIUS = 15.0;   // block-light propagation radius

static const uint MASK_WORLD    = 0x01u;
static const uint MASK_ENTITIES = 0x02u;
static const uint MASK_ALL      = 0x03u;

static const uint MATERIAL_SOURCE_REDSTONE = 4u;

// ---- ReSTIR constants (Bitterli et al. 2021) ----

static const float RESTIR_M_CAP               = 20.0;
static const float RESTIR_TEMPORAL_RATIO_CAP   = 8.0;
static const float RESTIR_SPATIAL_RATIO_CAP    = 5.0;
static const float RESTIR_OCCLUSION_DECAY      = 0.25;
