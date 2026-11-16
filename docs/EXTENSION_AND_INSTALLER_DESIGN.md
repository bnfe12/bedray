# Extension API, feature optionality and installer — design notes

Status: **design only, nothing here is implemented.** Written 2026-11-16 after an audit of `experimental`. It records what exists,
what is missing and an order of work; each part needs a decision from the owner before code.

## 1. What exists today (audited)

**Frame generation and neural rendering** (`FEATURE_MATRIX.md`, `FRAME_GENERATION.md`, `experiments/dlss5.md`)

| Technology | State | Notes |
|---|---|---|
| NVIDIA DLSS frame generation 2x / 3x / 4x (multi-frame) | BETA, L4 developer run | Streamline `vkb_sl.dll`; row `frame_generation_mode` is on the essentials page; status "Active" requires presented = multiplier x rendered |
| NVIDIA Reflex | BETA | same stack |
| AMD FSR frame generation | NOT_IMPLEMENTED | design notes only |
| XeSS frame generation / XeLL | not integrated | DX12-only parts; SDK files listed in `DLL_REFERENCE.md` |
| DLSS 5 Neural Rendering | EXPERIMENTAL, L2 | enabled by `-Dbedray.dlss5=true` only; needs frame generation and Ray Reconstruction off; the UI row was removed ("not implemented" at the time) |
| DLSS RR, NRD REBLUR/ReLAX, FSR 3.1/4.1.2, XeSS | BETA / EXPERIMENTAL | selectable in Settings |
| ReSTIR DI/PT, SER, radiance cascades, BLAS compaction | EXPERIMENTAL, mostly opt-in | PTLAS, opacity micromaps: not implemented |

**Settings descriptions.** 84 option ids are registered on the two Sodium pages. 80 have `bedray.opt.<id>.desc` in `en_us.json` and
81 in `ru_ru.json`; the rest (`frame_generation_mode`, `adaptive_quality`, `rays_pixel_shadows`, `rays_pixel_shadow_size`) are described only
by inline `SettingsCatalog` text. Gap to close: one description source, with a lint that fails when an option has no name, description,
requirement and cost in both locales.

**Add-on API** (`renderer-api` 1.8, 29 classes, `docs/ADDON_SETTINGS_API.md`)

| Seam | What an add-on can do | What it cannot |
|---|---|---|
| `GraphicsBackend` + `GraphicsBackendRegistry` | Replace the world renderer (the software voxel provider is the only user): receives bounded world snapshots, camera, `LightingFrame`, atlas | It is a whole-renderer swap, not a hook into the hardware path tracer |
| `AddonSettings` | Toggles, integer / decimal sliders, labelled choices, with conditions and groups | Settings only; nothing reads them inside a pass |
| `LightingFrame` | Receives the frame's environment (key, sky, block, ambient) | Read-only |
| Custom RT stage (`-Dbedray.customrt`) | One ray-query compute shader after RT, before hand/HUD | Dev-only, session flag, fixed bindings, temporal upscalers and frame generation must be off |
| Dirt RT / ray-pipeline packs | Compiled and pipelines built | Cannot trace yet |

So today another mod **cannot** change the lighting logic of the path tracer: emitters, sky, materials, sampling, denoiser choice and
tone mapping are all inside the jar.

## 2. Maximal optionality: one feature registry

Principle: every technology is a row with a state, not a hidden flag. A `Feature` record (id, group, requirement, cost, licence source,
state, reason) feeds the settings page, the F3 section, the diagnostics report and the add-on API.

* States: `NOT_INSTALLED` (library missing, shows the official source), `UNSUPPORTED` (device or driver), `AVAILABLE`, `ENABLED`,
  `FAILED` (with the log line). Disabled rows stay visible with the reason, as `renderer_status` and `gi_execution_status` already do.
* Put DLSS 5 Neural Rendering, FSR frame generation, XeSS frame generation, SER, PTLAS, opacity micromaps in the registry now, as
  `UNSUPPORTED`/`NOT_IMPLEMENTED` rows with a description. That makes optionality honest without claiming support.
* Registration lint (unit test, build-time): every option and every feature has name, description, requirement and cost in `en_us` and
  `ru_ru`. For add-on options the host rejects a registration whose help text is empty.

## 3. Letting another mod change the light logic

The shaders already have the right seams: `path_tracer.slang` is written against `ISceneTracer`, `ILightSampler`,
`IEnvironmentSampler` and `IReuseSampler` and names no backend. Proposed layers, each usable on its own and each capability-negotiated:

1. **Data providers (CPU, safe, first).** Typed, bounded, versioned callbacks the host polls once per frame: extra emitters
   (position, radiance, radius), sky / atmosphere parameters, per-region fog and exposure ("authored lighting zones", already
   specified in `RENDERING_EXTENSION_REQUIREMENTS.md`), material overrides by block id. No GPU code from the add-on.
2. **Pass hooks at fixed slots.** `PRE_TRACE`, `POST_TRACE_PRE_DENOISE`, `POST_DENOISE`, `POST_TONEMAP`. An add-on supplies a Slang
   module (AOT SPIR-V plus manifest) against a published resource contract, like the Custom RT stage but without its restrictions.
   The native render graph declares the producer/consumer edges, so history resets and barriers stay the host's job.
3. **Module replacement.** An add-on provides an implementation of `ILightSampler` / `IEnvironmentSampler` / `IReuseSampler`
   (Slang module composition, same ABI check as bundled passes). The host keeps the traversal and denoiser.
4. **Whole-pipeline replacement.** Reserved for `GraphicsBackend`-style add-ons: they own the render plan; the host keeps device,
   queue, presentation and resource retirement.

Rules for all layers: API minor gating (`apiMinorVersion()`), manifest-validated bindings and byte sizes (as `RtPathManifestValidation`
does), a failure drops only the add-on stage for the session with a log line (the denoiser drop path is the model), no add-on may
hold a recording or an image across frames without the host's retirement proof, and add-on stages show in F3 with their GPU span.

**Tools for add-on authors** (outside the shipped jar, per `RENDERING_EXTENSION_REQUIREMENTS.md`): a reference add-on project, a headless
test kit with a fake device (the native tests already have `fake_pass_device.h`), a shader validator that runs `slangc` and the
manifest check, a capture command (frame + stats slot dump), and the existing `native_perf` bench with an `--addon` argument.

## 4. Installer inside the jar (OptiFine-style) and a companion app

What I understand: the OptiFine pattern, where the same `.jar` is the mod and, when run directly, an installer. Reading of "Vanilla RTX
app" (please correct me if you meant something else): a small separate program that installs and updates the packs and settings for the
game without manual file copying.

**Is it allowed.** Bedray is LGPL-3.0-or-later and the jar already contains that code, so an installer entry point inside the same jar is
fine. Constraints from `NATIVES_LICENSES.md` and `NOTICE`:

* Bundle only what the policy table allows: `bedray_core.dll` (ours), AMD FidelityFX 1.1.4 and Intel XeSS 3.0.2 unmodified with their
  notices and pinned SHA-256. **NVIDIA DLSS/NGX, NRD and Streamline stay a user-side official download** (the existing Runtime
  libraries importer); the installer must not bundle or silently fetch them.
* Keep LGPL replaceability: native files stay separate, replaceable files, with sources published.
* No silent downloads; show source URL, size and licence and ask each time, as `INSTALL_NATIVES_WITH_AI.md` already requires.
* Never touch launcher accounts, credentials, other instances or the registry; no admin rights; Windows only for now.

**Shape.**

1. Add `Main-Class` to the loader jars (the loaders ignore it). Running `java -jar bedray-*.jar` opens a small Swing installer:
   detect Prism / official launcher / CurseForge instances and the loader, copy the mod and its required companions (Sodium),
   extract `bedray_core.dll` to `bedray/natives`, offer optional features from the feature registry, write a preset, run a self-check.
2. Put the logic in one library (`installer-core`) used by the jar installer, the in-game "Runtime libraries" screen and a later
   companion app, so the importer, hashes and path rules exist once.
3. The companion app (jpackage exe, later) adds instance profiles, preset import/export and update checks. It stays optional.

**Open questions for the owner.** Which launchers must be supported first? Is bundling the AMD and Intel DLLs in the installer wanted,
or only in the mod jar? Should the installer create a dedicated "Bedray" Prism instance (the safest default: it never touches an
existing instance)? Code-signing: an unsigned Windows `.exe` triggers SmartScreen; the jar route avoids it.

## 5. Suggested order

1. Feature registry plus description lint (small, touches only settings and docs).
2. Add-on API 1.9: data providers (layer 1) and mandatory help text; reference add-on and test kit.
3. In-jar installer using `installer-core`; reuse the existing importer.
4. Pass hooks (layer 2), then module replacement (layer 3), each behind a capability bit and measured against the bench.
5. Companion app, only if the installer proves too limited.

Nothing in this order requires a GPU except step 4; steps 1 to 3 can be built and tested with the Java suite and the native fake device.
