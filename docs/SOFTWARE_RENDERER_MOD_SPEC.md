# Bedray Compute — companion mod specification

**Status: staged implementation in development.** Implemented slices include the synthetic voxel scene, CPU/Vulkan traversal, companion integration and a bounded loaded-world occupancy diagnostic with current camera data. World capture/presentation has not yet passed in-game acceptance, and support/performance on non-RT hardware is unverified. See [current implementation and build instructions](COMPUTE.md). The remaining sections describe the full target, including work that has not been implemented.

Working name: **Bedray Compute**
Working mod ID: `bedray_compute`
Target baseline: Minecraft Java 26.3, Sodium 0.9.x, Java 25, Fabric and NeoForge.
Required renderer path: Vulkan compute. Dedicated RT units and Vulkan RT extensions must not be required by this backend.

---

## 1. Product definition

Bedray Compute is an optional renderer-backend provider for Bedray. It supplies software ray tracing with voxel traversal and path-tracing effects implemented in compute shaders. During the experimental phase it is installed as a separate mod and contributes its controls to Bedray's own graphics settings.

The long-term player-facing renderer choices should be:

1. **Automatic** — resolve to an available backend using a deterministic, documented policy.
2. **Hardware Ray Tracing** — Bedray's current Vulkan ray-tracing implementation when all required extensions are available.
3. **Software Voxel Tracing** — this mod's compute implementation; it must work without Vulkan RT extensions or dedicated RT units.
4. **Rasterized** — ordinary rendering and reliable fallback.
5. **Hybrid** — future option only, for a measured mix such as raster primary visibility plus software-traced shadows/reflections. Do not expose it until each effect and status is independently implemented.

This is a renderer extension, not a second game renderer. It must not own another Vulkan device, queue, swapchain, window, Sodium installation, or Minecraft lifecycle.

---

## 2. What “works on any GPU” means

“No RT cores required” means the software backend performs its own traversal in compute shaders. It must not require or invoke `VK_KHR_acceleration_structure`, `VK_KHR_ray_tracing_pipeline`, `VK_KHR_ray_query`, NVIDIA RTX APIs, or vendor DLLs.

It still needs a GPU/driver able to run Bedray's Vulkan renderer and the compute, storage-image, storage-buffer, synchronization, and shader features selected by the implementation. It cannot run on literally every adapter, old driver, software display device, or unsupported operating system.

Keep two claims separate:

- **API compatibility:** no RT extension is required; the device can create Bedray's ordinary Vulkan compute pipeline.
- **Usable performance:** the device renders the selected quality within acceptable frame-time and memory limits.

Do not infer the second claim from the first. Establish a practical minimum spec using NVIDIA, AMD, Intel discrete and integrated GPUs. If tracing is too slow or cannot allocate scene data, show the reason and fall back to raster. The minimum Vulkan version and shader profile must be measured and written into the compatibility matrix; do not promise them merely because they compile locally. Optional shader features must be capability-checked.

---

## 3. Existing work to reuse

Do not copy the current renderer wholesale. Find stable data and scheduling boundaries, then expose them through a small public API.

| Existing Bedray work | Reuse | Boundary |
|---|---|---|
| `SodiumMeshDecoder`, `DecodedSection`, `SectionCapture`, `SectionGeometryRegistry` | Immutable section geometry on chunk rebuild; source for voxelization | Consume versioned snapshots. Never read mutable Sodium buffers from a ray-dispatch thread. |
| `RtWorld` and section lifecycle | Section coordinates, load/unload/update events, world identity | Do not use internal BLAS/TLAS handles; those belong to the hardware backend. |
| `MaterialAtlasBaker`, `AutoPbr`, `AutoNormals`, LabPBR | Texture/material lookup, colour, alpha, emission, roughness, metallic and normal data | Expose stable material records or an atlas view through the API. |
| `RtPathTracerPass` and `rt_path.comp` | Sampling, temporal accumulation concepts, emissive lighting, current visual reference | Port algorithms deliberately. Hardware ray-query calls are not the software traversal. |
| `RtAccumulation` and motion/reprojection work | History lifetime, invalidation, camera conventions and stability diagnostics | Reuse host frame identity/camera data; do not create a conflicting history owner. |
| `NrdDenoiserPipeline`, NRD and A-Trous | Optional denoising and existing measurements | Provide a built-in fallback. NRD remains optional and is not a renderer prerequisite. |
| `RtFrameRenderer`, `RtCompositePipeline`, `RtTimings` | Scheduling, composition, GPU timings and diagnostics | Bedray owns presentation and render-graph order. The companion contributes a pass. |
| `SettingsCatalog`, `SettingsPolicy`, `VulkanSodiumConfig`, `BedrayConfigScreen` | Labels, descriptions, tiering, previews and player expectations | Refactor to provider-based settings first. No reflection, private mixins, or direct writes into Bedray static fields. |

