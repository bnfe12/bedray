// Surface decoding for Bedray 2.0.
//
// Traversal returns a geometric SurfaceHit.  This module turns that hit into a
// MaterialSample, applying exactly the same rules for a voxel face and for a
// triangle.  Cutout holes, translucent filters and optical interfaces are
// therefore decided here, not in a traversal backend.
//
// The atlas is deliberately described as data (MaterialAtlas) rather than as a
// set of global bindings.  An entry pass owns the bindings; a unit test can
// build a tiny atlas without a device.

public module surface;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/material.slang";
import "contracts/math.slang";

// ---- Atlas description ----

// Block atlas layout (same data the legacy GLSL read):
//   albedo   rgb = sRGB texture colour, a = alpha
//   material r = linear roughness, g = f0 or 1.0 for metal,
//            b = emission mask, a = 255 - source flags
//   normal   rg = DirectX normal xy, b = baked AO, a = height
//
// The source flags are kept in the legacy encoding because the host already
// produces that texture.  Decoding them into MATERIAL_SOURCE_* bits happens in
// decodeSurface, not in every consumer.
//
// Each field is a combined image sampler (Slang Sampler2D), because ABI v2 binds
// COMBINED_IMAGE_SAMPLER descriptors and has no separate sampler descriptor.
struct MaterialAtlas {
    Sampler2D albedo;
    Sampler2D material;
    Sampler2D normal;
};

// Per-block material.  A voxel hit has no UV, so the host resolves a block
// state to one dense record.  The record is intentionally the same shape as a
// decoded MaterialSample, which lets a voxel and a triangle share all shading
// code below.
//
//   albedo  rgb = linear albedo, a = alpha
//   surface x = roughness, y = dielectric f0, z = emission mask, w = IOR
//   optical x = transmission, y = thickness in blocks, z = baked AO, w = reserved
//   meta    x = MATERIAL_SOURCE_*, y = AlphaMode, z = stable material id,
//           w = MATERIAL_FLAG_*
struct BlockMaterial {
    float4 albedo;
    float4 surface;
    float4 optical;
    uint4  meta;
};

struct BlockMaterialTable {
    StructuredBuffer<BlockMaterial> materials;
    uint count;
};

BlockMaterial defaultBlockMaterial() {
    BlockMaterial material;
    material.albedo = float4(1.0, 1.0, 1.0, 1.0);
    material.surface = float4(0.9, 0.04, 0.0, 1.5);
    material.optical = float4(0.0, 1.0, 1.0, 0.0);
    material.meta = uint4(MATERIAL_SOURCE_NONE, uint(AlphaMode.Opaque), 0u, 0u);
    return material;
}

BlockMaterial blockMaterialAt(BlockMaterialTable table, uint index) {
    if (index >= table.count) return defaultBlockMaterial();
    return table.materials[index];
}

// ---- Sampling options ----

static const uint MATERIAL_SAMPLING_FLAG_NORMAL_MAP     = 1u << 0u;
static const uint MATERIAL_SAMPLING_FLAG_MATERIAL_ATLAS = 1u << 1u;
static const uint MATERIAL_SAMPLING_FLAG_APPLY_TINT     = 1u << 2u;
static const uint MATERIAL_SAMPLING_FLAG_EMISSIVE       = 1u << 3u;

struct MaterialSamplingOptions {
    float alphaCutoff;       // cutout threshold, 0.5 matches the legacy path
    float emissionScale;     // host exposure / emissive gain
    float normalStrength;    // 0 disables the normal map
    float defaultIor;        // dielectric IOR when the atlas has no optical profile
    float defaultThickness;  // slab thickness in blocks
    uint  flags;             // MATERIAL_SAMPLING_FLAG_*
};

MaterialSamplingOptions defaultMaterialSamplingOptions() {
    MaterialSamplingOptions options;
    options.alphaCutoff = 0.5;
    options.emissionScale = 1.0;
    options.normalStrength = 1.0;
    options.defaultIor = 1.5;
    options.defaultThickness = 1.0;
    options.flags = MATERIAL_SAMPLING_FLAG_NORMAL_MAP |
                    MATERIAL_SAMPLING_FLAG_MATERIAL_ATLAS |
                    MATERIAL_SAMPLING_FLAG_APPLY_TINT |
                    MATERIAL_SAMPLING_FLAG_EMISSIVE;
    return options;
}

// ---- Decoded surface ----

