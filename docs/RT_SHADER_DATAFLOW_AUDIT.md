# RT shader dataflow audit

11 November 2026. L0 source inspection of the native-scene Slang path.

| Stage | Source finding and implemented correction |
|---|---|
| GGX BRDF and PDF | Stable nonnegative denominator in shading/microfacet.slang preserves the finite FP32 smooth-surface peak. |
| Material decode | LabPBR alpha is converted to perceptual roughness once for the shared BSDF, RR and NRD guides. |
| Shading normals | Normal maps stay in the viewed geometric hemisphere. The geometric direction rejection follows RC diffuse integration. Foliage backlighting retains its separate transport. |
| Primary motion | NRD reads the mean geometry/motion plane. SR receives a separate canonical primary record containing world displacement, hit distance and composition coverage. Native validation checks the full two-plane range. |
| Raster matching | The hardware-depth reprojection admits object displacement only after matching its primary distance. Missing previous topology is represented in the temporal masks. |
| Mask consumers | FSR receives separate R8 reactive and composition textures. The updated direct NGX bridge can bind R8 current-color bias to DLSS. |
| Resource lifetime | New buffers follow existing frame-resource retirement. Mask commands share particle-composite completion/discard ownership. Failed scene preparation has a typed pre-record budget recovery. |
| HDR and display | Path radiance stays linear through the existing denoiser stages. The SDR world image still reaches SR after tone mapping. A full HDR SR rearrangement remains pending. |

The motion record uses 16 bytes per render pixel in addition to the existing denoiser plane. Half-float distance is used only for the depth-match test, with a relative tolerance larger than half-float quantization. Coverage is normalized. The new shading math adds no rays or textures. The optional masks add two bytes per render pixel and one compute pass.

The audit scope covers the changed dataflow and its independent source review. Full shader numerical coverage, AOT output, SDK execution and image acceptance require matching build and runtime evidence. Reference inspection and remaining visual gaps are tracked in [Rendering reference audit](RTX_REFERENCE_AUDIT.md).
