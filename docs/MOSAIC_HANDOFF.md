# Mosaic handoff (2026-11-24)

For the next agent. Mosaic is Bedray's node shader system: node graphs (`shaders/bedray/mosaic.json`) compiled into the
custom RT compute stage, a standalone editor (**Mosaic Studio**, Swing) and an older in-game editor screen. Everything
below is **uncommitted** in the main working copy (`git status`), together with unrelated earlier uncommitted work
(custom RT contract, SurfaceOverrides). Commit on a branch before anything risky.

## 1. Build, test, run (this PC)

* Repo path has Cyrillic characters, Gradle tests cannot load classes there. Use the plain `javac` harness:
  compile `common/src/main/java/.../shaderhost/mosaic/*.java` (+ `-sourcepath common/src/main/java;core/src/main/java;renderer-api/src/main/java`)
  and the tests in `common/src/test/java/.../shaderhost/mosaic/`, run them with a tiny `LauncherFactory` main.
  Classpath jars are all in `~/.gradle/caches/modules-2/files-2.1`: gson 2.11.0, junit-jupiter 5.10.2,
  junit-platform 1.10.2, opentest4j 1.3.0, apiguardian 1.1.2, lwjgl + lwjgl-shaderc **3.3.3 including natives-windows**
  (needed: `MosaicCompileTest` compiles every generated shader through real shaderc), slf4j-api.
  Paths from git-bash `/c/...` must be written `C:/...` for javac.
* Current state: **45 tests green** (graph, editor model, prefs, groups, world volume, real shaderc compile of every
  node type, every math op, every blend/ramp/gradient mode, every built-in effect and template, error-to-node mapping).
* Minecraft classes for compile checks: `libs/minecraft-26.3-client.jar` + Prism libraries
  (`%APPDATA%/PrismLauncher/libraries`), lwjgl 3.3.3 jars for Vulkan code. The host files (CustomRtStage, Executor,
  Resources, MosaicWorldFeed) compile; **nothing has run in the game** (this PC: Intel UHD, no ray tracing, no MC 26.3
  instance with Bedray; the user's RTX PC is separate).
* Toolchain that exists here (do not claim it is missing): slangc 2026.20 `C:/Temp/bedray-slang-2026.20/bin/slangc.exe`,
  MSVC 14.44 + CMake + Ninja in `C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools`, Windows SDK 10.0.26100.
* Run Studio: `java -cp <out;the jars above> io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio.MosaicStudio [mosaic.json | pack folder]`.
  `-Dmosaic.selfshot=<file.png>` writes each window's picture after 3.5 s (use it to verify UI; the user's terminal
  usually covers the window, so screen captures show the terminal).

## 2. Architecture (what exists)

Core (pure Java, `shaderhost/mosaic`):
* `MosaicGraph` - `analyze(json)` reports **all** problems (ERROR/WARNING/HINT, node, port, `Fix` kind), resolves kinds,
  reachable set, cost. `parse` = strict. Node types: sources, scene, ray (trace/shadow), world/materials (binding 15),
  values, Blender-style `math` (32 ops in `mask`, clamp = `index` bit 0), `mixColor` (14 blend modes), `mapRange`,
  `colorRamp` (stops in `value`, 5 numbers each), `reroute`, procedural `noise/voronoi/whiteNoise/checker/wave/gradient`,
  user functions `fn.<name>`. Named outputs generalised (`namedOutputs/outputKind`).
  **Inline input values** (Blender principle): node field `"defaults": {port: [numbers]}`; a wire overrides, removing the
  wire brings the value back. Required ports are satisfied by an inline value.
* `MosaicDocument` - editable doc: nodes (+label, defaults), `functions` (kept as text so broken code survives),
  `frames` (editor-only groups: title, color, rect), named undo/redo (`commit(before, label)`, `jumpTo`,
  `historyState/restoreHistory`), fragments for the clipboard (carry function definitions).
* `MosaicCodegen` - GLSL (runtime) and Slang (mirror) emission with an optimizer (prune unreachable, fold constants via
  `MosaicEval`, merge identical nodes incl. rays), line map (`Span`) so compiler errors map to node/function line,
  debug options (preview one node, NaN/Inf magenta). Only used bindings are declared; a graph without ray nodes
  declares no TLAS.
