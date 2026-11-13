# Slang toolchain + capability tiers + voxel pilot (subproject 1 of 4)

Date: 2026-11-12
Status: approved (owner delegated autonomous execution: "делай всё сам без участия меня")
Line: compute dev worktree `C:\Dev\Sky\scratch\bedray-compute-dev-20261020` (HEAD `78f529b6`).
Scope: subproject 1 only. Subprojects 2 (first-party shader migration), 3 (8-mode orchestrator),
4 (quality/optimization) are separate spec → plan → implementation cycles.

## Problem

All first-party shaders are hand-written GLSL compiled at runtime via LWJGL shaderc. The voxel
backend compiles `voxel.comp` on every pipeline creation
(`VoxelComputePipeline.create` → `ComputeShaderCompiler.compile`, shaderc `vulkan_1_0`). The owner
wants a single modern shading language (Slang) across all first-party shaders, a simpler build,
better long-term stack, and an 8-mode render selector — while keeping automatic Vulkan 1.0 support.

This subproject builds the enabling foundation: a build-time Slang toolchain with capability
tiering, proven by migrating the voxel shader as a measured pilot. It does not change the rendered
image; it changes how the shader is authored and compiled.

## Goals

- One first-party shader source language: Slang. No hand-maintained GLSL for the voxel path.
- Build-time compilation to SPIR-V; remove runtime shaderc from the voxel pipeline.
- Capability tiers with automatic device selection; Vulkan 1.0 (zero device extensions) is proven
  first, not deferred.
- Byte-exact uploaded ABI and a measured A/B against the V0 baseline (`docs/COMPUTE_BASELINE.md`):
  identical image hashes, GPU timings within noise, identical counters.

## Non-goals

- No host RT shader migration (subproject 2).
- No render-mode selector / settings UI (subproject 3).
- No image-quality or algorithm changes (subproject 4). Slang alone does not improve the image.
- No rewrite of third-party shaders (vanilla / Sodium / Iris / FSR / NIS / NRD). They stay as-is and
  interop via SPIR-V.
- No git commit/push and no installed-instance deploy without explicit owner permission.

## Key constraint: Slang and Vulkan 1.0 (verified by spike)

Slang's direct SPIR-V backend is stable only for SPIR-V 1.3+; SPIR-V 1.0/1.1/1.2 direct emission is
experimental (the spike reproduced warning E50011 "SPIR-V version too old"). The stable Vulkan 1.0
route is `-emit-spirv-via-glsl`, where Slang generates GLSL and compiles it with its bundled glslang.

Spike results (Slang v2026.22, `build/slang/spike/`):
- `slangc -target spirv -profile spirv_1_0 -emit-spirv-via-glsl -entry main` produces SPIR-V
  **version 1.0**.
- A representative shader using std430 storage buffers, explicit `[[vk::binding]]`, `rgba8` and
  `rgba32ui` storage images, `sampler2D`, `imageSize`, `atomicAdd`, a 112-byte `push_constant`
  std430 block, entry `main`, `numthreads(8,8,1)` yields capabilities exactly **{Shader=1,
  ImageQuery=50}** — the allowed set for the voxel line.
- The `spirv_1_0` profile auto-upgrades to include the `GLSL_400` capability (warning E41012); this
  is harmless and remains Vulkan 1.0 / zero-extension compatible.
- Unused resources are stripped by Slang; push constants must be statically used (they are in voxel).

