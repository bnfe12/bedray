# Migration verification log

Evidence levels follow [FEATURE_MATRIX.md](FEATURE_MATRIX.md): L0 source,
L1 build, L2 no-GPU unit tests, L3 game startup, L4 visible effect, L5 owner
confirmation. Historical results do not validate a different source revision.

## 2026-10-29 — Spatial reuse binds the captured entity atlas (L1 compile, L2 unit, L3 game, L4 visible effect)

Branch: `refactor/slang-pipeline`. Tool: `slangc` 2026.20, same target and
include flags as the batch below; jar `bedray-fabric-mc26.3-0.2.0-alpha.jar`,
`bedray_core.dll` rebuilt from the same checkout.

Root cause of the world-creation crash recorded in `hs_err_pid26912.log`:
`passes/restir_spatial.slang` built a `MaterialAtlas` without setting
`atlas.entities`, and Slang materialised the unset `EntityAtlas` members as
`OpUndef` pointers. The stage does re-evaluate pooled records through the
emitter list, so an entity emitter's record can address that atlas; NVIDIA
617.42 faulted in `vkCreateComputePipelines` on the resulting SPIR-V. A
standalone Vulkan harness (`vkpipe2.py`) reproduced it byte-exactly: the
43324 B artifact crashed pipeline creation, the 38964 B pre-entity artifact and
a variant with the entity path removed did not.

Source changes covered by this run:

- `passes/restir_spatial.slang` now declares bindings 23 `entityRects` and 24
  `entityTexels`, imports `traversal/entity_geometry.slang`, and fills
  `EntityAtlas{rects, texels, atlasSize}` in `main`. `atlasSize == 0` makes
  `entityTexel` return its fallback before touching either buffer, which is the
  no-entity-capture case. 8 bindings, 64 B push.
- `RestirSpatialPush` carries `uint entityAtlasSize` at byte 48. The block is
  std430, so a `float3`/`uint3` pad would be 16-byte aligned and move the lane
  to byte 60; the padding is six scalars and the struct is exactly 64 B.
- `RtSpatialReuseSlangPass` takes the trace pass's `Entities` fallback at
  creation, binds the frame's rects/texels or the one-element stand-ins, and
  validates `ENTITY_VECTOR_BYTES`/`ENTITY_SCALAR_BYTES` from the manifest.
  `ReferencePathTraceBackend` passes `capturedEntities` to `record`.
- The C++ `PathTracePass` validates the 8-binding spatial manifest, keeps
  `SpatialPush64.entity_atlas_size` at offset 48, and binds 23/24 in the spatial
  dispatch from `desc.path.entity_state[0]`. `path_trace_pass_test.cpp` mirrors
  the eight bindings, the resource count and the push lane.

Result:

| Check | Expected | Observed |
|---|---|---|
| `:common:test` (native lib + slangc) | 0 failures | 754 tests / 122 suites, 0 failures, 0 errors, 3 skipped; all 12 `RtSlangRestirSpatialTest` cases pass |
| CTest `build/bedray-native/windows-x64` on final HEAD | 19/19 | 19/19 pass |
| `vkCreateComputePipelines` on the final jar's `restir_spatial` | 0 | 0 (OK); manifest 8 bindings, 64 B push |
| Live `-Dvkbridge.autotest=world -Dvkbridge.rt.nativeScene=true -Dvkbridge.rt.nativePathTrace=true` | PASS, C++ DI + spatial | `restir_spatial, 8 binding(s), 64 B push`; `restir_di=native+spatial`; 3 shots 2560x1440; `result=PASS`; no `hs_err` |
| Same with `nativePathTrace=true` only | PASS, C++ DI + spatial | `restir_di=native+spatial`; `result=PASS` |
| Same with neither (Java Slang host) | PASS | `restir_di=fresh+spatial` then `temporal+spatial`; `result=PASS` |

The final matrix was re-run on the exact pushed HEAD after PR #29 (renderer
statistics readback slots) landed, because that PR changed `RtFrameRenderer`
after the previous build. All three live runs wrote fresh
`autotest/world/02_world_{a,b,c}.png` (2560x1440, non-uniform: per-image mean
458/464/474, stdev ~150) and left no new crash dump — the only `hs_err` in the
instance is the historical `hs_err_pid26912.log` from 00:45. The C++ runs
exercised the 8-binding contract at dispatch time, not just at build time.

