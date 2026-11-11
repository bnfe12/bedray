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

## Stationary lighting and shadow contracts

The owner reports that edges, shadows and reflected light wobble only while the camera stands still, and that dark mangrove shadows briefly turn gray. The supplied stills establish visible spatial artifacts; they cannot establish their temporal mechanism. The following corrections are based on source contracts rather than a claimed reproduction.

| Producer / consumer | Confirmed mismatch and correction | Scope / cost |
|---|---|---|
| Slang primary / DLSS RR | Rays use `pixel + 0.5 + J`. RR previously forwarded `(+J.x, -J.y)` to NGX; its X default now becomes negative, so the SDK receives `-J` on both axes. The raw positive sample offset still feeds motion reconstruction. | No new rays or resources. Explicit diagnostic sign overrides remain available. Direct NGX and RR Streamline constants share the SDK offset. SR's raster convention is unchanged. |
| Slang primary / NRD | The host re-derived jitter from the global upscaler and reported zero for the fallback Halton sequence. It now uses the dispatched `guideFrame().jitter`. NRD stores previous jitter alongside its own previous pose after successful recording; reset/discard invalidates both through `havePrev`. | No new GPU work. This preserves the existing recording/discard ownership; recording is not evidence of submission or completion. |
| Foliage receiver / sun sample | Passing frame zero did not freeze the disc sample because `sampleWord` still contained the absolute sample index. A shader-local `sampleStratum = s` now selects the fixed disc point on a foliage receiver. Other streams retain their absolute frame/sample inputs. | No new rays or ABI fields. This stabilizes illumination received by leaves/cards; it does not freeze shadows cast by foliage onto an ordinary floor receiver. |
| Finite emitter / visibility segment | Normal bias and optional pixel-shadow snapping displaced the origin while retaining the original light direction/distance. The segment now targets the same already end-biased `LightSample.position` from the actual biased origin. | No extra visibility walks. A degenerate snapped segment keeps the original receiver ray. Directional lights, refracted beams and the BSDF/PDF evaluation retain their paths. |
| Primary key-light selection | The indoor 1/8 roulette yielded seven absent attempts and a rare contribution divided by 1/8, without a key-light reservoir. Primary receivers now bypass that roulette; secondary indoor receivers retain it. | Expected illumination is preserved, but primary key visibility work increases: at 1 SPP with separate DI, up to 0.875 additional key visibility walks per indoor primary pixel. GPU time is unmeasured. This is a stability/cost tradeoff, not an FPS improvement claim. |
| DI temporal receiver validation | Plane membership used the mapped shading normal, so tangential jitter across one flat normal-mapped wall could exceed its plane tolerance. A shader-local oriented geometric normal now serves the plane gate; shading-normal compatibility and p-hat remain unchanged. | The GPU reservoir remains 64 bytes. The spatial stage still has only a stored shading normal and needs a separate geometric metadata design. |

The RR sign conversion follows the same sample-position convention in NVIDIA's [Vulkan path-tracing producer](https://github.com/nvpro-samples/vk_gltf_renderer/blob/main/shaders/gltf_pathtrace.slang) and [NGX evaluation wrapper](https://github.com/nvpro-samples/vk_gltf_renderer/blob/main/src/dlss_wrapper.cpp): positive ray sampling offset becomes negative SDK de-jitter offset. Bedray's FFM writer, direct native NGX evaluator and Streamline constant packer forward the supplied SDK values without another negation. The previous source comment described a November 7 comparison where `(+1,-1)` improved a historical scene over `(+1,+1)`; that observation is historical and does not validate the current complete producer/consumer contract. A current matching stationary capture is still required.

Remaining source/runtime work: spatial DI geometric metadata, RR geometric-normal preference (currently not consumed by the primary producer), NRD multi-sample geometry coherence, and glossy emitter MIS. Switching all RR guides to geometric normals is deferred because it would also replace water wave and smooth entity shading normals without proving the reported cause. No source finding here establishes that all colored floor patches or wall artifacts have been eliminated.
