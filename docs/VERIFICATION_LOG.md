# Migration verification log

Evidence levels follow [FEATURE_MATRIX.md](FEATURE_MATRIX.md): L0 source,
L1 build, L2 no-GPU unit tests, L3 game startup, L4 visible effect, L5 owner
confirmation. Historical results do not validate a different source revision.

## 2026-10-29 — DLSS-RR guide channels from the typed path (L1 compile, L2 unit)

Branch: `refactor/slang-pipeline`. Tool: `slangc` 2026.20, same flags as below.

Source changes covered by this run:

- `pathtrace/path_tracer.slang` fills `PathTraceResult.primary`
  (`PathPrimaryGuide`: position, view-facing normal, albedo floored at 0.02,
  F0, direct NEE, roughness, metallic) at the primary vertex, after the
  filtered-shadow mask and before `result.radiance += throughput * direct`, so
  the guide describes exactly the surface the radiance was shaded on.
  `PATH_TRACE_OPTION_GBUFFER` (bit 0) reuses the `PathTraceOptions.flags` word
  that was always 0 and no shader reads as a sample property; the push stays
  112 B.
- `passes/path_trace.slang` and `passes/path_trace_scene.slang` gained
  bindings 26–32 in the denoiser order: `outViewZ` (R32F),
  `outNormalRough`, `outSpecular`, `outDiffuse`, `outDirect` (RGBA16F),
  `outAlbedo`, `outSpecAlbedo` (RGBA8), and `pathTraceWriteGuides`, which
  follows `rt_path.comp`: view Z from the camera forward, sky as
  1e6 / (0,0,1,1) / zeros, `diffuse = radiance - direct` with the primary hit
  distance in `.a`, specular zero for the path tracer, and the legacy specular
  albedo rule (`metal > 0.5 || roughness < 0.6 ? (metal ? albedo : F0) : 0`).
  The call is gated by the option bit, not by the pixel.
- `RtPathTraceSlangPass` gained `Guides` (seven views, all-or-nothing),
  three pass-owned 1×1 dummy images (R32F, RGBA16F, RGBA8) so the descriptor
  set is complete in either mode, and a `record` check that throws when the
  option bit and the bound guides disagree. `ReferencePathTraceBackend` and
  `RtSlangPathPass` pass `views.gbuffer()` and set the bit only when all seven
  views exist; `RtFrameRenderer` now allows explicit `SLANG` + `DLSS_RR`,
  still refuses `NRD` (the RR packer consumes images only, but the NRD layout
  also reads the primary hit's world-space motion from `pc.motionAddr`, which
  the Slang path does not fill), refuses the C++ path backend (writes no
  guides), and keeps `AUTO` on the GLSL pass while a denoiser is selected.
- New `RtSlangGbufferGuidesTest` (7 guards) pins the capture point, the
  split-radiance identity, the sky pattern, the option gating on both sides,
  the short-array rejection, the denoiser channel order and the renderer
  policy. `SlangPassLibraryTest` pins 25 bindings; the entity guard that named
  the `record` argument list was updated for the new `Guides` argument.

The first full-suite run of this batch failed exactly that entity guard
(`theFrameHandsTheCapturedRingToThePass`, argument list pinned without the new
`Guides` argument); the guard was updated and both the targeted test and the
full suite below were rerun on the fixed source.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 18 | 18 | `path_trace` 550704 B, `path_trace_scene` 562520 B, 25 bindings, 112 B push |
| Harnesses | 6 | 6 | unchanged |
| Targeted `:common:test` | 26 tests | 0 failures | `RtSlangGbufferGuidesTest` 7, `SlangPassLibraryTest` 9, `RtPathTraceSlangPassTest` 10 |
| `:common:test` | 725 tests | 0 failures | 3 skipped |

This is L1/L2 only. It proves the guides compile in both path roots and the
source/host guards agree; no GPU dispatch, frame or screenshot exists, the
denoiser has not consumed these images, and no owner-verified image of the
Slang path exists. The guides were captured at the primary vertex of a path
whose radiance may still be rejected by the display pass (invalid/truncated
samples), so a rejected pixel can carry a guide; the denoiser's own history
rejection is expected to absorb that once a live frame is measured.

## 2026-10-29 — water volumetric medium in the typed path (L1 compile, L2 unit)

Branch: `refactor/slang-pipeline`. Tool: `slangc` 2026.20,
`-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major
-I common/src/main/resources/shaders/slang`.

Source changes covered by this run:

- `surface/water.slang` gained the medium: `WATER_CLEAR_ABSORPTION`
  (0.17, 0.036, 0.013), `WATER_SCATTERING` 0.055 and
  `WATER_MULTIPLE_SCATTER_FLOOR` 0.65 (the legacy `rt_common_water.glsl`
  values), `waterSigmaT` (clear absorption + biome dye + scattering),
  `WaterMedium {inside, sigmaT, scatterAlbedo, surfY}`, `noWaterMedium`,
  `waterMediumFrom`, `waterSegmentTransmittance`, `waterInScatter` (depth at
  the segment midpoint, 0.65 depth falloff) and the horizontal-face
  `waterEntersMedium`/`waterLeavesMedium` rules.
- `surface/surface.slang` makes the fluid interface clear
  (`material.albedo = float3(1.0)`, IOR 1.333, transmission 1) so the colour is
  distance absorption rather than a painted tint; `surfaceTransmittance` keeps
  the legacy `(1 - F) * waterBiomeTint * 0.85` sheet for callers without medium
  state, and the new `surfaceInterfaceTransmittance` returns only the Fresnel
  share for a fluid.
- `surface/visibility.slang` gained `traceFilteredVisibilityMedium` and
  `traceShadowVisibilityMedium`: segment absorption between the receiver and
  each candidate, fluid interfaces as Fresnel-only filters, medium toggling on
  horizontal fluid faces, tail absorption on a miss. Shadow rays add no
  in-scatter. The medium-blind walk is unchanged.
- `pathtrace/path_tracer.slang` carries `WaterMedium` along the path: a
  refracted transmission (`BSDF_LOBE_TRANSMISSION`) through a horizontal fluid
  face enters or leaves the body (reflections and TIR do not), every segment
  inside is absorbed by `waterSegmentTransmittance` and adds
  `waterInScatter` (downwelling from `environment.radiance(up)`), and
  `pathTraceDirectTerm` shadows through `traceShadowVisibilityMedium`. A cutout
  advance moves the ray origin, so the segment start tracks `-rayBias`.
  `PATH_TRACE_FLAG_WATER_MEDIUM` marks a path that entered the body.
- `verify_water.slang` extended with the medium entries (dye dominance,
  scatter albedo below one, outside body clear and non-scattering, absorption
  falling with length, positive in-scatter, enter/leave truth table).
- New `RtSlangWaterMediumTest` (6 guards) keeps the Slang constants on the
  legacy numerics and pins the interface-clear, shadow-medium and
  toggle-on-refraction rules.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 18 | 18 | `path_trace` 546524 B, `path_trace_scene` 558340 B, 18 bindings, 112 B push |
