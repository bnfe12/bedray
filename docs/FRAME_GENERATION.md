# Frame generation and Reflex

For Intel XeSS Frame Generation / XeLL, newer AMD FSR frame generation and their current API limits, see
[technology support](TECHNOLOGIES.md) and [GPU recommendations](GPU_GUIDE.md). The [DLL reference](DLL_REFERENCE.md)
lists the files individually; downloading them does not add an unimplemented frame-generation path.

> **Earlier developer report, on one NVIDIA GeForce RTX 5060 Ti (8 GB).** The developer reported 2x / 3x / 4x frame generation and
> Reflex working on that card (the status line shows "Active" only when the presented frame rate really is the multiplier times the
> rendered one for 30 consecutive frames). The repository does not contain a recorded benchmark, and no other GPU has been tried.
> Please report your results ([TESTING.md](TESTING.md)).

The newer [single RR + FG integration](ai-agent-addenda/RR_FRAME_GENERATION.md)
has source/CPU contract checks and separate NVIDIA/AMD camera conventions. It
has not yet received RTX live image/FPS acceptance; the earlier developer report
above does not validate this new path.

## What it is

NVIDIA DLSS frame generation (DLSS-G) inserts generated frames between rendered frames, so the displayed frame rate is higher than the
rendered one. It is done through NVIDIA **Streamline**. Reflex lowers latency.

* 2x: one generated frame per rendered frame. 3x and 4x (multi frame generation) are offered only if the driver reports support
  (RTX 50 series).
* AMD FSR 3 frame generation is a second implementation (see "AMD FSR 3 frame generation" below, 2x only).
* The F3 screen shows the **real** (rendered) and the **generated** (presented) frame rate side by side.

## Requirements

* NVIDIA RTX 40 or 50 series GPU, a current driver, Windows with hardware-accelerated GPU scheduling.
* A temporal upscaler selected (DLSS, FSR or XeSS), or initialized native-resolution RR with current depth/motion guides.
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

Bedray contains AMD frame-generation source and configuration paths. The new renderer/presentation integration has no accepted
live frame-generation result recorded in this guide. Earlier BETA descriptions do not establish that this path works in your build.

The current FSR upscaler uses FidelityFX SDK 1.1.4. Its DLL being installed does not prove that frame generation is available:
the required SDK entrypoints, presentation integration and runtime checks must also succeed. The newer AMD FSR SDK 2.4 has a separate
loader/effect API with Vulkan frame generation; that path still requires Bedray integration.

See [technology support](TECHNOLOGIES.md), [the exact DLL reference](DLL_REFERENCE.md) and [feature evidence](FEATURE_MATRIX.md).

## Intel XeSS Frame Generation and XeLL

Intel provides XeSS-FG and XeLL in SDK 3.0.2 as `libxess_fg.dll` and `libxell.dll`. The reviewed public integration uses DirectX 12,
and XeSS-FG requires XeLL. Bedray's Vulkan renderer does not implement that path. Its catalogue slots do not establish support.
See [Intel's integration requirements](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md).