* `MosaicEval` - CPU twins of pure nodes (folding). **Noise has no CPU twin yet** (needed for material baking).
* `MosaicFunction` (user nodes, GLSL-like signature), `MosaicEffects` (built-in effect/rendering/shading library,
  each a readable function), `MosaicTemplates`, `MosaicShaderCheck` (host-identical shaderc compile + error mapping,
  returns SPIR-V), `MosaicWorld` (world volume: 48x32x48 cells, 16 bit = material | blockLight<<8 | skyLight<<12,
  binding **15**, header ivec4 originRel / vec4 camFrac / ivec4 size), `MosaicArrange`, `MosaicCatalog`, keymap/prefs
  of the in-game screen.

Host (`shaderhost/customrt`):
* `CustomRtStage`: compiles mosaic.json through MosaicCodegen; skips recompiling when only node positions changed;
  `setMosaicDebug(previewNode, nan)`; `status()` telemetry; world volume update per frame (`MosaicWorldFeed`, budget 6144
  cells); TLAS only for shaders that bind it (graphs without rays run with raster/voxel renderers too); `timing.w` = time
  of day (0 noon) for `mosaicSunDir()`.
* `CustomRtExecutor`: optional TLAS binding, binding 15 storage buffer (header every frame, cells on change, 64 KiB
  `vkCmdUpdateBuffer` chunks), **fixed a real bug**: storage buffers were missing from the descriptor pool sizes.
* `CustomRtResources`: binding 15 is host-produced, not a pack file.

Studio (`shaderhost/mosaic/studio`): `StudioApp` (windows, global key dispatcher incl. Cyrillic layout mapping,
commands, menus inside the narrow custom title bar, restyle with cross-fade, Run game), `StudioModel` (shared state,
compile worker + compile cache, Live save, game link polling), `Dock` (Blender-like areas: split/maximize/pop out/dock
back, editor menu with icons, custom title bar, edge snapping), `GraphPanel` (canvas: frames, labels, knife
Ctrl+RMB, keyboard navigation, touchpad, DnD target), `NodeTree` (collapsible categories, DnD source), `Panels`
(palette, properties, result A/B, history, problems, code, outline, game link, add-ons, keys), `InspectorPanel`
(inline values with sliders, modes, ramp stops, code editor), `SettingsWindow`, `ProjectStore` (per-project meta +
compile cache in `<pack>/shaders/bedray/.studio/`), `Icons`, `StudioUi`, `StudioKeys`, `StudioSettings`
(`~/.bedray/mosaic-studio.json`).

## 3. Decisions the user made

* Slang only for Bedray's own shaders; **both** Slang paths, chosen in settings: (a) Studio compiles Slang and hands the
  game SPIR-V (recommended), (b) optional in-game Slang compiler add-on (~30 MB Windows: slang.dll + slang-compiler.dll
  + slang-glslang.dll; slang-llvm not needed; per-OS builds). Iris packs stay GLSL (the only exception).
* "Nodes are folders of Slang modules wired modularly" - the target architecture for the renderer itself.
* UX: Blender-like everything (areas, frames, inline values, knife, menus), keyboard-only must work, touchpad, themes,
  compact custom title bar, no flashing, adaptive text, icons, Russian keyboard layout.
* Real render, not simulation: preview comes from the game (Result area), not a CPU fake.

## 4. Hard queue (not easy; do in this order)

1. **Game side of the Studio link - implemented, compile-verified only** (`customrt/StudioLink`, capture in
   `CustomRtExecutor.recordCapture`): every 250 ms the before/after images are blitted (flipped, NEAREST) into RGBA8,
   copied to a double-buffered host-visible readback and handed over one capture later (no GPU wait); PNG encoding and
   `status.txt` / `addons.json` run on a daemon thread with atomic moves; `control.json` applies `disabledAddons`
   (new additive `LightProviders.setUserEnabled`), `previewNode` and `nanCheck` (Studio: Node menu / node context
   menu). Active only when `.studio/` exists. **Verify on the RTX PC**: image orientation, HDR values clip at 1 (a
   tonemap before the blit may be wanted), cost of the capture, world-color formats without blit support.
   Still to add: scene / camera / time commands, before-only capture when the stage is off.