| Harnesses | 6 | 6 | `verify_water.slang` now also reads the medium |
| Targeted `:common:test` | 16 tests | 0 failures | `RtSlangWaterMediumTest` 6, `RtWaterMediumTest` 7, `RtSlangVisibilityOverflowTest` 3 |
| `:common:test` | 718 tests | 0 failures | 3 skipped |

This is L1/L2 only.  It proves the medium compiles in both path roots and the
source guards hold; no GPU dispatch, frame or screenshot exists, and the
underwater look is not owner-verified.  Not modelled, documented in the module
header: a camera that starts inside water, Snell refraction and caustics of the
underwater NEE beam, side-aware IOR on an exit face, and the `waterStyle`
switch.

## 2026-10-29 — water surface model in the typed path (L1 compile, L2 unit)

Branch: `refactor/slang-pipeline` (merge `cbe478f`). Tool: `slangc` 2026.20,
`-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major
-I common/src/main/resources/shaders/slang`.

Source changes covered by this run:

- `surface/water.slang` is new: the six-wave slope field with pixel-footprint
  filtering and the filtered-out slope variance, `waterWaveNormal` (horizontal
  faces only, downhill shift on flowing top quads, same-side guard),
  `cardinalFaceShade`/`waterBiomeTint` (the legacy division of the baked face
  shade out of the vertex colour) and `waterIsSurface` (translucent,
  `luminance(f0) <= 0.025`, `roughness <= 0.035`, `metallic < 0.5` - glass at
  f0 0.04 and ice at roughness 0.05 stay panes). Ported from
  `rt_caustics.glsl`, `rt_common_surface.glsl` and `rt_common_water.glsl`.
- `surface/surface.slang` assigns `MATERIAL_SOURCE_FLUID`, `AlphaMode.Optical`,
  IOR 1.333 and the realistic-style surface (full wave normal, sheen from the
  lost variance) when the triangle is a fluid, and `surfaceTransmittance`
  filters a fluid by `(1 - F) * waterBiomeTint * 0.85` instead of the sprite
  alpha slab.
- Not ported, documented in the module header: the volumetric medium (depth
  absorption, in-scattering, Snell refraction, caustics) and the legacy
  `waterStyle` switch.
- New compile harness `verify_water.slang`.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 18 | 18 | `path_trace` 523968 B, `path_trace_scene` 535784 B, 18 bindings, 112 B push |
| Harnesses | 6 | 6 | `verify_water.slang` added to the five existing ones |
| `:common:test` | 712 tests | 0 failures | 3 skipped |

This is L1/L2 only.  It proves module resolution, both path roots compiling
with the water decode, and that no Java contract test regressed.  No GPU
dispatch, frame or side-by-side screenshot exists; a flat pane and a wave
surface compile identically.

## 2026-10-28 — typed RC store split and entry status (L1 compile)

Branch: `feat/slang-rc-transport-20261028`, commit `bcf77b7`.
Tool: `slangc` 2026.20, `-target spirv -profile cs_6_5 -entry main
-matrix-layout-column-major -I common/src/main/resources/shaders/slang`.

Source changes covered by this run:

- `radiance_cascades/segment_store.slang` is now a facade; the implementation
  lives in `segment_record.slang` (whole-record read/write, shared
  `IRCSegmentSource` for read-only and writable buffers, ray composition) and
  `probe_resolve.slang` (`RCProbeRadiance`, `rcResolveProbe`,
  `rcProbeComplete`).
- `rc_atlas_layout.slang` owns `rcLayoutCanonical`, the named interval
  constants and `rcUncheckedIntervalIndex`; the old `rcIntervalIndex` name is
  gone and `layout.flags` is validated.
- Fresh probe/dispatch entries return `RCTraceEntryStatus`; blended entries
  return `RCTraceBlendedStatus` with `RCTemporalStatus`.  They no longer return
  a bare failure count that could be mistaken for "the atlas is usable".
- `rcApplyCascadeOwnershipBand` writes an out parameter and reports success.
- `passes/rc_probe_trace.slang` is the first typed-RC consumer: one dispatch
  item decodes to one probe ray, writes the typed records and one status
  element.  It is host-agnostic and is not wired into the render loop.
- `RCSegment` and `RCTraceEntryStatus` are pinned in `STRUCT_LAYOUTS`.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 18 | 18 | includes `rc_probe_trace.slang` |
| RC/root harnesses | 5 | 5 | typed, environment, full trace API, both roots, scene tracer |

Reflection on `rc_probe_trace.slang` (layout evidence, not a runtime check):
`RCSegment` is 32 bytes at offsets 0/12/16/28; `RCTraceEntryStatus` is 32 bytes
at offsets 0/4/8/12/16/20/24/28; `RCProbeTracePush` is 112 bytes; the pass has
16 descriptors in one set plus the push block.

`rc_trace_segments.slang` remains obsolete and excluded from the sweep:
`RtSlangVisibilityOverflowTest.java` requires the `surfaceVisibilityProven(`
string in that path.  `path_trace.slang` and `path_trace_scene.slang` remain
duplicated because existing Java source-scan tests assert exact lines in
`passes/path_trace.slang`; deduplicating them is still blocked on changing
those tests.

This is L1 only.  It proves module resolution, re-exports, generic
instantiation and the reflected record layout above.  It does not prove a GPU
dispatch, the host descriptor range or stride, a live frame or visual parity.
`gradlew :common:test` cannot configure in this checkout because
`fabric/build.gradle` requires the missing `fabric-loader-0.19.5.jar`; native
CI does not compile shaders.  PR #10 remains open and unmerged.

## 2026-10-28 — shader module cleanup (L1 compile)

Branch: `feat/slang-rc-transport-20261028`.  Verified on cleanup commit
`b1137fd`; merged with the moving base `175515f` in `09e601b`.  The base merge
did not change any shader source, so the compile state below is the same tree
for the shader modules.
Tool: `slangc` 2026.20, `-target spirv -profile cs_6_5 -entry main
-matrix-layout-column-major -I common/src/main/resources/shaders/slang`.

Source changes covered by this run:

- `radiance_cascades/rc_trace.slang` is now a facade over
  `rc_trace_options`, `rc_trace_shade`, `rc_trace_walk`, `rc_trace_dispatch`,
  `rc_trace_history` and `rc_trace_ownership`.
- `rc_atlas_layout.slang` owns the shared addressing; `segment_store.slang`
  no longer imports the legacy float4 store.
- The legacy float4 implementation moved to `legacy_float4_store.slang`;
  `radiance_cascades_store.slang` is a compatibility facade. Its resolve is
  named `rcLegacyProbeIrradiance`/`rcLegacyProbeComplete`.
- `bedray.slang` is the curated typed root; `bedray_legacy.slang` is the
  explicit compatibility root.
- `radiance_cascades.slang` no longer imports the legacy `types` module; the
  addressing path defines its own layout records.
