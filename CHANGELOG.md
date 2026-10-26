# Changelog

## Unreleased

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