2. **Mosaic on Slang - implemented (compile-verified, slangc tested; not yet run on a GPU).** `MosaicSlang` translates
   GLSL node code to Slang (functions may carry their own Slang body; rtReflections does), compiles with slangc and maps
   errors to nodes. Studio (Settings > Compiler = Studio) writes `mosaic.spv` + `mosaic.spv.json` (sha256 of the
   generated Slang, `PREBUILT_OPTIONS`) next to mosaic.json on every save, cached in `.studio/cache/<sha>.slang.spv`.
   The game (`CustomRtStage.slangStage`): prebuilt SPIR-V when the hash matches -> slangc in game when installed
   (`MosaicSlang.locate`: -Dbedray.slangc, BEDRAY_SLANGC, PATH, ~/.bedray/slang, SLANG_DIR) -> GLSL/shaderc fallback;
   `-Dbedray.mosaic.slang=off` forces GLSL. **Verify on GPU**: matrix layout (`-matrix-layout-column-major` +
   `mul(M, v)` must match GLSL `M * v`), the 320 B frame UBO layout, image orientation. Still open: the packaged
   slangc add-on download (~/.bedray/slang), deleting the GLSL emitter once the Slang path is proven (keep shaderc for Iris).
3. **Renderer passes as nodes** (the "everything through nodes" goal). Each Slang module under
   `common/src/main/resources/shaders/slang` becomes a node folder: `node.json` (ports: images/buffers/params,
   reservoirs, history) + the module. Needs a **data-driven native frame graph** (today the C++ core records passes in a
   fixed order) - port manifests already come from Slang reflection (`gradle/slang.gradle`). Alternatives in one slot:
   ReSTIR DI/GI/PT variants, denoisers (NRD, own), upscalers. Must be built and GPU-tested on the RTX PC.
4. **Material system - first version implemented (CPU bake verified in tests and the studio; game reload compile-verified).**
   Studio has workspaces (title bar: Shaders | Materials, each with its own document and layout). Materials: Library
   (textures of Minecraft client jars found in launcher/build caches, game resource packs, the project; search, groups,
   thumbnails, double-click starts a material, drag adds an Image Texture node, Import copies files in), nodes
   `mat.texture` / `mat.image` / `mat.bsdf` (Principled: base colour, roughness, metallic, specular, emission, height,
   normal strength, AO) / `mat.output` plus every CPU-safe node (`MosaicGraph.MATERIAL_SAFE`), `MosaicMaterial` bakes per
   texel (procedural textures tile across the texture) into albedo (only when changed, mcmeta copied), LabPBR `_s` and
   `_n` (DirectX, AO, height) in `<game>/resourcepacks/Mosaic Materials/`, next to `<texture>.mosaic.json`; warnings for
   strong colour changes, noise finer than the texture, oversized images. The Material panel shows a CPU-lit block (drag
   the sun) and every map. The game (`customrt/MaterialLink`, ticked from `CustomRtStage.render`) watches
   `<game>/bedray-studio/materials.json`, enables the pack once and reloads resources. **Verify in game**: pack format
   accepted by 26.x, reload cost, that the path tracer picks the new maps after reload. Still open: texture painting,
   GPU preview, per-block (not per-texture) materials, texture browser for mod jars.
5. **New render targets.** Entities (RT entity instances exist, mask 0x02), partial blocks (fences, slabs, panes: add
   shape bits to the world volume - a contract change, bump the cell layout), 2D/GUI items (inventory item rendering
   hooks; e.g. reflective diamond sword in the inventory), parallax (LabPBR height; POM in the path tracer), third-party
   texture packs (detect LabPBR / old PBR formats).
6. **In-game Studio parity.** `MosaicEditorScreen` is the old monolith (pre-Studio); port the Studio model/panels to the
   Minecraft GUI (`GuiGraphicsExtractor`, `nextStratum`, scissor; see its file header).
7. **Package Studio**: a Gradle task / runnable jar or jpackage with lwjgl-shaderc natives and slangc; today it runs from
   a scratch classpath.

## 5. Known issues / verify first