Existing code is reusable work, not proof that the new backend is complete or that the current Bedray release supports non-RT GPUs.

---

## 4. Repository and module plan

Keep backend code loader-neutral. The initial development uses separate modules and companion JARs in the Bedray repository so host API changes and provider changes can be reviewed atomically. A separate repository/release project remains a later packaging decision, after the API and live-world path have been accepted.

Current modules: `renderer-api` (host-owned API), `compute-common` (grid, traversal, compute resources), `compute-fabric` and `compute-neoforge` (companion entry points). The companion does not include host/API classes. The layout below describes a possible later repository split.

~~~text
bedray-compute/
  settings.gradle
  build.gradle
  software-common/
    src/main/java/...
    src/main/resources/shaders/...
    src/test/java/...
  fabric/
    src/main/java/...
    src/main/resources/fabric.mod.json
  neoforge/
    src/main/java/...
    src/main/resources/META-INF/neoforge.mods.toml
  docs/
    ARCHITECTURE.md
    TESTING.md
    THIRD_PARTY_NOTICES.md
~~~

- `software-common` contains voxel structures, CPU voxelization, compute shaders, traversal, GPU resources and backend settings. It must not import Fabric or NeoForge APIs.
- `fabric` and `neoforge` contain only loader entry points, metadata and registration adapters.
- Build one companion JAR per loader; never install both in one instance.
- The companion includes no DLLs and performs no vendor-binary download.
- Match the targeted Bedray/Minecraft/Sodium/Java compatibility ranges.

### Public API prerequisite

Bedray's settings and renderer code are currently internal and coupled to the concrete renderer. Before implementing the companion, add a small, versioned `bedray-api` module to Bedray and publish it separately from loader-specific implementation classes.

The companion compiles against that artifact as provided/compile-only and declares Bedray as a required runtime mod. It must not shade/package a second API copy. Use a Gradle composite build or documented local Maven publication for development; releases use an immutable versioned API artifact.

The broader world-renderer API below remains proposed. The implemented diagnostic seam is `io.github.andrexarlay.bedray.api.v1` with `GraphicsBackend`, `ComputeFrameContext`, `GraphicsBackendRegistry`, `VoxelVolume`, `SectionSnapshot`, `WorldSnapshot`, `WorldSnapshotStream` and `CameraSnapshot`. It carries bounded occupancy classifications and a current camera; full materials, entities and temporal reconstruction guides are not part of that seam yet.

Proposed world-renderer package:

~~~text
io.github.andrexarlay.bedray.api.v1
~~~

Do not expose `io.github.andrexarlay.vulkancompat` classes as public API. Internal names/layouts may change; the public API must be deliberately maintained.

---

## 5. Required Bedray API

The first API release covers backend registration, scene snapshots, render services and settings contribution.

### 5.1 Backend registration and status

Provide a provider contract equivalent to:

~~~java
interface GraphicsBackendProvider {
    BackendId id();
    ApiVersion apiVersion();
    BackendDescriptor descriptor();
    Availability probe(DeviceCapabilities capabilities);
    GraphicsBackend create(RendererServices services);
}
~~~

It must support a stable namespaced ID, API version, localized name/description, required/optional capabilities, availability with machine-readable code and readable reason, lifecycle callbacks, and truthful runtime status: unavailable, initializing, active, degraded, failed, shutting down.

A provider is not active merely because it registered, a setting is selected, a pipeline compiled or a dispatch was submitted. “Active” means successful composition into the presented game frame.

### 5.2 Immutable scene snapshots

Expose versioned transfer objects containing:

- World/session ID and monotonically increasing section revision.
- Section world coordinates.
- Geometry or normalized block/material records.
- Material references and texture-atlas metadata.
- Opaque/cutout/translucent classification.
- Emission, roughness, metallic and normal data when available.
- Previous/current transform and motion data only if the host can provide valid values.
- Validity flags for unavailable data.

