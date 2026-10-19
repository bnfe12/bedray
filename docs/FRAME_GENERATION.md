# Frame generation and Reflex

> **Status: BETA, tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB).** The developer reports 2x / 3x / 4x frame generation and
> Reflex working on that card (the status line shows "Active" only when the presented frame rate really is the multiplier times the
> rendered one for 30 consecutive frames). The repository does not contain a recorded benchmark, and no other GPU has been tried.
> Please report your results ([TESTING.md](TESTING.md)).

## What it is

NVIDIA DLSS frame generation (DLSS-G) inserts generated frames between rendered frames, so the displayed frame rate is higher than the
rendered one. It is done through NVIDIA **Streamline**. Reflex lowers latency.

* 2x: one generated frame per rendered frame. 3x and 4x (multi frame generation) are offered only if the driver reports support
  (RTX 50 series).
* AMD FSR 3 frame generation is a second implementation (see "AMD FSR 3 frame generation" below, 2x only).
* The F3 screen shows the **real** (rendered) and the **generated** (presented) frame rate side by side.

## Requirements

* NVIDIA RTX 40 or 50 series GPU, a current driver, Windows with hardware-accelerated GPU scheduling.
* A temporal upscaler selected (DLSS, FSR or XeSS).
* Streamline files (not included) in `bedray/natives/streamline/` inside the game folder (`<instance>/minecraft/bedray/natives/streamline/`):
  `sl.interposer.dll`, `sl.common.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `sl.pcl.dll`, `nvngx_dlssg.dll`, and `vkb_sl.dll`, which you
  build from `native/sl_bridge` ([BUILDING.md](BUILDING.md)). Use a Streamline 2.14.x release. The old folder
  `vulkan_rtx/natives/streamline/` is still read.
* **DLSS Super Resolution and Ray Reconstruction next to frame generation:** Streamline then owns NVIDIA's NGX library in the game
  process, so DLSS Super Resolution and Ray Reconstruction run through Streamline's own plugins (`sl.dlss.dll`, `sl.dlss_d.dll` and the
  matching `nvngx_dlss.dll`, `nvngx_dlssd.dll` from the same Streamline release, placed in the same folder) instead of the separate
  `vkb_dlss.dll`. `vkb_sl.dll` provides that path.
* **Restart the game once after adding the files.** Streamline must be loaded when the game starts, because it hooks the Vulkan loader.

## Using it

1. Install the files, restart the game.
2. Video Settings, Bedray ALPHA: Upscaling, **Frame Generation** group. Choose Off / 2x / 3x / 4x; choose the Reflex mode and an optional
   presented-FPS cap.
3. The **status line** tells the truth: `Active 2x: <rendered> rendered / <presented> presented fps`, `Inactive: ...` with a reason,
   `Unsupported: ...`, or `Restart required`. The F3 overview shows rendered and generated FPS.

Frame generation switches itself off in menus, while loading and when the window is not focused.

## If it does not work

| Symptom | Likely cause |
|---|---|
| The mode control is greyed out, status "Unavailable" | Streamline files missing, or a restart is needed |
| Log: `streamline_not_found` / `shim_not_found` / a "missing files" status | a required file is not in `bedray/natives/streamline/` (the status names the missing files) |
| Status "Unsupported" | GPU, driver or Windows setting does not support it; the status line gives the reason |
| 3x / 4x missing | the driver reports no multi frame support |
| Image looks wrong or the game crashes | turn frame generation off, keep the log, report it |

Not tested on any GPU other than the developer's NVIDIA card.


## AMD FSR 3 frame generation

> **Status: BETA. Tested only on an NVIDIA GeForce RTX 5060 Ti (forced with `framegen_provider = "AMD"`). Not tested on AMD or Intel
> hardware. AMD and Intel testers wanted, please report ([TESTING.md](TESTING.md)).**

FSR 3 frame generation (FidelityFX SDK v1.1.4, ffx-api) runs on compute shaders and works on any Vulkan GPU in principle. It produces one
generated frame per rendered frame (**2x only**) and does not use Reflex (that is NVIDIA only).

* **Method** (Video Settings, Bedray, Frame Generation group): Auto / NVIDIA DLSS-G / AMD FSR 3. Auto uses DLSS-G when the NVIDIA driver and the
  Streamline files are present, otherwise FSR 3. Config key `framegen_provider` (`AUTO`, `NVIDIA`, `AMD`) in `config/bedray.toml`, JVM property
  `-Dvkrtx.framegen.provider=amd`. Both methods replace the swapchain, so changing the method needs a **game restart**; once FSR 3 is the
  method of the session, Off and 2x switch live. If frame generation is Off at startup, selecting it needs a restart too.
* **Files**: only `amd_fidelityfx_vk.dll` of the official FidelityFX SDK v1.1.4 (the same file as FSR upscaling), in `bedray/natives/fsr/`
  (the old flat `bedray/natives/` and `vulkan_rtx/natives/` are still read). It is not included in the repository or the jar.
* **Requirements**: a temporal upscaler selected (FSR, DLSS or XeSS), because frame generation needs depth and motion vectors; a GPU whose
  graphics queue family has at least 3 queues (the mod asks the driver for two more queues at device creation: the interpolation swapchain
  needs a present queue and an image acquire queue besides the game queue; NVIDIA reports 16, some AMD GPUs report 1 and then the status says
  `no_spare_queue`).
* **Status line** says Active only when the real swapchain presented about twice as many frames as were rendered (`getLastPresentCountFFXAPI`
  vs. rendered frames over 30 frame windows). Otherwise: `Not generating`, `Inactive` with the reason, `Failed`, `Restart required`.
* It switches off in menus and while loading; the UI is kept clean by passing the HUD-less picture to FSR 3.
* How it works and the contract with the SDK: [framegen/FSR3_FINDINGS.md](framegen/FSR3_FINDINGS.md).
