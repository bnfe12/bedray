# Material and texture set audit

11 November 2026. L0 source inspection.

The block material atlas stores LabPBR smoothness as microfacet alpha. The shared shading and denoising contracts consume perceptual roughness, so the block decoder now takes the square root when the source byte identifies an explicit specular map. AutoPBR, entity presets and voxel-table materials retain their existing representation. The water-identification predicate uses the encoded atlas roughness. [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)

The inspected block bake handles specular and normal companion textures. The entity atlas uses ItemPbr presets and needs a separate review for explicit material companions. Existing progressive material uploads remain in place.

Material source flags distinguish authored specular data and authored normal data. Channel decoding, normal-map orientation, animation synchronization and individual emissive masks still need texture-set captures for a complete pack audit. Albedo/material/normal sampling still uses LOD 0. Atlas mip generation and footprint-aware filtering are the next source-level anti-shimmer work.

This audit makes a source-level roughness correction. Current resource-pack visual acceptance and relative performance against the reference renderers remain open. [Rendering reference audit](RTX_REFERENCE_AUDIT.md)