Define ownership and release for every borrowed buffer. Use ref-counted or equivalent safe buffers if copying all vertices would stall chunk rebuilds. No pointer may outlive its source snapshot.

### 5.3 Render services/frame context

Bedray owns the Vulkan instance, device, queues, swapchain, synchronization and final composition. A backend may receive a constrained context to allocate resources and record passes. The API must specify the valid thread/phase, resource ownership, destruction order, image layouts, access masks, synchronization, resize/device-loss callbacks, pass failure behavior, and borrowed-vs-retained resource lifetimes.

Prefer Bedray-owned allocators and render-graph callbacks to unrestricted handles. If raw LWJGL handles are necessary initially, isolate them in one explicitly unstable low-level package; keep UI, config, scene and capability APIs independent of LWJGL.

### 5.4 Settings contribution

A setting contribution needs a stable namespaced key and type, default/range, localization keys, title, description, cost and requirements, capability-based visibility/enabled state, update effect (live/history reset/world reload/restart), optional preview or honest no-preview reason, and a callback into backend-owned configuration.

Bedray renders the contribution in its existing settings screen. If Bedray/API is missing or has an incompatible major version, the companion fails closed with a clear dependency message; it must not crash while opening the title screen.

---

## 6. Backend selection and fallback

Bedray owns one authoritative renderer selection:

~~~toml
[graphics]
backend = "AUTO" # AUTO, HARDWARE_RT, SOFTWARE_VOXEL, RASTER
~~~

Selected choice and active backend are different values.

- `RASTER` selects ordinary rendering and initializes no tracing backend.
- `HARDWARE_RT` selects the existing hardware path only when all device requirements are present. Otherwise show the exact reason; do not relabel another backend “Hardware RT”.
- `SOFTWARE_VOXEL` selects the companion compute path. Vulkan RT extensions are not a prerequisite.
- `AUTO` uses a deterministic documented policy. Initial policy may prefer hardware RT when supported and software voxel otherwise. Show the resolved choice; do not silently change quality/mode.
- If initialization/frame execution fails, release partial resources safely, log the stage/reason, then fall back to Raster. Automatic switching between tracing backends is permitted only for `AUTO` and must be documented.
- If the companion is absent while `SOFTWARE_VOXEL` is saved, report provider missing and offer Auto/Raster; never report the path active.
- Backend switching initially requires restart if live switching could leave competing GPU resources active. Enable live switching only after repeated lifecycle tests pass.

---

## 7. Voxel scene representation

Exploit Minecraft's 16×16×16 section layout without allocating a heavyweight object per block.

### 7.1 Source of truth

Initially consume Bedray's immutable captured sections/materials through the API. Never scan the entire world each frame or call world/block APIs from an arbitrary GPU worker.

Compare:

1. **Block-state voxelization:** preserves discrete block identity, but requires safe access to block state, model shape, resource-pack data and change notifications.
2. **Captured-mesh voxelization:** reuses Sodium capture and supports baked models, but quantizes triangle surfaces and may lose thin geometry, exact alpha and sub-block details.

Start with captured meshes and a small explicit material scope. Record the chosen voxel resolution/error. Do not claim equivalence with exact triangle intersections when the result is approximate.

### 7.2 Candidate data model

Use compact section records:

- Section origin/revision.
- Coarse occupancy/hierarchy for skipping empty regions.
- Compact cell/material references, not per-cell Java objects.
- Packed opaque/cutout/translucent/emissive/special-shape flags.
- Shared material and texture tables.
- Optional microvoxel/analytic data for partial shapes only after measurements justify it.
- Incremental world-to-section index.

A first prototype can use one logical voxel per full-cube block and a special-shape path for other geometry. Never turn stairs, slabs, fences, plants, glass or water into falsely opaque cubes. For unsupported geometry choose a truthful option: accurate software-triangle fallback, documented raster composite, or explicit omission from the alpha. Unknown material must never become a black hit or unintended emitter.

Suggested hierarchical traversal:

1. Traverse coarse sections and skip unloaded/empty ones.
2. Use 3D DDA through occupied cells.
3. Resolve material and surface data at a hit.
4. Use analytic/microvoxel data for supported special shapes.
5. Take the explicit fallback for unsupported geometry.

Before freezing the format, profile section memory, build time, upload bandwidth, traversal cost and cache locality.

### 7.3 Ray-hit contract

