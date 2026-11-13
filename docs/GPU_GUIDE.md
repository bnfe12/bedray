# GPU recommendations

[Technology overview](TECHNOLOGIES.md) · [Library downloads](RUNTIME_LIBRARIES.md) · [DLL reference](DLL_REFERENCE.md)

Updated **2026-11-13**. These are starting points, not Bedray performance guarantees.
Bedray hardware validation is limited; check [feature evidence](FEATURE_MATRIX.md) for your renderer revision.

## Start with your GPU

| Hardware | Starting point in current Bedray | What to keep in mind |
| :--- | :--- | :--- |
| **GeForce RTX 20 / 30** | Compare available DLSS SR modes with native resolution. Avoid forcing a heavier AI model before measuring its cost. | NVIDIA's DLSS 4.5 FAQ suggests Model K as a higher-FPS alternative. NVIDIA's current compatibility table does not support DLSS Frame Generation on these generations. |
| **GeForce RTX 40** | Compare DLSS SR quality modes; test denoising separately. | FP8 hardware reduces the relative cost of newer SR models. NVIDIA's multi-frame-generation support is not the same as its RTX 40 frame-generation support. |
| **GeForce RTX 50** | Compare DLSS SR / DLAA and the available denoisers, then evaluate supported frame-generation modes. | Check the multiplier actually accepted by Bedray and the runtime. Vendor announcements of newer modes do not establish Bedray support. |
| **Radeon RX 6000 / 7000** | Try FSR (AMD) with Auto on Adrenalin 26.10.1 or later. | The signed AMD FSR SDK 2.4 pair enables FSR 4.1.2 on supported GPUs. F3 shows which provider ran. |
| **Radeon RX 9000** | Try FSR (AMD) with Auto on Adrenalin 26.10.1 or later. | AMD's newer ML upscaling, ML frame generation and Ray Regeneration have different API requirements; Ray Regeneration is not available through the reviewed Vulkan SDK. |
| **Intel Arc** | Start by comparing XeSS-SR quality modes with native resolution. | Intel's SDK includes frame generation and XeLL, but the reviewed public interfaces for them are DirectX 12. They are not enabled by copying two DLLs into Bedray. |
| **Other GPUs** | Check Bedray's Vulkan and ray-query requirements first. Built-in spatial scaling needs no extra vendor runtime. | An upscaler does not add missing hardware ray-tracing capabilities. The software voxel provider remains experimental. |

The NVIDIA generation distinctions above follow its [current DLSS compatibility information](https://www.nvidia.com/en-us/geforce/technologies/dlss/).
AMD's current hardware, API and driver requirements are on the [FSR SDK page](https://gpuopen.com/amd-fsr-sdk/).
XeSS requirements are in Intel's [SR guide](https://github.com/intel/xess/blob/v3.0.2/doc/xess_sr_developer_guide_english.md)
and [FG guide](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md).

## NVIDIA's DLSS 4.5 advice, precisely

In its **14 January 2026** FAQ, NVIDIA explains that the heavier second-generation Super Resolution models use FP8 acceleration
available on RTX 40/50. RTX 20/30 incur a larger performance cost and may get higher FPS by keeping Model K from DLSS 4.0.
That is a trade-off recommendation, not a statement that DLSS 4.5 cannot run on older RTX cards.

The same FAQ recommends Model M primarily for Performance mode and Model L primarily for 4K Ultra Performance;
its January “Recommended” override uses Model K for the remaining SR modes.
These are **model** choices, not instructions to download a differently named DLL.
[NVIDIA staff FAQ](https://www.nvidia.com/en-us/geforce/forums/geforce-graphics-cards/5/580689/dlss-45-super-resolution-faq-updated-jan-14/).

Bedray does not promise an in-game K/M/L selector. Use only controls actually exposed by your build or a supported driver override.
Verify which model is active and compare with your current driver: the FAQ is dated advice, and model selection can change.
Ray Reconstruction and Neural Rendering have their own requirements; SR advice does not establish compatibility for those features.

## Compare settings without losing the useful evidence

The following is Bedray testing guidance, rather than a vendor benchmark:

1. Start with frame generation off. Record resolution, render scale, upscaler, denoiser and render distance.
2. Compare the same scene while moving and standing still. Look at fine edges, distant lights, ghosting and exposure changes.
3. Measure **rendered** frame time and VRAM as well as average FPS. Compare generated/presented FPS separately.
4. Change one setting at a time. A newer model can improve image stability while costing more GPU time.
5. Add frame generation only after the base frame rate and pacing are acceptable. Recheck input latency and UI artifacts.

Intel's XeSS-FG guide recommends approximately **60 rendered FPS** for its best latency experience; this is not a minimum FPS
guarantee for Bedray. It also requires XeLL and cautions against using another latency-reduction solution alongside XeSS-FG.
[Intel frame-generation recommendations](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md).

## Choosing libraries

Download files for the feature your build can use. Keep each SDK runtime set on one compatible release and prefer the set supplied
with the exact Bedray package. SDK version numbers are not a universal “newer is faster” ranking.
Use [the download guide](RUNTIME_LIBRARIES.md) for supported setup and [the DLL reference](DLL_REFERENCE.md) for each filename.