Reference: Slang user guide, "SPIR-V-Specific Functionalities"
(http://shader-slang.org/slang/user-guide/spirv-target-specific).

## Architecture

### Capability tiers

- **Tier 0 — Vulkan 1.0 core compute.** Zero device extensions. SPIR-V capabilities must be a subset
  of {Shader=1, ImageQuery=50}. Path (verified): `slangc -target spirv -profile spirv_1_0
  -emit-spirv-via-glsl -entry main` → SPIR-V 1.0 using Slang's bundled glslang. No separate shaderc
  invocation is required for the build.
- **Tier 1 — Vulkan 1.2+ (SPIR-V 1.3+).** Path: `slangc -target spirv -profile spirv_1_3
  -emit-spirv-directly` → SPIR-V directly (stable). Used by host RT in subproject 2. The tier
  framework is introduced here; the voxel pilot only exercises Tier 0.

Automatic selection: at runtime, query device API version + enabled extensions and pick the highest
supported tier. The voxel pilot needs only Tier 0, so a Vulkan 1.0 device always has a working path.
Tier 1 entry points are stubbed here and filled in subproject 2.

### Matrix convention (ABI adaptation, verified necessary)

In the via-glsl route Slang emits **RowMajor** matrix storage (HLSL convention); neither
`-matrix-layout-column-major` nor a `column_major` qualifier changes this in the generated SPIR-V.
The original GLSL uses column-major storage with `M * v`. To keep the uploaded bytes identical and
the result identical, the port reverses matrix–vector multiplication order:

- GLSL `M * v`  →  Slang `mul(v, M)`
- GLSL `v * M`  →  Slang `mul(M, v)`

This applies to `diagnostic.inverseViewProjection` (push constant) and
`environment.previousViewProjection` (Environment buffer). The buffer layout, offsets, sizes and
bindings are unchanged; only the in-shader multiply order differs. Correctness is gated by the A/B
image-hash comparison against V0. If the hash diverges, the convention is revisited before any
further migration.

### Source of truth

`voxel.slang` is the single authored source. The legacy `voxel.comp` is removed from the runtime path
once the pilot passes A/B (kept in git history; not ported-from thereafter, per the delete-not-debug
rule for legacy GLSL).

### Build-time toolchain

- Pinned compiler: Slang **v2026.22**, asset `slang-2026.22-windows-x86_64.zip` from
  `github.com/shader-slang/slang/releases` (canonical URL, no proxy).
  Verified SHA-256: `dd0c4a86bd44cdb7e9cf712af0c8ebf2fff1bba19fa5e3c128ff87384e8ff17b`.
  The Gradle toolchain script pins this hash; a mismatch fails the build.
- `slangc` is build-time only, extracted under `build/slang/` (gitignored), NOT shipped as a runtime
  DLL. Vendor runtime DLLs (NRD/DLSS/Streamline) are unaffected.
- A Gradle task obtains/verifies the compiler (download once, offline cache thereafter) and a
  `compileSlangShaders` task compiles `.slang` → `.spv` per tier/variant into generated resources
  packaged into the jar.

## Components

1. `gradle/slang.gradle`: pinned version + SHA-256, download/verify/extract task, offline cache.
2. `compileSlangShaders` Gradle task in `compute-common/build.gradle`: invokes `slangc` for each used
   variant, produces `.spv` resources under `build/generated/shaders/...`, wired into
   `processResources`.
3. `compute-common/src/main/resources/assets/bedray_compute/shaders/voxel.slang`: the ported shader.
4. `VoxelComputePipeline`: loads the precompiled `.spv` resource for the selected tier/variant instead
   of calling `ComputeShaderCompiler` at runtime.
5. `ComputeShaderCompiler` (runtime shaderc): removed from the voxel path. Deleted if no other
   consumer remains in compute-common; otherwise left untouched.

## Data flow

Build time: `voxel.slang` → (Tier 0) `slangc -emit-spirv-via-glsl -profile spirv_1_0` →
`voxel_<variant>_v10.spv`; (Tier 1, later) `slangc -emit-spirv-directly -profile spirv_1_3` →
`voxel_<variant>_v12.spv`. SPIR-V variants are packaged as resources.

Runtime: detect tier → select variant (texturedCubes / rasterInputs / gpuCounters) → load matching
`.spv` resource → `vkCreateShaderModule` → existing pipeline creation. No runtime compilation.

## ABI preservation contract (uploaded bytes must not change)

- Descriptor bindings 0–10 exactly as today: 0 output `image2D rgba8`; 1 Grid std430; 2 CubePalette;
  3 blockAtlas sampler; 4 Models (headers[3456] + ModelQuad quads[]); 5 Environment; 6 rasterColor;
  7 rasterDepth; 8 historyA `rgba32ui`; 9 historyB `rgba32ui`; 10 HistoryCounters (GPU_COUNTERS only).
- `ModelQuad` = 96 bytes (twenty interleaved xyzuv floats + uvec4 metadata). std430 offsets unchanged.
- Push-constant block `Diagnostic` = 112 bytes (mat4 @0, vec4 @64, uvec4 @80, vec4 @96), unchanged.
- Entry point name `main`; `local_size_x=8, local_size_y=8`.
- Variant semantics: `TEXTURED_CUBES`, `RASTER_INPUTS`, `GPU_COUNTERS` (GPU_COUNTERS requires
  RASTER_INPUTS). The default variant (no defines) must remain behaviorally identical.
- Matrices: uploaded column-major bytes unchanged; in-shader multiply order reversed per the matrix
  convention above.

## Variants

The pipeline uses four combinations: default (no atlas/no raster), textured (atlas, no raster), live
(atlas+raster), live+counters. Slang reproduces these via preprocessor macros / `-D` flags passed by
`compileSlangShaders`. Each used combination × tier yields one `.spv`.

## Error handling

- Compiler download hash mismatch → fail build with a clear message (no silent fallback).
- `slangc` non-zero exit or empty SPIR-V → fail build, surface stderr.
- Cap audit: any Tier 0 SPIR-V capability outside {1,50} → test failure.
- Missing `.spv` resource at runtime → fail fast with the variant/tier name (no silent runtime
  recompile fallback in the voxel path).
- Unsupported tier on a device → explicit, surfaced reason (relevant to subproject 3 mode gating).

## Testing and verification (measure-before-shader)

1. **Feasibility spike (DONE, GO):** representative subset compiled through the Tier 0 route; SPIR-V
   1.0, caps {1,50}, layouts preserved; matrix convention identified. Recorded in `build/slang/spike/`.
2. **Cap audit test:** extend `ComputeShaderTest` to assert every Tier 0 variant SPIR-V has
   capabilities ⊆ {Shader=1, ImageQuery=50} and version 1.0.
3. **A/B against V0:** run `:compute-common:computeBaseline` before and after. The three scenes
   (field/darkroom/cave) must produce identical final-frame SHA-256 (or a documented, justified
   delta), GPU totals within measurement noise, and identical GPU history counters.
4. **Regression suite:** renderer-api, compute-common `test` + `vulkanProbe`, and `common` tests all
   green; `worldSnapshotContracts` green.
5. Evidence level L2 (offscreen, real Vulkan 1.0 device, zero extensions). L4 (in-game) is a later
   owner-verified step, not claimed here.

## Risks and mitigations

- **Matrix convention correctness:** gated by the A/B image hash; if it diverges, fix the multiply
  order before any further migration. This is the primary porting risk.
- **Slang GLSL fidelity** for the full 703-line shader (loops, structs, atomics): the spike covered
  the risky constructs; the full port is validated by the same cap audit + A/B.
- **Compiler distribution / licensing:** Slang is Apache-2.0; pinned version + verified hash;
  build-time only, offline cache for reproducibility.
- **Variant/build-time growth:** only the used combinations are compiled; cache outputs.

## Reproduce

```
./gradlew :compute-common:compileSlangShaders   # build-time SPIR-V generation (new)
./gradlew :compute-common:test :compute-common:vulkanProbe
./gradlew :compute-common:computeBaseline       # A/B against docs/COMPUTE_BASELINE.md
```

## Exit criteria

- `voxel.slang` is the authored source; Tier 0 `.spv` variants are generated at build time and loaded
  at runtime; runtime shaderc is gone from the voxel path.
- Cap audit green (version 1.0, caps ⊆ {1,50}); A/B hashes match V0 (or justified delta); timings
  within noise; counters identical.
- Full regression suite green; L2 evidence recorded.
- Pause and report before subproject 2. No commit/push without owner permission.