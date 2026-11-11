# Material and texture set audit

11 November 2026. L0 source inspection.

The block material atlas stores LabPBR smoothness as microfacet alpha. The shared shading and denoising contracts consume perceptual roughness, so the block decoder now takes the square root when the source byte identifies an explicit specular map. AutoPBR, entity presets and voxel-table materials retain their existing representation. The water-identification predicate uses the encoded atlas roughness. [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)

The inspected block bake handles specular and normal companion textures. The entity atlas uses ItemPbr presets and needs a separate review for explicit material companions. Existing progressive material uploads remain in place.

Material source flags distinguish authored specular data and authored normal data. Channel decoding, normal-map orientation, animation synchronization and individual emissive masks still need texture-set captures for a complete pack audit. Albedo/material/normal sampling still uses LOD 0. Atlas mip generation and footprint-aware filtering are the next source-level anti-shimmer work.

This audit makes a source-level roughness correction. Current resource-pack visual acceptance and relative performance against the reference renderers remain open. [Rendering reference audit](RTX_REFERENCE_AUDIT.md)


## Adaptive normal filtering — 11 November 2026

The hardware block decoder now projects the primary camera footprint into UV space using the triangle's complete world-to-UV metric. Both triangles of a quad share UV bounds from all four corners. This handles rectangular and sheared mod faces without a filtering seam at the shared diagonal.

The normal atlas keeps its single-mip representation. A sub-texel footprint retains the point sample. Each unresolved UV axis gradually switches to manually computed bilinear weights, bounded inside the quad UV range. Individual taps are decoded into tangent normals before averaging. AO is interpolated as a scalar. The filter uses at most four normal reads. It adds a fourth-corner UV lookup for a shaded primary hit.

The shortened average normal contributes an estimate of unresolved variance to microfacet alpha squared. The normalized result supplies the shading normal, and the compensated perceptual roughness reaches both the BSDF and denoiser guides. A small numerical tolerance avoids broadening an otherwise uniform mirror from FP32 roundoff. This follows the normal-variation principle described by Toksvig, adapted here to the existing GGX roughness representation. [NVIDIA normal-map filtering paper](https://developer.download.nvidia.com/assets/gamedev/docs/Mipmapping_Normal_Maps.pdf)

Material metadata, metal classification, optical profiles, albedo and alpha retain their existing sampling. Analytic water bypasses normal-atlas filtering. Its wave filter now uses the camera-derived world footprint when available, so the footprint follows render resolution and FOV.

The primary cone grows through pane/cutout advances and is carried through delta continuations. Rough and diffuse continuation widening remains outside this change. Full distant albedo minification still requires a separate per-sprite mip or wider-footprint policy.

Evidence is L0 source inspection and independent review. Shader compilation, matching screenshots and GPU timings were not run under the current implementation-only instruction.