* Studio UI is compile-verified and snapshot-checked, **not exercised by a person end to end**: drag and drop from the
  palette, knife, pop-out windows, restyle cross-fade, touchpad pinch (Windows sends Ctrl+wheel), keyboard-only flow.
* The user reported Ctrl+Z not undoing a node move: the model and the full app pass a scripted repro (move is recorded
  as "Move <id>", Ctrl+Z restores it). Probable causes fixed: Cyrillic layout (shortcuts now map JCUKEN letters to
  QWERTY keys) and focus in a text field (Undo/Redo now always work). Re-check with the user.
* Studio-side effects bodies only have GLSL; the Slang mirror returns zero for them (hint in Problems).
* `MosaicEditorScreen` (in game) predates functions/defaults/frames; it still compiles but does not expose them.
* Docs to update when landing: docs/CUSTOM_RT_SHADERS.md (binding 15, timing.w, defaults/frames/functions fields),
  docs/MOSAIC_EDITOR.md (in-game), a new Studio doc.


## Renderer workspace (2026-11-25): Bedray itself is configured through nodes

The owner's rule: Bedray is developed through nodes from now on. `MosaicRenderer` turns every stage of the real renderer
(Scene, Materials, Sun and sky, Path tracer, ReSTIR DI, ReSTIR PT, Radiance Cascades, Hybrid ray effects, Volumetric fog,
Denoiser, Exposure and tone, Post effects -> Bedray frame) into node types `bedray.*` whose settings are generated from
`RtSettings` (keys, ranges, defaults) and the language file (labels, help). Wired stages are on, left-out stages are
switched off; the analyzer checks the pipeline order and needs (ReSTIR PT needs ReSTIR DI). The studio's Renderer
workspace edits `<game>/bedray-studio/renderer.mosaic.json`, starts from the game's values (`renderer-state.json`,
written by the mod from `RtSettings.write`) and Live sends `renderer.json`; the mod (`MaterialLink.renderer`) applies
every pair with `RtSettings.applyKeyValue` and saves bedray.toml. **Next**: make stages swappable Slang modules (ReSTIR
variants, denoisers), let Mosaic shader graphs plug in as stages, expose per-pass timings on the nodes.

## Wishlist from the owner (2026-11-24) - not done yet, in rough priority

1. **(Game host done 2026-11-25, compile- and shaderc-verified, not run on a GPU)** Multi-pass graphs: Buffer
   (`mosaic.pass`, name + scale 1/2/4/8), Read buffer (`mosaic.bufferIn`, any uv), Previous frame (`mosaic.history`),
   Rain / Thunder / Temperature / Sky / Fog colour (binding 16 `BedrayWorldInfo`); `MosaicPasses.plan` orders passes and
   emits a standalone graph per pass (bindings 19 history, 20+ buffers); the studio compiles every pass and the GPU
   preview runs the chain with history. **Game side**: `CustomRtStage.compileChain` compiles every pass (slangc in game or
   GLSL; studio SPIR-V covers single-pass graphs only) and contract-checks each; `unionModule` merges their bindings
   (a kind conflict is refused) for the pack resources and one shared set layout; `CustomRtResources.build(.., host, ..)`
   skips 16 / 19 / buffer bindings of a Mosaic graph (they are host-produced, also for single-pass graphs with weather or
   Previous frame, which used to demand buffer16.bin / texture19.png). `CustomRtExecutor`: one pipeline + descriptor set
   per pass, rgba16f image per Buffer at 1/scale, bilinear sampler for buffers and history, history cleared to black and
   copied from the final output each frame, binding 16 filled per frame by `CustomRtStage.fillWorldInfo` (IrisWorldCapture
   rain / sky / fog, `getThunderLevel`, biome base temperature at the camera). Pool now also reserves storage-image
   descriptors (binding 3 was never counted). Test: `customrt/CustomRtChainTest`. **Verify on the RTX PC**: blur and
   bloom look as in the studio preview, history orientation, cost per pass. Still wanted on top: depth-aware passes,
   mip chains, loops / kernels, studio SPIR-V for every pass.
   Note: 4 tests of `CustomRtContractTest` already failed before this change (example rt.comp / fixture drift).
   Original wish: **Node editor strong enough for a Complementary-class shader**: multi-pass graphs (named buffers / passes, previous
   frame for temporal effects, mip / blur / bloom chains, shadow-like passes), weather / time / biome inputs, loops or
   kernel nodes. Ties into "renderer passes as nodes" (item 3 above).