Earlier intermediate trees during this batch gave 735 tests / 18 CTest
(pre-rebase) and 745 tests / 19 CTest (after PR #28); those are superseded by
the 754 / 19 result above.

`EntityAtlasBindingContractTest` now guards the crash class: every pass whose
source declares an `EntityAtlas` local must declare bindings 23/24 as storage
buffers *and* assign `.rects`, `.texels` and `.atlasSize`. The manifest alone
cannot see an unassigned member, so the test pairs the generated manifest with
the pass source. Removing one assignment from `restir_spatial.slang` makes it
fail with the OpUndef explanation; the pass source is unmodified in this tree.

Rebased onto `4dc68d5` (PR #27, native guide outputs), `0898b00` (PR #28,
native particle composite) and `fc1861d` (PR #29, proven stats slots). The one
semantic merge change is the renderer's NRD guard: the Java Slang path fills
motion now, while the C++ backend forwards the seven guides but still writes no
world-space motion, so `RtFrameRenderer` refuses NRD only when
`SLANG_PATH_TRACE_CPP` is set. `RtSlangGbufferGuidesTest` pins that split.

## 2026-10-29 — NRD IN_MV motion channel from the typed path (L1 compile, L2 unit)

Branch: `refactor/slang-pipeline`. Tool: `slangc` 2026.20,
`-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major
-I common/src/main/resources/shaders/slang`.

Source changes covered by this run:

- `pathtrace/path_tracer.slang` gained `PATH_TRACE_OPTION_NRD_MOTION`
  (`1u << 1u`), the second bit of the previously always-zero
  `PathTraceOptions.flags` word. The push block stays 112 B. The tracer
  already produced `PathTraceResult.primaryMotion` at the primary vertex
  (`float4(traversal.surfaceMotion(hit), hit.kind == HitKind.Entity ? 1.0 :
  0.0)`), so no capture point moved for this batch.
- `passes/path_trace.slang` and `passes/path_trace_scene.slang` gained
  binding 33, `RWStructuredBuffer<float4> outMotionBuffer`, and a gated write
  under the new option bit: `pathTraceNrdMotion` reproduces the legacy
  `rt_path.comp:615-616` record — `xyz` is the primary surface's world motion
  (previous minus current), `w` is the trust flag
  (`primaryMotion.w > 0.5 || dot(motion, motion) > 1.0e-8`), and a pixel with
  no accepted primary surface (sky, invalid) writes zero, which the NRD pack
  already treats as a static miss. 26 bindings, 112 B push in both roots.
- `RtPathTraceSlangPass` gained `MOTION_ELEMENT_BYTES = 16`,
  `OPTION_NRD_MOTION = 2`, a `BufferView nrdMotion` argument on `record`, and
  all-or-nothing validation: the bit and a bound range must agree, the range
  must hold `width * height * 16` bytes, and motion without guides is
  rejected (NRD reads both). A one-element pass-owned dummy keeps the
  descriptor set complete when the bit is clear, exactly like the guide
  dummies.
- `RtPipeline.Views` carries the `entity_motion_px` handle (default 0),
  `RtFrameRenderer` allocates the buffer before `pass.bind` for NRD frames
  and keeps the existing StatsBuf address write, `ReferencePathTraceBackend`
  turns the handle into the frame's `BufferView` from the dispatch flags, and
  `RtSlangPathPass` sets the bit only when the views carry the buffer and the
  guides. Explicit `SLANG` + `NRD` is now accepted; `SLANG` + `DLSS_RR` stays
  accepted, `AUTO` still keeps the GLSL pass until a live frame consumes the
  guides.
- The C++ `PathTracePass` accepts 18-, 25- and 26-binding trace manifests:
  the 25/26 sets are checked for the seven guide storage images, the 26 set for
  the binding-33 storage buffer, and configure creates the one-texel guide
  stand-ins (R32F, RGBA16F, RGBA8) plus, for 26, a 16-byte `IN_MV` stand-in. A
  native frame binds 18, 25 or 26 resources to match its program and pins the
  stand-ins it uses; `destroy` retires them and `reset_state` clears them. The
  pass never sets either option bit, so the stand-ins are never addressed.
  `path_trace_pass_test.cpp` mirrors all three sets and the new fake-resource
  counts. (Rebased onto `7cba897`, which had introduced the 18/25 guide
  stand-in compatibility; this batch keeps it and adds the 26-binding motion
  lane instead of replacing it.)
- New `RtSlangNrdMotionTest` (5 guards) pins the legacy vec4 record, the
  option gate on both sides, the host's bit/buffer promise, the renderer's
  allocate-before-bind order and the vec4-per-pixel buffer contract.
  `SlangPassLibraryTest` pins 26 bindings and `outMotionBuffer` (33,
  storage buffer, 16 B per element); `RtSlangGbufferGuidesTest` and
  `RtSlangEntityTest` were updated for the new `record` argument and the
  now-accepted NRD policy.

Result:

| Set | Files | Exit 0 | Notes |
|---|---|---|---|
| `passes/*.slang` | 18 | 18 | `path_trace` 551880 B, `path_trace_scene` 563696 B, both 26 bindings / 112 B push |
| Targeted `:common:test` | 35 tests | 0 failures | `RtSlangNrdMotionTest` 5, `RtSlangGbufferGuidesTest` 7, `RtSlangEntityTest` 14, `SlangPassLibraryTest` 9 |
| Native `bedray_core` Release x64 | build | OK | `bedray_core.dll`, `bedray_path_trace_pass_test.exe` rebuilt |
| CTest (`build/bedray-native/windows-x64`) | 18 tests | 18 passed | includes `bedray_path_trace_pass_test` with the 18/25/26-binding contract and stand-in counts |

Mutation proofs: changing the shader trust threshold `1.0e-8` to `0.0` fails
`theMotionRecordIsTheLegacyVec4`; replacing the host's
`wantsMotion != (boundMotion.buffer() != 0)` check with `false` fails
`theHostAndThePushKeepTheirPromise`. Both files were restored byte-identical
(sha256 verified).

No clean full-suite claim is recorded for this batch: one run raced a
concurrent, uncommitted spatial-reuse edit in the same worktree (its three
`RtSlangRestirSpatialTest` failures are stale-compile artifacts — the same
test passes against the settled sources), and two later attempts were killed
by host memory exhaustion (JVM `hs_err`, "insufficient memory", while another
session's Gradle build ran on the same 16 GB machine). The full-suite run is
to be repeated on a free host before this batch is treated as L2-complete.

This is L1/L2 only. No GPU dispatch, frame or screenshot exists; the NRD
layout has never consumed these values on a device, and no owner-verified
image of the Slang path exists. The guide capture happens before the display
pass may reject a sample, so a rejected pixel can carry a motion record too;
the denoiser's own history rejection is expected to absorb that once a live
frame is measured.

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

## 2026-10-29 — remaining ReSTIR branches integrated on the current backend boundary

PR #17 is reconciled with base `4dc68d5aea060da5be1823925a523f702b5b2bcd`.
RestirDiState now drives the reference backend's parity, history, pending clear
and exact width/height layout, while the common controller uses its delegated
mode only to track whether a reset request actually reached a DI dispatch.
Native allocation/history/parity stay in C++. A transposed extent invalidates
the reference ring even at identical pixel count. A reset survives an empty
emitter frame; failed recording cannot consume it. DI history publishes only
after the complete reference recording succeeds.

Java compilation and 112 selected tests pass, zero failures/errors/skips (39s):
PathTraceBackendTest, RestirDiStateTest, RtSlang*Test, RtRestirSettingsTest,
RtPathTraceSlangPassTest, SlangPassLibraryTest and BedrayPathTraceNativeTest.
The controller test now exercises an empty-emitter frame and a transposed extent;
the seven state tests cover both ownership modes. The bounded offline Gradle
command, JDK/dependencies and JVM limits match the preceding entries. Native and
shader sources are unchanged, so this is Java L1/L2 evidence, no GPU/game check.

PR #22's source assertions targeted the old monolithic host. The current tests
already inspect the controller/reference/native split and pass above; restoring
those old strings would undo the ownership boundary. Its commits are retained
as merged history with the current implementation/tests kept. The older 74-test
evidence in that branch remains historical, not evidence for this revision.

Branch cleanup keeps main and refactor/slang-pipeline. Before deleting merged
branches, all refs/history were saved to a verified local Git bundle. GitHub's
kept-history ruleset is narrowed to those two branches with its deletion and
non-fast-forward protections intact; the main PR rule is unchanged.

## 2026-10-29 — native particle/weather composite boundary

Base `b5d852c76e30a5e2f3e46e61d7d9fb1977d9db0f`. The stateless native
particles_pass module now validates/records the existing AOT entry, its two
bindings, exact 16-byte push, dispatch groups and entry/exit memory barriers.
The additive bedray_core_render_particles export accepts a separate 56-byte
versioned/sized descriptor; existing ABI-v2 layouts and shader sources remain
unchanged. The generic core's immutable per-dispatch recordings already retain
program/descriptors until exact proof, so no duplicate configure/destroy owner,
private image, submission or wait is introduced. All external views stay borrowed.

Java supplies host views and scalar state through one coarse FFM call, validates
the exact reflected bindings/8x8x1 group, and retains the existing recording
identities. Game-layer barriers are removed with their native replacement.
Adapter destruction no longer closes the runtime-owned cached shader program.

[Native CI 40810744617](https://github.com/bnfe12/bedray/actions/runs/40810744617)
passes on `0e5d6c40165d416aad80d56df58d8240754404b8`: candidate core 20/20 +
scene 1/1 + lifetime 1/1; baseline core 15/15 + scene 1/1 + lifetime 1/1;
ABI-v2 probes exit 0. New tests check malformed ABI/size/inputs/contracts before
commands, distinct input/output, finite strength, exact descriptors/push/groups,
barrier order, changed targets across dispatches, failure propagation and real
C ABI bad_alloc/unexpected-exception containment. Native build reports no C++
warnings in that run.

Java compilation and 173 selected tests pass, zero failures/errors/skips (31s).
The preceding 161-test filters now include RestirDiStateTest (7),
ParticleGroupLedgerTest (3) and BedrayParticlesNativeTest (2). The latter checks
every field/offset, zero strength and invalid inputs before downcalls. JDK,
offline dependencies and bounded JVM settings match the preceding entries.
`git diff --check` passes. No GPU/game or visual evidence; native stays opt-in.

Repository cleanup retains only main/refactor/slang-pipeline branches. Merged
head branches are deleted automatically in GitHub; local fetch pruning is
enabled. Old worktrees are detached with their files/dependencies retained.

## 2026-10-29 — consolidated audit integration and complete Java test run

The remaining audit branch is merged with `8d18bd7` (native volumetric integration).
Its build/platform/NRD constant-ring fixes and shared path entry are preserved,
alongside current native passes, exact stats leases, DI state and NRD motion
binding 33. The shared entry receives the latest motion buffer/helper; no primary
transport arithmetic is rewritten. Conflicting source tests follow the actual
shared entry and current backend state rather than restoring obsolete strings.
All earlier audit evidence appended below remains historical to its own revision.

Runtime shutdown is additionally made retry-safe: core destruction runs before
cached-program invalidation. Pending recordings leave the library and programs
usable; successful core destruction invalidates Java wrappers without calling
through the freed core handle. A failed library unload forbids rendering and
can retry without destroying the core twice. Two pure lifecycle tests cover both
refusal and retry cases.

Final source revision `0a1244f543c2a7a9a6d906dba3657a6ff11cb765` passes full
`:common:test`: **786 tests, 777 passed, 9 skipped, zero failures/errors** (63s).
All 19 production AOT entries compile on this merged tree. The skipped cases
require optional real-pack/environment inputs; no GPU/game test is claimed.
The first full run found three stale source assertions after integration; those
are updated to the shared motion helper and DI state module, and the full repeat
passes. The bounded offline command/JDK/dependencies match preceding entries.

[Native CI 40841976213](https://github.com/bnfe12/bedray/actions/runs/40841976213)
passes on the same revision: candidate core 21/21 + scene 1/1 + lifetime 1/1;
baseline core 15/15 + scene 1/1 + lifetime 1/1; ABI-v2 probes exit 0. No C++
warnings are reported. Subsequent edits only update documentation. Windows is
the validated build target; non-Windows opt-in is explicitly untested. Native
path stays opt-in, with no visual/performance/owner live acceptance claim.

## 2026-10-29 — exact host statistics readback leases

Base `0898b0049508b29f0a5f26cd753fbd775049de47`. StatsBuf no longer reads or
reuses a mapped slot because it is two frames old. RecordingReadbackSlots leases
the entire slot (GPU input fields as well as counters) until the exact existing
host ticket proves completion/discard. Completed written slots remain retained
until CPU consumption; discard and unwritten frames publish nothing. A late or
retried callback cannot touch a reused slot or a replacement pool after teardown.
Pressure uses the existing host fence drain before opening a new command buffer,
never a frame delay or device-idle call. Producer metadata snapshots accumulation,
transparency, daylight, ordering and timestamp rather than reading current settings.

An explicit SHADER_WRITE/TRANSFER_WRITE -> HOST_READ buffer-range barrier is
recorded before command-buffer end. Stats memory is already required to be
HOST_VISIBLE|HOST_COHERENT by RtMemory, so no invalidate is required; completion
is still mandatory. This follows the
[Khronos compute readback example](https://docs.vulkan.org/guide/latest/synchronization_examples.html).
Shader layouts/algorithms and native ABI are unchanged in this change.

Final Java compilation and 29 focused tests pass, zero failures/errors/skips
(23s): Recording*Test, RtStatsLayoutTest, PathTraceBackendTest, RestirDiStateTest.
The new tests include delayed proof, slot pressure, same-address reuse, unknown
execution, discard, failed host read and retry after another owner's retirement
fails. A preceding broader run passed 134 tests (including four separately
prepared volumetric-interface tests); that shader interface is a separate PR.
The bounded offline Gradle/JDK/dependency setup matches preceding entries.
An independent read-only source review found no blocking lifetime/visibility
issue. No GPU/game evidence; native mode stays opt-in.

## 2026-10-29 — volumetric native-scene binding adapter (needs shader owner review)

The legacy volumetric march body/push are extracted into one shared generic
entry helper; the new volumetric_march_scene entry binds GeometrySection through
the existing GeometryProvider/HardwareTraversal with a fixed world-only mask 1.
It supplies no entity streams and never decodes entities. LegacySection remains
bound only by the legacy entry. Both keep bindings 0/2/11/12/16, 608-byte frame,
96-byte push and 8x8x1 groups. Apply and scattering arithmetic are unchanged.

Pinned SHA256 of the original body from 0898b004, removing only the four legacy
traversal setup statements, matches the extracted helper. Standalone slangc
2026.20 compiles both march entries and apply, exit 0/no warnings. Gradle also
compiles all 19 production entries; five RtSlangVolumetricSceneTest cases pass,
zero failures/errors/skips (28s), checking exact body, push types/order, native
record field identity and generated manifests. No host activation in this PR.

Existing limitations are preserved: binary world-only visibility, no shaft
bob-origin correction, no material/alpha filtering. Exposure/reprojection must
use the typed display contract when integrated. This is a separate interface
PR marked needs shader owner review; no GPU/game or visual evidence.

## 2026-10-29 — native volumetric history and host integration

Base `47b0d6984253f31b284f216994a1fcb0ef02a2e6` includes the separately reviewed
interface PR #30 plus the shader owner's NRD/spatial updates. VolumetricPass owns
half-resolution RGBA32F history, per-recording 608-byte uploads, 96/24-byte push
packing/history overrides, five/four-resource descriptors and barriers. Separate
48/120-byte coarse descriptors and exports are additive; existing ABI-v2 layouts
are unchanged. Both scene layouts are validated/selected independently.

The Java settings adapter sends one call for march/apply, shares frame conversion
with path tracing, uses typed display exposure 1 and current-minus-previous camera
delta, and participates in existing exact atmosphere completion/discard. Requested
god rays are connected before bloom. The existing shader's nonzero bob-origin
limitation is explicitly reported rather than rendered with incorrect coordinates;
binary world-only visibility remains an explicit shader contract.

Native revision `400bbea29d2c3595cd6183e0c3a2b8a821f95126` passes
[Windows CI 40840132518](https://github.com/bnfe12/bedray/actions/runs/40840132518):
candidate core 21/21 + scene 1/1 + lifetime 1/1; baseline core 15/15 + scene 1/1 +
lifetime 1/1; ABI-v2 probes exit 0. Tests cover descriptors/push/groups, native and
legacy programs, consecutive/gapped/reset history, resize and discard generations,
pre-record upload failure, partial dispatch failure and device loss with two
pending records. No C++ warnings were reported in that run.

Java compilation, all 19 production AOT entries and 148 selected tests pass,
zero failures/errors/skips (56s). The test filters cover recording lifetime,
backend/state, RtSlang*, settings, path/manifest/native packets, bloom packets and
stats layout; new tests check every coarse packet offset/payload slice, exposure,
delta direction, native history ownership and explicit bob handling. SDK/JDK,
local dependencies and bounded offline JVM settings match preceding entries.
No GPU/game, visual parity or performance evidence; native path remains opt-in.


## 2026-10-29 — P1 audit pass: completion-proof retirement and module wiring (L2 tests; no GPU)

P1-4: `RtFrameRenderer` registers recording retirement through
`RecordingCompletion.submitted(...)` after `encoder.execute`, so release runs
only in the destruction queue that drains after the submission fence. The dead
`SlangRuntime.retireSubmittedRecording` is removed, and
`RecordingCompletionTest.retirementWaitsForHostCompletionProof` now asserts that
a submission alone releases nothing.

P1-1: `NativeModuleWiringTest` pins the current wiring: the core compiles
`history_c.cpp`, the GPU buffer/image C wrappers and `frame_graph_owner.cpp`,
links the graph/scene targets, and Java uses the native buffer/image owners.
The audit records the remaining gap honestly: the history C API has no Java
binding, and `bedray_frame_lifetime` is still standalone.

P1-2 is marked fixed in the audit: the per-program descriptor cache reuses up to
eight `{pool,set}` pairs, so a steady-state dispatch no longer creates and
destroys a pool. P1-3 is marked latent: the live core graph has one
`opaque_group` pass and emits no graph barrier; the conservative all-commands
barrier remains for future multi-pass dispatch graphs. P1-5 is marked mitigated:
`SlangRuntime.close` keeps the core and `closed=false` when destroy reports
`IN_USE`, and the renderer retains `teardownPending` for a later retry.

Evidence on `6bfd935` from the ASCII worktree:

| Check | Result |
|---|---|
| targeted `RecordingCompletionTest`, `NativeModuleWiringTest` | passed |
| `:core:test` | 107 tests, 0 failures, 0 errors |
| `:common:compileSlang :common:test` | 18 passes (task up-to-date, shaders unchanged); 752 tests, 0 failures, 0 errors, 9 skipped, 124 classes |

Not run: CMake is not installed, so no native configure/build; no GPU dispatch,
validation layers, game startup or rendered effect. P1-3's performance claim is
not measured.

## 2026-10-29 — platform native names, build-path hygiene and the CMake opt-in (L2 tests; no native configure)

P2-3: `BedrayCoreLocator` and `BedraySceneLocator` hardcoded `bedray_core.dll` /
`bedray_scene.dll`. Both now expose `FILE = System.mapLibraryName(LIBRARY_ID)`
and pass that name to `NativeLibraries`. `LibraryDescriptor` accepts `.dll`,
`.so` and `.dylib` allowlist entries; path separators and versioned names such
as `libbedray_core.so.1` are still rejected. Windows still resolves
`bedray_core.dll`, so the installed layout is unchanged.

P2-2: `native/bedray_core` and `native/bedray_scene` no longer fail a
non-Windows configure unconditionally. The default still refuses the platform,
but the message names `-DBEDRAY_ALLOW_UNSUPPORTED_PLATFORM=ON`; that opt-in
emits a warning that the build is untested and unsupported. `bedray_scene`
keeps `PREFIX ""` on Windows only, so Linux/macOS output matches the JVM name.

P2-1: the `C:/Dev/Sky/tools/sdk/Vulkan-Headers` fallback is gone from
`bedray_core`, `bedray_scene` and `bedray_render_graph`; only the
`BEDRAY_VULKAN_HEADERS` cache variable and `VULKAN_SDK` remain.

P2-4: the `slangDir`/`SLANG_DIR` lookup uses `slangc.exe` on Windows and
`slangc` elsewhere, while explicit `-Pslangc`/`SLANGC` still win.

Evidence on `320b274` from the ASCII worktree:

| Check | Result |
|---|---|
| targeted `BedrayNativeNameTest`, `NativePlatformGateTest`, `RtSlangGbufferGuidesTest` | passed |
| `:core:test` (at `1c2ea7c`) | 107 tests, 0 failures, 0 errors |
| `:common:compileSlang :common:test` | 18 passes; 749 tests, 0 failures, 0 errors, 9 skipped, 123 classes |

Not run: CMake is not installed here, so neither project was configured or
built; no Linux/macOS machine was used, and the `slangDir` branch was not
exercised because the runs pass an explicit `-Pslangc`. The structural check in
`NativeLibrariesImpl` is still PE-only, and the NRD/DLSS wrappers are
Windows-only. P2-2/P2-3 remove the hardcoded name and the unconditional gate;
they do not make non-Windows loading or rendering supported.

## 2026-10-29 — NRD constant-buffer ring (L0 source contract; native build not run)

P0-7 in [AUDIT_DEFECTS_AND_ISSUES.md](AUDIT_DEFECTS_AND_ISSUES.md): every
`vkb_nrd_dispatch` call uploaded its constant blocks from slot 0 of a buffer
sized for one frame, while the host may keep 1-8 frames in flight. Frame N+1
could overwrite bytes that frame N's command buffer was still reading.

The buffer is now `cbStride * cbSlots * d->frames`, the upload base is
`d->ring * d->cbSlots`, and the ring advances after the upload, in lockstep
with the per-frame descriptor pool. `dispatchN <= cbSlots` still bounds one
frame's slots. `NrdConstantRingSourceTest` pins the allocation, the base, the
slot arithmetic, the old expression's absence and the order of the ring
advance.

This is not L1 for the native bridge: NRD and Vulkan headers are external
(`VKB_NRD_SRC`, `VKB_VK_HEADERS`) and are not installed on this machine, so
`native/nrd_bridge/build.bat` was not run. The Java source contract is L2 and
the C++ change itself is L0-reviewed only. Full `:common:test` after the
change: **740 tests, 0 failures, 0 errors, 9 skipped, 121 classes**. No GPU
dispatch, TDR repro or denoised frame exists.

## 2026-10-29 — origin merge: guides + water + native bloom on the deduplicated entry (L1 compile, L2 no GPU)

Branch: `fix/audit-remediation-20261029`, merge commit `84b1697`, merging
`origin/refactor/slang-pipeline` (`4dc68d5`, 27 commits) into the P2 batch
(`bccdced`). The run used the ASCII worktree at the merge commit, because the
Gradle test worker mangles the Cyrillic project path on this machine. Tool:
`slangc` 2026.20 via `-Pslangc=...`.

Conflict resolution kept the single shared body in
`pathtrace/path_trace_entry.slang`: the guide writer and the seven guide
outputs from origin now live there once, and both `passes/path_trace.slang`
and `passes/path_trace_scene.slang` stay thin wrappers that declare bindings
26-32 and forward all seven channels. The `portalTime` and
`autoNormalStrength` material options are set in the shared body. The origin
water, bloom, recording-lifetime and native-backend changes merged without
shader-body conflicts; `RtRestirSettingsTest` now checks both the reference
and the native backend.

Result:

| Check | Result |
|---|---|
| `:common:compileSlang` | 18 passes, `BUILD SUCCESSFUL` |
| `path_trace` manifest | 25 bindings, 112 B push, guides 26-32 |
| `path_trace_scene` manifest | 25 bindings, 112 B push, same names and types |
| `:common:test` | 739 tests, 0 failures, 0 errors, 9 skipped, 120 classes |

This is L1/L2 only. It proves the merged tree configures, both path roots
compile with the water/gbuffer changes, and no Java contract test regressed.
No GPU dispatch, frame, screenshot or game run exists on `84b1697`.

## 2026-10-29 — shaderc optimizer hang and the shared path entry (L1 compile, L2 no GPU)

Branch: `fix/audit-remediation-20261029`, commits `f631326` and `6209614`, on
top of `cc3c0db`. The run used an ASCII worktree of the same commit because the
Gradle test worker mangles the Cyrillic project path on this machine. Tool:
`slangc` 2026.20 via `-Pslangc=...`.

### The shaderc hang was the SPIR-V optimizer

`RtVolumetricFogTest.testShaderCompilation` did not merely run slowly: the test
worker was blocked inside native `shaderc_compile_into_spv` on the expanded
`rt_rays.comp` source (261,010 characters without `VKB_GBUFFER`). A standalone
reproducer over the same include expansion isolated the stage:

| Compiler | Variant | Level | Result |
|---|---|---|---|
| lwjgl-shaderc 3.3.3 | plain | `performance` | no result after 90 s (killed) |
| lwjgl-shaderc 3.3.3 | `VKB_GBUFFER` | `performance` | no result after 90 s (killed) |
| lwjgl-shaderc 3.3.3 | plain | `size` | no result after 60 s (killed) |
| lwjgl-shaderc 3.3.3 | `VKB_GBUFFER` | `size` | no result after 60 s (killed) |
| lwjgl-shaderc 3.3.3 | plain | `zero` | 448 ms, 400,760 B SPIR-V |
| lwjgl-shaderc 3.3.3 | `VKB_GBUFFER` | `zero` | 930 ms, 409,768 B SPIR-V |
| lwjgl-shaderc 3.4.3 | plain | `performance` | no result after 120 s (killed) |
| lwjgl-shaderc 3.4.3 | `VKB_GBUFFER` | `performance` | no result after 120 s (killed) |
| lwjgl-shaderc 3.4.3 | `VKB_GBUFFER` | `zero` | 1,271 ms, 409,696 B SPIR-V |

The regression is in shaderc's SPIR-V optimizer, not in the shader and not in a
particular LWJGL build. `RtShaderCompiler` now pins
`shaderc_optimization_level_zero` (`RUNTIME_OPTIMIZATION_LEVEL`), and the disk
cache profile moved to `bedray-spirv-cache-v3 ... optimization=zero`, so a
performance-era cache cannot mask the change. `RtShaderCompilerOptionsTest`
fails if the optimizer level is raised again. This affects only the runtime
GLSL fallback compiler: the AOT Slang passes are optimised by `slangc`, and the
Vulkan driver still optimises the module when it builds the pipeline.

### Test gate

- `lwjgl-shaderc:<version>:natives-windows` is now a `testRuntimeOnly`
  dependency, so `:common:test` no longer needs a launcher installation to load
  `shaderc.dll`.
- JUnit's default timeout is 120 s; a future native hang fails the run instead
  of holding the whole gate.
- `runShimTests` no longer requires the launcher (`testClasses` only).
- The build warns when the project path contains non-ASCII characters and
  points at the ASCII-worktree workaround, now documented in BUILDING.md.

### One path-trace entry body

`passes/path_trace.slang` and `passes/path_trace_scene.slang` were 270/271-line
copies differing only in binding 2 and the traversal backend. The shared
integration now lives in `pathtrace/path_trace_entry.slang`; each pass file
declares its own bindings and backend and calls `pathTraceEntry`.

```
[slang] path_trace:       420,476 B SPIR-V, 18 binding(s), 112 B push
[slang] path_trace_scene: 431,924 B SPIR-V, 18 binding(s), 112 B push
```

`SlangPassLibraryTest.pathTraceScenePassKeepsTheSameHostContractAsTheLegacyEntry`
compares every descriptor, the push size, capability bits and workgroup;
`PathTraceEntryDedupTest` rejects a copied `tracePath(` call in either wrapper;
`RtSlangBlockEmitterTest` reads the shared body. `:common:compileSlang` builds
18 passes.

### Targeted result

`:common:test` with `SlangPassLibraryTest`, `PathTraceEntryDedupTest`,
`RtPathTraceSlangPassTest`, `RtSlangBlockEmitterTest` and
`RtShaderCompilerOptionsTest`: 29 tests, 0 failures. An earlier targeted run of
`RtVolumetricFogTest` (including `testShaderCompilation`),
`RtShaderDiskCacheTest` and `RtShaderCompilerOptionsTest` was also green.

This is L1 (Slang/GLSL compile) and L2 (no-GPU Java tests). No Vulkan device,
game launch, visual parity, denoiser, Fast RT, auto-exposure, volumetric,
composite or sky/fog output was verified on this revision. The historical
2026-10-28 note that `path_trace`/`path_trace_scene` deduplication was blocked is
superseded by this entry.

### Full suite

`:common:test` from the ASCII worktree after the entry-body cleanup: **693
tests, 0 failures, 0 errors, 9 skipped, 111 test classes**, `BUILD SUCCESSFUL`
(58 s with warm Gradle). The nine skips are the optional pack/GPU-gated tests,
not ignored failures. This is the first full-suite green run on a revision with
the shaderc natives on the test runtime classpath.

### P2 safety batch

Three P2 findings from the audit are closed without changing rendering
behaviour:

- `contracts/math.slang`: `isFinite` classifies the IEEE-754 exponent
  (`(asuint(value) & 0x7f800000u) != 0x7f800000u`) instead of testing
  `value == value`, which fast-math may fold to `true`.
- `passes/velocity_reproject.slang`: the four-byte gap before `jitterPrevious`
  and the eight tail bytes are now named fields (`padJitter`, `pad2`). The
  reflected block is still 96 B, `jitterPrevious` is still at offset 64, and
  `VelocityPushLayoutTest` checks both the source declaration and the compiled
  manifest.
- `shading/bsdf.slang`: the diffuse Fresnel factor stays view-angle based for
  parity with the owner-tuned legacy GLSL. This is not strictly
  Helmholtz-reciprocal and is documented in `KNOWN_ISSUES.md`; changing it
  needs a GPU A/B that this gate cannot provide.

Full suite after the batch: **697 tests, 0 failures, 0 errors, 9 skipped, 113
test classes**, `BUILD SUCCESSFUL` (1 m 37 s, including the Slang rebuild).

## 2026-10-28 — ReSTIR source tests and the first runnable :common:test (L2, no GPU)

Branch: `fix/stale-restir-source-tests-20261028`, commit `248a7e1`, on top of
the merged PR #10 (`6632bca`).

The earlier configuration blocker (missing Fabric/NeoForge launcher jars) was
resolved locally without adding anything to the repository:
`fabric-loader-0.19.5`, NeoForge `26.3.0.57-beta`, FML `12.0.9`, bus `8.0.5`,
mergetool `2.0.7`, and the gitignored `libs/` client/Iris/Sodium jars.
`:common:test` now configures and runs `:common:compileSlang` first (18 passes).

Three source-scan tests were stale relative to the host's native-reservoir path,
which already existed on the base before PR #10 (`a8f796b` has
`nativeReservoirs`). The tests still asserted the pre-native strings:

- `diParity = nativeReservoirs ? 0 : reservoirFrame & 1` (the Slang ring still
  alternates; the native path owns its parity).
- `diFlags = DI_FLAG_ENABLED | (!nativeReservoirs && reservoirHistoryWritten ? ...)`
  (the native path must not claim a history it did not write).
- the dispatch guard and mode log likewise admit the native ring.

The fix changes only those assertions; shader and host behaviour is unchanged.

Full-suite status is deliberately not claimed green:

- Without the shaderc natives jar, 57 of 681 tests fail: 54 are `shaderc.dll`
  load failures, 3 were the stale assertions above.
- With `lwjgl-shaderc-3.3.3-natives-windows.jar` added only to the local test
  classpath, the run stops in `RtVolumetricFogTest` and was killed after 40
  minutes. That is an environment/harness limitation, not evidence about the
  typed RC shaders.
- The Gradle test worker mangles the non-ASCII project path on this machine, so
  the run was executed from an ASCII worktree of the same commit.

Targeted evidence after the fix: `74 tests, 0 failures` for `RtSlang*`,
`RtRestirSettingsTest` and `SlangPassLibraryTest`, `BUILD SUCCESSFUL`. This is
L2 (Java source scans, no GPU, no game). It does not change the L1 shader
compile evidence or prove runtime behaviour.
