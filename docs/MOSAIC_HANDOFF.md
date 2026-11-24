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

1. **Game side of the Studio link.** In `CustomRtExecutor.record`: every ~250 ms copy a downscaled before (input) and
   after (output) image to a host-visible buffer (`vkCmdBlitImage` to a small image, then `vkCmdCopyImageToBuffer`,
   read it a frame later with a fence, never `vkDeviceWaitIdle` per frame), encode PNG off-thread into
   `<pack>/shaders/bedray/.studio/{before,after}.png`, write `status.txt` (state, compile ms, last problem from
   `CustomRtStage.status()`), `addons.json` (renderer-api add-on registry: id, name, version, kind) and read
   `control.json` (`disabledAddons`, later scene/camera/debug commands). Atomic writes (temp + move): Studio polls.
2. **Mosaic on Slang.** Generate Slang only (mirror exists; function bodies and the effect library are GLSL today and
   must be written in Slang), compile in Studio with slangc (bundle it with Studio), ship SPIR-V + reflection next to
   mosaic.json; teach `CustomRtStage` to load prebuilt SPIR-V. Then the in-game compiler add-on via Java FFM on
   slang.dll. Archive the GLSL emitter afterwards. Keep `MosaicShaderCheck` semantics (error -> node mapping via
   `Span`; slangc reports `file(line)`).
3. **Renderer passes as nodes** (the "everything through nodes" goal). Each Slang module under
   `common/src/main/resources/shaders/slang` becomes a node folder: `node.json` (ports: images/buffers/params,
   reservoirs, history) + the module. Needs a **data-driven native frame graph** (today the C++ core records passes in a
   fixed order) - port manifests already come from Slang reflection (`gradle/slang.gradle`). Alternatives in one slot:
   ReSTIR DI/GI/PT variants, denoisers (NRD, own), upscalers. Must be built and GPU-tested on the RTX PC.
4. **Material system (Principled BSDF).** Material graphs: `Principled BSDF` -> `Material Output` with base color,
   roughness, metallic, F0, emission, normal, height (parallax), AO; each input slider / number / texture map. Bake on the
   CPU per texel into **LabPBR** `_s` (R smoothness, G F0/metal, B porosity/SSS, A emission) and `_n` (normal + AO +
   height in alpha) PNGs plus `.bedray.json` sidecars (see docs/SURFACE_MATERIAL_OVERRIDES.md and
   TRANSPARENT_MATERIALS.md), into a resource pack, then reload resources. Needs: CPU noise twins identical to the GLSL
   ones; texture browser (vanilla client jar `assets/minecraft/textures`, resource packs, PNG import); a 16x16 texture
   paint editor; **warnings for strong changes** (large color drift from the source, noise frequencies above the texel
   Nyquist, upscaled textures, normal maps on 16 px, emission on non-light blocks).
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
