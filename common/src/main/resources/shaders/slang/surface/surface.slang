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
import "surface/water.slang";
import "traversal/entity_geometry.slang";

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

    // A captured model wears the RT entity atlas rather than the block atlas,
    // and its texels are an integer grid with no sampler, so they travel beside
    // the three textures instead of being folded into them.  A pass whose
    // traversal cannot return an entity hit still has to name them: an entry
    // pass binds the frame's atlas for every decode it shares.
    EntityAtlas entities;
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
// Shadow queries only: a cutout plant or leaf filters the key light instead of blocking it.  Visibility, AO, sky and
// camera queries keep the binary cutout, because a canopy still occludes them.
static const uint MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE = 1u << 4u;

// Share of the key light one leaf face lets through, and how far that share bends toward the texel's own hue.  The
// legacy path used the same pair: ~60 % per face keeps ~36 % through the entry and exit face of one leaf block.
static const float FOLIAGE_LAYER_TRANSMITTANCE = 0.60;
static const float FOLIAGE_HUE_MIX = 0.45;
// Back-lit leaf term of the direct estimator (rt_path.comp): strength, the smooth ramp that keeps the term from
// flickering at the silhouette, and the warm scatter tint of light that crossed a leaf.
static const float FOLIAGE_BACKLIT_GAIN = 0.42;
static const float3 FOLIAGE_BACKLIT_TINT = float3(1.05, 1.25, 0.75);

struct MaterialSamplingOptions {
    float alphaCutoff;        // cutout threshold, 0.5 matches the legacy path
    float emissionScale;      // host exposure / emissive gain
    float normalStrength;     // 0 disables the normal map
    float autoNormalStrength; // 0 disables generated entity normals
    float portalTime;         // frame clock in seconds
    float defaultIor;         // dielectric IOR when the atlas has no optical profile
    float defaultThickness;   // slab thickness in blocks
    float waterWaveMix;       // how much of the analytic wave field a fluid face keeps: 1 realistic, WATER_WAVE_MIX_VANILLA damped
    uint  flags;              // MATERIAL_SAMPLING_FLAG_*
};

