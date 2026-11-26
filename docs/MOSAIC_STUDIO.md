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

## Big nodes stay small

A node with more than six inputs shows only the required, wired and changed ones; the rest are edited in Properties
("+N more" on the node). Ctrl+H (or the right-click menu) shows every input of the selected nodes, to wire one.

## Projects and GitHub

Studio starts on the **projects screen** (Settings > Editor > Projects turns it off): a card per project with its
picture, name and description - the game's shader packs with a Mosaic graph, your projects folder and recent ones - a
search, **New project** (name, description, template, picture, licence, GitHub files), **Open**, **Clone from GitHub**
and **Empty graph**. Right click a card: set the picture, edit the card, prepare / publish / update on GitHub, show the
folder. File > Projects brings the screen back, File > This project works on the open one.

A project is a shader pack folder: `shaders/bedray/mosaic.json`, its card `mosaic-project.json` (name, description,
author, version, GitHub address, licence, tags) and its picture `pack.png` (Preview > Image > Use as the project picture,
or a thumbnail of the preview is kept until you choose one).

- **Prepare for GitHub** writes README.md (picture, description, install, release), .gitignore, the licence you choose
  and `.github/workflows/release.yml`: pushing a tag such as `v1.0.0` zips the pack and attaches it to a release.
- **Publish to GitHub** (git and the GitHub CLI `gh`): git init, commit, `gh repo create --source . --push` as a
  private or public repository of your account.
- **Update from GitHub**: `git pull`, then the repository's description, topics and owner into the card. **Clone from
  GitHub** does the same for a repository you name.
- Agents: `mosaic project new | list | info | card | github | refresh | clone`.

## Scene: the parts of Minecraft's picture

The **Scene** panel shows the graph the way Blender's outliner shows a scene: groups (the graph's frames, like
collections), and in them the **Minecraft parts** - Sky, Water, Lava, Leaves, Plants and flowers, Stone and ore, Dirt and
grass blocks, Wood, Sand and gravel, Snow, Ice, Glass, Metal, Light sources, Other blocks, All blocks, Entities particles
and hand, Underwater view - then effects and lights, passes (buffers), Bedray and the Output, each with a line saying
what it does. Click shows a node, the dot turns it on / off, double click renames.

Right click a part in Scene for its ready **looks** (Sunset glow, Tropical water, Autumn leaves, Warm glow lights, Noir
blocks... about thirty; `mosaic looks`, `mosaic look <graph> <node> <look>`).

Every part is one node (category *Minecraft parts*) that changes only its pixels: wire your own shader for the part into
**look** and set **lookAmount**, or adjust it with tint, brightness, saturation and contrast; **strength** fades the change.
Each part also has a mask node (1 on the part, 0 elsewhere). Template **Minecraft by parts** starts with the main parts
chained between Scene and Output; Scene > Add part puts another one before the output.

## Finding things and getting around

- **Command palette** (Ctrl+Shift+P or F3): one search over every command, every menu item, the templates, the node
  types to add, the nodes of the open graph and the recent files. Words match in any order, initials work too ("tm" finds
  Toggle minimap); the commands you used last come first.
- **Find a node** (Ctrl+F): by name, type, id or buffer name; Enter selects it and centres the view on it.
- **L / Shift+L** select everything that feeds the selection / everything it feeds, through wires and buffers.
- **F8 / Shift+F8** walk the errors and warnings that sit on nodes (selects the node, shows the message).
- **Minimap** (Ctrl+Shift+M): the whole graph in a corner when it does not fit the view; selected nodes in the accent
  colour, nodes with errors in red; click or drag in it to move the view.
- **File > Open recent**: the last 12 files.

## Every number is yours

Built-in nodes take every number that changes the picture as an input with a default (distances such as where far
shadows take over, colours, biases, strengths, speeds), so a value is never decided inside a node. The numbers under
all nodes - sky model, sun path tilt, ray lengths, march limits, material colours - are **Graph settings**: click empty
canvas, edit them in Properties; they are saved with the graph.

Properties > Graph settings: a filter field, a slider over the sensible range of every single number (logarithmic for
wide ranges such as ray lengths), **reset** per value, a dot on changed values, **presets** (Fast, Quality, Big world
volume, Golden hour sky, Overcast, Bright night, and your own: Save preset... writes
`~/.bedray/studio/setting-presets/<name>.json`), Copy / Paste as JSON. A value outside its range still works and is
listed as a warning in Problems.

## Settings of the world volume and the preview

- **World volume** (graph settings, Properties > Graph settings > World volume): radius and height of the block
  volume the game keeps around the camera for voxel shadows, GI, reflections and material nodes, and how many blocks
  it re-reads per frame. Saved with the graph, applied by the game when it loads the pack.
- **Preview** (Studio settings): resolution (Preview > size list) and the look of the preview world
  (Preview > World look: light map curve, daylight, block light colour and strength, face shading, water and glass
  opacity, view distance, moon). The preview's sky follows the graph's own sky settings.

## Preview tools

- **View**: Image, False colour (brightness as a heat ramp), Clipping (red where a channel is at 255, blue where all are
  near 0), Brightness, Red, Green, Blue.