struct SurfaceSample {
    bool          valid;
    SurfaceHit    hit;
    MaterialSample material;
    float3        shadingNormal;
    float3        tangent;
    float3        bitangent;
    float2        uv;
};

MaterialSample defaultMaterialSample() {
    MaterialSample material;
    material.albedo = float3(1.0);
    material.emission = float3(0.0);
    material.f0 = float3(0.04);
    material.roughness = 0.9;
    material.metallic = 0.0;
    material.alpha = 1.0;
    material.ior = 1.5;
    material.transmission = 0.0;
    material.thickness = 1.0;
    material.ambientOcclusion = 1.0;
    material.alphaMode = AlphaMode.Opaque;
    material.sourceFlags = MATERIAL_SOURCE_NONE;
    material.materialId = 0u;
    material.flags = 0u;
    return material;
}

float3 decodeAtlasNormal(MaterialAtlas atlas, MaterialSamplingOptions options,
        SurfaceHit hit, float3 geometricNormal, out float ambientOcclusion) {
    ambientOcclusion = 1.0;
    if ((options.flags & MATERIAL_SAMPLING_FLAG_NORMAL_MAP) == 0u) return geometricNormal;
    if ((hit.flags & HIT_FLAG_TANGENT_VALID) == 0u) return geometricNormal;

    float4 normalTexel = atlas.normal.SampleLevel(hit.uv, 0.0);
    ambientOcclusion = clamp(normalTexel.b, 0.0, 1.0);

    float2 xy = (normalTexel.rg - float2(0.5)) * 2.0;
    xy *= options.normalStrength;
    float lengthSquared = dot(xy, xy);
    if (lengthSquared > 0.81) xy *= 0.9 * rsqrt(lengthSquared);
    if (lengthSquared <= 1.0e-6) return geometricNormal;

    float z = sqrt(max(1.0 - dot(xy, xy), 0.0));
    float3 tangent = safeNormalize(hit.tangent, float3(1.0, 0.0, 0.0));
    float3 bitangent = safeNormalize(hit.bitangent, cross(geometricNormal, tangent));
    float3 normal = tangent * xy.x + bitangent * xy.y + geometricNormal * z;
    normal = safeNormalize(normal, geometricNormal);
    return orientNormal(normal, geometricNormal);
}

