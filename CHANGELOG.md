# Changelog

## Unreleased

* Pool block-emitter reservoirs across neighbours in the Slang path tracer: `restir/spatial_block_emitters.slang` and its
  entry `passes/restir_spatial.slang` (6 bindings, 64-byte push, two checker dispatches per frame) are the port of
  `rt_restir_spatial.comp`, so a surface picks its direct light from its neighbourhood instead of from that pixel's own
  draws and the next frame's temporal import starts from a better sample. The numbers are the legacy tuned ones — eight
  draws from a plus/minus-seven-pixel square forced onto the opposite checker colour (which is what makes the in-place
  update race-free without a second ring), normal dot 0.85, the same 5 % + 0.1-block plane gate, M cap 20 and spatial
  ratio cap 5 through the same `mergeReservoirs` primitive — and the stage traces no rays, because visibility stays the
  shading pass's job. Two contracts the reader cannot recompute are now stored for it: the receiver's own-emission bit
  (`DI_RECEIVER_SELF_EMISSIVE`, so a wall of lamps does not light itself from its own quad after a neighbour's sample is
  re-resolved at it) and the receiver normal's z sign; `restirDiTemporal`/`restirDiSpatial` take a `LightReceiver` instead
  of assuming a non-emissive surface. A merged record keeps the writing pixel's receiver lanes and a non-finite winner is
  dropped, since next frame reads that slot. `RtSpatialReuseSlangPass` binds the *trace pass's own* `FrameConstants`
  upload and takes the extent from it — the push carries no size field, so a pooled record cannot be indexed against a
  resolution the ring no longer has — orders its own two phases and ends with a trailing compute barrier (the legacy
  stage does the same before its consumers run), and it is optional: a missing artifact or a manifest mismatch closes the
  program, logs `spatial reuse off: …` once and leaves temporal reuse running. The player switch is `path_restir_spatial` (row
  enabled only while `path_restir_di` is on, catalog entry, English/Russian text, packed as bit 8 of the per-frame reuse
  diff so flipping it drops the histories it invalidates). Evidence: `compileSlang` now produces 15 passes including
  `restir_spatial` (33 968-byte SPIR-V, 6 bindings, 64-byte push); `RtSlangRestirSpatialTest` pins the numbers against the
  legacy kernel, the checker discipline, the push layout byte by byte (an unwritten offset fails as "offset 28 is never
  written"), the binding set and the optional degrade; eight mutations (draws 8→4, the colour-forcing shift removed,
  receiver lanes not preserved, a push lane unpadded, a binding name typoed, the `diFlags` gate dropped, the parity
  inverted, the self-emission bit swapped) each failed exactly the guard meant to catch it, and all eight were reverted
  byte-exactly under verified checksums. No owner-verified frame yet. See `docs/SLANG_MIGRATION.md`.

