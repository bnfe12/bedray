# Host RT shader migration to Slang (subproject 2 of 4)

Date: 2026-11-12
Status: approved (owner delegated autonomous execution: "делай всё сам без участия меня")
Line: compute dev worktree `C:\Dev\Sky\scratch\bedray-compute-dev-20261020` (HEAD `78f529b6`,
branch `integration/combined-jar-20261025`).
Scope: subproject 2 only — migrate the first-party **host RT** GLSL shaders (`common/`) to Slang
modules compiled at build time (Tier 1). Builds on subproject 1's proven toolchain
(`gradle/slang.gradle`, Slang v2026.22, `compileSlangShaders`, precompiled `.spv` loader).
Subprojects 3 (8-mode orchestrator) and 4 (quality/optimization) are separate cycles.

## Problem

The host hardware-RT renderer (`common/src/main/java/.../vulkancompat/rt/`) compiles its GLSL
compute shaders at runtime via LWJGL shaderc (`RtShaderCompiler`, Vulkan 1.2 / SPIR-V 1.4,
`GL_EXT_ray_query`). The owner wants one modern shading language (Slang) across all first-party
shaders, a simpler build, and a better long-term stack. Subproject 1 migrated the software-voxel
shader (`compute-common/voxel.slang`, Tier 0 / Vulkan 1.0). This subproject migrates the host RT
shaders, which legitimately require Tier 1 (Vulkan 1.2+ ray queries) — they cannot fall back to
Vulkan 1.0, and the design does not claim they can.

The migration must preserve the rendered image and the uploaded ABI. It changes how the shaders
are authored and compiled, not what they render.

## Inventory (verified, file:line evidence in investigation)

12 first-party `.comp` shaders in `common/src/main/resources/shaders/` (third-party `spatial/`
FSR/NIS excluded; they stay as-is and interop via SPIR-V):

- Ray-query compute (include `rt_common.glsl` → `rt_common_core.glsl` for `GL_EXT_ray_query`,
  `GL_EXT_buffer_reference`, `GL_EXT_buffer_reference_uvec2`, subgroup basic/ballot, local_size 8×8):
  `rt_rays.comp` (497 lines), `rt_path.comp` (544), `rt_volumetric.comp` (209), `rt_atrous.comp`
  (127, includes core but no ray helpers), `rt_test.comp` (53, direct ray query).
