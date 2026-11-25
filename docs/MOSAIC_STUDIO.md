---
title: Mosaic Studio
---

# Mosaic Studio

Mosaic Studio is the node editor of Bedray: a desktop app for shaders and materials, with a matching editor inside
the game. The **mod** part lives inside Bedray (it runs the shaders and loads the materials). The **app** runs next
to Minecraft, or without it.

> Status: in development on the `mosaic-studio` branch. Mosaic graphs run in the game (2026-11-25: multi-pass bloom,
> temporal smoothing, voxel sun shadows and screen AO on raster, checked by the autotest on an integrated GPU). Still to
> check on an RTX PC: the ray-traced renderer with Mosaic, result frames, material reload.

## What it does

| Area | What you get |
|---|---|
| **Shaders** | Node graphs for the custom ray-tracing stage: Blender-style math, colour, textures and noise, ray and world nodes, a library of effects, your own nodes in code. Errors land on the node that caused them. Compiled through Slang (in the studio or in the game) with GLSL as the fallback. |
| **Materials** | A Principled BSDF graph per block, item or entity texture, baked into LabPBR `_s` / `_n` maps and the albedo of a resource pack that the game reloads by itself. |
| **Texturing** | The real block model in the preview (fences, stairs, plants...), UV layout with seams, pixel painting (brush, eraser, fill, picker) on the UV view or straight on the model. |
| **Library** | Textures of Minecraft (26.3 first), resource packs and mods of every game instance found in the official launcher, Prism / MultiMC, CurseForge, Modrinth, ATLauncher and GDLauncher. |
| **Shader browser** | Every shader pack of the game folder (Mosaic graphs and Iris packs) with its last frame; open a graph in a tab, choose an Iris pack for the game, new pack from a template, import / export zips, export a screen-space graph as an Iris pack. |
| **Shader pack building blocks** | Buffer passes at 1/2..1/8 with Blur / Kawase reads (bloom mip chains), Reproject + Previous frame (temporal smoothing, motion blur), weather and sky inputs, voxel sun shadows, screen AO and light shafts that also work on raster (the game's depth becomes the ray distance). Far shadows over a 256 x 256 height field, Temporal AA with neighbourhood clamp, Repeat (a loop as a node over one of your functions). Templates: "Bloom chain (mip)", "Temporal smoothing", "Shader pack look (no RT)". |
| **Result** | The frame of the running game before and after the shader, side by side, split, or against an earlier step; record GIF / MP4, save frames. |
| **Workflow** | Document tabs, Blender-like areas you can split, pop out and dock back, rebindable keys (also on the Russian layout), themes including OLED black, named undo history per tab. |

## Every number is yours

Built-in nodes take every number that changes the picture as an input with a default (distances such as where far
shadows take over, colours, biases, strengths, speeds), so a value is never decided inside a node. The numbers under
all nodes - sky model, sun path tilt, ray lengths, march limits, material colours - are **Graph settings**: click empty
canvas, edit them in Properties; they are saved with the graph.

## Preview world

The Preview renders the open graph over a **real scene**, not a picture: the blocks around the player when the game
opened Studio (J), or a built-in landscape (hills, a lake, a forest, a stone house with glass and torches, lava) until
then. A G-buffer pass draws the blocks with Minecraft's textures and light map and gives the graph the same inputs as
the game: scene colour, ray distance (so position, normal, material and light nodes work), the 96 x 48 x 96 world
volume, the camera and the previous camera (Accumulate, TAA). Drag to look around, the wheel or W A S D / Space /
Shift to fly, the Time slider for the time of day. Graphs that trace rays (Trace / Shadow ray nodes, RT functions) run
in the preview with their rays walking the world volume; in the game they use the hardware rays.

File contract: when J opens Studio the game writes `<game>/bedray-studio/preview-world.bin` (little endian: int magic
`0x3157534D`, int size x / y / z, int origin x / y / z (absolute blocks), double camera x / y / z, float yaw, pitch
(Minecraft degrees), time of day (0 noon, 0.25 sunset); cells from byte 64, one 16-bit cell per block, x fastest, then z,
then y, cell = material | blockLight << 8 | skyLight << 12). "Reload world" reads it again.

## A renderer of your own

The main workspace is the **Render graph**: the frame as nodes. Bedray's own renderer is one ready-made node of it,
**Bedray render** (double-click: its stages and settings in the **Bedray stages** workspace); wire your passes after it
(template "Bedray render + your passes"), or build the whole renderer yourself:

"+" > **New renderer of your own** opens a whole renderer made of nodes, which runs in the game's custom RT stage on any
GPU (voxel rays through the world volume, no ray tracing hardware needed):

- G-buffer: Albedo (from the game image), Hit position, Surface normal, Sky light, Block light.
- Lights (category *Render pipeline*): Sun & moon light with voxel and far shadows, Block light, Sky ambient, Emission,
  Diffuse GI (voxel paths), Reflection (voxels).
- Passes: Buffer (1/1..1/8), **Accumulate** (temporal accumulation with reprojection and disocclusion reset),
  **Buffer (last frame)** (feedback), **Denoise (a-trous)** steps, Temporal AA.
- Combine (shade), **Tone map** (ACES, Reinhard, Hable, exponential, clip).

A third variant, "Renderer from scratch (hardware RT)", uses Sun & moon light (RT) and Diffuse GI (RT): shadow and GI
rays through the real scene geometry on GPUs with hardware ray tracing. Reflective surface (water) adds Fresnel
reflections with waves on one material.

The GI variant traces one path per pixel at 1/2 size, accumulates it over frames and denoises it in three steps; the
fast variant uses sky ambient instead. Every node is an ordinary node: open its code, change it, or rewire the frame.

## Bedray as nodes

- Bedray stages workspace (the inside of the Bedray render node): the frame as a chain of stages (one render mode: Raster, Path tracer or Hybrid ray effects).
  Double-click a stage: its public parameters and every module and function it is made of.
- Every function of Bedray's Slang pack opens as nodes ("+" > Bedray function as nodes). Change it, slangc checks it
  inside its module, **Apply to Bedray** rebuilds the passes that use it for the game.
- "Your post stage": a stage of your own, built from nodes, run in Bedray's frame after its looks.
- Help > Config: every public number of the renderer in one place; Studio's numbers win over the game's menu while
  Live is on. Help > Guide explains the editor step by step.

## AI agents

An AI agent (or you, in a terminal) can do everything through the `mosaic` command: list node types, read a graph,
add / connect / set / mute / delete nodes, arrange, compile with errors on nodes, bake materials and change the
studio's design. `--json` gives machine-readable output.

    mosaic new shader.json Cinematic
    mosaic add shader.json noise n1
    mosaic connect shader.json n1 mix.fac
    mosaic compile shader.json
    mosaic set-setting theme OLED

A running studio takes every change in as one undoable step ("External edit"). Access is **off** until you turn it on
in **Settings > Agent**, with separate switches for graphs, materials, design and compiling; agents cannot change
those switches. Every call is logged to `~/.bedray/agent-log.txt`. **Settings > Agent > Create the mosaic command**
writes `mosaic.cmd` / `mosaic` into `~/.bedray/bin`.

## Files it writes

- `~/.bedray/studio/functions/*.mosaic.json`: your graphs of Bedray functions; `~/.bedray/studio/stages/`: stage insides.
- `<game>/bedray-studio/slang`, `<game>/bedray-studio/passes`: modules and passes rebuilt by Apply to Bedray (delete them,
  or "Remove Studio passes", and Bedray runs its own).


* `~/.bedray/mosaic-studio.json` – studio settings, layouts and open tabs.
* `<shader pack>/shaders/bedray/mosaic.json` – a shader graph; `mosaic.spv` and `mosaic.spv.json` next to it when the studio compiles Slang.
* `<shader pack>/shaders/bedray/.studio/` – per-project history, view and compile cache; the game writes result frames and status here.
* `<game>/resourcepacks/Mosaic Materials/` – materials (`<texture>.mosaic.json`, baked `_s` / `_n` maps, painted colours, imported images).
* `<game>/bedray-studio/` – the reload request for materials and the game's answer.

Without a chosen game folder, materials stay in `~/.bedray/studio/`.

## Privacy

Mosaic Studio makes no network requests and sends nothing anywhere. It reads launcher folders on your computer to
list game instances and textures, and only reads them. Details: [Privacy](../PRIVACY.md#mosaic-studio).

## More

* [Mosaic editor (node reference and format)](MOSAIC_EDITOR.md)
* [Custom RT shaders (the stage the graphs compile to)](CUSTOM_RT_SHADERS.md)
* [Surface material overrides](SURFACE_MATERIAL_OVERRIDES.md)
