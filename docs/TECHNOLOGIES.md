# Rendering technologies

[GPU recommendations](GPU_GUIDE.md) · [Downloads](RUNTIME_LIBRARIES.md) · [Every runtime DLL](DLL_REFERENCE.md) · [Feature evidence](FEATURE_MATRIX.md)

Updated **2026-11-13** for development `0.2.0-alpha`. Vendor availability and Bedray integration are separate requirements.
The published `0.1.0-alpha.1` is an earlier renderer; use its release notes.

## What each technology does

| Technology | Purpose | Current Bedray position |
| :--- | :--- | :--- |
| **DLSS Super Resolution / DLAA** | Reconstruct a higher-resolution image, or perform temporal anti-aliasing at native resolution. | Integrated, hardware and library dependent. DLAA and upscaling quality modes are distinct from the selected AI model. |
| **DLSS Ray Reconstruction** | Reconstruct traced lighting from noisy input signals. | Experimental integration; use the evidence for your renderer revision. It is a denoiser, not frame generation. |
| **DLSS Frame Generation / Multi Frame Generation** | Insert generated frames between rendered frames. | Streamline integration exists. Supported multipliers depend on the GPU, driver and Bedray path; current UI is not a promise of every multiplier offered by NVIDIA. |
| **NVIDIA Reflex** | Coordinate CPU/GPU timing to reduce latency. | Streamline integration; runtime support must initialise successfully. |
| **DLSS 5 Neural Rendering** | Apply a separate neural-rendering stage. | Experimental direct Vulkan path with restricted setup and pending RTX evaluation; see the [specific guide](experiments/dlss5.md). |
| **NRD REBLUR / ReLAX** | Denoise ray-traced signals without DLSS SR. | Experimental integration. Compare actual output and motion stability; file presence alone is not a successful denoiser run. |
| **FSR 2 / 3.1 upscaling** | Reconstruct a larger image from lower-resolution input and frame history. | Current FidelityFX 1.1.4 Vulkan integration. Its `amd_fidelityfx_vk.dll` is the reviewed AMD runtime in the existing bundle. |
| **FSR 4.1.2 upscaling** | AMD's newer ML-based upscaling. | Available in AMD FSR SDK 2.4 for Vulkan; **not integrated in Bedray**. Requires the newer loader/effect API, not a file rename. |
| **AMD FSR Frame Generation** | Generate intermediate frames. | Source work exists, but current documentation has no accepted live result for the new path. The newer SDK's FSR 4.0.2 implementation also needs integration. |
| **AMD FSR Ray Regeneration 1.2.1** | AMD's reconstruction of ray-traced lighting. | **Not integrated**; the reviewed public AMD SDK exposes it for DirectX 12, not Vulkan. |
| **XeSS Super Resolution** | Intel's image reconstruction, with supported cross-vendor paths. | Vulkan integration exists through `libxess.dll`; GPU requirements and current validation still apply. |
| **XeSS Frame Generation / Multi Frame Generation** | Intel's frame interpolation. | **Not integrated**; the reviewed public Intel API is DirectX 12. `libxess_fg.dll` alone does not add a Vulkan path. |
| **XeLL** | Intel's latency reduction, required by XeSS-FG. | **Not integrated** in Bedray. Intel also restricts standalone XeLL use on non-Intel GPUs. |
| **NVIDIA NRC** | Train and query a world-space neural cache of indirect lighting. | NVIDIA supplies a Vulkan SDK; **not integrated** in Bedray. It requires changes to the path tracer and device setup, plus its dependencies. |
| **AMD Radiance Caching** | Cache indirect lighting with AMD's SDK. | Vendor technical preview, **not integrated**; the reviewed public SDK lists DirectX 12 support only. |
| **Radiance Cascades / ReSTIR** | Reuse or cache lighting samples inside Bedray's own renderer. | Experimental renderer features. They are separate from NVIDIA NRC and do not become NRC by adding a vendor DLL. |
| **FSR 1 / NIS / Bilinear** | Built-in spatial scaling. | No additional vendor DLL required. NIS here means the integrated shader implementation. |
| **DirectSR** | Microsoft's super-resolution integration API. | Catalogue placeholder; no Bedray integration or general Vulkan support is claimed. |

For Bedray verification levels, use [the feature matrix](FEATURE_MATRIX.md). Older GLSL-era results do not automatically validate the Slang renderer.

## Vendor requirements worth checking first

AMD FSR SDK 2.4 separates a loader from signed effect DLLs. FSR 4.1.2 upscaling supports Vulkan on the AMD hardware listed by AMD;
ML frame generation has narrower hardware requirements. AMD Ray Regeneration and Radiance Caching remain DirectX 12 features in this SDK.
[AMD requirements and versions](https://gpuopen.com/amd-fsr-sdk/).

Intel's reviewed SDK provides Vulkan XeSS-SR, but its frame-generation and low-latency guides use a DirectX 12 swapchain.
XeSS-FG requires XeLL. On non-Intel GPUs, the reviewed guide limits interpolation to one generated frame per rendered frame.
[XeSS-SR guide](https://github.com/intel/xess/blob/v3.0.2/doc/xess_sr_developer_guide_english.md),
[XeSS-FG guide](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md),
[XeLL guide](https://github.com/intel/xess/blob/v3.0.2/doc/xell_developer_guide_english.md).

NRC is NVIDIA Neural Radiance Cache. It has Vulkan and DirectX 12 interfaces, uses training data from the path tracer,
and requires device features to be negotiated during initialisation. It is independent of the driver's on-disk shader cache.
[NVIDIA NRC integration guide](https://github.com/NVIDIA-RTX/RTXGI/blob/main/Docs/NrcGuide.md).

## Keep these choices separate

- **Upscaler and denoiser:** higher output resolution and cleaner traced lighting solve different problems.
- **SDK version, DLL version and AI model:** an updated file does not prove that a different model is active.
- **Rendered FPS and generated FPS:** interpolation changes presentation rate; compare base frame time and latency too.
- **Downloaded, installed and active:** each stage needs its own status. A feature is active only after its runtime has initialised and processed frames.

The [GPU guide](GPU_GUIDE.md) gives starting points, including NVIDIA's advice about heavier DLSS 4.5 models on RTX 20/30.