- New harnesses: `verify_rc_trace_api.slang` and `verify_bedray_roots.slang`.
  `verify_rc_typed.slang` now imports `rc_atlas_layout.slang`, not the legacy
  store.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 17 | 17 | unchanged pass sources |
| RC/root harnesses | 5 | 5 | typed, environment, full trace API, both roots, scene tracer |

`rc_trace_segments.slang` is obsolete and was deliberately not compiled.

This is L1 only. It proves module resolution, re-exports and generic
instantiation. It does not prove a GPU dispatch, the reflected 32-byte stride,
a descriptor range, a live frame or visual parity. The native CI on `102ddae`
(L2) remains the latest host/ABI evidence and does not compile shaders.

## 2026-10-28 — native CI and P0-2 exception containment

Base: `refactor/slang-pipeline` at
`aaaf00cb4ad90887739c2f4813841c13f14b62e1` (remote clone confirmed).
Branch: `codex/native-ci-abi-exceptions`.

### Source review

Read: `SLANG_MIGRATION.md`, `PATH_TRACE_CPP_INTEGRATION_PLAN.md` (including
sections 9/10), `DEVELOPMENT_BRANCHES.md`, `AUDIT_DEFECTS_AND_ISSUES.md`,
`FEATURE_MATRIX.md`; native core `RECORDING_LIFETIME.md`, scene `SCENE_NOTES.md`,
history `C_ABI.md`, render graph `RENDER_GRAPH_NOTES.md` (including live-path
gap); addenda 01, 02, 03, both 04 files, 06, 09, 10 and 11. The addenda are
historical handoffs; the current task explicitly authorizes scoped native CI
and tests and does not inherit their old verification freeze.

P0-2 is confirmed at the base: buffer/image/history C wrappers lacked outer
exception boundaries. Several core exports also lacked them. The patch adds
function-try-blocks with `bad_alloc` and catch-all mapping. Resource-owner
creation publishes its handle only after successful construction/validation.
No ABI structure size, field offset, function signature or existing numeric
status changes. Two additive wrapper errors distinguish unexpected exceptions:

| Boundary | Allocation exception | Unexpected exception |
|---|---|---|
| Core status exports | `BEDRAY_ERROR_OUT_OF_MEMORY` | `BEDRAY_ERROR_INTERNAL` |
| GPU buffer/image wrappers | `BEDRAY_GPU_OUT_OF_MEMORY` | `BEDRAY_GPU_INTERNAL_ERROR` (0x101) |
| History wrappers | `BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY` | `BEDRAY_HISTORY_INTERNAL_ERROR` (0x103) |

The GPU/history status domains remain independent of `BedrayStatus`; they are
not numerically cast to it. Version exports return zero on an unexpected
exception. Existing core void exports contain exceptions but cannot return a
status without an ABI change. They remain an explicitly documented limitation.
`VkResult` and successful outputs are valid only on their documented return
paths; a wrapper exception is not a Vulkan driver failure. The boundary does
not promise rollback after arbitrary internal mutation, catch invalid-pointer
accesses, or recover from a violation of an internal `noexcept` contract.

The new allocation-injection test links the actual GPU/history C wrappers into
one process and replaces its throwing `operator new`. It injects `bad_alloc`
and a non-standard exception into all three owner-creation exports, checks the
exact status and null output, then checks successful creation/destruction.
Core sampler tests inject both exception kinds through fake Vulkan callbacks
and check status/error agreement and containment during destruction. Existing
tests continue covering lifecycle, descriptors, scene, history and scheduler.
The ABI probe and the standalone dynamic C resource test are now registered
with CTest as well as built.

P0-1: `SlangProgram.nativePushConstants` and
`SlangPassLibraryTest.heapAndDirectPushConstantsHaveNativeStorageAndKeepTheInputPosition`
already exist at the base. Heap and direct input coverage is present; no fresh
Java execution is claimed. Source recheck of P0-3 finds branch probability
multiplication and the sole-branch return, guarded by the existing
`RtSlangBlockEmitterTest.theBranchProbabilityIsFoldedIntoTheDensity`. P0-5 now
uses geometry-local primitive IDs with `geometryIndex` selecting the range,
adding `firstQuad` once; the scene contract test checks range-local AS builds.
P0-6 publishes the merged reservoir when the original sample survives,
guarded by the existing `RtSlangRestirSpatialTest` source checks. These are
source observations, not executed Slang or fresh Java evidence. P0-4's old
float4 confidence-weighted resolve still exists and requires shader-owner
investigation alongside the typed segment path; do not connect the two as if
their contracts matched. This PR changes no shader code and does not certify
shader math.

### Reproducible native CI

`.github/workflows/native-windows.yml` runs on `windows-2022` via
`workflow_dispatch` or pushes to this work branch. Separate baseline and
candidate jobs use the exact base SHA and the triggering candidate SHA.
No Java, Minecraft, proprietary `libs/`, driver or GPU is required.

Pinned inputs:

- Vulkan-Headers v1.4.321, commit `2cd90f9d20df57eac214c148f3aed885372ddcfe`.
- Slang v2026.20, `slang-2026.20-windows-x86_64.zip`, SHA-256
  `e6dd32bbddf1720ebb4e679d55e9635ce792865a5bd84f3d2cd9af28647c0174`.
  Its version probe verifies availability only; native CMake does not consume
  Slang, and this workflow does not compile or execute shaders.
- Checkout/upload actions are pinned by full commit SHA in the workflow.

CI driver (identical on baseline and candidate):

```powershell
./driver/.github/scripts/native-ci.ps1 -SourceRoot source `
    -Headers deps/Vulkan-Headers/include -BuildRoot evidence/build
