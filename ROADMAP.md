# Bedray roadmap

**Status: active alpha planning, current public build `0.1.0-alpha.1`.** This roadmap describes direction and acceptance gates, not release dates or guarantees. Bedray is expected to change substantially: renderer internals, settings, configuration, compatibility and supported features may change between alpha versions. Bugs, crashes, visual artifacts and performance changes are possible. Use a separate test instance and back up worlds and configuration before trying new builds.

The project is unofficial and client-side. The current baseline is Minecraft Java 26.3, Sodium 0.9.x, Java 25, Windows, native Vulkan, and NVIDIA RTX 5060 Ti. Other GPUs and Fabric need more evidence. See the [feature matrix](docs/FEATURE_MATRIX.md), [known issues](docs/KNOWN_ISSUES.md), [release checklist](docs/RELEASE_CHECKLIST.md), and [testing guide](docs/TESTING.md).

## Product goal

Deliver a maintainable, well-instrumented Vulkan renderer add-on for Minecraft Java that gives consistent path-traced lighting and materials, usable temporal reconstruction and upscaling, and optional frame generation, while keeping the ordinary game usable when an optional vendor feature is absent.

A feature is not considered done merely because its code compiles or a unit test passes. Completion requires the appropriate combination of focused tests, clean packaging, fresh logs from the matching build, and visual checks in the game. User acceptance is recorded separately from developer evidence.

## Phase 0 — Verify the current alpha and build a support baseline

**Priority: P0. Status: incomplete; alpha `0.1.0-alpha.1` is published, but publication is not live-game acceptance.**

- Recheck the exact published Fabric and NeoForge JARs in clean instances; save the matching `latest.log`, crash report (if any), diagnostic report and screenshots for each run.
- Exercise startup, world entry, path tracing, denoiser/upscaler initialization and shutdown. Record DLSS Super Resolution, Ray Reconstruction and Frame Generation as separate capabilities; test FG with tracing enabled and confirm presented/generated-frame counters rather than inferring success from a toggle.
- Reproduce the reported redstone fringe, movement-correlated lighting instability, dark pixels and noisy/distant reflections, missing metallic response, glass/water behavior and glowing sign text. Keep each issue separate from reconstruction unless logs/captures show the denoiser is the cause.
- Use the five repeatable scenes defined in [TESTING](docs/TESTING.md): desert daylight, sunset, water, closed room and lava at night. Add small focused scenes for redstone alpha/emission, glowing signs, metal/reflection at near and far distances, glass at several angles, and moving emissive particles.
- Keep loader-specific downloads clearly named; inspect JAR metadata, native contents, licenses and hashes for every candidate release.
- Keep install, troubleshooting, feature evidence and known limitations synchronized with the exact artifacts. F3 and reports must show actual runtime state and distinguish unavailable, disabled and active features; separate rendered FPS from generated/presented FPS.

**Exit gate:** both loaders launch from the exact published artifacts in clean instances; a fresh evidence bundle records the exact artifact hashes and build IDs, startup/tracing/denoiser/upscaler/frame-generation states, repeatable scenes and remaining defects. Document which checks are blocked by hardware, optional libraries or runtime hooks. A passing build or unit suite alone does not pass this gate.

## Phase 1 — Startup, lifecycle and crash reliability

**Priority: P0.** Establish a dependable baseline before expanding rendering features.

- Reproduce and fix startup failures in the Vulkan backend, geometry capture, shader/pipeline creation, denoiser initialization and swapchain/present integration.
- Exercise repeated world entry/exit, resize, settings changes, device loss, optional-library absence and shutdown/restart.
- Make DLSS Super Resolution initialization errors actionable and independent from Ray Reconstruction and Frame Generation state.
- Make Streamline wrapper/export mismatches degrade gracefully; report the missing function/library rather than leaving ambiguous “active” status.
- Preserve useful crash context and include the active backend, selected denoiser/upscaler, runtime initialization results and artifact build ID in diagnostics.

**Exit gate:** no known release-blocking startup or teardown crash on the supported configuration; repeated clean runs and lifecycle changes have matching logs; missing optional libraries leave the game in a safe documented state.

## Phase 2 — Temporal stability and reconstruction

**Priority: P0/P1.** Fix light and image motion that does not correspond to scene motion.

- Verify camera bob, camera shake, view transforms, reprojection matrices, jitter and motion vectors use the same current and previous camera poses.
- Test standing, walking, sprinting, jumping, turning, head bob on/off, render-scale changes, camera cuts and auto-exposure transitions in a fixed lighting scene.
- Verify NRD depth, normals, albedo and motion guides against the ray-hit surface; verify DLSS SR and DLSS Ray Reconstruction independently.
- Ensure history invalidation handles teleports, disocclusion, geometry rebuilds and denoiser/upscaler switches.
- Compare captured frames and per-frame runtime logs so a stable reconstruction path is not confused with an unrelated lighting or material defect.

**Exit gate:** no camera-correlated lighting swim in the agreed motion scenes; no persistent ghost trails after disocclusion; each denoiser/upscaler reports its real state and passes a fresh in-game run on the release build.

