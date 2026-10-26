# Feature matrix: current source and historical release evidence

## Current main integration (2026-10-26)

The 0.1.1-alpha.13 source combines the development branches listed in [DEVELOPMENT_BRANCHES](DEVELOPMENT_BRANCHES.md).
**No build, shader compilation, tests, game session or FPS measurement was run for this integration.** Historical evidence
below does not transfer to the combined source. All current changes remain experimental and unreleased.

| Area | Implemented source | Current integration evidence / limits |
|---|---|---|
| Hardware RT | ReSTIR history limits, spatial reuse, pixel shadows, weather capture, GPU geometry dequantization and barriers | Source integration only; runtime and performance unmeasured |
| Transparent interfaces | RT camera/secondary segments use one query with nearest 16 layers sorted; PT reuses 16-entry batches within its 24-interface budget | Source change only; bounded sorting/storage cost unmeasured; unresolved RT transmission suppressed |
| Water / glass | Realistic water Snell/medium approximation; vanilla textured sheet; glass thin tint/Fresnel filter | Full glass volume transport remains absent; water caustics are approximate |
| Software Voxel | Streamed loaded sections, bounded residency, light invalidation and water entry/exit segments | Separate Compute provider nested in player JAR; incomplete coverage preserves raster |
| Iris | Opt-in post subset, world/frame guides and custom RT hooks | General pack support incomplete; see [IRIS_VULKAN](IRIS_VULKAN.md) |
| Shader / pipeline caches | Local SPIR-V warmup with an atomic compiler-profile snapshot, shared driver pipeline cache | Local caching; no Microsoft Advanced Shader Delivery service integration |
| DXGI | Optional compatibility policy for Minecraft's own presentation path | Requires a game version with Mojang's DXGI implementation; project still targets 26.3 |

## Historical evidence: 0.1.0-alpha.1

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
| FSR 4 | NOT_IMPLEMENTED | | not available on Vulkan | |
| DLSS 5 / neural rendering | NOT_IMPLEMENTED | | disabled line in the settings page | |

## Ray tracing

| Feature | Status | Level | Evidence | Requirements |
|---|---|---|---|---|
| Chunk geometry capture, acceleration structures | BETA | L4 | `blas built=... tlas instances=... failed=0`, screenshots | ray query |
| Ray Tracing (Fast) mode (simplified: shadows, soft sun/moon, AO, reflections, block light, basic 1-bounce GI) | IN DEVELOPMENT, hidden in the release UI (`experimental_rays`) | L4 | mode logs, screenshots of test scenes | ray query |
| Path Tracing mode (recommended; main quality mode: GI, bounces, accumulation, auto exposure) | EXPERIMENTAL, available | L4 | accumulated frames logged, screenshots | ray query |
| AO / Direct modes | IN DEVELOPMENT, hidden | L2 | only with `experimental_rays`; less tested | ray query |
| LabPBR `_s` / `_n`, auto PBR, auto normals | EXPERIMENTAL | L4 | `materials baked ... labpbr_s=...`, normals debug view | |
| Emissive block light (next-event estimation) | EXPERIMENTAL | L4 | emitter log lines, night-scene screenshots | |
| NRD denoiser for Path Tracing | EXPERIMENTAL | L4 for diffuse light | `denoiser_ready mode=nrd_reblur`; reflections are still blurred (no specular input) | `NRD.dll` + `vkb_nrd.dll` |
| DLSS Ray Reconstruction | EXPERIMENTAL | L5 (author) | confirmed by the owner on the RTX 5060 Ti; not re-verified on other systems | NVIDIA RTX, `nvngx_dlssd.dll` |
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