A hit record must provide world position/distance, geometric and optional shading normal, material ID/base colour, alpha result, roughness/metallic/emission, UV/texture coordinate when needed, stable section/material identity for history validation, and motion data only when valid.

Alpha correctness is a release blocker. Compare redstone dust, leaves, grass, glass, signs and cutout textures against the same material inputs as Bedray's triangle backend.

---

## 8. Compute renderer scope and performance

The implementation uses ordinary Vulkan compute only. It must not use RT pipeline stages, shader binding tables, acceleration structures, ray queries or vendor ray libraries.

### First renderer milestone

Keep the first milestone narrow:

- Opaque full-cube world blocks.
- Primary visibility and direct sun/sky lighting.
- At most one diffuse bounce.
- One sample/pixel, configurable internal resolution.
- Conservative temporal accumulation/history resets.
- Built-in simple denoiser/spatial filter or Bedray's denoiser interface.
- Composition into a Bedray-owned target.
- Rasterized UI and safe entity/hand handling until supported.

Then add in separate changes: emissive blocks/direct shadows; indirect light; rough/metal reflections; cutout and partial geometry; glass/water; entities/block entities/items/particles; higher-quality presets.

No setting may claim a feature before the scene data and shader implement it. Hide unsupported controls.

### Performance rules

- Build/upload only on section revision changes.
- Never device-idle for ordinary chunk rebuilds.
- Avoid full-world CPU arrays every frame.
- Bound section count, voxel memory, histories and scratch buffers.
- Defer destruction until in-flight frames finish.
- Make resolution, SPP, path depth, distance, voxel detail and denoising change actual work.
- Time voxelization, upload, tracing, denoising and composition separately.
- On budget exhaustion, evict safely or fall back; do not silently exhaust VRAM.
- Establish FPS/memory targets after multi-device measurements; never promise fixed FPS for every GPU.

---

## 9. Settings and configuration

The Bedray screen remains the player's graphics control center. The companion provides a “Software Voxel” group/page and a status row in the main renderer selector.

Initial settings:

| Setting | Suggested values | Notes |
|---|---|---|
| Renderer | Auto, Hardware RT, Software Voxel, Raster | One persisted choice; separately show active renderer. |
| Software quality | Fast, Balanced, Quality, Custom | Presets map to verified values and honest cost text. |
| Internal resolution | Native, 75%, 67%, 50% | Apply before tracing; invalidate incompatible histories. |
| Samples per pixel | 1, 2, 4 | Alpha default 1. |
| Path depth | 1, 2, 3+ | Start at 1, cap by preset and measure marginal cost. |
| Ray distance | Bounded block distance | Report truncation; no “infinite” claims. |
| Voxel detail | Block, Enhanced Shapes, Custom | Show enhanced only when implemented. |
| Temporal accumulation | Off/On | Reset on camera cuts, edits, section changes, resize, world changes and relevant settings. |
| Denoiser | Built-in; NRD if installed | NRD optional, never required to render. |
| Debug view | Final, Albedo, Normal, Emission, Occupancy, Hit Distance | Label approximate data honestly. |

Keep beginner controls concise; put low-level controls in Advanced/Expert. Use Bedray's descriptions/cost/requirement/preview conventions. Disable unavailable values with a specific reason. Persist through the Bedray config owner with versioning and validation. Invalid values must repair to safe defaults and log the key/reason. State whether a change is live, resets history, reloads world or needs restart.

Example config shape; final key names are an API decision:

~~~toml
[graphics]
backend = "AUTO"

[software_voxel]
quality = "BALANCED"
resolution_scale = 0.67
samples_per_pixel = 1
path_depth = 1
max_ray_distance = 96
temporal_accumulation = true
denoiser = "BUILT_IN"
voxel_detail = "BLOCK"
~~~

Exactly one component owns each persisted key. Bedray and the companion must never independently write the same setting.

---

## 10. Runtime lifecycle and integration

1. Bedray discovers providers and validates API major/minor compatibility.
2. Bedray creates/validates the Vulkan device; hardware RT capability is optional and separately reported.
3. Bedray resolves the selected backend and asks its provider to probe capabilities.
4. An unavailable provider gives a reason; Bedray applies the defined fallback.
5. At world start Bedray assigns a session ID and sends bounded immutable section snapshots.
6. On section rebuild/load/unload, Bedray sends revision/change/removal events.
7. The provider stages voxelization/upload without blocking the game thread.
8. At an agreed render-graph phase the provider records compute passes with Bedray-owned targets/synchronization.
9. Bedray composes, updates state/timings, and presents via its existing host.
10. Resize, reload, world change, backend switch, device loss and shutdown invalidate histories/release resources in documented order.

