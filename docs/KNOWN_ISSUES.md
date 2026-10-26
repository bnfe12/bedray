# Known issues

Current `main` is the unreleased 2026-10-26 source integration. No build, tests, shader compilation or game session was run
for it. The platform evidence below belongs to earlier alpha builds; see [FEATURE_MATRIX](FEATURE_MATRIX.md).

Bedray is an alpha. **Tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42.** Everything below may differ, or
more may be wrong, on other GPUs, drivers and systems (AMD Radeon and Intel Arc have not been checked at all).

## Setup

* The release does not include NVIDIA or Streamline vendor DLLs. DLSS, NRD and frame generation need additional vendor files and
  the small Bedray wrappers (`vkb_dlss.dll`, `vkb_nrd.dll`, `vkb_sl.dll`) built from `native/`. See [INSTALL.md](INSTALL.md).
* Sodium 0.9.x only (tested with 0.9.2). Other Sodium versions are untested.
* Windows only.

## Image

* Path Tracing is **EXPERIMENTAL**. "Ray Tracing (Fast)" is in development and hidden in this release (`experimental_rays` in bedray.toml unlocks it for developers; unsupported). Expect noise, flicker, ghosting while moving, and wrong lighting in some places.
* Redstone dust can show a blocky gray fringe from alpha coverage in the traced image; the fix is not yet confirmed in a final live run.
* Reflections stay noisy with the NRD denoiser (no specular denoiser input yet).
* Animated textures (fire, lava, portals) bake only their first frame into the materials, so glow and roughness can mismatch.
* Particles do not (fully) take part in ray tracing: they may cast no shadow and be missing in reflections. Entities, items and the
  hand are only partly supported.
* Glass is a thin reflection/tint filter without physical closed-volume refraction. Realistic water uses approximate Snell
  refraction and medium segments; caustics are analytic patterns rather than a full caustic light-transport solver.
* Camera and secondary transmission retain at most 16 sorted interface candidates. An unresolved deeper path suppresses
  transmitted light instead of substituting sky, so very deep stacks can darken. Sorting/storage cost and visual correctness
  need a matching-build GPU run; no FPS improvement has been measured for this integration.
* Temporal upscalers can ghost or smear. Image quality was not compared systematically between upscalers.
* The first seconds after entering a world can be slow or look incomplete while geometry is built.

## Performance and memory

* Optical profiles use 31 distinct immutable palette slots plus legacy. New property sets after palette exhaustion retain
  legacy optics with a warning until atlas recreation/restart. Blocks/panes sharing a sprite share its authored thickness.
* Clear glass now retains alpha-zero dielectric candidates; optical sprites cannot use fixed alpha opacity-micromap culling.
  Reflection/absorption quality and the net performance cost of this change have not been evaluated.
* Extra glass-face filtering is conservative and section-local. It may remove nothing when Sodium already culled the faces
  or the matching companion-face proof is absent. Its toggle affects newly captured sections; reload to rebuild all sections.
* Transparency diagnostics count instrumented operations and use delayed frame-slot readback. They do not measure separate
  transparent GPU milliseconds, and enabling their atomics adds overhead. See [TRANSPARENT_MATERIALS](TRANSPARENT_MATERIALS.md).

* Ray and especially path tracing are very expensive. On 8 GB of VRAM use a render distance around 12 chunks or less.
* "RT Memory Budget" limits the memory for chunk geometry (default 2048 MB); far chunks are dropped when it is full.
* Changing render scale or upscaler during play was exercised by automatic tests only for part of the combinations. If you see
  corruption after a switch, restart the game and report it.

## Features

* Frame generation (BETA) was run only on one RTX 5060 Ti; there is no benchmark table, and other GPUs are untested ([FRAME_GENERATION.md](FRAME_GENERATION.md)). It needs a restart after the Streamline files are added.
* DLSS Ray Reconstruction was checked by the author on one machine only.
* DLSS 5 / neural rendering, AMD frame generation, FSR 4 and general Iris / Dirt RT shader-pack compatibility are not implemented. The [experimental post subset](IRIS_VULKAN.md) is source-only and unverified in-game.
* **Fabric:** the jar builds and its metadata is checked, but there is much less in-game evidence than for NeoForge.
* Some mods that replace the renderer or draw with OpenGL may conflict. Test on a clean instance first.

## Settings

* Some options show "restart required" because the Vulkan device is created before the page is used.
* Settings from a pre-release build (`config/vulkan_rtx.toml`) are copied to `config/bedray.toml` once.

Report new problems with the forms described in [TESTING.md](TESTING.md).