// Decodes one hit.  A miss or a malformed hit returns valid = false; a cutout
// hole is still a valid surface (surfaceIsHole decides whether it is a hole),
// because a visibility walk must distinguish a hole from a filter.
SurfaceSample decodeSurface(MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceHit hit, MaterialSamplingOptions options) {
    SurfaceSample surface;
    surface.valid = false;
    surface.hit = hit;
    surface.material = defaultMaterialSample();
    surface.shadingNormal = hit.geometricNormal;
    surface.tangent = hit.tangent;
    surface.bitangent = hit.bitangent;
    surface.uv = hit.uv;

    if (!hit.hit) return surface;

    MaterialSample material = defaultMaterialSample();
    float3 geometricNormal = safeNormalize(hit.geometricNormal, -float3(0.0, 1.0, 0.0));
    float3 shadingNormal = geometricNormal;
    float ambientOcclusion = 1.0;

    if (hit.kind == HitKind.Voxel) {
        BlockMaterial block = blockMaterialAt(blockMaterials, hit.materialId);

        material.albedo = block.albedo.rgb;
        material.alpha = clamp(block.albedo.a, 0.0, 1.0);
        material.roughness = clamp(block.surface.x, 0.0, 1.0);
        material.f0 = float3(max(block.surface.y, 0.0));
        material.emission = block.albedo.rgb * max(block.surface.z, 0.0);
        material.ior = max(block.surface.w, 1.0);
        material.transmission = clamp(block.optical.x, 0.0, 1.0);
        material.thickness = max(block.optical.y, 0.0);
        material.ambientOcclusion = clamp(block.optical.z, 0.0, 1.0);
        material.alphaMode = AlphaMode(block.meta.y);
        material.sourceFlags = block.meta.x;
        material.materialId = block.meta.z != 0u ? block.meta.z : hit.materialId;
        material.flags = block.meta.w;
        material.metallic = (material.flags & MATERIAL_FLAG_METAL) != 0u ? 1.0 : 0.0;

        // Occupancy already says the voxel is solid.  The block flags refine
        // *how* it shades: foliage is cutout, a fluid is a filter.
        if ((hit.flags & HIT_FLAG_CUTOUT) != 0u) {
            material.alphaMode = AlphaMode.Cutout;
            material.flags |= MATERIAL_FLAG_CUTOUT;
        }
        if ((hit.flags & HIT_FLAG_TRANSLUCENT) != 0u) {
            material.alphaMode = AlphaMode.Translucent;
            material.flags |= MATERIAL_FLAG_THIN_GLASS;
        }
        if ((hit.flags & HIT_FLAG_EMISSIVE) != 0u) {
            material.flags |= MATERIAL_FLAG_EMISSIVE;
        }

        if ((options.flags & MATERIAL_SAMPLING_FLAG_APPLY_TINT) != 0u) {
            material.albedo *= hit.tint;
        }
        // A block material has no atlas normal map; the provider may still
        // supply a tangent frame for procedural shading.
    } else {
        // Triangle or entity: sample the block atlas at the interpolated UV.
        float4 albedoTexel = atlas.albedo.SampleLevel(hit.uv, 0.0);
        float4 materialTexel = float4(0.9, 0.04, 0.0, 1.0);
        if ((options.flags & MATERIAL_SAMPLING_FLAG_MATERIAL_ATLAS) != 0u) {
            materialTexel = atlas.material.SampleLevel(hit.uv, 0.0);
        }

        material.albedo = albedoTexel.rgb;
        material.alpha = clamp(albedoTexel.a, 0.0, 1.0);
        material.roughness = clamp(materialTexel.r, 0.0, 1.0);
        material.metallic = materialTexel.g > (229.0 / 255.0) ? 1.0 : 0.0;
        material.f0 = material.metallic > 0.5 ? float3(0.04) : float3(max(materialTexel.g, 0.0));
        material.emission = albedoTexel.rgb * max(materialTexel.b, 0.0);
        material.ior = max(options.defaultIor, 1.0);
        material.thickness = max(options.defaultThickness, 0.0);
        material.ambientOcclusion = 1.0;
        // The material atlas still carries the legacy source byte: bit 1 is
        // the LabPBR normal marker, bit 2 is redstone, bits 3+ are an optical
        // profile.  Do not copy that byte into MATERIAL_SOURCE_* directly.
        uint legacySourceFlags = uint(255.0 - materialTexel.a * 255.0 + 0.5);
        material.sourceFlags = MATERIAL_SOURCE_NONE;
        if ((legacySourceFlags & 4u) != 0u) {
            material.sourceFlags |= MATERIAL_SOURCE_REDSTONE;
        }
        if ((hit.flags & HIT_FLAG_EMISSIVE) != 0u) {
            material.sourceFlags |= MATERIAL_SOURCE_EMISSIVE;
        }
        material.materialId = hit.materialId;
        material.alphaMode = AlphaMode.Opaque;
        material.flags = 0u;

        if ((hit.flags & HIT_FLAG_CUTOUT) != 0u) {
            material.alphaMode = AlphaMode.Cutout;
            material.flags |= MATERIAL_FLAG_CUTOUT;
        }
        if ((hit.flags & HIT_FLAG_TRANSLUCENT) != 0u) {
            material.alphaMode = AlphaMode.Translucent;
            material.flags |= MATERIAL_FLAG_THIN_GLASS;
        }
        if ((hit.flags & HIT_FLAG_EMISSIVE) != 0u) {
            material.flags |= MATERIAL_FLAG_EMISSIVE;
        }
        if (material.metallic > 0.5) material.flags |= MATERIAL_FLAG_METAL;
        if (material.emission.r + material.emission.g + material.emission.b > 0.0) {
            material.flags |= MATERIAL_FLAG_EMISSIVE;
        }

        if ((options.flags & MATERIAL_SAMPLING_FLAG_APPLY_TINT) != 0u) {
            material.albedo *= hit.tint;
        }
        shadingNormal = decodeAtlasNormal(atlas, options, hit, geometricNormal,
            ambientOcclusion);
        material.ambientOcclusion = ambientOcclusion;
    }

    // Source flags are authoritative for tags that do not change geometry:
    // foliage is cutout, a fluid filters light, an emitter has emission.
    if ((material.sourceFlags & MATERIAL_SOURCE_REDSTONE) != 0u) {
        // Redstone dust is forced into the translucent pass by the vanilla
        // model, but the tagged texel is a cutout solid, not glass.
        material.alphaMode = AlphaMode.Cutout;
        material.transmission = 0.0;
        material.flags |= MATERIAL_FLAG_CUTOUT | MATERIAL_FLAG_EMISSIVE;
    }
    if ((material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) != 0u) {
        material.flags |= MATERIAL_FLAG_CUTOUT;
        if (material.alphaMode == AlphaMode.Opaque) material.alphaMode = AlphaMode.Cutout;
    }
    if ((material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u) {
        material.flags |= MATERIAL_FLAG_THIN_GLASS;
        if (material.alphaMode == AlphaMode.Opaque) material.alphaMode = AlphaMode.Translucent;
    }
    if ((material.sourceFlags & MATERIAL_SOURCE_EMISSIVE) != 0u) {
        material.flags |= MATERIAL_FLAG_EMISSIVE;
    }
    if ((material.flags & MATERIAL_FLAG_EMISSIVE) != 0u &&
        (options.flags & MATERIAL_SAMPLING_FLAG_EMISSIVE) != 0u) {
        material.emission *= options.emissionScale;
    } else {
        material.emission = float3(0.0);
    }

    if (material.alphaMode == AlphaMode.Translucent && material.transmission <= 0.0) {
        material.transmission = 1.0;
    }
    if (material.alphaMode == AlphaMode.Optical && material.transmission <= 0.0) {
        material.transmission = 1.0;
    }

    material.roughness = clamp(material.roughness, 0.0, 1.0);
    material.metallic = clamp(material.metallic, 0.0, 1.0);
    material.alpha = clamp(material.alpha, 0.0, 1.0);
    material.transmission = clamp(material.transmission, 0.0, 1.0);
    material.thickness = max(material.thickness, 0.0);
    material.ambientOcclusion = clamp(material.ambientOcclusion, 0.0, 1.0);

    surface.valid = true;
    surface.material = material;
    surface.shadingNormal = orientNormal(shadingNormal, geometricNormal);
    surface.tangent = hit.tangent;
    surface.bitangent = hit.bitangent;
    return surface;
}

// A cutout texel below the threshold is a hole, not a filter.  Opaque and
// translucent materials never become holes through this test.
bool surfaceIsHole(SurfaceSample surface, float alphaCutoff) {
    if (!surface.valid) return true;
    if (!materialIsCutout(surface.material)) return false;
    return !materialAcceptsAlpha(surface.material, alphaCutoff);
}

// Filtered transmittance of one translucent layer.  Thin panes use the texel
// alpha as absorption thickness (the legacy rule); an optical interface uses
// the authored slab thickness and the refracted path length.
float3 surfaceTransmittance(SurfaceSample surface, float3 direction) {
    if (!surface.valid || !materialIsTranslucent(surface.material)) {
        return float3(1.0);
    }

    MaterialSample material = surface.material;
    float cosine = clamp(abs(dot(surface.hit.geometricNormal, direction)), 0.05, 1.0);
    float ior = max(material.ior, 1.0);
    float sinSquared = max(1.0 - cosine * cosine, 0.0);
    float insideCosine = sqrt(max(1.0 - sinSquared / (ior * ior), 0.0));
    float slabLength = max(material.thickness, 0.0001) / max(insideCosine, 0.05);

    float f0 = clamp(luminance(material.f0), 0.0, 1.0);
    float fresnel = f0 + (1.0 - f0) * pow5(1.0 - cosine);
    float3 absorption = pow(clamp(material.albedo, float3(0.001), float3(1.0)),
        float3(max(slabLength * material.transmission, 0.0)));

    if (material.alphaMode != AlphaMode.Optical) {
        // Thin pane: alpha is the authored optical thickness, not a physical
        // slab, so the grazing path length must not darken it to black.
        float thinPower = max(material.alpha, 0.0) * max(material.transmission, 0.0);
        absorption = pow(clamp(material.albedo, float3(0.001), float3(1.0)),
            float3(thinPower));
    }

    return (1.0 - fresnel) * absorption;
}

// Convenience for a pass that has only a MaterialSample and no SurfaceHit.
float3 materialTransmittance(MaterialSample material, float3 geometricNormal,
        float3 direction) {
    SurfaceSample surface;
    surface.valid = true;
    surface.material = material;
    surface.hit = missHit(0.0);
    surface.hit.geometricNormal = geometricNormal;
    surface.hit.t = 0.0;
    surface.shadingNormal = geometricNormal;
    surface.tangent = float3(1.0, 0.0, 0.0);
    surface.bitangent = float3(0.0, 1.0, 0.0);
    surface.uv = float2(0.0);
    return surfaceTransmittance(surface, direction);
}