The companion must tolerate empty worlds, repeated enter/exit, rapid chunk changes, config changes, missing optional data and absent upscaler/denoiser libraries.

Do not use private mixins into Sodium, Minecraft or Bedray renderer code as the primary integration. If a hook is missing, add it to Bedray's API/host and cover lifecycle behavior.

---

## 11. Diagnostics and player-facing status

UI, F3 and reports distinguish requested, resolved and active backend; provider/API version; GPU vendor/device and Vulkan version; whether RT extensions were required (must say “no” for Software Voxel); section/update counts; voxel memory/budget/evictions; voxelization/upload/trace/denoise/composite timings; SPP/depth/resolution/distance; active denoiser and upscaler independently; fallback/failure stage and reason.

Use stable reason codes plus readable explanations, e.g. `provider_missing`, `api_incompatible`, `compute_feature_missing`, `scene_budget_exceeded`, `shader_compile_failed`, `device_lost`, `fallback_raster`.

A setting is not an active state. Keep frame generation status separate; this mod must not change the meaning/ownership of existing F3 values.

---

## 12. Licensing, security and packaging

- Keep source/license choices explicit. Unless decided otherwise, keep new code compatible with Bedray's LGPL-3.0-or-later licensing and retain notices for reused code.
- Reuse Bedray work through its API; copied code/shaders retain applicable notices.
- Include no NVIDIA DLSS/NGX/Streamline/NRD binaries or static libraries. The compute backend does not need them.
- Do not copy driver, Windows, Minecraft, Sodium, launcher or random game DLLs.
- Before adding third-party code/assets, record exact source/version/hash/license/redistribution permission.
- A downloader does not bypass vendor terms; do not download/rehost vendor binaries.
- Publish separate Fabric/NeoForge companion artifacts with Bedray required dependency/API range, checksums, build provenance and tested compatibility matrix.

---

## 13. Testing and acceptance

A passing unit suite is not proof of a working renderer.

### API/CPU tests

Cover provider registration/duplicates/API compatibility; capability resolution with RT support present and absent; Auto/explicit/fallback behavior; section indexing including negative coordinates; revision ordering/add/change/remove; empty/full occupancy; deterministic voxelization; bounds/normals/UV/material; alpha and unknown-material fallback; budget/eviction/deferred destruction; history resets on camera cuts, edits, section change, resize, backend/world change.

### Shader/build validation

Compile all compute shaders to the declared SPIR-V profile; validate descriptors, push constants, formats and bounds; use validation layers in developer runs; fail closed if a shader/resource is missing.

### In-game acceptance

Test empty/flat and dense builds, caves/interiors, foliage, water/glass, redstone, torch/lava/fire and custom resource-pack models. Exercise walk/head bob, sprint, jump, turn, teleport, resize, world transition, section rebuild, repeated shutdown/restart, missing companion/libraries, unsupported compute, budget pressure and injected backend failure. Record frame time and memory by quality preset.

At least one actual NVIDIA, AMD and Intel device is required before claiming cross-vendor support; integrated GPU support needs integrated-device evidence. Prove Software Voxel works with RT extensions unavailable/masked. An RTX model name or successful shader compile is not that proof.

Release criteria:

1. Exact Bedray/companion build IDs and hashes recorded.
2. Requested/resolved/active states are correct.
3. Software mode works without Vulkan RT extensions.
4. No release-blocking crash/deadlock in tested lifecycle.
5. No unexplained black pixels, alpha fringe, camera-correlated light swim or false emission in agreed scenes.
6. GPU time/memory/limitations recorded for each tested device/preset.
7. Missing capability falls back safely and explains why.
8. Screenshots/logs match the published artifacts.

Until these gates pass, mark it **experimental** and list exactly tested hardware.

---

## 14. Implementation phases and review gates

### A — Host API

In Bedray: extract backend/capability/settings/scene/render contracts; adapt the hardware backend without behavior change; preserve current settings; add requested/resolved/active status; publish a versioned API and sample provider. Do not begin voxel shader work until both loaders build and lifecycle contracts are covered.

### B — Companion shell

Create shared/Fabric/NeoForge modules, dependency/API checks, provider registration and one settings group. Probe capabilities and show unavailable reason. Verify the JAR contains no duplicate API, Bedray implementation, RT-only native artifact or vendor DLL.