- **Compare**: Keep A stores the frame shown now; Split shows A and the current frame side by side (drag the line, or
  Shift+drag anywhere), Difference shows |now - A| brightened (Settings > Preview > Difference gain).
- **Pixel probe**: hover the image for the pixel position, its 8-bit and 0..1 values and its brightness.
- **Views**: save the camera under a name, go back with keys 1..9, R returns to the start view, P pauses.
- **Image**: save the frame as PNG (as it is, or as shown with the view and compare), copy it to the clipboard.
- **Size**: 320 x 180 up to 2560 x 1440, or Custom... (any size 160..3840 x 90..2160).
- **Settings > Preview**: look, fly and wheel speed, invert up / down, frames per second, GPU share (the preview waits after
  a heavy frame so the studio stays smooth on laptops), background frame interval, shortest pause, day speed (the time of
  day runs while the preview plays), smooth or exact-pixel scaling, the info line, difference gain, clipping thresholds,
  false colour range.
- **Settings > Editor**: how many inputs a node shows before it folds the rest, minimap, fit on open, how long after an
  edit the graph compiles, minimap size, how many recent files and palette commands are kept.

## Preview world

**World** (Preview > World): one of your saves (the blocks around where you stand, read from its region files: block
and sky light, the time of day, the far land from the heightmaps), another save folder, the place the game captured (J),
or the built-in landscape. Every block is drawn with its own textures from its model (grass and leaves coloured by the
game's colour maps for the biome set in Settings > Preview > World), taken from the resource packs the game has switched on
(options.txt, in its order, Mosaic Materials included) before the vanilla jar, so material edits show on the world; the
preview reloads them when a pack changes. `mosaic render ... --world <save folder>` does the same headlessly.

Blocks that are not full cubes (stairs, slabs, torches, flowers and grass, fences, doors, lanterns...) are drawn by the
quads of their models, the world's entities by the game's own entity models (exported once from the client jar) with
their textures, and faces get the game's smooth lighting and ambient occlusion (World look: Smooth lighting, Ambient
occlusion, Block models and entities). The built-in landscape has grass, flowers and animals too.

Grass, leaves and water take the colours of each column's biome (from the save, or the game's capture), computed like the
game from the biome's temperature, downfall and colour overrides in the client jar's worldgen data.

Block states come with the world (saves of 26.x: palettes of names and {id, properties}; the game's capture: every
state), so stairs, logs, fences, walls, vines and doors face the way they do in the game: the matching blockstate
variant, or every multipart part whose condition holds. Water, lava, fire and other animated textures play their
.mcmeta animations; the world's rain falls as the game's rain texture around the camera and darkens the light (World look:
Rain, Rain opacity / speed / darkening, Animated textures); the game's cloud layer (clouds.png at y 192.33, drifting) and
distance fog are drawn too (Clouds, Cloud height / speed / opacity, Fog, Fog start).

