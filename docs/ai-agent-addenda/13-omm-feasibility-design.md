# 13 — OMM 2-state for cutout geometry: feasibility + integration design

Status (2026-11-12): **feasibility verified, integration designed, implementation deferred.**
This note is the deliverable for catalog step "OMM 2-state". It records what was verified,
the full design needed to implement it, and the evidence-based reason it is not enabled now.

## Verdict

Opacity micromaps (VK_EXT_opacity_micromap, 2-state) are **feasible** on this stack but are a
large, cross-boundary, greenfield feature whose benefit for Minecraft terrain is **unproven and
contradicted by the reference workload**. The cheap-stack goal is better served by the existing
software candidate-alpha path, which already works. OMM stays **off / not implemented** until a
foliage- or glass-heavy scene measures a clear win (see Measurement plan).

## Verified facts (zero-trust, file:line)

* **Bindings exist on both sides.** LWJGL 3.3.3 (`lwjgl-vulkan-3.3.3.jar`) ships
  `EXTOpacityMicromap`, `VkPhysicalDeviceOpacityMicromapFeaturesEXT`,
  `VkAccelerationStructureTrianglesOpacityMicromapEXT`, `VkMicromapBuildInfoEXT`,
  `VkMicromapCreateInfoEXT`, `VkMicromapTriangleEXT`, `VkMicromapUsageEXT` (32 micromap classes).
  The pinned SDK Vulkan-Headers (`H:/Sky/tools/sdk/Vulkan-Headers`) declare `vkCreateMicromapEXT`
  / `vkCmdBuildMicromapsEXT`. The earlier "LWJGL lacks EXT bindings" caveat (from SER notes) does
  **not** apply to OMM.
* **Capability detection already exists.** `native/bedray_core/src/core.cpp:635-645` queries the
  extension + `VkPhysicalDeviceOpacityMicromapFeaturesEXT.micromap` and sets
  `BEDRAY_CAP_OPACITY_MICROMAP` (bit 7, `bedray_core.h:65`; Java mirror
  `rt/core/BedrayCoreLayouts.java:39`). Detection only — no build/attachment.
* **No micromap build or attachment anywhere.** Zero `vkCreateMicromapEXT` /
  `VkAccelerationStructureTrianglesOpacityMicromapEXT` in `native/bedray_scene/**`. Scene caps are
  3 bits (`bedray_scene.h:96-98`). `rt/slang/SlangDeviceCapabilities.java:20-28` has no micromap
  entry, so the core cap bit is never surfaced to Java backend selection.
* **BLAS vertices are position-only.** `acceleration_scene.cpp:470`
  `vertexFormat = VK_FORMAT_R32G32B32_SFLOAT`. UV/material are fetched in-shader from a separate
  surface buffer, not present in the native geometry stream. Cutout geometry gets `flags = 0`
  (`acceleration_scene.cpp:491-493`, `blas_pass == 1`); only the solid quad-stream pass is
  `VK_GEOMETRY_OPAQUE_BIT_KHR`.
* **No any-hit shader.** The RT path is RayQuery-in-compute with a software candidate-alpha loop:
  `surface/visibility.slang:54 traceFilteredVisibility` (16-layer budget, hole-skip),
  `contracts/material.slang:82 materialAcceptsAlpha`, `traversal/scene_traversal.slang:132-157`
  commits non-opaque candidates without alpha test. OMM's classic benefit (skipping any-hit
  invocations) does not directly apply; it would instead reduce candidate hits in traversal.
* **Gating pattern to mirror.** `rtx/raytracing/RtRayPipelineDeviceRequirements.java:43-59`
  (`serRequirement()`: extension set + `FeatureRequest.of(...FeaturesNV, "rayTracingInvocationReorder")`,
  startup `-D` gate, host freezes the enabled feature set). Device features are enabled only at
  startup before `vkCreateDevice` (project rule).

## Why deferred (evidence, not preference)

