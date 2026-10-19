# User guide

Bedray is an **alpha**. It was tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42. Read
[KNOWN_ISSUES.md](KNOWN_ISSUES.md) before you judge the image.

## First start

1. Install as in [INSTALL.md](INSTALL.md). Start the game with the Vulkan backend (Bedray sets it in `options.txt`).
2. Open **Options, Video Settings**. Find **Bedray ALPHA** with three pages: Upscaling, Ray Tracing, Advanced and Experimental.
3. Press **F3**: Bedray adds sections (overview, load, quality, libraries) with the device, the upscaler and its state, the render
   resolution, ray tracing state, the real and the generated (frame generation) FPS, and which libraries were found. Two more sections
   (timing, diagnostics) are off by default; switch them on in the F3+F6 screen. This is the quickest way to see what is really active.

## Turn ray tracing on

1. Page **Ray Tracing**, option **Tracing**: choose *Path Tracing (recommended)*: full light transport, several bounces, best picture, noisier while moving.
   (*Ray Tracing (Fast)* is in development and hidden in this release.) If the option only offers "Off", the GPU does not report ray query support.
2. There are no quality presets in the alpha (presets are planned): Path Tracing is one mode with tested default values. Fine tuning is in the *Advanced* and *Expert* sections of the page.
3. Optional: **Light shafts** (volumetric light of the sun and moon), bloom, sun glare and distance haze are in the same group.
4. Wait a moment: the first time the shaders are compiled (a few seconds) and the geometry of loaded chunks is built.

Tips

* Ray tracing covers the render distance of the game; lower the render distance to save memory and FPS.
* Use an upscaler (Page Upscaling) to render the world smaller. This matters more than any single RT switch.
* Path Tracing is noisy while the camera moves. The denoiser (NRD, DLSS Ray Reconstruction) or accumulation reduces this;
  without the libraries only accumulation is used.
* Sun and moon: nights are dark with only moon light and torches. This is the traced result, not a bug.

## Modes

| Mode | What you get |
|---|---|
| Off | normal rendering |
| Path Tracing (recommended) | complete light transport with several bounces, global illumination |

*Ray Tracing (Fast)* (shadows, AO, reflections, block light) is in development and hidden in this release.
| Direct | direct light and shadows only |

## Resource packs and materials

* LabPBR `_s` (specular) and `_n` (normal) maps are read.
* Vanilla textures get automatic roughness, metal and emission by rules, and automatic normals (switchable).
* Animated textures bake only their first frame into the material atlas. Fire and lava can therefore mismatch their glow.

## What is traced, what is not

Blocks and their chunk geometry are traced. Entities, items and particles are only partly supported: see
[FEATURE_MATRIX.md](FEATURE_MATRIX.md). Water and glass use a thin-surface approximation (reflection and tint, no real refraction).

## Hotkeys

O toggles ray tracing, I switches Off / Path Tracing, U cycles the denoiser, Y toggles voxel sharpness, K toggles the debug overlay.
They can be changed under Controls, Key Binds.

## Problems

[TROUBLESHOOTING.md](TROUBLESHOOTING.md), [FAQ.md](FAQ.md). To help the project: [TESTING.md](TESTING.md).