## Phase 3 — Vanilla material and alpha correctness

**Priority: P1.** Make common blocks and items read as their Minecraft materials.

- Fix cutout and translucent alpha coverage, starting with redstone dust's blocky gray fringe and emissive textures.
- Validate color space, texture filtering, UVs, mip selection, roughness, metallic response and normal orientation on iron, gold, copper, stone, glass and water test scenes.
- Keep sign text, glowing signs, maps and item/block models consistent between raster output and traced material data.
- Handle animated textures deliberately: update material frames or label and test the static-frame approximation by material class.
- Maintain a small reproducible material scene and reference captures for every fix.

**Exit gate:** no black/pink/gray coverage defects in the curated material scene; alpha and normals match the source texture/model; changes pass captured comparisons on both loaders.

## Phase 4 — Lighting, reflections and light transport

**Priority: P1.** Improve the correctness and cost of secondary light paths.

- Separate diffuse and specular signals sufficiently for the selected denoiser; avoid blurring reflection detail as a side effect of diffuse cleanup.
- Diagnose black pixels, fireflies, missing hit data and unstable material response in reflected geometry.
- Make roughness/metallic response and reflection confidence behave predictably over distance and camera motion.
- Evaluate glass and water beyond the current thin-surface approximation; implement refraction only with a clear correctness and performance budget.
- Keep ray depth, samples per pixel, denoising, upscaling and quality presets observable so reflection cost can be measured rather than guessed.

**Exit gate:** reflection scenes have documented visual comparisons and GPU timings; no unexplained persistent dark-pixel defects; quality/performance settings have bounded, repeatable costs.

## Phase 5 — Particles, emitters and dynamic content

**Priority: P1/P2.** Bring moving and emissive vanilla objects into the same lighting model.

- Verify emissive particles contribute light with sensible intensity and lifetime.
- Decide and document which particles cast traced shadows or appear in reflections; test particle replay/compositing under motion.
- Improve entities, held items, dropped items and the first-person hand, with explicit coverage and limitations.
- Keep light from torches, redstone, lava, fire, portals and other emissive materials stable as blocks and animations change.

**Exit gate:** reproducible scenes for torches, powered redstone, fire/lava, portal effects and particles; captures and logs confirm light contribution; unsupported shadow/reflection behavior is clearly stated.

## Phase 6 — Frame generation and presentation

**Priority: P1/P2.** Make generated frames useful, measurable and robust across supported window modes.

- Validate Streamline setup, required external libraries, focus/present hooks, frame pacing and resize behavior.
- Report rendered FPS and generated/presented FPS separately in F3; never infer generated FPS from the multiplier alone.
- Test window focus changes, menus, UI overlays, world transitions and pause behavior.
- Keep Frame Generation optional and independent from DLSS SR and Ray Reconstruction so one failed feature does not misreport another.

**Exit gate:** the status is backed by real present counters and a fresh matching-build log; generated FPS is visible beside base FPS; no claim of active generation when no generated frames were presented.

## Phase 7 — Performance, memory and compatibility

**Priority: P2.** Expand beyond the one currently tested GPU/configuration.

- Measure GPU time by pass and track VRAM use in repeatable scenes at native and upscaled resolutions.
- Bound acceleration-structure, history, denoiser and texture memory; exercise low-VRAM behavior and render-distance changes.
- Compare sample count, ray depth, denoiser and reflection quality against measured cost.
- Build a compatibility matrix for NeoForge and Fabric first, then AMD RDNA 2+ and Intel Arc where hardware is available.
- Test vendor-library versions and graceful fallback paths; do not bundle files unless redistribution terms permit it and notices ship with them.

**Exit gate:** every supported combination has a reproducible start/test procedure and recorded evidence; unsupported combinations are not advertised as tested.

## Stable-release gate

A stable release requires all of the following for each advertised loader/GPU configuration:

1. Reproducible clean build, archive inspection, dependency/license review and published hashes.
2. No open release-blocking crash or data-corruption issue.
3. Fresh game logs and captures from the exact candidate artifacts for startup, path tracing, movement stability, materials, reflections, denoising/upscaling, and frame generation where advertised.
4. Clear performance and memory expectations, plus reliable diagnostics for unavailable optional features.
5. Updated install guide, compatibility matrix, known issues and release notes.
6. Project-owner acceptance of the exact candidate build.

Until these gates are met, releases remain alpha or beta and may change substantially.

## How to track this roadmap

- **P0:** startup, crash, data-integrity or misleading runtime-state problem.
- **P1:** core image correctness, temporal stability or primary advertised feature.
- **P2:** secondary quality, feature coverage or performance refinement.
- **P3:** portability, polish or exploratory work.

Roadmap items should be converted into focused GitHub issues with a reproduction scene, affected build, acceptance evidence and dependencies. Keep each defect independently trackable (for example, alpha coverage, emissive classification, temporal motion, material normals, reflection hit data, denoiser state and frame presentation). Close an issue only when the acceptance evidence is attached; update this document when priorities or scope change. This plan is intentionally broad, but every phase should ship in small reviewable changes with evidence rather than one risky renderer rewrite.