* **Reference workload disables it.** `docs/RTX_REFERENCE_AUDIT.md:65-70`: "Minecraft Remix
  explicitly disables opacity micromaps for its high-volume terrain"; "Shader compilation proves
  no speedup; measure glass and foliage scenes separately." `native/bedray_scene/SCENE_NOTES.md:439,458`
  calls OMM "not modelled in this candidate; open integration gap", ownership undecided.
* **Cost is real, benefit uncertain.** A 2-state micromap adds per-triangle subtriangle opacity
  data (memory + build time) to every cutout BLAS. At RD16 the fixed-seed scene is **43% cutout**
  (1,576,329 / 3,663,500 quads, `docs/measurements/restir_pt_rd16/compute_pt_off.json`). The
  software candidate loop already handles this correctly; OMM would trade memory/build cost for
  fewer candidate hits, which Remix found net-negative for terrain.
* **Disproportionate ABI surface.** Position-only vertices mean OMM baking needs new additive
  transport of per-quad UV/atlas identity to a baking pass, plus micromap lifetime ownership —
  a large change to the scene contract for an optional, likely-neutral optimization.

## Integration design (for future implementation)

Additive only; ordinary candidate alpha remains the fallback. Never required for universal mode.

1. **Device gate (Java, startup).** Add `OMM_PROPERTY = "bedray.rt.omm"` and `ommRequirement()`
   to `RtRayPipelineDeviceRequirements`: base AS extensions + `VK_EXT_opacity_micromap` +
   `FeatureRequest.of(VkPhysicalDeviceOpacityMicromapFeaturesEXT.class, "micromap")`. Register only
   when requested. Host freezes the feature set before `vkCreateDevice`.
2. **Capability surface (Java).** Add a micromap entry to `SlangDeviceCapabilities` fed by the
   existing `BEDRAY_CAP_OPACITY_MICROMAP` core bit; expose supported/enabled/implemented checks
   (implemented=false until step 5 lands). Settings UX (#12) shows it honestly as
   "supported, not implemented" rather than a toggle that does nothing.
3. **Baking pass (Slang/GPU, owns the atlas).** A compute pass per cutout section samples the
   material atlas alpha at 2-state micromap subtriangle centers (subdivision level 2-3), writes a
   packed OMM index buffer + per-primitive usage counts. Runs where UV + atlas already live; no
   atlas readback to CPU. Animated alpha invalidates the section's micromap (ledger 7.4).
4. **Additive scene ABI.** `BlasView` gains `omm_index_address`, `omm_usage_address`,
   `omm_subdivision`, `omm_triangle_count` (new fields, old layout preserved). `bedray_scene`
   caps gain an OMM bit. Native builds the micromap (`vkGetMicromapBuildSizesEXT` →
   `vkCreateMicromapEXT` → `vkCmdBuildMicromapsEXT`) and attaches
   `VkAccelerationStructureTrianglesOpacityMicromapEXT` via `data.pNext` on the cutout geometry in
   both `query_blas_sizes` (`acceleration_scene.cpp:447`) and `record_blas_build` (`:610`). Micromap
   lifetime follows the BLAS generation/retire rules already used for compaction.
5. **Shader integration.** With OMM attached, traversal skips transparent subtriangles in hardware;
   the software candidate loop skips alpha re-test for OMM-covered opaque subtriangles. Keep the
   candidate loop intact as the fallback when OMM is absent/disabled.
6. **Default off.** `bedray.rt.omm=false` until step 7 produces numbers.

## Measurement plan (gate before enabling)

A/B on the fixed-seed autotest world, **separate glass and foliage scenes** (per the audit), RD16
and RD32, 1080p, DLSS RR, no FG: OMM off vs on. Record `gpu_rt_ms` median/p95, `rt_pass` EWMA,
reserved/peak VRAM (micromap memory), BLAS `build_ms`, and candidate-hit counts. Enable only if a
scene shows a clear net win after memory/build cost; otherwise keep off and record the negative
result. "No speedup from compilation" — only a capture counts (L4).

## Disposition

No speculative half-implementation was written. The capability detection already in core is left
as-is. This note + the verified feasibility are the step deliverable; full implementation is
deferred pending the baking pipeline, additive ABI, and a measured foliage/glass win.