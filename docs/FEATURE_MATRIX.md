# Feature matrix: current source and historical release evidence

## Current `main` (0.2.0-alpha source, 2026-11-13)

`main` is the Slang AOT renderer with the native GPU scene (see [BRANCH_LAYOUT](BRANCH_LAYOUT.md)); the previous GLSL renderer is history only.
Rows cite the document or run that holds the evidence. **L4 means a measured live run on one NVIDIA GeForce RTX 5060 Ti 8 GiB, driver NVIDIA 621.14,
Windows, 1920x1080 native, fixed-seed autotest world; nothing here is L5 (owner-confirmed) unless stated.** Features not listed keep the status they have
in the historical tables below only where those tables say they belong to the Slang pass; rows that name the deleted GLSL pass do not transfer.

| Area | Status | Level | Evidence / measured result |
|---|---|---|---|
| Slang AOT pipeline, shared pipeline cache, native GPU scene core (ABI 3) | BETA | L4 (bench stand) | all runs of the [GI stack report](measurements/GI_STACK_REPORT.md); Windows native CI (`.github/workflows/native-windows.yml`) covers DLSS resource contracts without a GPU |
| BLAS compaction | BETA | L4 | RD16 about 1747 MiB reserved; RD32 about 3607 MiB fits 4096 MiB, ray tracing stable (`measurements/compaction_*`) |
| ReSTIR PT (`path_restir_pt`, Enhanced) | EXPERIMENTAL | L4 | proven running; +24.7 % `gpu_rt`, +569 MiB versus PT off; quality is a feature, not a speed-up |
| Ray-generation PT execution | EXPERIMENTAL | L4 | -7.8 % `gpu_rt` versus the compute path |
| Shader execution reordering (SER) | EXPERIMENTAL, opt-in, keep OFF | L4 | steady-state A/B: 2.14x slower trace pass (+114 % `gpu_rt`, -42 % FPS) |
| Opacity micromaps | NOT_IMPLEMENTED (deferred) | L0 / design | no measured terrain win; [design note](ai-agent-addenda/13-omm-feasibility-design.md) |
| Partitioned PTLAS | NOT_IMPLEMENTED (deferred); regional in-place TLAS update present | L2 | TLAS-inclusive `world` span captured; isolated `tlas_ms` needs a native ABI split ([design](ai-agent-addenda/14-ptlas-feasibility-design.md)) |
| NRD REBLUR denoiser | EXPERIMENTAL | L4 | runs in game; -18.4 % `gpu_rt` versus DLSS RR at native 1080p |
| NRD ReLAX | EXPERIMENTAL | L2 | selectable and in the bench; A/B numbers not captured ([disposition](ai-agent-addenda/16-nrd-tuning-disposition.md)) |
| DLSS Ray Reconstruction | EXPERIMENTAL | L4 (runs); preset D/E/F L2 | preset hint is additive and default off; A/B capture pending; RR sharpness is not wired (the NVIDIA API call is a no-op) |
| Settings auto-policy and texture style presets | BETA | L4 | `gi_settings_l4` result PASS; the `gi_execution_status` row and `dlss_rr_preset` row are L2, live read-back pending |
| Radiance Cascades | EXPERIMENTAL, opt-in | L2 | native finite-window field with temporal reuse; typed trace split into modules; no live acceptance recorded |
| Glass: Fresnel on thin panes, ambient through glass, reflection guides through transmitted panes, tinted glass | EXPERIMENTAL | L2 | source fixes of 2026-11-12/13, live effect not recorded; a see-through-triangle report is localized to distant stair shading ([A/B notes](VERIFICATION_LOG.md)) |
| Nether and End bioluminescence | EXPERIMENTAL | L2 | selective materials; no live capture found in the documents |
| Torch glow and auto-exposure fix | EXPERIMENTAL | L2 | `RtExposure`, emitter and surface shaders; live effect not recorded in the documents |
| Iris post host | EXPERIMENTAL | source-only per its document | see [IRIS_VULKAN](IRIS_VULKAN.md) |
| Iris pack-level raster forward host | EXPERIMENTAL, source-integrated | L2 | merged in PR #84; acceptance (real pack visible in a screenshot, honest log) is defined in the [design](superpowers/specs/2026-11-12-iris-raster-core-design.md) and is **not recorded as done** |
| Voxel provider (nested `bedray_compute`) | EXPERIMENTAL | see document | streamed loaded sections, bounded residency; levels and open items in [COMPUTE](COMPUTE.md) |
| Sodium NeoForge 0.9.2 ... 0.9.3-beta.5 | BETA | L0 contract, L1 compile | seven official builds, sha512-locked in `tools/sodium-matrix.json`; no in-game run per version; the Bedray page not appearing in one owner instance is accepted and unexplained |
| Transparent interfaces, optical profiles, cost controls and counters | EXPERIMENTAL | L2 | unchanged contract, see [TRANSPARENT_MATERIALS](TRANSPARENT_MATERIALS.md); cost unmeasured |
| Shader / pipeline caches, DXGI policy | EXPERIMENTAL | L2 | local caches only; DXGI needs a game version with Mojang's implementation |