- Plain compute: `rt_composite.comp` (86), `rt_bloom.comp` (211), `rt_pack_nrd.comp` (193,
  buffer_reference+uvec2), `rt_particles_composite.comp` (37), `fg_prepare.comp` (31, #version 450),
  `iris_depth.comp` (40, 450), `velocity_reproject.comp` (187, 450, buffer_reference+uvec2).

No first-party shader uses `GL_EXT_ray_tracing` (RT is entirely ray-query compute, not an RT
pipeline). `GL_EXT_opacity_micromap` appears in no source file; it is injected at runtime by
`RtShaderCompiler.probeOpacityMicromapExtension()` after a probe that may bump SPIR-V 1.4→1.5.

Shared GLSL: `rt_common.glsl` is a 13-line ordered umbrella over 10 modules (3606 shared lines):
`rt_common_core`, `rt_tonemap`, `rt_sky`, `rt_common_light`, `rt_common_sampling` (→`rt_noise`),
`rt_common_surface` (→`rt_caustics`), `rt_common_emitters`, `rt_common_temporal`, `rt_common_water`,
`rt_common_shade`. Ray-query surface lives in core/sampling/surface/water/shade; tonemap/sky/noise/
caustics/emitters/temporal are non-RT.

15 compile variants (`RtShaderCompiler.warmupVariants()`): rt_rays ×2 (accum, gbuffer+accum),
rt_path ×2, rt_atrous ×1, rt_volumetric ×2 (VOL_STAGE 0/1), rt_bloom ×4 (BLOOM_STAGE 0–3),
rt_composite ×1, rt_pack_nrd ×2 (PACK/COMPOSE), rt_particles_composite ×1. Defines: `VKB_GBUFFER`,
`VKB_ACCUM`, `VKB_POST`, `VOL_STAGE`, `BLOOM_STAGE`, `VKB_PASS_PACK`, `VKB_PASS_COMPOSE`.
Not warmed: rt_test, fg_prepare, iris_depth, velocity_reproject (compiled on demand).

9 host loader call sites: via `RtShaderCompiler` — `RtPipeline` (driven by `RtRayEffectsPass`,
`RtPathTracerPass`, `RtVolumetricPass`), `RtCompositePipeline`, `RtBloomPass`, `NrdPackPipeline`,
`RtParticleComposite`; own shaderc — `VelocityPipeline`, `FrameGenGpu`, `IrisDepthConverter`;
dual shaderc/.spv — `RtTestPipeline`. `RtFrameRenderer` orchestrates/binds, does not compile.

## Feasibility spike (DONE, GO)

Slang v2026.22, local `build/slang/bin/slangc.exe`, probes in `build/slang/spike-rt/` (gitignored,
throwaway). Results:

- **Ray query** (`RaytracingAccelerationStructure` + `RayQuery<RAY_FLAG_NONE>` + `TraceRayInline` +
  `CommittedStatus`/`CommittedRayT`): compiles via `-emit-spirv-directly -profile spirv_1_3` and
  `spirv_1_4`, AND via `-emit-spirv-via-glsl -profile spirv_1_4`. Emits `SPV_KHR_ray_query`,
  capability `RayQueryKHR`.
- **Subgroup** (`WaveActiveBallot`, `WaveGetLaneCount`): compiles; capabilities `GroupNonUniform`,
  `GroupNonUniformBallot`.
- **Device-address buffer reference**: Slang `Stats*` pointer emits `SPV_KHR_physical_storage_buffer`,
  `OpMemoryModel PhysicalStorageBuffer64`. A pointer constructed from a `uint64_t` assembled from a
  `uint2` (`((uint64_t)a.y<<32)|a.x`) compiles. **The uvec2 ABI bytes are preserved**: uvec2 (8 B)
  and uint64 (8 B) are byte-identical little-endian, so the host keeps writing the same 8-byte
  address fields and the Slang shader reinterprets them as a 64-bit pointer. No host ABI byte change.
  - Direct cast `(Stats*)uint2` is NOT supported by Slang ("too many initializers"); the `uint64_t`
    assembly route is used. It pulls capability `Int64` (11). This is acceptable for Tier 1 host RT
    (Vulkan 1.2+ devices support Int64); the voxel Tier 0 path remains Int64-free.
- **Opacity micromap**: no Slang support found (zero examples in the distribution test suite).
  Decision: drop OMM in the Slang port. It is an optional optimization; `RtShaderCompiler` already
  disables it gracefully when the probe fails, leaving shaders unchanged. The optical/perf effect
  of dropping OMM is documented, not hidden.

Tier 1 target: SPIR-V 1.4 (matches the existing `RtShaderCompiler` Vulkan 1.2 target). Direct
emission is stable for SPIR-V 1.3+; via-glsl is the verified fallback route.

## Goals

- One first-party shader source language: Slang, for the host RT path as for voxel.
- Build-time compilation to SPIR-V; remove runtime shaderc from the migrated RT pipelines.
- Preserve the rendered image (measured A/B) and the uploaded ABI bytes.
- Capability tiering: Tier 1 (Vulkan 1.2+ ray query) for host RT; Tier 0 (Vulkan 1.0) remains the
  voxel path. Automatic device selection; a Vulkan 1.0 device runs voxel but not RT (correct gating).
- An offscreen RT measurement harness producing a baseline and enabling image A/B (none exists today).

## Non-goals

- No render-mode selector / settings UI (subproject 3).
- No image-quality or algorithm changes (subproject 4). Slang alone does not improve the image.
- No rewrite of third-party shaders (vanilla / Sodium / Iris / FSR / NIS / NRD / DLSS / Streamline).
  They stay as-is and interop via SPIR-V; the NRD/DLSS G-buffer formats and bindings they consume
  must be preserved exactly.
- No opacity micromaps in the Slang port (unsupported; optional optimization dropped).
- No git commit/push and no installed-instance deploy without explicit owner permission.
- No mixing lines: do not copy classes from the public export or the installed RT instance; do not
  edit `…\bedray-public-export-20261020`; do not touch the installed instance without permission.

## Architecture

### Tier 1 toolchain

Extend `gradle/slang.gradle` (pinned Slang v2026.22, SHA-256
`dd0c4a86bd44cdb7e9cf712af0c8ebf2fff1bba19fa5e3c128ff87384e8ff17b`, canonical
`github.com/shader-slang/slang` URL, no proxy) with a Tier 1 compile path. Add a
`compileSlangShaders` task to `common/build.gradle` (mirroring `compute-common/build.gradle`):

- Route: `slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main` (direct).
  Via-glsl (`-emit-spirv-via-glsl`) is the verified fallback if a construct fails direct emission.
- Each used variant × module entry → one `.spv` under `build/generated/shaders/...`, wired into
  `processResources` and packaged into the jar.
- Defines passed per variant (`VKB_GBUFFER`, `VKB_ACCUM`, `VKB_POST`, `VOL_STAGE`, `BLOOM_STAGE`,
  `VKB_PASS_PACK`, `VKB_PASS_COMPOSE`) via Slang preprocessor (`-D`).
- Cache key covers compiler binary + base args + defines + source (subproject 1's caching fix).
- Cap audit: Tier 1 SPIR-V version 1.4; capabilities a subset of the expected RT set. The required
  capabilities include {Shader, ImageQuery, Int64, PhysicalStorageBufferAddresses, RayQueryKHR,
  GroupNonUniform, GroupNonUniformBallot} plus any image capabilities the shaders use. The exact
  numeric set is pinned empirically from the compiled variants in Stage 2.0 (not guessed); the audit
  then asserts every variant stays within that pinned set. Extensions: `SPV_KHR_ray_query`,
  `SPV_KHR_physical_storage_buffer`.

### Slang module structure

Mirror the existing 10-module umbrella as Slang modules with `import`, preserving the dependency
order so the import graph is acyclic and each module is included once:

- `rt_common_core.slang`: acceleration-structure binding, `Section`/`StatsBuf`/`Emitter`/`Reservoir`
  structs, push-constant block, flags/modes, `candidateAccepted`, the `CANDIDATE_ACCEPTED` ray-query
  decode (Slang idiom replacing the GLSL macro), device-address pointer helpers (uvec2→`uint64_t`→`T*`).
- `rt_tonemap.slang`, `rt_sky.slang`, `rt_common_light.slang`, `rt_common_sampling.slang`
  (imports `rt_noise`), `rt_common_surface.slang` (imports `rt_caustics`), `rt_common_emitters.slang`,
  `rt_common_temporal.slang`, `rt_common_water.slang`, `rt_common_shade.slang`, `rt_noise.slang`,
  `rt_caustics.slang`.
- Entry shaders: `rt_rays.slang`, `rt_path.slang`, `rt_volumetric.slang`, `rt_atrous.slang`,
  `rt_composite.slang`, `rt_bloom.slang`, `rt_pack_nrd.slang`, `rt_particles_composite.slang`,
  `rt_test.slang`, `fg_prepare.slang`, `iris_depth.slang`, `velocity_reproject.slang`.

Slang modules are authored under `common/src/main/resources/shaders/slang/` (mirroring subproject 1's
`voxel.slang` placement); generated `.spv` are gitignored. The legacy GLSL is removed from the runtime
path only after its Slang port passes A/B (kept in git history; not ported-from thereafter, per
delete-not-debug).

### Offscreen RT measurement harness (new; measure-before-shader foundation)

The RT path has **no offscreen render harness** today: validation is source-text + shaderc-compile
(19 tests, device=0) + in-game only. The voxel pilot's image-hash A/B (`computeBaseline`) has no RT
analogue. To honor "ни одна правка шейдера не принимается без baseline и A/B", build a focused
offscreen RT harness in `common` (analogous to `compute-common`'s `ComputeBaselineRunner`):

- Create a Vulkan device enabling the RT extensions the shaders need (ray query, acceleration
  structure, buffer device address). The development GPU (RTX 5060 Ti, Vulkan 1.4) supports them.
- Build a fixed synthetic scene: a small BLAS/TLAS (a few quads/cubes), section tables, a block
  atlas (synthetic or a fixed real atlas), `StatsBuf` and push constants matching `RtFrameRenderer`'s
  layout exactly. Fixed camera, lighting, frame counter.
- Dispatch the primary ray-query shader (`rt_rays`) and path tracer (`rt_path`) through the GLSL
  path (shaderc) → read back the output image → baseline SHA-256 + GPU timestamp timings.
- For post-processing shaders (composite/bloom/atrous/pack_nrd/particles), feed fixed synthetic
  input images and compare outputs (a simpler image A/B).
- Discard warmup frames, measure N frames (mirror computeBaseline's 30 warmup / 120 measured).
- One Gradle task `:common:rtBaseline` runs the harness and emits `docs/RT_BASELINE.md` (per-scene/
  per-shader hashes + timings + counters), mirroring `:compute-common:computeBaseline`.
- A/B gate: the Slang port must reproduce the baseline image hashes (or a documented, justified
  delta), GPU totals within measurement noise, identical counters. The baseline is produced by the
  GLSL reference BEFORE any port; it is not regenerated to hide a delta.

Evidence level L2 (offscreen, real Vulkan device with RT). L4 (in-game) is a later owner-verified
step, not claimed here. The harness runs the GLSL reference once to capture the baseline (measurement,
not debugging legacy); after the port passes A/B the GLSL is deleted.

### Host integration

Replace runtime shaderc with precompiled `.spv` resource loading for each migrated shader, at the
9 loader sites. Introduce a small loader helper (mirroring `VoxelComputePipeline.precompiledSpirv`)
that selects the `.spv` resource by shader + variant. Keep `RtShaderCompiler` for any not-yet-migrated
shader during staging; remove it once all RT shaders are migrated and A/B-proven.

Tier selection: query device API version + enabled extensions; host RT requires Tier 1 (Vulkan 1.2+
with ray query). A device without ray query keeps the raster fallback (the host already falls back
to raster when RT is unavailable); voxel Tier 0 still works on Vulkan 1.0. Initialization/structural
failures remain explicit and latched (no silent fallback).

### ABI preservation contract (uploaded bytes must not change)

- Descriptor bindings exactly as today per shader (e.g. rt_rays/rt_path: 0 TLAS, 1 outImage,
  2 Sections, 3 atlas, 4–8/13/14 G-buffer (VKB_GBUFFER), 9 matAtlas, 10 nrmAtlas, 11 accumImage
  (VKB_ACCUM), 12 outDepth, 15 lightmap). The G-buffer formats/bindings consumed by NRD/DLSS are
  preserved exactly.
- `StatsBuf` = 508 bytes, std430 offsets unchanged (the full field list in `rt_common_core.glsl`).
  `Section` = 64 bytes. `Emitter`, `Reservoir` layouts unchanged.
- Push-constant block `PC` = 128 bytes (mat4 invViewProj @0, uvec2 statsAddr, uint modeBits,
  uint frame, vec4 keyDir, keyColor, skyColor), unchanged.
- Device addresses: stored as uvec2 (8 bytes) by the host, unchanged; the Slang shader assembles
  `uint64_t` and casts to `T*`. Byte-identical.
- Entry point `main`; local_size 8×8×1 (the three #version 450 shaders use 8×8).
- Matrices: uploaded column-major bytes unchanged; in-shader multiply order per the proven
  convention (GLSL `M*v` → Slang `mul(M,v)`; GLSL `v*M` → Slang `mul(v,M)`). Gated by A/B.
- Variant semantics preserved (the 15 variants + on-demand shaders).

## Staging (decomposition for scale)

Subproject 2 is large (3606 shared GLSL lines + 12 shaders + 15 variants + 9 loaders + ~38 tests +
a new measurement harness). It is implemented in four internal stages, each its own plan and each
ending in a verified, A/B-proven deliverable:

- **Stage 2.0 — Foundation.** Tier 1 toolchain in `common/build.gradle` + cap-audit test; offscreen
  RT measurement harness + `docs/RT_BASELINE.md` baseline (GLSL reference). No shader port yet.
  Deliverable: one-command RT baseline + a Tier 1 compile path proven on a representative probe.
- **Stage 2.1 — Plain compute shaders.** Port the non-ray-query shaders (rt_composite, rt_bloom,
  rt_atrous, rt_pack_nrd, rt_particles_composite, fg_prepare, iris_depth, velocity_reproject) +
  their shared modules (rt_tonemap, rt_noise, rt_caustics, non-RT parts of core). A/B per shader.
- **Stage 2.2 — Ray-query shaders.** Port rt_rays, rt_path, rt_volumetric, rt_test + the ray-query
  shared modules (core/sampling/surface/water/shade/light/emitters/temporal/sky). A/B per shader.
- **Stage 2.3 — Integration + cleanup.** Wire all 9 loaders to precompiled `.spv`; port the ~38 RT
  tests; full regression; remove legacy GLSL RT + `RtShaderCompiler` once unused; update docs.

Each stage honors measure-before-shader (baseline before, A/B after) and the owner-permission guards.

## Error handling

- Compiler download hash mismatch → fail build (no silent fallback).
- `slangc` non-zero exit or empty SPIR-V → fail build, surface stderr.
- Cap audit: any Tier 1 SPIR-V capability outside the expected RT set → test failure.
- Missing `.spv` resource at runtime → fail fast with shader/variant name (no silent runtime recompile).
- Device without ray query → explicit surfaced reason; raster fallback retained (subproject 3 gating).
- A/B image-hash divergence → stop, investigate the port (matrix convention, ray-query semantics,
  ABI offsets) before proceeding; do not regenerate the baseline to hide the delta.

## Testing and verification

1. Feasibility spike (DONE, GO): recorded above.
2. Tier 1 cap-audit test: every RT variant SPIR-V has version 1.4 and capabilities ⊆ the expected set.
3. Offscreen RT baseline + A/B: `docs/RT_BASELINE.md` hashes reproduced by the Slang port (or
   justified delta), timings within noise, counters identical.
4. Ported RT tests: the 19 shaderc-compile tests assert Slang-compiled SPIR-V; the ~19 source-text
   tests assert Slang source/module structure; `RtShaderModulesTest` becomes a Slang import-graph check.
5. Full regression: `common` tests + `runShimTests` green; `compute-common` + `renderer-api` unaffected.
6. Evidence L2 offscreen; L4 in-game is owner-verified later.

## Risks and mitigations

- **Measurement harness complexity** (TLAS/BLAS, geometry, the full binding chain): the largest new
  effort. Mitigation: stage it first (2.0), mirror computeBaseline's synthetic-scene approach, keep
  the scene minimal but representative; validate the harness reproduces a stable GLSL baseline before
  any port.
- **Ray-query semantic fidelity** (CANDIDATE_ACCEPTED decode, committed/candidate intersections,
  transmittance walks, self-avatar skip): gated by image A/B; port the decode idiom carefully.
- **Device-address ABI** (uvec2→uint64_t pointer): proven byte-identical in the spike; gated by A/B.
- **Int64 capability difference** (original uvec2 avoided Int64): acceptable for Tier 1; documented.
- **OMM dropped**: optional optimization; documented optical/perf implication; not a correctness gap.
- **NRD/DLSS G-buffer interop**: formats/bindings preserved exactly; third-party denoisers unchanged.
- **Scale**: decomposed into four stages, each independently verified.
- **Matrix convention**: proven in subproject 1; re-gated by RT A/B.

## Constraints (carry forward)

- Worktree `C:\Dev\Sky\scratch\bedray-compute-dev-20261020`; do not touch other lines/repos.
- No `git commit`/`git push` without explicit owner permission; no `git add -A`; do not revert others'
  changes. No installed-instance deploy without explicit owner permission.
- Code and comments in English (match the tree); chat reports in Russian.
- Measure-before-shader: every shader change gated by baseline + A/B. Raster fallback sacred.
- Third-party shaders stay as-is; interop via SPIR-V.
- Canonical URLs only (`github.com/shader-slang/slang`); no proxy substitution; re-verify each URL.
- Never run two Gradle tasks concurrently; buffered stdout; XML is truth for tests.

## Reproduce

```
./gradlew :common:compileSlangShaders        # build-time Tier 1 SPIR-V generation (new)
./gradlew :common:rtBaseline                 # offscreen RT baseline + A/B harness (new, Stage 2.0)
./gradlew :common:test :common:runShimTests  # regression
```

## Exit criteria

- All 12 first-party RT shaders authored in Slang; Tier 1 `.spv` variants generated at build time and
  loaded at runtime; runtime shaderc gone from the RT path.
- Offscreen RT baseline reproduced by the Slang port (A/B hashes match or justified delta); timings
  within noise; counters identical; cap audit green.
- Ported RT tests + full regression green; L2 evidence recorded; legacy GLSL RT removed.
- No commit/push/deploy without owner permission. Report before subproject 3.