### C — CPU voxel prototype

Voxelize deterministic synthetic full-cube sections; implement reference CPU DDA; compare hits to reference geometry; measure sparse/dense memory and build time. Decide partial-shape representation before GPU work.

### D — Compute prototype

Trace a synthetic scene into hit distance/normal/material debug buffers. Prove it runs without RT extensions enabled. Mask RT capability where possible. Measure before adding multi-bounce lighting.

### E — Live world

Connect section snapshots/revisions, opaque blocks, direct light, one-bounce PT, temporal accumulation and composition. Integrate denoising/upscaling only through Bedray API. Add diagnostics/fallback. Release an experimental companion for a clean test instance.

### F — Correctness/features

Add alpha, partial shapes, materials, reflections, fluids, entities and particles one at a time. Each needs a reproducer, measured cost, fallback decision and acceptance evidence.

### G — Cross-GPU/performance

Run shared scenes across vendors; tune resolution, traversal, memory, SPP and denoising by measured GPU tier. Publish supported combinations and failures. Keep hardware RT optional and independent.

---

## 15. Future merge into one Bedray mod

Keep the companion's algorithms independent of loader and private host classes so they can move into Bedray without changing behavior.

After acceptance:

1. Merge the versioned API and backend into Bedray shared modules while preserving source history where practical.
2. Keep backend IDs, setting keys/defaults, diagnostics codes, and test scenes.
3. Register Software Voxel as a built-in provider instead of a companion entrypoint.
4. Remove companion-only loader metadata and runtime dependency; keep a separate internal renderer module.
5. Retain one UI/config owner, one device/swapchain owner, one scene-capture contract and one backend per frame.
6. Remove duplicate/shaded API and obsolete reflection/mixin integration.
7. Migrate config once, preserving user values or logging an explicit migration.
8. Keep Raster, Hardware RT, Software Voxel and Auto selectable.
9. Detect/prevent duplicate registration if an old companion remains installed; document transition.
10. Retire companion releases only after merged artifacts pass the same build, lifecycle, GPU, license and in-game gates.

A single Bedray JAR may contain multiple strategies; it must not contain competing settings sources or presentation owners.

---

## 16. First engineering tickets

Create small reviewable issues in this order:

1. `BEDRAY-API-001` — inventory lifecycle/capabilities/config/UI; propose SPI.
2. `BEDRAY-API-002` — add versioned API and adapt current hardware backend.
3. `BEDRAY-API-003` — refactor settings to provider contributions, preserving behavior.
4. `COMPUTE-001` — create loader shells and API compatibility checks.
5. `COMPUTE-002` — immutable section/material snapshots and ownership rules.
6. `COMPUTE-003` — CPU voxelization and reference DDA for full cubes.
7. `COMPUTE-004` — benchmark representation, build time and memory.
8. `COMPUTE-005` — compute hit-test shader and debug views.
9. `COMPUTE-006` — incremental chunk revisions and safe uploads.
10. `COMPUTE-007` — direct light, path accumulation, denoiser hook and composition.
11. `COMPUTE-008` — F3, failure injection, fallback and lifecycle coverage.
12. `COMPUTE-009` — validate NVIDIA/AMD/Intel without RT-extension support.
13. `COMPUTE-010` — decide treatment for partial shapes, alpha, fluids and entities.
14. `COMPUTE-011` — experimental release and exact compatibility evidence.
15. `COMPUTE-012` — execute merge checklist after acceptance.

Every issue states API version, repro/benchmark scene, expected behavior, measured result and close-out evidence.

---

## 17. Instructions to the implementer

- Treat this document as requirements, not proof of implementation.
- First deliver the Bedray API seam and adapt the existing backend. Do not build a parallel renderer coupled to private internals.
- Keep software traversal independent of RT extensions and vendor binaries.
- Keep device, queues, frame graph, settings, config and presentation owned by Bedray.
- Reuse geometry/material work only through versioned immutable APIs.
- Start with opaque full cubes and hit debug output; add materials/path tracing after traversal is verified.
- Explicitly mark unsupported blocks/materials; never silently turn them into opaque/emissive voxels.
- Do not claim universal GPU support. Publish a measured device matrix and minimum requirements.
- Fail back to Raster with an actionable reason.
- Keep companion releases separate until cross-vendor evidence passes; preserve a module boundary after merge.