2. **Agent tooling**: a console tool so an AI agent can do everything (list/add/connect/set/delete nodes, materials,
   paint, settings and the design itself, export/import, run the game) with per-permission switches in Settings; the
   studio reloads files changed outside.
3. **Settings window redesign** (sidebar with icons, cards, search) and small UI animations everywhere.
4. **Shader browser**: saved Mosaic graphs and installed Iris packs with thumbnails; import / export Iris packs as
   zips (screen-space graphs could export as an Iris composite pass).
5. **Preview window besides Result** (GPU preview inside the studio on the last game frame), **render and record**
   (frame sequences / GIF / MP4 via ffmpeg when present) of the result.
6. **Material preview**: HDRI environment maps, test lights, a small scene instead of one block; entity models
   (export the game's model layers from the mod so the studio can show the entity and its UV layout).
7. **Texturing**: layers per channel (roughness / height paint), selection, auto-unwrap for custom models.
8. **Accounts and publishing**: AI API keys, Modrinth / CurseForge upload, GitHub save; later Microsoft sign-in.
   Privacy policy and a GitHub page for the studio; app packaging (jpackage for Windows / macOS / Linux) and the mod
   built into Bedray.
9. **Mods**: materials for mod blocks (mod jars are already library sources), custom shaders for mods,
   a node-based effect (particle / VFX) generator.

## Bedray's pack as nodes (2026-11-25 night)

Goal of the owner: every smallest part of Bedray (RT, PT, voxels, every graphics setting) is made and changed through
nodes; Bedray's own parts are called Bedray, yours are yours; a config of public numbers that wins over the game menu.

- **Library** (`MosaicSlangLib`): parses all 122 modules of `common/src/main/resources/shaders/slang`: functions and
  struct methods (params with in / out / inout, defaults, doc comments, bodies with offsets, callees), structs with fields,
  enums, `static const`. Overloads get ids with their parameter types (`contracts/math/safeNormalize(float3,float3)`).
  Methods of generic structs / interfaces and entry points are not callable nodes (entries open as graphs).
- **Node types** (`MosaicSlangGraph`): `bedray.fn:<module>/<name>` (methods: `<Struct>.<name>` with `self`, mutating
  ones give it back; out params are named outputs; void calls chain with `then` / `after`), `bedray.make:` /
  `bedray.break:` structs, `bedray.const:`, body pieces `slang.param / result / out / let / literal / op / call / do /
  member / set / code`, `bedray.setting` (a stage setting as a public parameter). Ports carry Slang types;
  `MosaicGraph.Kind.S` is a non-numeric Slang value that connects only to its own type, `auto` ports are checked by slangc.
- **Bodies as nodes** (`MosaicSlangDecompile`): every function opens as a graph: one variable node per assignment,
  operators / calls / fields, pack calls as real function nodes, early `if (c) return x;` as selections, control blocks
  (if / for / while / switch) as `slang.code` nodes whose ports are the variables they read and change, comments as
  node notes. 656 functions: 489 entirely nodes, 154 with code blocks, 13 one code node; every standalone module (76)
  compiles after all its functions went through nodes (probe in the transcript; tests round-trip microfacet, sun,
  restir_di).
- **Back to Slang**: `MosaicSlangGraph.function` writes a body (code blocks become helper functions next to it,
  mutating / static like the original); `overrideAll` replaces bodies inside the module (offsets from the end, imports
  added); `checkModules` compiles a copy of the shader folder with slangc (Bedray's flags).
- **Studio**: "+" > Bedray function as nodes / double-click a pack call: the body in a tab (palette: body pieces, every
  function by folder, structs, constants, math). Inspector: signature, developer note, slangc verdict after every
  change, code-block editor, Apply / Reset / Remove Studio passes. Files: `~/.bedray/studio/functions/<id>.mosaic.json`.
- **Apply** (`StudioPack`): modules with your graphs -> `<game>/bedray-studio/slang`; every pass whose imports reach them
  (quoted and `import name;`) is rebuilt with slang.gradle's flags (compute / raygen / SER), refused when it would use a
  binding the manifest does not have (push constants excluded), written as `bedray-studio/passes/<name>.spv + .json`
  (manifest copied from the build or the jar, word count updated). Game: `SlangPassLibrary.load` takes
  `bedray-studio/passes` first (`-Dbedray.studioPasses=false` off, `-Dbedray.studio.dir` elsewhere). Verified:
  SunMoonSampler.sample -> 9 passes; the game's loader accepts them. **Not run in Minecraft yet.**
- **Stages open inside** (`MosaicStageMap`): double-click a renderer stage: its settings as `bedray.setting` nodes (edits
  go to the renderer graph and the game) and every module of it (`MosaicPack.stagesOf`: Hybrid ray effects shares the
  path trace passes) framed with each function. A stage with edited functions shows "(your version)".
- **Render modes / settings**: one wired mode stage decides `rt_mode` (Raster / Path tracer / Hybrid ray effects with rays /
  AO / direct). Stages Voxels and Upscaler added; all 64 keys are on nodes. MaterialLink applies VulkanRtxConfig keys too.
- **Priority config**: renderer.json says `priority`; MaterialLink puts Studio's numbers back every 2 s if the menu changed
  them. Help > Config lists, edits, copies / saves (bedray.toml lines) and sends every public number.
- **Your post stage** (`user.post`): Bedray's pack has one hook, `post_cinema.slang postStudio(color, uv, pixel,
  frameIndex)` (identity), called after the looks in path_display, local_tonemap_apply and the NRD compose. The node is
  named by its label; its graph is the hook; Apply rebuilds those six passes.
- **Other**: Help > Guide (built-in guide), node notes (Settings showNotes), Vector Math / Separate / Hue-Sat /
  Bright-Contrast / Gamma / Invert / Fresnel / Mapping / Smoothstep / Blackbody nodes (GLSL + Slang tested), material
  preview lights (presets, HDRI .hdr/.png/.jpg, sun and fill colours), agent CLI `mosaic pack ...` / `mosaic config`.

Next, in order: run Apply in the real game on the RTX PC (pipeline reload of Studio passes without a restart); more
user stage hooks (before tone mapping, sky, material decode) the same way as postStudio; turn the remaining code blocks
(loops) into loop nodes; per-pass timings on nodes; HDRI in the game's sky; auto-unwrap / paint layers; Iris export.

## Shader-pack level (2026-11-25, afternoon)

What a Complementary-class pack needs, now in Mosaic (all in the game, see VERIFICATION_LOG 2026-11-25):
multi-pass host (`CustomRtExecutor` chain), raster depth as ray distance (host pass `RASTER_DEPTH_GLSL`, precision-safe
`1 - window` form), Blur buffer / Kawase blur (`mosaic.blur`, `mosaic.kawase`, read a Buffer by name like Read buffer),
Reproject (`mosaic.reproject`, BedrayPrevFrame binding 17), effects `voxelSunShadow` / `sunVisibility` / `ssao` / `godRays`,
templates Bloom chain (mip), Temporal smoothing, Shader pack look (no RT), Studio inspector for buffer names and sizes,
Shader browser "Export as Iris pack". Build here: javac harness (see top) or `gradlew :fabric:combinedJar -x test
-Pneoforge_libs_dir=<stub jars> -Pslangc=C:/Temp/bedray-slang-2026.20/bin/slangc.exe` with
BEDRAY_LAUNCHER_LIBRARIES = Freesm libraries (NeoForge jars are not installed on this PC; empty stubs pass configuration).
In-game check: Freesm instance Mosaic-Bedray-26.3, JvmArgs `-Dvkbridge.autotest=iris -Dbedray.customrt=<pack>`, shots in
`<instance>/minecraft/autotest/iris/`.

Later the same day: far shadows over a 256 x 256 height field (`MosaicHeight`, binding 18, `mosaicFarShadow`), Temporal AA
with neighbourhood clamp (`mosaic.taa`), loops as nodes (`mosaic.repeat`), studio SPIR-V per pass
(`MosaicSlang.prebuiltName`). Only hardware RT with Mosaic is unverified: it needs the RTX PC (this GPU has no ray query).
