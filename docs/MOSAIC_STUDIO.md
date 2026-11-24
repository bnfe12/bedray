---
title: Mosaic Studio
---

# Mosaic Studio

Mosaic Studio is the node editor of Bedray: a desktop app for shaders and materials, with a matching editor inside
the game. The **mod** part lives inside Bedray (it runs the shaders and loads the materials). The **app** runs next
to Minecraft, or without it.

> Status: in development on the `mosaic-studio` branch. The studio and its tests run; the in-game parts (result
> frames, Slang SPIR-V loading, material reload) are compile-verified and still need a run on an RTX PC.

## What it does

| Area | What you get |
|---|---|
| **Shaders** | Node graphs for the custom ray-tracing stage: Blender-style math, colour, textures and noise, ray and world nodes, a library of effects, your own nodes in code. Errors land on the node that caused them. Compiled through Slang (in the studio or in the game) with GLSL as the fallback. |
| **Materials** | A Principled BSDF graph per block, item or entity texture, baked into LabPBR `_s` / `_n` maps and the albedo of a resource pack that the game reloads by itself. |
| **Texturing** | The real block model in the preview (fences, stairs, plants...), UV layout with seams, pixel painting (brush, eraser, fill, picker) on the UV view or straight on the model. |
| **Library** | Textures of Minecraft (26.3 first), resource packs and mods of every game instance found in the official launcher, Prism / MultiMC, CurseForge, Modrinth, ATLauncher and GDLauncher. |
| **Result** | The frame of the running game before and after the shader, side by side, split, or against an earlier step. |
| **Workflow** | Document tabs, Blender-like areas you can split, pop out and dock back, rebindable keys (also on the Russian layout), themes including OLED black, named undo history per tab. |

## Files it writes

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
