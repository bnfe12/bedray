# Upscalers

An upscaler lets the game draw the 3D world at a lower resolution and bring it up to the window size. The GUI always stays at full
resolution. Choose it under **Video Settings, Bedray ALPHA: Upscaling, Upscaler**. Evidence levels are explained in
[FEATURE_MATRIX.md](FEATURE_MATRIX.md). Image quality has **not** been compared systematically in this alpha.

| Upscaler | Needs | GPU | Kind | Status |
|---|---|---|---|---|
| Bilinear (built-in) | nothing | any | simple stretch, no reconstruction | BETA |
| FSR 1 (built-in) | nothing | any | spatial (one frame), AMD EASU + optional RCAS | EXPERIMENTAL |
| NIS (built-in) | nothing | any | spatial, NVIDIA Image Scaling | EXPERIMENTAL |
| FSR Upscaling 4.1.2 (AMD) | signed SDK 2.4 Vulkan upscaler DLL | supported Radeon GPU and AMD driver | ML temporal | source implementation |
| FSR 3.1 / 2.x (AMD) | official `amd_fidelityfx_vk.dll` | any Vulkan GPU in theory; only tested on NVIDIA | temporal | BETA |
| XeSS (Intel) | `libxess.dll` | any in theory; only tested on NVIDIA | temporal | BETA |
| DLSS Super Resolution (NVIDIA) | `nvngx_dlss.dll` + `vkb_dlss.dll` | NVIDIA RTX | temporal | BETA |
| DLSS Ray Reconstruction (denoiser) | `nvngx_dlssd.dll` and the DLSS files | NVIDIA RTX | AI denoiser for Path Tracing | EXPERIMENTAL |

Temporal upscalers use previous frames, motion vectors and camera jitter, so they look better than spatial ones but can ghost or
smear. Spatial ones (Bilinear, FSR 1, NIS) do not.

## Which one should I use?

* **NVIDIA RTX, you installed the DLSS files:** DLSS (Quality or Balanced). Use DLAA if you only want anti-aliasing at full size.
* **Other GPUs, or no DLSS files:** FSR 3.1 (needs the AMD DLL) or XeSS (needs the Intel DLL). Without any download: FSR 1 or NIS.
* **No files at all and you only need more FPS:** Bilinear with 3D Scale, or FSR 1.

With ray tracing, render the world smaller: tracing cost follows the number of pixels. A temporal upscaler plus a denoiser is the
intended combination.

## Quality modes

| Mode | Render scale (each axis) |
|---|---|
| Native AA / DLAA | 100% |
| Quality | about 67% (FSR: 1.5x; DLSS and XeSS use the library's value) |
| Balanced | about 59% (FSR: 1.7x) |
| Performance | 50% (FSR: 2.0x) |
| Ultra Performance | about 33% (FSR: 3.0x) |
| Custom | any value from the slider |

The exact input size is returned by the vendor library; the page shows ratios from the vendor documentation. After changing the
mode the new size takes effect on the next frame. If the page says **restart required**, the Vulkan device was created without the
extensions that backend needs (for example the library appeared after the game started); restart the game.

## Notes

* Texture detail: "Auto Texture LOD Bias" (Page 1) sharpens textures when the render scale is below 100%.
* **FSR Version** is beside the FSR quality mode. Auto prefers FSR 4 when the SDK reports it for the enabled Vulkan device, then FSR 3.1 or FSR 2. An explicit FSR 4 request keeps the selection and reports a missing runtime, a required restart or unsupported hardware if it cannot run.
* **Custom** exposes separate saved input scales for FSR, DLSS and XeSS. The provider can constrain the actual input size. The FSR settings panel shows the provider name and actual input/output dimensions after context creation.
* **DLSS 5** is a separate neural rendering effect. [NVIDIA's November 9 developer response](https://forums.developer.nvidia.com/t/official-dlss-5-neural-rendering-plugin-access-for-unreal-engine-5-8-2/382731) says the SDK is partner-only. Bedray's DLSS Super Resolution and Ray Reconstruction retain their own settings.
* A backend that is not usable is rejected with a log line, never silently replaced: look for `upscaler=rejected` in `latest.log`.
* Frame generation is separate: [FRAME_GENERATION.md](FRAME_GENERATION.md). With it on, DLSS runs through Streamline plugins (see that page).