Owner-gated or GPU-gated and **not** claimed: L5 sign-off for every row, before/after screenshots, preset and denoiser A/B numbers, native
1080p/60 FPS (an unmeasured target, see [NATIVE_1080P_60_AUDIT](NATIVE_1080P_60_AUDIT.md)). The full list of open obligations per technology is in
[STACK_IMPLEMENTATION_PLAN](STACK_IMPLEMENTATION_PLAN.md); how to reproduce every number: [L5_REPRO](measurements/L5_REPRO.md).

## Historical evidence: 0.1.0-alpha.1 (previous GLSL renderer)

The tables below describe the published pre-release. Rows that name the GLSL pass, the Java-side renderer or GLSL-era autotests do not apply to current `main`; use the current table above.

**Bedray is an alpha. Nothing below is marked STABLE.** The statuses are based on the code and on the developer's own test runs.
Tests were made only on **NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42**, mostly with NeoForge. Other GPUs and
Fabric have much less or no evidence.

## Status words

| Status | Meaning |
|---|---|
| STABLE | Proven over time and on several systems. **None in this release.** |
| BETA | Seen working in the game by the developer; may still have bugs. |
| EXPERIMENTAL | Implemented, expect visual errors, crashes or high cost, or little proof. |
| NOT_IMPLEMENTED | No working code. Hidden or shown as a disabled line. |

## Evidence levels

| Level | Meaning |
|---|---|
| L0 | Code written |
| L1 | Builds |
| L2 | Unit-level check passes (no GPU, **not** proof the feature works in the game) |
| L3 | The game starts and the feature initialises without a crash (log) |
| L4 | The effect was seen: a log line or a screenshot from a real run |
| L5 | Confirmed by the project owner |

The "Evidence" column names where the level comes from (autotest log, screenshot, unit check). Levels reflect project test records
through October 2026 and are **not re-measured for every build**. The current release artifacts passed a clean build and archive
inspection, but have not yet had final live-game smoke acceptance (see `docs/RELEASE_CHECKLIST.md`). Quality of the image (versus
other upscalers, or noise levels) has not been evaluated.

## Platform

| Feature | Status | Level | Evidence | Requirements |
|---|---|---|---|---|
| Vulkan backend, NeoForge | BETA | L4 | autotest runs on RTX 5060 Ti: menu, world, inventory screenshots; a soak of about 150 s without crash (5 minutes not confirmed) | Windows, MC 26.3, Sodium 0.9.x |
| Vulkan backend, Fabric | EXPERIMENTAL | L1 | jar builds, metadata checked; no recorded in-game run in this repository | fabric-loader 0.19.5+ |
| NVIDIA GPUs other than RTX 5060 Ti | EXPERIMENTAL | none | not tested | |
| AMD Radeon (RDNA 2+) | EXPERIMENTAL | none | **not tested, testers wanted** | ray query |
| Intel Arc | EXPERIMENTAL | none | **not tested, testers wanted** | ray query |
| Settings pages in Sodium Video Settings | BETA | L3 | page builds, upscaler selection logged; reset buttons and collapsible sections seen in game | Sodium 0.9.x |
| F3 "Bedray" sections (overview, load, quality, libraries; timing and diagnostics off by default), real and generated FPS | BETA | L3 | present in autotest runs | |
| Settings file `config/bedray.toml`, migration from the old name | BETA | L2 | unit check for migration; round trip checked in tests | |

## Upscalers