MaterialSamplingOptions defaultMaterialSamplingOptions() {
    MaterialSamplingOptions options;
    options.alphaCutoff = 0.5;
    options.emissionScale = 1.0;
    options.normalStrength = 1.0;
    options.autoNormalStrength = 0.0;
    options.portalTime = 0.0;
    options.defaultIor = 1.5;
    options.defaultThickness = 1.0;
    // Realistic waves are the player's default (RtSettings.waterStyle), and an
    // option that never met a frame keeps the same full wave field.
    options.waterWaveMix = 1.0;
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
    material.emissionMask = 0.0;
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

float3 entityPortalColor(float3 worldPosition, float3 rayDirection, float portalTime) {
    float pt = portalTime * 0.015;
    float3 parallax = rayDirection / max(abs(rayDirection.y), 0.25);
    float3 color = float3(0.015, 0.035, 0.065);
    float3 layerColors[6] = {
        float3(0.22, 0.58, 0.72),
        float3(0.45, 0.28, 0.85),
        float3(0.18, 0.82, 0.75),
        float3(0.75, 0.42, 0.95),
        float3(0.35, 0.92, 0.88),
        float3(0.95, 0.88, 1.00)
    };
    for (uint layer = 0u; layer < 6u; ++layer) {
        float fl = float(layer + 1u);
        float2 puv = worldPosition.xz * (1.8 + fl * 0.85) + parallax.xz * (0.12 * fl)
            + float2(sin(pt * (0.7 + 0.2 * fl) + fl * 1.7),
                cos(pt * (0.6 + 0.15 * fl) - fl * 2.3)) * 0.45;
        float2 cell = floor(puv * 8.0);
        float2 local = frac(puv * 8.0) - 0.5;
        uint h = uint(int(cell.x)) * 73856093u ^ uint(int(cell.y)) * 19349663u ^ layer * 83492791u;
        h ^= h >> 16u;
        h *= 0x7feb352du;
        h ^= h >> 15u;
        float2 starOffset = (float2(float(h & 0xFFu), float((h >> 8u) & 0xFFu)) / 255.0 - 0.5) * 0.55;
        float probability = float((h >> 16u) & 0xFFu) / 255.0;
        if (probability > 0.72) {
            float2 delta = local - starOffset;
            float star = exp(-dot(delta, delta) * (90.0 + fl * 25.0))
                * (0.6 + 0.4 * sin(pt * 3.0 + float(h & 63u)));
            color += layerColors[layer] * star * (1.25 - 0.10 * fl);
        }
    }
    return clamp(color, float3(0.01), float3(1.0));
}

float entityAtlasLuminanceOffset(EntityAtlas atlas, uint slot, float2 uv, int2 offset) {
    uint rectCount;
    uint rectStride;
    atlas.rects.GetDimensions(rectCount, rectStride);
    if (atlas.atlasSize == 0u || slot >= rectCount) return -1.0;

    uint4 rect = atlas.rects[slot];
    uint2 size = uint2(rect.z & 0xFFFFu, rect.w & 0xFFFFu);
    if (size.x == 0u || size.y == 0u) return -1.0;

    int2 texel = int2(clamp(uv, float2(0.0), float2(0.99999)) * float2(size));
    texel = clamp(texel + offset, int2(0), int2(size) - int2(1));
    uint index = (rect.y + uint(texel.y)) * atlas.atlasSize + rect.x + uint(texel.x);
    float4 color = entityUnpackPacked(atlas.texels[index]);
    if (color.a < ENTITY_ALPHA_HOLE) return -1.0;
    return dot(color.rgb, float3(0.299, 0.587, 0.114));
}

float3 decodeEntityAutoNormal(EntityAtlas atlas, SurfaceHit hit,
        float3 geometricNormal, float strength) {
    if (strength <= 0.0 || (hit.flags & HIT_FLAG_TANGENT_VALID) == 0u) return geometricNormal;

    float center = dot(entityTexel(atlas, hit.materialId, hit.uv).rgb, float3(0.299, 0.587, 0.114));
    float lxn = entityAtlasLuminanceOffset(atlas, hit.materialId, hit.uv, int2(-1, 0));
    float lxp = entityAtlasLuminanceOffset(atlas, hit.materialId, hit.uv, int2(1, 0));
    float lyn = entityAtlasLuminanceOffset(atlas, hit.materialId, hit.uv, int2(0, -1));
    float lyp = entityAtlasLuminanceOffset(atlas, hit.materialId, hit.uv, int2(0, 1));
    if (lxn < 0.0) lxn = center;
    if (lxp < 0.0) lxp = center;
    if (lyn < 0.0) lyn = center;
    if (lyp < 0.0) lyp = center;

    float2 gradient = float2(lxn - lxp, lyp - lyn);
    gradient *= smoothstep(0.025, 0.08, length(gradient)) * (0.28 * strength);
    float gradientLength2 = dot(gradient, gradient);
    if (gradientLength2 > 0.25) gradient *= 0.5 * rsqrt(gradientLength2);
    if (dot(gradient, gradient) <= 1.0e-4) return geometricNormal;

    float3 tangent = safeNormalize(hit.tangent, geometricNormal);
    float3 bitangent = safeNormalize(hit.bitangent, geometricNormal);
    tangent = safeNormalize(tangent - geometricNormal * dot(geometricNormal, tangent), geometricNormal);
    bitangent = safeNormalize(bitangent - geometricNormal * dot(geometricNormal, bitangent), geometricNormal);
    float z = sqrt(max(0.25, 1.0 - dot(gradient, gradient)));
    float3 normal = safeNormalize(tangent * gradient.x + bitangent * gradient.y + geometricNormal * z,
        geometricNormal);
    float3 candidate = dot(normal, geometricNormal) > 0.0 ? normal : reflect(normal, geometricNormal);
    float normalDot = dot(candidate, geometricNormal);
    return safeNormalize(lerp(geometricNormal, candidate, smoothstep(0.20, 0.65, normalDot)),
        geometricNormal);
}

// Decodes one hit.  A miss or a malformed hit returns valid = false; a cutout
// hole is still a valid surface (surfaceIsHole decides whether it is a hole),
// because a visibility walk must distinguish a hole from a filter.
// `wantNormal` is the transmittance-walk switch of the legacy decoder: a shadow ray reads only the material, the
// geometric normal and the tint, so it must not pay for the atlas normal, the AO texel or the water wave normal. Every
// value those walks consume is computed on both paths, so the filter it applies is the same one a shaded hit gets.
SurfaceSample decodeSurface(MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceHit hit, MaterialSamplingOptions options, float3 rayDirection, bool wantNormal) {
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
        material.emissionMask = max(block.surface.z, 0.0);
        material.emission = block.albedo.rgb * material.emissionMask;
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
    } else if (hit.kind == HitKind.Entity) {
        // A captured mob, item or armour stand.  Its texture is the RT entity
        // atlas at the part's own slot, multiplied by the vertex tint vanilla
        // shaded the model with; there is no normal map and no biome tint.  The
        // texel's alpha byte does double duty - below the cutout edge it is
        // transparency, from the cutout edge to 223/255 it is a real pane, and
        // at 224/255 and above the texel is opaque and the same five bits are an
        // emission code - so the three bands are decided here rather than left
        // to the generic alpha rule.
        bool outline = (hit.flags & HIT_FLAG_ENTITY_OUTLINE) != 0u;
        bool endPortal = (hit.flags & HIT_FLAG_ENTITY_END_PORTAL) != 0u;

        float3 color = hit.tint;
        float emission = 0.0;
        float alpha = 1.0;
        bool glassy = false;
        EntitySurfacePbr pbr;
        pbr.roughness = 0.85;
        pbr.metallic = 0.0;
        pbr.f0 = 0.04;

        if (!outline) {
            float4 texel = entityTexel(atlas.entities, hit.materialId, hit.uv);
            color = texel.rgb * hit.tint;
            emission = entityTexelEmission(texel);
            alpha = texel.a;
            if ((hit.flags & HIT_FLAG_ENTITY_HURT) != 0u) {
                color = lerp(color, float3(1.0, 0.05, 0.05), 0.65);
            }
            // A black texel is the shadow blob or a decal under the model, not a
            // pane: taken as glass it would tint nothing at all and the ray walk
            // would spend a layer on it.
            glassy = entityTexelTranslucent(texel) && max(color.r, max(color.g, color.b)) > 0.08;
            pbr = entitySurfacePbr(atlas.entities, hit.materialId);
            // The authored metal of a held tool is only as good as the texel
            // under it: the same part carries a dark brown wooden handle.
            if (pbr.metallic > 0.5 && color.r > color.b * 1.8 && color.r < 0.45 && color.g < 0.30) {
                pbr.metallic = 0.0;
                pbr.roughness = 0.85;
            }
        }
        if (endPortal) {
            color = entityPortalColor(hit.worldPosition, rayDirection, options.portalTime);
            emission = 0.95;
            alpha = 1.0;
            glassy = false;
            pbr.roughness = 0.05;
        }
        if (wantNormal && !endPortal && !outline && alpha >= ENTITY_ALPHA_HOLE) {
            shadingNormal = decodeEntityAutoNormal(atlas.entities, hit, geometricNormal, options.autoNormalStrength);
        }

        material.albedo = color;
        material.roughness = glassy ? min(pbr.roughness, 0.05) : pbr.roughness;
        material.metallic = pbr.metallic;
        material.f0 = pbr.metallic > 0.5 ? float3(0.04) : float3(pbr.f0);
        material.ior = max(options.defaultIor, 1.0);
        material.thickness = max(options.defaultThickness, 0.0);
        material.ambientOcclusion = 1.0;
        material.emissionMask = emission;
        material.emission = color * emission;
        material.materialId = hit.materialId;
        material.sourceFlags = MATERIAL_SOURCE_NONE;
        material.flags = pbr.metallic > 0.5 ? MATERIAL_FLAG_METAL : 0u;

        if (outline) {
            // Geometry the player drew, not geometry the world has: a selection
            // wireframe must not stop a shadow.  A translucent surface with no
            // authored thickness passes every ray through unchanged - the
            // thin-pane absorption is pow(albedo, alpha * transmission) and
            // alpha is zero - while still shading its own colour.
            material.alphaMode = AlphaMode.Translucent;
            material.alpha = 0.0;
            material.transmission = 0.0;
            material.emissionMask = ENTITY_OUTLINE_EMISSION;
            material.emission = color * ENTITY_OUTLINE_EMISSION;
            material.flags |= MATERIAL_FLAG_EMISSIVE;
        } else if (alpha < ENTITY_ALPHA_HOLE) {
            // A hole in the model.  Zero is under every cutoff a pass can be
            // given, so the texel stays a hole whatever the path asks for; the
            // vanilla entity cutout is 0.1 and the tracer's own is 0.5.
            material.alphaMode = AlphaMode.Cutout;
            material.alpha = 0.0;
            material.flags |= MATERIAL_FLAG_CUTOUT;
        } else if (glassy) {
            material.alphaMode = AlphaMode.Translucent;
            material.alpha = alpha;
            material.flags |= MATERIAL_FLAG_THIN_GLASS;
        } else {
            material.alphaMode = AlphaMode.Opaque;
            material.alpha = 1.0;
        }
        // The legacy path applies this floor after the outline / hurt / plain
        // chain, so a fullbright part is raised over 0.35 too - and only a texel
        // that survived the cutout edge can be one: a hole shades nothing.
        if (hit.light.x > 0.995 && hit.light.y > 0.995 && alpha >= ENTITY_ALPHA_HOLE) {
            material.emissionMask = max(material.emissionMask, ENTITY_FULLBRIGHT_EMISSION);
            material.emission = color * material.emissionMask;
        }
        if (material.emissionMask > 0.0) material.flags |= MATERIAL_FLAG_EMISSIVE;
    } else {
        // Triangle: sample the block atlas at the interpolated UV.
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
        material.emissionMask = max(materialTexel.b, 0.0);
        material.emission = albedoTexel.rgb * material.emissionMask;
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
        if (wantNormal) {
            shadingNormal = decodeAtlasNormal(atlas, options, hit, geometricNormal,
                ambientOcclusion);
            material.ambientOcclusion = ambientOcclusion;
        }
        // A fluid is not a tinted pane.  The auto-PBR atlas tags it with water's
        // reflectance and roughness, the sheet takes the analytic wave normal
        // and the Fresnel split of an optical interface, and its transmission is
        // tinted by the biome colour instead of by the opaque 16x16 sprite.
        if ((hit.flags & HIT_FLAG_TRANSLUCENT) != 0u
                && waterIsSurface(true, material.f0, material.roughness, material.metallic)) {
            material.sourceFlags |= MATERIAL_SOURCE_FLUID;
            material.alphaMode = AlphaMode.Optical;
            material.ior = WATER_IOR;
            if (wantNormal) {
                float footprint = hit.t * WATER_PIXEL_ANGLE /
                    max(abs(dot(rayDirection, geometricNormal)), 0.06);
                float lostVar = 0.0;
                float3 waveNormal = waterStyledWaveNormal(hit.worldPosition, geometricNormal,
                    options.portalTime, footprint, options.waterWaveMix, lostVar);
                // Both styles are the same water: the medium, the IOR and the light
                // that reaches the floor do not depend on the switch.  Only the face
                // does - the realistic style keeps the full tilt, the damped one
                // leans back toward the flat normal and carries a gentler sheen, the
                // pair the legacy surface decoder used.
                material.roughness = options.waterWaveMix >= 1.0
                    ? clamp(sqrt(max(lostVar, 0.0)) * 1.6, 0.02, 0.30)
                    : clamp(0.02 + sqrt(max(lostVar, 0.0)) * 0.8, 0.02, 0.16);
                shadingNormal = waveNormal;
            }
            material.metallic = 0.0;
            material.f0 = float3(WATER_INTERFACE_F0);
            material.flags &= ~MATERIAL_FLAG_METAL;
            material.transmission = 1.0;
            material.thickness = 0.0;
            // The interface itself is clear: the colour of the water is the
            // medium, and a caller that tracks it (the path tracer, the
            // visibility walk) applies the tint as distance absorption.  A
            // caller that does not gets the tint from surfaceTransmittance,
            // which reads the biome colour off the hit.
            material.albedo = float3(1.0);
        }
    }

    // Source flags are authoritative for tags that do not change geometry:
    // foliage is cutout, a fluid filters light, an emitter has emission.
    //
    // Foliage arrives with the geometry, not with the material table: the host
    // classifies plants and leaves per quad while staging a section, and both
    // traversal backends surface that tag as HIT_FLAG_FOLIAGE.
    if ((hit.flags & HIT_FLAG_FOLIAGE) != 0u) {
        material.sourceFlags |= MATERIAL_SOURCE_FOLIAGE;
    }
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
        material.emissionMask = 0.0;
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

// A caller that shades the surface needs the decoded normal and AO.
SurfaceSample decodeSurface(MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        SurfaceHit hit, MaterialSamplingOptions options, float3 rayDirection) {
    return decodeSurface(atlas, blockMaterials, hit, options, rayDirection, true);
}

// A cutout texel below the threshold is a hole, not a filter.  Opaque and
// translucent materials never become holes through this test.
bool surfaceIsHole(SurfaceSample surface, float alphaCutoff) {
    if (!surface.valid) return true;
    if (!materialIsCutout(surface.material)) return false;
    return !materialAcceptsAlpha(surface.material, alphaCutoff);
}

// A leaf texel is a coloured filter for the key light, not an opaque blocker.  Only the cutout-pass vegetation tag may
// filter: bars, chains, panes, ladders and doors share the cutout pass, and a glowing plant (a torch flower, a campfire
// model drawn as a cutout) is an opaque emitter - a flame does not transmit sunlight.
bool materialFiltersAsFoliage(MaterialSample material) {
    if ((material.sourceFlags & MATERIAL_SOURCE_FOLIAGE) == 0u) return false;
    if ((material.flags & MATERIAL_FLAG_CUTOUT) == 0u) return false;
    return (material.flags & MATERIAL_FLAG_EMISSIVE) == 0u;
}

// Transmittance of one leaf face: 60 % of the light, bent toward the texel's own hue (its albedo over the strongest
// channel), so a canopy shades green instead of grey.
float3 foliageLayerTransmittance(SurfaceSample surface) {
    if (!surface.valid) return float3(1.0);
    float3 albedo = max(surface.material.albedo, float3(0.0));
    float maximum = max(albedo.r, max(albedo.g, albedo.b));
    float3 hue = albedo / max(maximum, 1.0e-3);
    return FOLIAGE_LAYER_TRANSMITTANCE * lerp(float3(1.0), hue, FOLIAGE_HUE_MIX);
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
    if ((material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u) {
        // A caller that does not track the medium still gets the legacy
        // biome-tinted sheet (tint * 0.85, the realistic style).  The tint is
        // read from the hit, because the interface albedo is now clear.
        float3 tint = clamp(waterBiomeTint(surface.hit.tint,
            surface.hit.geometricNormal) * 0.85, float3(0.001), float3(1.0));
        return (1.0 - fresnel) * tint;
    }
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

// Interface-only filter for a caller that tracks the fluid medium itself.  The
// Fresnel share belongs to the interface; the colour is distance absorption
// along the segment, applied by the caller, so tinting here would darken the
// water twice.  Every other material keeps the full filter.
float3 surfaceInterfaceTransmittance(SurfaceSample surface, float3 direction) {
    if (!surface.valid || !materialIsTranslucent(surface.material)) {
        return float3(1.0);
    }
    if ((surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) == 0u) {
        return surfaceTransmittance(surface, direction);
    }
    float cosine = clamp(abs(dot(surface.hit.geometricNormal, direction)), 0.05, 1.0);
    float f0 = clamp(luminance(surface.material.f0), 0.0, 1.0);
    float fresnel = f0 + (1.0 - f0) * pow5(1.0 - cosine);
    return float3(1.0 - fresnel);
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