Chests (normal, trapped, ender, copper) and bells are drawn by the game's entity models, turned to their facing. The sky is
the game's: the camera biome's sky colour overhead and fog colour at the horizon, dimmed by the time of day, an orange
glow towards the sun at dawn and dusk, the sun and moon textures as squares and stars at night (World look: The game's
sky, Sunrise / sunset glow, Stars; 0 uses the graph's own sky model). Cold biomes get snow instead of rain.

It is still Studio's own renderer: particles, signs, banners, beds and thunder flashes are not drawn, and lighting follows the game's light map rather than its exact shader. The game's own frame with your graph is in Result (and Preview > Game frame)
while the game runs with the pack.

The Preview renders the open graph over a **real scene**, not a picture: the blocks around the player when the game
opened Studio (J), or a built-in landscape (hills, a lake, a forest, a stone house with glass and torches, lava) until
then. A G-buffer pass draws the blocks with Minecraft's textures and light map and gives the graph the same inputs as
the game: scene colour, ray distance (so position, normal, material and light nodes work), the 96 x 48 x 96 world
volume, the camera and the previous camera (Accumulate, TAA). Drag to look around, the wheel or W A S D / Space /
Shift to fly, the Time slider for the time of day. Graphs that trace rays (Trace / Shadow ray nodes, RT functions) run
in the preview with their rays walking the world volume; in the game they use the hardware rays.

Beyond the 96 x 48 x 96 volume the preview draws the **far land**: a 384 x 384 height field with the top block class of
every column (the game captures it with the world; the built-in landscape generates hills, a sea and tree crowns),
marched with growing steps (World look: far view distance, far march step), so the world does not end at the volume.

File contract: when J opens Studio the game writes `<game>/bedray-studio/preview-world.bin` (little endian: int magic
`0x3157534D`, int size x / y / z, int origin x / y / z (absolute blocks), double camera x / y / z, float yaw, pitch
(Minecraft degrees), time of day (0 noon, 0.25 sunset); cells from byte 64, one 16-bit cell per block, x fastest, then z,
then y, cell = material | blockLight << 8 | skyLight << 12), then optionally the far land: int magic `0x3152414D`, int size,
int origin x / z, size x size shorts (absolute y of the top block, -32768 unknown), size x size bytes (material class),
then optionally int magic `0x31444E47` and size x size shorts: the ground under leaves (MOTION_BLOCKING_NO_LEAVES), so
far trees are drawn as a canopy (World look: far canopy depth) over open ground. "Reload world" reads it again.

## A renderer of your own

The main workspace is the **Render graph**: the frame as nodes. Bedray's own renderer is one ready-made node of it,
**Bedray render** (double-click: its stages and settings in the **Bedray stages** workspace); wire your passes after it
(template "Bedray render + your passes"), or build the whole renderer yourself:

"+" > **New renderer of your own** opens a whole renderer made of nodes, which runs in the game's custom RT stage on any
GPU (voxel rays through the world volume, no ray tracing hardware needed):

- G-buffer: Albedo (from the game image), Hit position, Surface normal, Sky light, Block light.
- Lights (category *Render pipeline*): Sun shadow (one shadow ray shared by diffuse and specular), Sun & moon light,
  Sun specular (GGX), Roughness (by material), Block light, Sky ambient, Emission, Diffuse GI (voxel paths), Reflection
  (voxels), Reflective surface (water), Atmosphere (aerial perspective toward the sky and the sun), Block light
  (directional, shadowed: light from where the block light rises, with a shadow ray toward it), Sky (procedural) and
  Sky background (gradient, sunset, sun / moon discs, stars, drifting cloud layer), Clouds (volumetric: ray-marched slab,
  light marched toward the sun, forward scattering), Bright pass and Bloom mix, Log luminance + Average (buffer) + Adapt
  exposure + Auto exposure (eye adaptation), Water body (refraction, absorption per channel, deep colour) and the pass node
  Screen-space reflections (reflections read from your HDR buffer, sky model where they leave the screen).
- Passes: Buffer (1/1..1/8), **Accumulate** (temporal accumulation with reprojection and disocclusion reset),
  **Buffer (last frame)** (feedback), **Denoise (a-trous)** steps, Temporal AA.
- Combine (shade), **Tone map** (ACES, Reinhard, Hable, exponential, clip).

A third variant, "Renderer from scratch (hardware RT)", uses Sun & moon light (RT) and Diffuse GI (RT): shadow and GI
rays through the real scene geometry on GPUs with hardware ray tracing. Reflective surface (water) adds Fresnel
reflections with waves on one material.

All variants trace the sun's shadow (Sun shadow / Sun shadow (RT)) and the shadowed block light at 1/2 size and read them
back with Depth-aware upsample; volumetric clouds run at 1/4 size; the GI is denoised at 1/2 and upsampled by depth.
On an Intel UHD at 640 x 360 the graph costs about 29 ms (voxel GI), 32 ms (hardware RT, with voxel rays in the preview)
and 14 ms (fast).

The GI variant traces one path per pixel at 1/2 size, accumulates it over frames and denoises it in three steps; the
fast variant uses sky ambient instead. Every node is an ordinary node: open its code, change it, or rewire the frame.

## Bedray as nodes

- Bedray stages workspace (the inside of the Bedray render node): the frame as a chain of stages (one render mode: Raster, Path tracer or Hybrid ray effects).
  Double-click a stage: its public parameters and every module and function it is made of.
- Every function of Bedray's Slang pack opens as nodes ("+" > Bedray function as nodes). Change it, slangc checks it
  inside its module, **Apply to Bedray** rebuilds the passes that use it for the game.
- "Your post stage": a stage of your own, built from nodes, run in Bedray's frame after its looks.
- "Your material (path tracer)": every surface Bedray's path tracer reaches (camera hit and every bounce) passes through
  your graph before it is shaded - albedo, roughness, metallic and emission in and out, with the world position, shading
  normal and material id. It changes the light transport itself (bounced light, reflections, emission), not just the
  picture. Hook `pathtrace/path_tracer/materialStudio`; values are clamped to physical ranges after it.
- Help > Config: every public number of the renderer in one place; Studio's numbers win over the game's menu while
  Live is on. Help > Guide explains the editor step by step.

## AI agents

See [MOSAIC_RENDER_HOOKS.md](MOSAIC_RENDER_HOOKS.md) for the path tracer stages and the agent loop (`mosaic hook`,
`mosaic render`, `mosaic pack apply`).


An AI agent (or you, in a terminal) can do everything through the `mosaic` command: list node types, read a graph,
add / connect / set / mute / delete nodes, arrange, compile with errors on nodes, bake materials and change the
studio's design. `--json` gives machine-readable output.

    mosaic new shader.json Cinematic
    mosaic add shader.json noise n1
    mosaic connect shader.json n1 mix.fac
    mosaic compile shader.json
    mosaic set-setting theme OLED
    mosaic lint shader.json                            # errors, warnings, hints with their node
    mosaic find shader.json bloom                      # nodes by id, type, label or buffer
    mosaic passes shader.json                          # pass plan: buffers, scales, reads, history copies
    mosaic graph-settings shader.json                  # every graph setting: value, default, range
    mosaic graph-settings shader.json voxelSteps 256   # set one ("default" removes it)
    mosaic graph-settings shader.json --preset Quality # or --reset

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