| Feature | Status | Level | Evidence | Requirements |
|---|---|---|---|---|
| Bilinear + 3D Scale | BETA | L4 | `world_target=640x360 main=1280x720` and screenshot at 50% | none |
| FSR 1 (built-in) | EXPERIMENTAL | L2 | shaders compile in tests; no recorded in-game image check | none |
| NIS (built-in) | EXPERIMENTAL | L2 | same | none |
| FSR 3.1 / 2.x | BETA | L4 | autotest PASS: `fsr=dispatch ok`, all quality modes, version switch, runtime switch, screenshots | release jar includes official `amd_fidelityfx_vk.dll`; only tested on NVIDIA |
| XeSS | BETA | L4 | `xess=dispatch ok ... input=854x480 output=1280x720`; quality vs other upscalers not assessed | release jar includes `libxess.dll`; only tested on NVIDIA |
| DLSS Super Resolution | BETA | L4 | `ngx_init code=0`, dispatch ok; also with ray tracing (`temporal_upscaler active=true backend=DLSS`) | NVIDIA RTX, `nvngx_dlss.dll`, self-built `vkb_dlss.dll` |
| Runtime switching of upscaler / mode | BETA | L4 for FSR (autotest `upscaler_switch`), L2 for others | log `upscaler_reconfigure ... phase=done` | |
| Texture LOD bias with upscaling | BETA | L2 | unit checks | |
| DLSS frame generation (2x, 3x, 4x) | BETA | L4 (developer run, no recorded benchmark in the repository) | status "Active" requires presented = multiplier x rendered for 30 frames; F3 shows real and generated FPS | RTX 40/50, Streamline files, restart |
| NVIDIA Reflex | BETA | L4 (developer run) | same | Streamline files |
| DLSS SR / Ray Reconstruction through Streamline plugins (when frame generation is on) | BETA | L3 | code path in `vkb_sl.dll`; reported working by the developer | Streamline plugins, self-built `vkb_sl.dll` |
| AMD FSR frame generation | NOT_IMPLEMENTED | L0 | design notes only | |
| FSR 4 | NOT_IMPLEMENTED | [AMD FSR SDK](https://gpuopen.com/amd-fsr-sdk/) | available in the vendor Vulkan SDK, pending Bedray integration | |
| DLSS 5 Neural Rendering | NOT_IMPLEMENTED | NVIDIA partner SDK | local file diagnostics in the Natives Manager | |

## Ray tracing

| Feature | Status | Level | Evidence | Requirements |
|---|---|---|---|---|
| Chunk geometry capture, acceleration structures | BETA | L4 | `blas built=... tlas instances=... failed=0`, screenshots | ray query |
| Ray Tracing (Fast) mode (the ray profile of the Slang pass: primary surface plus one indirect bounce, one sample per pixel; the Shadows / Soft Shadows / Reflections / AO / GI / Emissive switches choose which continuations it traces, and the Reflection / GI / AO / Emissive distance values bound how far each one reaches) | IN DEVELOPMENT, hidden in the release UI (`experimental_rays`) | L2 | `ray_slang(...)` mode log, `[RT-EFFECTS]` F3 line, unit checks of the profiles and of the switch-to-option-bit mapping; the earlier L4 screenshots belong to the deleted GLSL pass and do not validate this one | ray query |
| Path Tracing mode (recommended; main quality mode: GI, bounces, accumulation, auto exposure) | EXPERIMENTAL, available | L4 | accumulated frames logged, screenshots | ray query |
| AO / Direct modes | NOT_IMPLEMENTED in the Slang renderer: refused by name (`slang_ao`, `slang_direct`) | L2 | unit checks pin the refusal | ray query |
| LabPBR `_s` / `_n`, auto PBR, auto normals | EXPERIMENTAL | L4 | `materials baked ... labpbr_s=...`, normals debug view | |
| Emissive block light (next-event estimation) | EXPERIMENTAL | L4 | emitter log lines, night-scene screenshots | |
| NRD denoiser for Path Tracing | EXPERIMENTAL | L2 (the earlier L4 `denoiser_ready mode=nrd_reblur` run belongs to the deleted GLSL pass) | the Slang pass writes the same seven guides and NRD's motion, unit checks pin every channel, and the setting resolves again under a trace mode; no owner-verified frame consumes them yet. Reflections stay blurred (no specular input) | `NRD.dll` + `vkb_nrd.dll` |
| DLSS Ray Reconstruction | EXPERIMENTAL | L2 for the Slang guides (L5 on the deleted GLSL pass) | the owner confirmed DLSS-RR on the RTX 5060 Ti before the migration; the guide layout is unchanged and now filled by the Slang primary vertex, not re-verified on any system yet | NVIDIA RTX, `nvngx_dlssd.dll` |
| Accumulation-only fallback (no denoiser library) | BETA | L4 | used when libraries are missing | |
| Volumetric / distance fog | EXPERIMENTAL | L2 | | |
| Entities, items, hand in the traced scene | EXPERIMENTAL | L1 | code and unit checks only; no recorded in-game image check | |
| Particles in the traced scene | EXPERIMENTAL | L1 | partial; particles may not cast shadows or appear in reflections | |
| Water and glass | EXPERIMENTAL | historical L4 | Earlier screenshots only. Water has a realistic Snell/medium approximation; glass remains a thin filter. Current integration has no fresh evidence | |
| Animated block textures in materials | EXPERIMENTAL | L4 | only the first frame is baked | |
| RT debug buffers | EXPERIMENTAL | L4 | normals, depth, albedo views | |

## Not included

| Feature | Status | Notes |
|---|---|---|
| General Iris / "Dirt RT" shader-pack compatibility | NOT_IMPLEMENTED | [Source-only post subset](IRIS_VULKAN.md) is implemented experimentally; no game/GPU validation or released support claim |
| Linux, macOS | NOT_IMPLEMENTED | Windows only |

Historical evidence applies only to its named build. Current implementation scope is listed above and in the linked source docs.
