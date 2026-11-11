# RT shader dataflow audit

11 November 2026. L0 source inspection of the native-scene Slang path.

| Stage | Source finding and implemented correction |
|---|---|
| GGX BRDF and PDF | Stable nonnegative denominator in shading/microfacet.slang preserves the finite FP32 smooth-surface peak. |
| Ideal mirror | BSDF evaluation, mixture PDF and sampling share the existing roughness <= 1e-3 delta threshold. The mirror samples one reflected direction with Fresnel divided by lobe selection probability; finite-angle evaluation and PDF retain only continuous lobes. |
| Material decode | LabPBR alpha is converted to perceptual roughness once for the shared BSDF, RR and NRD guides. |
| Shading normals | Normal maps stay in the viewed geometric hemisphere. The geometric direction rejection follows RC diffuse integration. Foliage backlighting retains its separate transport. |
| Primary motion | NRD reads the mean geometry/motion plane. SR receives a separate canonical primary record containing world displacement, hit distance and composition coverage. Native validation checks the full two-plane range. |
| Raster matching | The hardware-depth reprojection admits object displacement only after matching its primary distance. Missing previous topology is represented in the temporal masks. |
| Mask consumers | FSR receives separate R8 reactive and composition textures. The updated direct NGX bridge can bind R8 current-color bias to DLSS. |
| Resource lifetime | New buffers follow existing frame-resource retirement. Mask commands share particle-composite completion/discard ownership. Failed scene preparation has a typed pre-record budget recovery. |
| HDR and display | Path radiance stays linear through the existing denoiser stages. The SDR world image still reaches SR after tone mapping. A full HDR SR rearrangement remains pending. |

The motion record uses 16 bytes per render pixel in addition to the existing denoiser plane. Half-float distance is used only for the depth-match test, with a relative tolerance larger than half-float quantization. Coverage is normalized. The new shading math adds no rays or textures. The optional masks add two bytes per render pixel and one compute pass.

The audit scope covers the changed dataflow and its independent source review. Full shader numerical coverage, AOT output, SDK execution and image acceptance require matching build and runtime evidence. Reference inspection and remaining visual gaps are tracked in [Rendering reference audit](RTX_REFERENCE_AUDIT.md).


## Primary footprint and normal filtering

Ray and SurfaceHit gained shader-local footprint fields. makeRay and missHit initialize them. The primary diameter uses vertical FOV and render height. Hardware triangle conversion projects that diameter into UV space, retaining scale for sheared mappings. This changes no native descriptor, buffer layout or FFM structure.

Normal filtering is adaptive and bounded to the quad UV range, with decoded-vector interpolation and roughness compensation. Water wave filtering uses the same world footprint. The normal-filter path is scoped to primary and delta-continuation footprints. [Material filtering details](MATERIAL_AND_TEXTURE_SET_AUDIT.md)

## Ideal-mirror consistency

Previously the specular sampler flagged roughness <= 1e-3 as delta while still sampling a finite GGX distribution with an alpha floor. Direct-light evaluation and the mixture PDF also evaluated that finite peak. The path tracer uses the delta flag to admit celestial discs on continuation, so the three operations represented different lobes.

The shared BSDF now samples that threshold as an ideal reflection, reports the existing delta PDF sentinel of 1, and excludes its specular term from finite-angle evaluation/PDF. Smooth dielectrics retain their diffuse term and mixture probability. Rough GGX, optical transmission and the legacy view-angle diffuse Fresnel factor retain their formulas. This follows the distinction between [sampled delta distributions and continuous BSDF densities in pbrt](https://www.pbr-book.org/4ed/Reflection_Models/BSDF_Representation).

Consumers reviewed: path-tracer NEE, continuation and reverse-density evaluation, plus radiance-cache direct shading. ReSTIR PT continues to own only primary diffuse continuations with a certified rough diffuse first scattered vertex. The change adds no rays, resources or native ABI fields, and avoids VNDF sampling for ideal reflection. Independent source review found no remaining blocker. This is L0 source evidence; neither shader compilation nor runtime image or frame-time validation was performed in this pass by user instruction. Non-delta glossy emission/NEE weighting remains a separate transport audit item.