* Offer each ReSTIR technique as its own player switch: `rays_restir_temporal`, `rays_restir_spatial` (Ray Tracing (Fast)) and
  `path_restir_di` (Slang path tracer) are registered `RtSettings` booleans, so they persist under their section prefixes and
  still obey a `-Dvkrtx.rt.<key>` session override. Every one is read where its work is decided, not where the pipeline is
  built: the legacy ring is skipped by handing the shader a zero reservoir address (its own "off" test), the legacy spatial
  pass is gated at the two checker dispatches, and `RtSlangPathPass` splits `diRequested()` (allocation, static) from
  `diActive()` (per frame, the player's) so a flip never costs a `vkDeviceWaitIdle` mid-frame; `describe()` reports
  `restir_di=off` for the switch, not only for the missing ring. `RtFrameRenderer` packs all three into the per-frame change
  diff, so any reuse change drops the histories it invalidates and names them in the `resetHistory:` log. The Bedray screen
  gets three rows (spatial only enabled while temporal is on, because it merges the records temporal writes) with catalog
  entries and English/Russian text; presets leave them alone so a preset change does not silently re-enable reuse. Evidence:
  `RtRestirSettingsTest` pins each switch to its consumer, and each of the three mutations (row renamed, getter bypassed,
  dispatch ungated) failed the test that should catch it. No owner-verified frame yet. See `docs/SLANG_MIGRATION.md`.

* Reuse block-emitter samples across frames in the Slang path tracer: `restir/di_block_emitters.slang` implements the
  `IReuseSampler` contract over `restir/restir_di.slang`, `passes/path_trace.slang` binds a per-pixel reservoir ring
  (binding 19, 96-byte push) and `RtSlangPathPass` owns `rt_path_restir` — two halves, parity advanced only by a dispatch
  that wrote records, dropped when the light list or the render size changes. The merge numbers, the receiver gates and the
  occlusion fade-out are the legacy ones (M cap 20, temporal ratio 8, decay 0.25, normal dot 0.8 static / 0.6 moving,
  5 % + 0.1-block tolerance), pinned by `RtSlangRestirDiTest` against `rt_common_emitters.glsl`; a stored point is
  re-resolved through `BlockEmitterSampler.evaluate`, so a reused light and a freshly drawn one cannot disagree about
  `p^`, pdf or `Le`. Reprojection uses the same camera-delta convention as `path_display.slang`, so interior torches
  accumulate while walking instead of resetting every frame. `RtFrameParams.emitterFingerprint` carries the list identity;
  switch `-Dvkbridge.rt.restirDi` (default true) and `restir_di=temporal` in the mode log. Spatial reuse followed in the
  entry above. Evidence: `compileSlang` produces the 11-binding/96-byte manifest and `:common:test` is green (627 tests); no
  owner-verified frame yet. See `docs/SLANG_MIGRATION.md`.

* Give the Slang path tracer block-emitter next-event estimation (`light/block_emitters.slang`, binding 18 over the records
  `RtEmitters` already uploads): a torch-lit interior is no longer black in Path Tracing mode. The transport kernel, the eight
  chromatic categories, the two-sided floor, the redstone bit test and the open-source shadow end bias are the legacy tuned
  ones, pinned on both sides by `RtSlangBlockEmitterTest`; `RtSlangPathPass` samples the list under
  `-Dvkbridge.rt.blockEmitters` with `-Dvkbridge.rt.emitterCandidates` and `-Dvkbridge.rt.emitterProbability`.
  `ILightSampler` now takes a `LightReceiver` so a glowing face rejects its own quad instead of being lit twice, and
  `MaterialSample` carries the scalar emission mask. Entity-atlas emitters and the pixelated cell shadow stay unported; at
  this point each call still sampled the list on its own. Evidence: `:common:test` green (615 tests) and `compileSlang`
  produces the 10-binding/64-byte manifest; no owner-verified frame yet. See `docs/SLANG_MIGRATION.md`.

* Integrate the 15 later ReSTIR/barriers commits through `1ae6580`: finite temporal/denoiser inputs, entity buffer retirement,
  primitive entity capture, native exception containment, DLL/load-path checks, backend escape-hatch and teardown fixes,
  log-path redaction, rain/storm celestial suppression and narrower bloom/AO work. Preserve the optical-material changes.
* Keep partially allocated retired entity rings in one generation record: `ArrayDeque` cannot contain null buffer elements.
  Retry allocation when any ring buffer is missing. This source integration was not built or tested.

* Add per-texture optical sidecars and a bounded immutable GPU palette for IOR, authored slab thickness, reference RGB
  transmittance/distance and diffuse coatings. Preserve dielectric clear-glass centres and keep optical sprites unresolved
  by opacity micromaps. Custom water IOR/absorption reaches its boundary, camera-medium and sun-beam paths.
* Add persisted direct/secondary transparent-shadow controls, conservative extra shared full-block glass-face filtering,
  and opt-in delayed GPU transparency counters plus CPU face totals. Expose controls in the Bedray screen in English/Russian.
* Preserve the thin/slab approximation explicitly; no physical closed-volume glass claim, build, tests, GPU session or FPS
  validation accompanies this source follow-up. See `docs/TRANSPARENT_MATERIALS.md` for limits and authoring.

* Consolidate the 2026-10-26 development branch heads into main, retaining source history and the latest Voxel light/water
  follow-up, Iris scenario, ReSTIR limits, weather, GPU geometry dequantization, cache and DXGI work.
* Resolve transparent interface traversal independently of driver candidate order: keep the nearest bounded metadata,
  finish the terminal-hit query, sort layers, then integrate only the interfaces before that terminal. Suppress unresolved
  transmission beyond the layer budget instead of leaking sky. Retain one traversal per straight segment and bounded mirrors.
  Reuse sorted hit batches in Path Tracing too, preserving its 24-interface budget, water bends and first-visible G-buffer.
* Capture shader extension requirements and SPIR-V target atomically so background warmup compiles for the same profile
  used by its cache key. Reject a null shaderc options handle before native calls.
* Correct water/glass diagnostics and documentation: realistic water refracts approximately; glass stays a thin filter.
  Caustic diagnostics now follow the selected setting. No builds, tests, shader compilation or FPS measurements were run
  for this source integration at the owner's request; it is not a new validated release.

* Notify the Voxel stream directly about propagated block/sky-light changes, including empty air
  sections, with bounded delivery and current-world checks independent of geometry uploads.
* Track water transport by medium segments, stop water extinction after an exit, and refract at
  water-to-air boundaries. Preserve raster for unresolved or internally reflected transmission paths.

* One jar per loader is now the whole product: the combined artifact (host + nested Voxel provider)
  is the default build task for both loaders, so an instance no longer needs a second compute jar.
  Extracting the nested jar into `mods/` still detaches the provider again.
* Own the pixel shadow policy in one module and cut dead work from the path tracer: one shared gate,
  cell-snapped origin, honest option cost labels.
* Adaptive quality governor for Path Tracing: rays per pixel drop first, then bounces, never above the
  values the player chose. Off by default.
* Honest telemetry: a path-traced session reports `mode=path`, and history percentages are reported
  as unavailable instead of a fake `0.0` when nothing accumulates.
* Cheaper emitter sampling: the light radius is hoisted out of the candidate loop and the distance
  falloff avoids `pow`.
* Settings text: Frame Generation shows a real description instead of an empty tooltip, and RT Pixel
  Shadows / Pixel Shadow Size no longer claim to be hidden-mode only (path tracing uses them too).

## 0.1.1-alpha.13 (development, unreleased)

* Include the separately registered Voxel provider in the default Fabric/NeoForge player JAR. Keep
  host-only and standalone provider artifacts for modular development; avoid duplicate external mods.
* Add render-distance-driven loaded-world section streaming, bounded scene arenas/uploads, real
  residency diagnostics and eviction feedback through API 1.6. Preserve actual lighting in empty air.
* Replace the duplicated immediate-write settings screen with shared definitions, private pending
  changes, search, direct selection menus and Apply/Revert. Add Fast/Beautiful/Custom quality profiles.
* Increase valid temporal reuse during motion and section streaming, and detect lighting changes.
  Visual noise reduction and motion quality remain live acceptance requirements.
* Integrate the current atlas upload stack fix and emitter publication during world streaming.
* Make developer deployment back up old JARs, reject running Java clients, verify copied bytes and
  restore previous files on failure. Optional native synchronization is a separate action.

Compilation, package inspection and live evidence for this checkpoint are recorded separately; this
entry does not designate a stable release or claim a new simultaneous hardware-RT/Voxel renderer.

## 0.1.0-alpha.1 (2026-10-20, ALPHA)

First public alpha. Tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42.

### Name and files

* The project is called **Bedray**: mod id `bedray`, jars `bedray-neoforge-mc26.3.0-<version>.jar` and
  `bedray-fabric-mc26.3-<version>.jar`.
* Settings file is now `config/bedray.toml`. An existing `config/vulkan_rtx.toml` is copied once (the old file stays).
* One self-contained jar per loader: the platform layer is built in (directory `core/`), no other mod is needed besides Sodium.
* Native libraries live in `bedray/natives/` (and `bedray/natives/streamline/`); the folder name can be changed with
  `-Dbedray.nativesRoot=<name>`. Release jars include the exact AMD FidelityFX Vulkan and Intel XeSS redistributable DLLs;
  NVIDIA libraries, Streamline DLLs and native wrappers remain external. Folders of earlier test builds are still read, read-only.

### Features (see docs/FEATURE_MATRIX.md for status and evidence)

* Path Tracing (main quality mode) and Ray Tracing (fast, experimental) modes on the native Vulkan backend (ray query), with shadows, ambient occlusion, reflections,
  emissive block light, one-bounce GI, LabPBR and automatic materials. EXPERIMENTAL.
* Upscalers: DLSS Super Resolution, XeSS, FSR 3.1 / 2.x, built-in Bilinear, FSR 1 and NIS.
* Denoisers: NRD, DLSS Ray Reconstruction, accumulation.
* NVIDIA DLSS frame generation (2x, 3x, 4x) and Reflex through Streamline. BETA, tested only on an RTX 5060 Ti. With it on, DLSS
  Super Resolution and Ray Reconstruction use Streamline plugins.
* New F3 screen in sections, with real and generated FPS.
* Settings pages "Bedray ALPHA" in Sodium Video Settings, F3 information block, hotkeys.
* NeoForge and Fabric builds.

### Honesty changes

* The settings page and the F3 screen are marked ALPHA.
* The "DLSS 5" line is labelled "not implemented" and cannot be switched on.
* The F3 line of the unfinished Iris / "Dirt RT" host is hidden unless such a pack is selected.
* Removed a joke tooltip from the settings page.

### Not included

Iris / Dirt RT shader-pack host, DLSS 5, AMD frame generation, FSR 4, Linux and macOS. NVIDIA vendor binaries and native wrappers
are not shipped; see docs/INSTALL.md for the exact bundled runtime files and license notices.