```

For core it enables graph, history, GPU resource and standalone shared C ABI
tests, builds Release x64, runs CTest with `--no-tests=error`, and runs the ABI
probe. It then independently configures/builds/tests scene and frame lifetime.
The driver logs commands and exit codes. Text-only artifacts retain dependency
versions, build/configure logs and CTest JUnit output for both revisions.
Generated SDKs, SPIR-V, DLLs, libraries and JARs are not committed or uploaded.

### Results at preparation time

- `git rev-parse HEAD`: base SHA above, exit 0.
- `git diff --check`: exit 0.
- Local CMake, CTest and C++ compiler: not found in PATH or standard Visual
  Studio/CMake installation locations; no local build/test was run.
- CI execution: pending branch publication; no success is inferred from the
  existence of a workflow. Source changes are L0 until a successful matching run.
- No Java build/test, shader compilation, GPU/validation-layer run, Minecraft
  session, performance measurement or owner live acceptance.

The native path remains opt-in. No graph/history live path is enabled and no
queue/device/submission/wait operation is introduced. If the baseline fails,
preserve its logs and resolve that baseline separately before claiming a
candidate regression or moving on to live integration.

### Executed CI result — run 40712959065

[Windows CI run](https://github.com/bnfe12/bedray/actions/runs/40712959065)
completed successfully on 2026-10-28. Candidate commit:
`0a076fb1cfb2195521174a3b0b03bb61539f6d2c`.

| Revision | Core CTest | Scene CTest | Frame lifetime CTest | ABI probe |
|---|---:|---:|---:|---|
| Base `aaaf00cb4ad90887739c2f4813841c13f14b62e1` | 12/12 | 1/1 | 1/1 | v2, exit 0 |
| Candidate `0a076fb1cfb2195521174a3b0b03bb61539f6d2c` | 15/15 | 1/1 | 1/1 | v2, exit 0 |

Every configure/build/CTest command exited 0 on both revisions. Candidate's
additional CTest entries are `bedray_abi_probe`, `bedray_abi_exception` and
`bedray_gpu_dynamic_c`. The core sampler test also executed its new exception
cases. Both dependency checksum/version steps and text artifact uploads passed.
Baseline compiled without C compiler warnings reported in the job log;
candidate reported three C4389 signed/unsigned comparison warnings in the new
test. A follow-up makes those comparisons explicitly use the public status type;
that source change requires its own matching run before extending this evidence.

Evidence for the tested native modules at the named commits is **L2**, with no
GPU. The whole Slang/C++ migration remains incomplete; Java, shader compilation,
driver behaviour, real resources and game rendering were not checked.

### Clean follow-up — run 40713467738

[Windows CI run](https://github.com/bnfe12/bedray/actions/runs/40713467738)
completed successfully for candidate
`32794834789a974b5726603b4ffb9bd721882d51` on 2026-10-28.

- Baseline: core 12/12, scene 1/1, frame lifetime 1/1; ABI v2 probe exit 0.
- Candidate: core 15/15, scene 1/1, frame lifetime 1/1; ABI v2 probe exit 0.
- Both job logs contain no C compiler or linker warnings/errors. All logged
  configure/build/test commands exit 0; dependency and evidence-upload steps pass.
- CMake/CTest 3.31.6, MSVC 19.44.35229.0, Windows SDK 10.0.26100.0,
  Visual Studio 17 2022 x64 Release on `windows-2022`.
- Native evidence remains L2. No additional Java, shader or game evidence.

The following journal-only commit does not alter tested code or CI scripts;
its `[skip ci]` marker avoids rerunning identical native tests solely to record
their result. Review code evidence against the exact candidate SHA above.

## 2026-10-28 — RC transport branch, native CI on the merged revision

Branch: `feat/slang-rc-transport-20261028` (PR #10).
Candidate: `554c77d2cb5cebeaef7c339eb30efa97639b1266` — merge of
`refactor/slang-pipeline` at `16a675a` into
the Radiance Cascades transport branch.
Baseline: `aaaf00cb4ad90887739c2f4813841c13f14b62e1`.

[Windows CI run 40717925815](https://github.com/bnfe12/bedray/actions/runs/40717925815)
completed successfully on 2026-10-28. The workflow has no `pull_request`
trigger yet, so it was started manually with `workflow_dispatch`. Both jobs
fetched and checksum-verified the pinned Vulkan-Headers and Slang dependencies.

| Revision | Core CTest | Scene CTest | Frame lifetime CTest | ABI probe |
|---|---:|---:|---:|---|
| Base `aaaf00c` | 12/12 | 1/1 | 1/1 | v2, exit 0 |
| Candidate `554c77d` | 15/15 | 1/1 | 1/1 | v2, exit 0 |

Every logged configure/build/CTest command exited 0. Candidate's additional
core entries are `bedray_abi_probe`, `bedray_abi_exception` and
`bedray_gpu_dynamic_c`. The text evidence artifacts uploaded.

Evidence for the tested native modules at `554c77d` is **L2**, no GPU. This
does not validate the Slang shader changes in the same branch; their evidence
is the separate L1 `slangc` 2026.20 compile sweep recorded in
`SLANG_MIGRATION.md`. Java, shader dispatch, driver behaviour and game rendering
were not checked. The RC runtime contract (stride, barriers, cross-cascade
resolve, environment boundary supplied by the host) remains unproven.

## 2026-10-28 — host recording completion bridge

Integration base: `16a675afb13a775c7b706d2eec0799ca6a2d2087` (PR #9 merged
after preserving the newer audit commits through `5fc969e`). PR #9 was rebuilt
successfully after conflict resolution in
[run 40716123286](https://github.com/bnfe12/bedray/actions/runs/40716123286).

`RtFrameRenderer` now retires one captured owner set through the same encoder's
post-submission destruction callback, rather than releasing by reused command
buffer address. The CPU `RecordingCompletion` ticket rejects handle reuse while
pending; an old callback cannot free a newer generation. Unsubmitted abandon
releases immediately. Exceptions during execute or callback registration keep
UNKNOWN resources for host-proven completion. Independent release failures
retain their own strong references and can be retried without repeating already
successful owner releases. Scene and Sodium resources use the same callback;
the particle callback captures its recorded composite owner. Scene close remains
retryable when release/destroy fails.

The existing host drain now creates a host fence for the pending shared encoder
submission, submits through the host and awaits its completion. Timeout/failure
retains resources. No native submit/wait or `vkDeviceWaitIdle` is introduced;
the old device-wide wait in this host drain is removed.

Verification (no GPU):

- JDK 25.0.3, Gradle 9.5.1; local launcher-provided 26.3/Sodium/Iris JARs kept
  in ignored `libs/`, Slang 2026.20 verified against the SDK SHA-256 above.
- Baseline and candidate `:common:compileJava`: exit 0, both completed.
- Standalone `javac`/`java` RecordingCompletionChecks: exit 0. Covers completion,
  discard, unknown execute, enqueue failure, duplicate/stale tickets, late
  callbacks, release failure/retry and retention of open recordings during idle.
- `:common:test --tests '*RecordingCompletionTest' --tests
  '*SlangPassLibraryTest.heapAndDirectPushConstantsHaveNativeStorageAndKeepTheInputPosition'
  --tests '*BedrayScene*Test'`: exit 0, **2 tests executed and passed** (the last
  selector matched no test class). Existing Unsafe warnings in Iris test sources
  remain. The invocation sets `BEDRAY_LAUNCHER_LIBRARIES`,
  `-Pneoforge_libs_dir=<local downloaded public loader dependencies>`, `SLANG_DIR`,
  and scopes `-PslangPasses=path_trace.slang,path_trace_scene.slang,path_display.slang,restir_spatial.slang,bloom_*.slang,sodium_dequantize.slang`.
- Those nine AOT programs compile and pass the build-time reflection/layout
  checks. No shader source was changed by this host-lifetime patch.
- Evidence: L1 Java/AOT compilation and L2 CPU lifecycle/heap-direct checks.
  No GPU dispatch, validation layers, game startup or rendered effect checked.

## 2026-10-28 — scene embedded in core and BDA allocation safety

Base `4236c75ab72fded1347dd19d88f38a3804742a38` includes the host-completion
bridge (PR #11). Scene's two implementation sources are now compiled once into
an object target and embedded in core; the standalone scene DLL is retained
for ABI testing. FFM uses the validated core path for scene exports; the player
distribution builds/packages core alone. Gradle inputs include all linked native
modules. No existing C ABI layout/signature changes, no Vulkan loader link,
second device/queue or new submission API.

P0-8 is confirmed in the baseline scene allocator: a BDA usage flag was not
accompanied by the memory allocation flag. The patch attaches the device-address
allocation flags only for buffers that use BDA and rejects a zero returned
address with cleanup before publication. The fake driver checks each memory
binding's usage/allocation flags (including staging without BDA), and the scene
test exercises zero-address failure without recording AS builds. A new dynamic
core test checks all ten scene exports and ABI v2 sizes from the actual core DLL.

Windows native CI now also runs on `codex/migration-*` branches and compares
against this integration base. Native compilation/tests are pending publication
for this patch. No GPU/driver/Minecraft or visual result is claimed.

Executed result: [run 40718040730](https://github.com/bnfe12/bedray/actions/runs/40718040730),
candidate `5e892753d62eeb72a85f732b3ffca9880af08db2`: core **17/17**,
standalone scene **1/1**, frame lifetime **1/1**, ABI probe v2 exit 0.
Baseline core 15/15 + scene 1/1 + lifetime 1/1. Both job logs contain no C/C++
compiler warnings/errors; configure/build/CTest, dependency checks and artifact
uploads all succeed. The embedded scene export probe passes against core DLL.
Local `:common:compileJava` also exits 0 with the same ignored local dependencies
as the host-completion batch. Scoped native evidence is L2, Java compilation L1.
The following journal-only commit skips identical native CI.

## 2026-10-28 — native opaque frame graph and scheduler

Base `7a06dd7b996815fdf3c2177aec6b0cf328260065`. The native coarse path now
constructs `FrameGraphOwner`, compiles one opaque recording group for the real
`PathTracePass::render` callback and leases its recording through FrameScheduler.
The existing completion/discard C ABI release reaches the scheduler's exact
ticket and calls back into `bedray_core_release_recording` to drop descriptors
and pass resource pins. A guarded reentrant path avoids a second ticket release.
Configuration/destruction refuse outstanding graph tickets, including failed
partial recordings. Failed release callbacks retain their ticket/slot for retry.

[MIGRATION_INTEGRATION_DECISIONS.md](MIGRATION_INTEGRATION_DECISIONS.md) records
all eight live-path decisions: pass-owned allocator/private barriers/history,
opaque dispatch group, enabled capability mapping, exact host proof, eight
leases over shared pass image generations, one graphics family, unchanged v2
coarse ABI. No synthetic serial or frame index is used as completion proof.

New no-GPU tests exercise opaque-node barrier suppression, capability rejection,
slot exhaustion, duplicate recording rejection, partial recording failure,
release failure/retry and unknown-handle idempotence. Scheduler tests now require
lease retention when release fails and reject stale exact-ticket proof.
Matching Windows build/CTest results are pending publication. No GPU, driver,
Minecraft, performance or visual evidence is claimed; opt-in stays unchanged.

Executed result: [run 40720124561](https://github.com/bnfe12/bedray/actions/runs/40720124561),
code `5fb4ad1aababe224013ed908eb108358c047fbb0`: core **18/18** (including the
new frame-graph owner), standalone scene **1/1**, frame lifetime **1/1**.
All configure/build/CTest commands exit 0; ABI probe remains v2. Candidate job
log has no C/C++ compiler warnings/errors. Workflow's pinned comparison baseline
is `4236c75` (15/15 + 1/1 + 1/1), while this PR is based on `7a06dd7` whose
native code was separately checked by the preceding scene run. Native evidence
is L2 for the named code. The following journal-only commit skips identical CI.

## 2026-10-28 — native DI reservoir ownership

Base `6fb04a27a2255d1a18e57e9609bef6cd5f14389b`. Adds an optional feature-mask
query and host-only NATIVE_DI/RESET_DI bits without changing ABI v2 structures
or shader push/binding layouts. PathTracePass allocates and pins its two-parity
ring, clears it through the borrowed command buffer and owns history/parity.
Resize/disable retire allocations through the existing completion-proof owner.
Java sends reset requests and scene captures; older DLLs keep the external-ring
contract. The native path remains opt-in. Buffer ranges now validate overflow,
reserved words and complete material/emitter/reservoir records before recording.

Fake-driver checks cover native clear/history/parity, reset, resize with three
outstanding recordings, disable retirement and conflicting host ownership.
Windows native compilation/CTest and local Java compilation are pending.
No GPU, driver, game, performance or visual result is claimed.

Executed result: [run 40723147943](https://github.com/bnfe12/bedray/actions/runs/40723147943),
code `7dc6521`: core 18/18, scene 1/1, frame lifetime 1/1; no native test
failures. Local `:common:compileJava` exits 0 (Java L1). Native evidence is L2;
no live claim. Journal-only follow-up skips unchanged CI.

## 2026-10-28 — single native scene owner

Base `0181549b3537659c6d83bbc3ac655d81cff53ebd`. Opt-in native scene mode now
paces CPU section captures and serializes POD directly into bedray_scene. It
allocates no Java geometry/BLAS arenas, TLAS, decode pipeline, scratch, instance
table or entity BLAS. The existing Java mode remains the reference when native
scene is not selected/available at initialization. A failure after native scene
activation propagates, retains pending updates and never silently omits geometry
or switches to unallocated Java resources. Emitter scanning visits accepted CPU
captures. Native AS counters and actual VkDeviceMemory bytes feed telemetry.

An additive resident-bytes query leaves ABI v2 layouts intact. Entity-only/empty
section sets publish a real zeroed 64-byte section descriptor while section_count
stays zero. Driver tests compare reported resident bytes against all actual fake
allocations, including retired/pinned generations, and check entity-only tables.
A POD packing test checks pass-local indices and vertex attributes. Native scene
is closed after host drain before unloading core; world teardown no longer calls
vkDeviceWaitIdle. Native CI and Java/AOT verification are pending; no GPU/game
or visual evidence is claimed.

Executed result: candidate `ae45bf9`, [run 40724808514](https://github.com/bnfe12/bedray/actions/runs/40724808514)
passes core 18/18 + standalone scene 1/1 + frame lifetime 1/1, ABI v2 probe.
No C/C++ compiler warnings/errors in either job log. The first candidate run
40724238754 correctly caught an old table-test assertion (one upload); publishing
the valid empty section descriptor adds a second upload. That expectation was
updated and the complete native run repeated successfully. Local Java compile
and all 17 AOT passes compile successfully. Targeted JUnit: 19 recording/manifest/
path-contract tests plus 4 scene API/POD packing tests pass. Existing Iris test
Unsafe warnings and JOML deprecation diagnostics remain. Evidence: native/CPU L2,
AOT/Java L1. No GPU/game result. Journal-only follow-up skips unchanged native CI.

## 2026-10-28 — completion versus abandoned native writes

Follow-up to the single-owner scene batch. RecordingCompletion now carries
separate completed/discarded actions per owner and retains both disposition and
successful actions across cleanup retries. UNKNOWN execution stays pinned until
host drain, then uses conservative abandonment cleanup. Open recordings stay
pinned; late callbacks cannot retire a reused handle generation.

Additive core discard invalidates image initialization/accumulation and DI
history/parity before exact graph/scheduler release. Additive scene discard
poisons further update/prepare/resource queries, since its committed uploads/AS
builds may never have executed; unrelated outstanding frames stay pinned and
must still be released before scene recreation. Normal completion keeps caches.
Java routes proof type to each owner. ABI v2 layouts and shader interfaces remain
unchanged. The native-buffer fallback budget now covers the native DI ring when
device-memory telemetry is absent (512 MiB rather than the old constants-only
16 MiB). No submit/wait/device-wide idle is introduced in core/scene.

No-GPU checks cover successful versus abandoned cleanup, unknown execution,
discard retry, stale callbacks, pass history/parity invalidation and scene reuse
refusal while another frame remains pinned. Java/native results pending for the
final source revision; no GPU/game/visual correctness is claimed.

Executed result: source `2ce6c6249180ad171556541a9e8bd466c21cf584`,
[run 40725919179](https://github.com/bnfe12/bedray/actions/runs/40725919179):
core 18/18, standalone scene 1/1, frame lifetime 1/1; ABI v2 probe exit 0.
Both native jobs have no C/C++ compiler warnings/errors. GitHub reports the
pinned actions' Node 20 runtime deprecation, separate from compiler/test results.
Final local `:common:compileJava :common:test` with RecordingCompletionTest,
BedraySceneGeometryTest, RtSceneApiTest, RtPathTraceSlangPassTest and
SlangPassLibraryTest exits 0: 23 tests, zero failures/errors/skips. The recording
suite executes the standalone completion/discard transition checks. All 17 AOT
passes compiled in this worktree; the unchanged final shader sources reused
those outputs. This is native/Java CPU L2 and compiler L1, not GPU/game evidence.
Journal-only follow-up skips identical native CI. Native switches remain opt-in.

## 2026-10-28 — native CI on the typed RC cleanup head (L2)

[Run 40727076703](https://github.com/bnfe12/bedray/actions/runs/40727076703)
executed on `c5ce8c6`, the published head of `feat/slang-rc-transport-20261028`
after the cleanup `b1137fd` and the base merge `09e601b`. The merge changed no
shader source (`git diff b1137fd..c5ce8c6 -- common/src/main/resources/shaders
common/src/test/resources/shaders` is empty).

Baseline `4236c75`: core 15/15, standalone scene 1/1, frame lifetime 1/1.
Candidate `c5ce8c6`: core **18/18**, standalone scene **1/1**, frame lifetime
**1/1**, ABI probe v2 exit 0. All configure/build/CTest commands exit 0 and there
are no native test failures in either job. The workflow is manual
(`workflow_dispatch`), does not compile or run shaders, and provides no GPU,
game, performance or visual evidence. Shader L1 is recorded in the section above;
the Java source-scan tests were checked manually because `:common:test` cannot
configure without the Fabric launcher jars.

The base advanced again to `a8f796b` (PR #16, recording discard) and was merged
in `eda6e19`; that merge also changed no shader source. Final head
[run 40727836738](https://github.com/bnfe12/bedray/actions/runs/40727836738):
baseline `4236c75` core 15/15, candidate `eda6e19` core **18/18**, standalone
scene **1/1**, frame lifetime **1/1**, ABI probe v2 exit 0. Both jobs succeeded
with no native test failures. Same limits: native L2 only, no shader compile,
GPU, game or visual claim. A final shader sweep on the published tree at
`913e4e0` repeated all 17 `passes/*.slang` plus the five harnesses with exit 0;
the shader sources are still identical to `b1137fd`.

## 2026-10-28 — migration code cleanliness review

Reviewed the integrated migration from base `a8f796b95f4b3dcb1866669d521586d45a4f6323`,
with focus on Java FFM/host ownership, scene capture, completion/discard bookkeeping,
PathTracePass pin/retirement and the graph owner. Removed write-only frame/memory
fields and unused internal memory arguments; simplified section ID preservation;
made the chosen world scene owner final. Corrected stale DI/table-lifetime comments,
misplaced/duplicated API JavaDoc and verbose repeated type names. Native DI telemetry
now says native rather than claiming fresh Java-owned history every frame.

`git diff --check` passes. Searches found no conflict markers or empty catch blocks
in the reviewed ABI/scene code, nor submit/queue-wait/device-idle calls in native
core/scene. No dedicated formatter/linter configuration was found. The focused
review found no additional critical defect; this is not an audit of every subsystem.

Executed `:common:compileJava :common:test` with RecordingCompletionTest,
BedraySceneGeometryTest, RtSceneApiTest, RtPathTraceSlangPassTest and
SlangPassLibraryTest: BUILD SUCCESSFUL, 23 tests, zero failures/errors/skips.
Local dependency paths match the preceding verification batch. Existing Java
API/JOML Unsafe deprecation diagnostics remain. Native sources and shader sources
are byte-for-byte unchanged from the preceding passing native/AOT evidence;
identical native CI is deliberately skipped for this Java cleanup. No GPU/game
result is claimed. ABI layouts, native lifetime protocol and shader math are unchanged.

The integration base advanced to `6632bca4219c13937e9f19fed7f65983e670f5be`
(RC shader-owner PR #10) during review. Merged it without editing its sources;
the sole conflict was the append-only verification journal and both records are
preserved. Repeated the same Java compile/23-test command on `e4eea40`: success,
zero test failures/errors/skips. The default AOT build freshly compiled all
18 production passes, including rc_probe_trace, with reflection validation.
The final follow-up only clarifies the stale migration-status documentation.
Relative to the refreshed base, cleanup changes no native or shader sources.

## 2026-10-28 — explicit native/reference path execution boundary

Base `a7f91404132d9abed9607867ff94c5374b82480a`. RtSlangPathPass now captures
camera/settings/scene POD and delegates execution through PathTraceBackend.
NativePathTraceBackend registers borrowed runtime programs and calls the coarse
C++ pass; it creates no Java GPU owner, dummy buffers, descriptors or Vulkan
commands. ReferencePathTraceBackend contains the existing Java orchestration,
private images and DI ring. The native implementation therefore no longer
constructs the reference pass just to obtain program handles. Matching native
cores must advertise NATIVE_DI; explicit native selection reports unsupported
cores instead of silently selecting reference execution. The default stays Java
until owner GPU/game verification. ABI v2 layouts and shader sources are unchanged.

Spatial wrappers now borrow cached programs from SlangRuntime. Closing a wrapper
cannot close a still-cached program; pending recording retirement is required
before wrapper close. The controller publishes successful history/reset
consumption only after record returns. Unknown discard handles do not reset it.

Executed comparison with the same local dependencies/JDK/Slang SDK as above:
`:common:test --tests '*RtSlang*Test' --tests '*RtPathTraceSlangPassTest'
--tests '*SlangPassLibraryTest' --tests '*RecordingCompletionTest'
--tests '*BedraySceneGeometryTest'`. Base runs 80 tests, 3 failing obsolete Java
source assertions in RtSlangRestirDiTest and RtSlangRestirSpatialTest (native DI
ownership from the earlier integration); no shader math test fails. This change
updates those ownership/file-boundary assertions and adds PathTraceBackendTest.
Candidate command adds `--tests '*PathTraceBackendTest'`: 83/83 pass, zero
errors/skips, including record failure/reset latching, resize/discard, close
retry, display-bit translation and byte-exact preservation of other push fields.
Java compilation exits 0. All 18 unchanged AOT passes also compile on the baseline.
Native C++ is unchanged, so the existing 20-test native evidence remains scoped
to the same sources and identical native CI is skipped. No GPU/game claim.

## 2026-10-28 — isolated native section capture queue

Base `446622916bb3d75c3f7ecded5571ab4a59c92760`. NativeSectionCapture now owns
CPU selection, stable nearest-first ordering, pacing, accepted capture snapshots
and retry bookkeeping. RtWorld delegates capture selection then calls the native
scene owner; capture acceptance never denotes GPU completion. SectionGeometryIdentity
shares geometry/tag-hash rules between reference and native paths. Capture has no
Vulkan handles, allocations or native resource destruction. Construction rollback
also closes an empty scene if capture-module construction fails.

The CPU budget now includes stale/identical queued captures, which previously
could bypass it. Sink failure leaves that capture pending and the accepted
snapshot/version unchanged. Scan-key publication follows successful eviction/
queue construction, so an eviction failure is retried. Camera/radius changes
rescan even with an unchanged source version. Equal-distance order uses the
section key for deterministic selection.

Executed the previous 83 checks plus NativeSectionCaptureTest and RtSceneApiTest:
92 tests, zero failures/errors/skips; Java build exits 0. Six new no-GPU cases
exercise quota/order, failed copy and eviction retry, identical remesh/tag changes,
stale snapshots, camera changes, budget enforcement and clear/rescan. Shader and
native sources are unchanged; native/AOT evidence is reused for those exact
sources. Matching GPU/game verification remains outstanding; native stays opt-in.

## 2026-10-28 — entity capture leases follow exact recording proof

Base `99228bcff711ceb037a7675fb875819a541df18b`. Extracted host entity GPU
storage into EntityCaptureBuffers, using RecordingResourceOwner for exclusive
writable slices and shared immutable atlas generations. Exhausted slots allocate
a new generation. Replaced modulo slot overwrite and fixed-frame destruction
with release through RtWorld's exact completion/discard action. World/device
identity is checked; close refuses outstanding recordings. Partial allocation,
atlas-copy and destructor failure retain retryable ownership. Untracked GPU
upload overloads now reject work; null-memory compatibility remains available.

Also corrected staged-quad index packing: it previously wrote the 65,536-quad
template into an allocation that could be only 256 KiB. EntityQuadIndices now
checks the mapped range before writing the actual quad count. No shader math,
bindings, native implementation or ABI layout changed.

Executed Java compilation and 109 selected tests, zero failures/errors/skips:
the preceding 92 checks, RecordingResourceOwnerTest (6), EntityCaptureBuffersTest
(6), and RtEntityUploadTest (5). The fault-injection cases cover slot pressure,
shared atlas readers, stale tickets, foreign owners, partial create/copy/destroy,
close retry, quad-buffer reuse and bounded index writes. Command uses the same
local dependencies/JDK/Slang SDK, `--offline --max-workers=2 :common:test` with
the preceding test filters plus the three classes above. `git diff --check`
passes. Native sources and all 18 AOT pass sources are unchanged.

An initial broader `*RtEntity*Test` run was interrupted while the unchanged
RtEntityHermeticityTest.testShaderCompilation spent several minutes in native
shaderc compiling legacy GLSL. That interrupted run is not passing evidence;
the subsequent focused command completed successfully. No GPU/game claim.

## 2026-10-28 — immutable emitter publications with completion leases

Base `fcc0f799e4f10dcb86fd15da4a06d0246490bde9`. Replaced the unproven three-
segment emitter overwrite rule with EmitterCaptureBuffers. Each selected list
is copied into fresh immutable storage; unchanged lists share storage and every
reading recording pins it through RecordingResourceOwner. Publications allocate
only the selected record bytes, empty lists allocate nothing, and old storage
retires only after all readers' completion/discard. The frame ticket captures
the exact RtEmitters owner before recording begins. Selection/scan logic stays
in RtEmitters; GPU allocation/copy/retirement is in the separate storage module.
Published origin/count/fingerprint change after the copy. No emitter math,
record layout, shader source, native implementation or ABI layout changed.

Java compilation and 134 selected tests pass, zero failures/errors/skips.
Command repeats the preceding 109-test filters and adds EmitterCaptureBuffersTest
(7), RtEmittersStabilityTest (10), RtEmitterScanPassTest (6), and QuadEmitterBuilderTest
(2). New no-GPU tests cover shared lists, ten outstanding readers, byte-exact
packing/range guards, empty lists, allocation/copy/cleanup failures, close refusal
and a late completion callback after command-handle reuse through the real host
ticket bridge. `git diff --check` passes. The existing native/18-pass AOT evidence
applies to unchanged implementation sources; no identical native CI repeated.
This changes allocation behavior but has no measured GPU/performance/game result.

## 2026-10-28 — reconcile migration contracts with the backend boundary

Base `65ad244eb3ffd131525778053cd13dfc389a8fda`. Refreshed native ownership,
explicit selection failure, embedded scene, DI, graph/scheduler and host-fence
descriptions in SLANG_MIGRATION and the integration plan. The earlier graph gap
analysis is retained as a clearly superseded historical checkpoint, with the
current implementation/decision document taking precedence. Supporting post
passes, arbitrary cross-node image contracts, shader parity and GPU/game
acceptance are not declared complete.

RtRestirSettingsTest still searched for allocation/dispatch code in the old
controller. Baseline command `:common:test --tests '*RtRestirSettingsTest'`
ran five cases: four pass, thePathTracerSplitsTheRingFromTheReuse fails at the
obsolete dispatch source assertion. Updated that guard to follow the real
controller request, reference allocation/dispatch and required native-DI coarse
flag; persistence, UI/dependency, reset and ray-pass checks remain intact.

Candidate repeats the preceding 134-test command with RtRestirSettingsTest:
139 tests pass, zero failures/errors/skips, Gradle exits 0. `git diff --check`
passes. Production Java, native implementation, shader sources and ABI layouts
are unchanged in this contract/documentation change. No new GPU/game evidence.

## 2026-10-28 — coarse native bloom recorder

Base `bc1bf8d36e6e9bba2a7a1e9ecd294775fc5aa66e`. BloomPass now owns the existing
six-mip prefilter/down/up/apply chain, transitions, entry/inter-dispatch/exit
barriers and exact image-generation pins. RtBloomPass supplies borrowed runtime
programs/samplers, host views and the unchanged 128-byte push payload through
BedrayBloomNative. One coarse call records twelve dispatches with bloom or one
glare-only apply. The adapter contains no image owner, per-dispatch binding table,
dispatch loop or Vulkan barrier. Removed its duplicate mip-size implementation;
the corresponding old Java helper checks now exercise the native implementation
in bloom_pass_test.cpp. Path trace and bloom tests share one fake pass driver.

The additive bedray_bloom.h descriptors are 64/56 bytes with version/size guards,
native offset assertions and Java FFM layout/slice tests. Existing ABI v2 layouts,
shader bindings and shader math are unchanged. Programs remain borrowed from
SlangRuntime. Matching bloom exports are required; no GLSL fallback is added.

Resize retains old pinned generations. A discarded current generation forces
fresh mip images because its transitions may not have executed; discarding an
older generation cannot invalidate a newer one. Close/reconfigure refuse pending
recordings. Device loss stops new recording and retains the whole lost pool until
every affected command buffer receives host completion/discard proof, then frees
it together. An intermediate CI run on 5faae45 exposed that the lost image owner
rejects ordinary complete_use calls; this final bulk cleanup follows all host
proofs instead of assuming individual lost-owner operations still work.

Final native implementation/test revision `b8186f9092b1b56165e2177741691bb855f7862e`:
[Windows CI run 40787939814](https://github.com/bnfe12/bedray/actions/runs/40787939814)
passes baseline core 15/15 + scene 1/1 + lifetime 1/1 and candidate core 19/19 +
scene 1/1 + lifetime 1/1; ABI probe v2 exits 0. Tests include dispatch order/groups,
binding contracts, byte-exact pushes, glare-only mode, resize, partial allocation/
recording failure, discard, unsupported extent and loss with two pending readers.

Final Java command repeats the preceding 139-test filters with BedrayBloomNativeTest
(3): 142 tests pass, zero failures/errors/skips; Java/test compilation exits 0.
It uses `--offline --max-workers=1`, JDK25 and the same local dependencies, with
Gradle JVM `-Xms32m -Xmx768m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC
-XX:ActiveProcessorCount=2`. Earlier attempts failed before tests when Windows
commit memory was exhausted (JVM native allocation/metaspace failures); those are
not passing evidence. The completed retry and final run succeed. A source-body
comparison also confirms fillPushConstants is unchanged from the base.

`git diff --check` passes. All 18 AOT shader sources are unchanged. No GPU/game,
visual parity or performance claim. Supporting volumetric integration remains
blocked on its legacy-section shader boundary; it cannot consume a native table
merely because the record byte size matches.

## 2026-10-29 — native compatibility with the shader-owner guide contract

Base `486a0841ae81bbd32c7f23719028d882ca927c0b` includes the shader-owner entity,
water and DLSS-RR guide changes through edb6a72. Those entries now declare 25
bindings; the native pass still required 18 and therefore could not configure
against the current AOT programs. C++ now validates either complete contract,
independently for the legacy/native scene program. Current programs bind three
native-owned one-texel images with the exact R32F/RGBA16F/RGBA8 formats at guide
bindings 26..32. Their pins, transitions and destruction follow the pass's existing
recording proof. A guide-write request is explicitly unsupported before pins or
commands; this change does not claim native denoiser output.

The additive GUIDE_BINDINGS feature distinguishes matching DLLs before program
registration. Old 18-binding C ABI clients remain supported; ABI-v2 structures
and shader sources are unchanged. Native FFM packing now reuses the canonical
112-byte Dispatch writer. It preserves the shader owner's auto-normal strength
in entityState.w, which the old native adapter still forced to zero, while
retaining the bound-entity range normalization for the first three lanes.

[Native CI run 40803800553](https://github.com/bnfe12/bedray/actions/runs/40803800553)
on native revision `0ed486ec64dedcbb67fddf732caca67d26e1b7f6`: candidate core
19/19, standalone scene 1/1 and lifetime 1/1 pass; baseline core 15/15 plus the
same two standalone checks passes; ABI probe v2 exits 0. The new path test covers
malformed guide bindings, partial stand-in allocation cleanup, formats/extents,
inactive guide descriptors, forbidden writes before recording, discard and mixed
18/25-binding scene variants. The shared fake driver now records view/image/format
identity so those assertions inspect the actual bound resources.

Java compilation and 160 selected tests pass, zero failures/errors/skips. The
preceding filters now include the shader owner's water/guide tests via
`*RtSlang*Test` and two new BedrayPathTraceNativeTest cases. They compare all
112 packet bytes for heap/native storage, guard both ends, preserve the auto-normal
lane and validate entity normalization. Their first run caught an alignment
assumption in the new helper's heap override; using the same ByteBuffer packet
writer for that override fixes it. The final repeated command exits 0. JDK,
dependency paths and bounded Gradle JVM settings match the preceding batch.
The current 18 production AOT passes freshly compile during the first run.

`git diff --check` passes. No GPU/game or visual result; native remains opt-in.
Full native guide output and the volumetric scene boundary remain separate work.

## 2026-10-29 — borrowed native guide outputs for DLSS-RR

Base `7cba8972b94a9e108df8611bd8e93a33cb073460`. The additive
`bedray_core_render_path_trace_guides` call and GUIDE_OUTPUTS feature accept the
separate 64-byte BedrayPathTraceGuides descriptor; old ABI-v2 structures and
exports are unchanged. All seven non-zero GENERAL views and the G-buffer option
are required together. The existing opaque graph forwards borrowed POD
synchronously to the same path pass; no second history or barrier authority is
introduced. The host retains the targets through exact completion/discard. The
native pass never creates, transitions or destroys those external targets.

Java checks matching native feature/export before selecting native guide output,
validates flag/targets before configure, and explicitly maps the denoiser's array
order to bindings 26..32 (specular precedes diffuse in the shader). Explicit
native SLANG + DLSS-RR is enabled with a matching core; AUTO retains the existing
live-acceptance gate and NRD still requires primary world-space motion.

Native revision `f49c2700a28610d50c90c0115aceef4381096a86` passes
[Windows CI 40807393855](https://github.com/bnfe12/bedray/actions/runs/40807393855):
candidate core 19/19 + scene 1/1 + lifetime 1/1; baseline core 15/15 + scene 1/1 +
lifetime 1/1; ABI-v2 probe exits 0 on both. The pass test now checks real targets,
invalid ABI/size/missing views/flag agreement before commands, active-to-inactive
descriptor replacement, both scene variants, partial failure/discard and legacy
18-binding rejection of real guides. The fake driver faults if borrowed host
views are destroyed. The graph test checks synchronous guide forwarding.

Java compilation and 161 selected tests pass, zero failures/errors/skips (51s),
using the preceding filters, local dependencies and bounded JVM settings. The
additional case verifies distinct guide channels, ABI header, heap/native packet
packing, both range guards and malformed inputs. Shader compilation is up to
date from the preceding 18-pass run; shader sources are unchanged in this batch.
`git diff --check` passes. No GPU/game, visual parity or performance evidence;
native remains opt-in. The volumetric native-scene boundary remains separate.
