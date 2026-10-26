// Material contract for Bedray 2.0.
//
// The atlas is the source of truth; this module only defines the *decoded*
// surface description handed to shading code.  Traversal backends never see
// material rules, which keeps voxel, hybrid and hardware paths interchangeable.

public module material_contract;

// How a surface interacts with a ray that is allowed to pass through it.
enum AlphaMode : uint {
    Opaque      = 0u,   // always blocks
    Cutout      = 1u,   // alpha-tested: blocks when alpha >= cutoff
    Translucent = 2u,   // blocks camera rays, filters shadow/visibility rays
    Optical     = 3u,   // dielectric interface: reflection/refraction split
};

// LabPBR source bits carried through the material atlas alpha channel.
static const uint MATERIAL_SOURCE_NONE      = 0u;
static const uint MATERIAL_SOURCE_REDSTONE  = 1u << 0u;
static const uint MATERIAL_SOURCE_EMISSIVE  = 1u << 1u;
static const uint MATERIAL_SOURCE_FOLIAGE   = 1u << 2u;
static const uint MATERIAL_SOURCE_ENTITY    = 1u << 3u;
static const uint MATERIAL_SOURCE_FLUID     = 1u << 4u;

// Surface flags a pass may use without re-decoding the atlas.
static const uint MATERIAL_FLAG_METAL       = 1u << 0u;
static const uint MATERIAL_FLAG_EMISSIVE    = 1u << 1u;
static const uint MATERIAL_FLAG_CUTOUT      = 1u << 2u;
static const uint MATERIAL_FLAG_THIN_GLASS  = 1u << 3u;
static const uint MATERIAL_FLAG_SUBSURFACE  = 1u << 4u;

struct MaterialSample {
    float3 albedo;              // linear diffuse albedo
    float3 emission;            // linear emitted radiance
    float3 f0;                  // reflectance at normal incidence
    float  roughness;           // linear roughness (0..1)
    float  metallic;            // 0 = dielectric, 1 = metal
    float  alpha;               // atlas alpha at the hit
    float  ior;                 // index of refraction (water 1.333, glass 1.5)
    float  transmission;        // 0..1 fraction of refracted energy
    float  thickness;           // authored slab thickness in blocks
    float  ambientOcclusion;    // baked AO from the LabPBR normal atlas
    AlphaMode alphaMode;
    uint   sourceFlags;         // MATERIAL_SOURCE_*
    uint   materialId;          // stable id for temporal/history comparisons
    uint   flags;               // MATERIAL_FLAG_*
};

// A material that has not been resolved yet.  Traversal returns this shape;
// the surface pass replaces it with a full MaterialSample.
struct MaterialKey {
    uint materialId;
    uint sourceFlags;
    uint atlasLayer;
    uint reserved;
};

bool materialIsMetal(MaterialSample material) {
    return material.metallic >= 0.5 || (material.flags & MATERIAL_FLAG_METAL) != 0u;
}

bool materialIsEmissive(MaterialSample material) {
    return (material.flags & MATERIAL_FLAG_EMISSIVE) != 0u ||
           any(material.emission > float3(0.0));
}

bool materialIsCutout(MaterialSample material) {
    return material.alphaMode == AlphaMode.Cutout ||
           (material.flags & MATERIAL_FLAG_CUTOUT) != 0u;
}

bool materialIsTranslucent(MaterialSample material) {
    return material.alphaMode == AlphaMode.Translucent ||
           material.alphaMode == AlphaMode.Optical ||
           material.transmission > 0.0;
}

// Cutout decision lives here, not in the traversal backend, so every backend
// applies exactly the same rule.
bool materialAcceptsAlpha(MaterialSample material, float cutoff) {
    if (material.alphaMode == AlphaMode.Opaque) return true;
    if (material.alphaMode == AlphaMode.Cutout) return material.alpha >= cutoff;
    return true;
}

// Shadow/visibility rays treat translucent and optical surfaces as filters.
bool materialBlocksVisibility(MaterialSample material) {
    if (material.alphaMode == AlphaMode.Opaque) return true;
    if (material.alphaMode == AlphaMode.Cutout) return material.alpha >= 0.5;
    return false;
}

MaterialKey materialKey(MaterialSample material) {
    MaterialKey key;
    key.materialId = material.materialId;
    key.sourceFlags = material.sourceFlags;
    key.atlasLayer = 0u;
    key.reserved = 0u;
    return key;
}
