# Migration verification log

Evidence levels follow [FEATURE_MATRIX.md](FEATURE_MATRIX.md): L0 source,
L1 build, L2 no-GPU unit tests, L3 game startup, L4 visible effect, L5 owner
confirmation. Historical results do not validate a different source revision.

## 2026-10-30 — Glass cost and NaN guards return as typed tests (L0 source, L1 build, L2 unit; no runtime change)

Branch: `refactor/slang-pipeline`.

Two of the tests the GLSL deletion invalidated compared the port against GLSL text, so they were removed rather than re-aimed. Their coverage is back, read out of the Slang sources that own the behaviour; no shader changed in this batch, so the deployed jar and natives are untouched.

- `RtSlangGlassCostTest` (5) pins the cost of the typed visibility walk: the
  1e-3 transmittance floor ends an absorbed ray before the next pane, each of
  the four floor branches closes into its own `skipSurfaceCandidate` advance
  (`ray.tMin = hitDistance + bias`, the origin stays), the six advances, the
  hole's advance without a filter, and the medium walk's order - the water
  column's `waterSegmentTransmittance` first, the fluid interface's
  `1 - Fsc` Fresnel share second, so water is tinted once. The numeric half
  reads `SURFACE_VISIBILITY_MAX_LAYERS` (16) and
  `SURFACE_VISIBILITY_MIN_TRANSMITTANCE` (1e-3) out of the shader and shows
  five 25 % panes end the ray while a clean window fills the budget.
- `RtSlangNaNGuardTest` (9) re-establishes the deleted `RtShaderNaNGuardTest`:
  `safeNormalize`'s squared-length gate, both GGX half vectors, the block
  emitter's density/cosine rejections and its weight and shadow-length floors,
  the sun's axis fallbacks and post-divide PDF rejection, the tangent frame's
  det gate plus guarded numerators (with the collinear-UV numeric case), both
  primary-normal gates, and a tree-wide count of bare `normalize(` calls.
- The deleted test's `safeW` camera-ray guard has no typed counterpart to fix:
  `pathTracePrimaryRay` builds the ray from the explicit camera basis - no
  matrix multiply, no division - which is stronger than guarding the divisor,
  and the two velocity modules sanitise a non-finite reprojection to zero
  motion plus full disocclusion, so the live-parity module keeps the bare `w`
  divide its GLSL source had. Both facts are pinned instead of rewriting
  live-parity arithmetic.

Thirty-one mutation probes (floor branch/order/value, layer budget, hole
skip/break/filter, skip origin, medium filter, fluid and non-fluid share, gate
value and comparison, both half vectors, emitter direction/cosine/floor/bias,
sun axis and PDF order, tangent det and numerator, both entry gates, a new bare
normalize, the camera divide and a camera matrix, both velocity sanitises, the
pass clip-w gate) were each caught, and every shader was restored by sha256.

Full `:common:test --rerun-tasks`: 154 classes, 888 tests, 0 failures, 6
skipped, `BUILD SUCCESSFUL`. No shader, Java main source or native source
changed, so there is no new distribution to deploy: the instance jars stay at
`5f52f205295d0d4e…`.

## 2026-10-30 — Distant block light hands back to the baked field (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

The deleted `rt_common_shade.glsl` mixed the traced block-emitter estimate back
to the vanilla block-light field past the emitter list's reach: `blockLightFarMix`
smoothstepped over `listRange ± EMITTER_RADIUS` (inner edge floored at eight
blocks) from the camera distance, and a fully faded receiver skipped the emitter
branch and read the baked field instead. The Slang path kept tracing every
fragment against the truncated list, so a village lamp, a lit cave or a lamp on
the far bank dimmed to black instead of handing over to the propagation the game
itself shows.

- `light/block_emitters.slang` owns the field: `blockLightFarMix`,
  `BLOCK_LIGHT_FAR_FULL` (0.999), `BLOCK_LIGHT_FIELD_GAIN` (0.05) and
  `vanillaBlockIrradiance` (warm `(1.0, 0.82, 0.60)` tint, block light only).
  The legacy renderer tinted the field from the lightmap texel; a typed path
  pass binds no lightmap and the native core validates the exact descriptor
  count of both path passes, so the pre-lightmap warm tint stands in and a
  resource pack's block-light hue reaches the traced emitters only.
- The handover is a mix, not a max: the emitter share of the composite draw is
  scaled by `1 - emitterFar` (fresh and reused alike) and `pathBakedBlockLight`
  - the reflecting lobe's albedo, the `1 - Fsc` the interface did not take and
  the LabPBR AO, with no cosine and no 1/pi, the tuned legacy term - adds
  `* emitterFar`. The distance is `length(hit.position)`, camera-relative the
  way the list was sorted.
- Past `BLOCK_LIGHT_FAR_FULL` the receiver sets `LightReceiver.bakedBlockLight`;
  `SunAndBlockEmitterSampler` then draws no emitter share (the key light keeps
  its full density) and the tracer skips the emitter sample entirely, relying on
  the end-of-path `reuseClear` so a skipped ReSTIR DI record cannot persist.
- No push growth: the list range already rides at offset 76
  (`diCameraDelta.w` -> `emitterSampler.maxDistance`) and now also reaches the
  tracer as `PathTraceOptions.emitterListRange`; `PathTracePush` stays 112 B.
  The option is flag bit 16 (`PATH_TRACE_OPTION_BLOCK_EMITTERS`), which the host
  sets only while `SLANG_BLOCK_EMITTERS` / `RtSettings.emissive()` keeps
  emitters on, so switching the feature off hands the field nothing.
- `RtSlangDistantBlockLightTest` (9) mirrors the band, the gain and tint, the
  mix structure, the primary-diffuse gate, the exhausted-list skip and the host
  wiring. Twenty-two mutation probes (band floor, gain, tint, clamp, sampler
  gate, fade on both branches, distance source, bounce/metal/light gates, reuse
  skip, verdict, albedo count, entry range, host flag, host switch, path range,
  contract default, bit collision, clear) were each caught, and every mutated
  file was restored by sha256.
- Three contract tests were re-aimed at the preserved invariants:
  `RtSlangBlockEmitterTest` reads the reused-branch gate plus the new baked
  clause, `RtSlangNrdMotionTest` expects the block-emitter term closing the same
  OR chain, and `RtSlangPixelShadowTest` reads the option line inside it.

Full `:common:test --rerun-tasks`: 152 classes, 874 tests, 0 failures, 6
skipped, `BUILD SUCCESSFUL`. All Slang entry passes compile; `path_trace` is
1 116 428 B SPIR-V, 26 bindings, 112 B push constants (unchanged).

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `5f52f205295d0d4e…`; the previous
(pixel-shadow) jar is kept as `….20261030-080400.bak` (sha256
`9b2f92a2e07f869d…`). The bundled `shaders/slang/spv/path_trace.spv` is
1 116 428 B, sha256 `a29b017aced495f0…`, equal to the fresh compile;
`bedray_core.dll` (`9ca5cd88cc01f823…`) is byte-identical to the deployed
native, so no native changed.

Open: no owner-verified frame shows the handover, and the field's hue is the
pre-lightmap tint rather than the sampler's lightmap colour.

## 2026-10-30 — Pixel-shaped key-light shadows return (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

The deleted `rt_common_pixel_shadow.glsl` evaluated the key-light shadow once per
world cell: it snapped the receiver horizontally to the cell centre, accepted the
snap only when one validation ray was unblocked, and rotated the sun/moon disc
from a cell-stable seed so the quantised penumbra was one value instead of
per-pixel speckle. The Slang path lit every fragment exactly, so the look option
did nothing. This batch ports the policy without growing the push block:

- `PATH_TRACE_OPTION_PIXEL_SHADOWS` is flag bit 7 and bits 8..15 carry the cell
  size in texture pixels (1..16, one sixteenth of a block each, the legacy
  `StatsBuf.shadowPixel/16`); `PathTracePush` stays 112 B and
  `RtSlangPathPass.pixelShadowFlags()` clamps a stale setting instead of
  refusing the frame.
- `pathPixelShadowOrigin` snapshots only the receiver, keeps the lateral
  displacement horizontal, projects it into the surface plane and returns the
  exact position when the probe is blocked. It runs for the primary receiver
  only - a deeper vertex is 1-3 spp and each one would spend a validation ray -
  and never for a leaf, an entity or an underwater receiver, whose sun-side and
  bent-beam origins win.
- `pathTraceShadowCell`, `pathTraceStableSunCell` and `pathTraceFoliageSunCell`
  name the world-locked rotation cell: the estimator and the disc seed hash the
  same cell, a non-cell fragment keeps a 1/16-block sheared world cell while the
  option is on, and a leaf freezes to its own block at frame 0 exactly as the
  legacy `sunSampleFrame(foliage)` did. `LightReceiver.stableSun`/`sunSeed`
  carry the choice into `SunMoonSampler`, which replays the same draws into the
  path stream when it is off, so an ordinary receiver keeps its exact seed.
- `worldPosition` is the world-anchored (modulo 1024 blocks) hit anchor every
  backend fills; every cell size the host can send divides that wrap, so a cell
  never drifts across the anchor boundary. `RtSlangHistoryAnchorTest` now lists
  the tracer as a reader with that reason.
- `RtSlangPixelShadowTest` (10) mirrors the cell arithmetic, the projection,
  the blocked-cell fallback, the primary-only rule, the foliage freeze, the
  shared estimator/rotation cell, the stream sync and the host clamp, read out
  of `pathtrace/path_tracer.slang`, `pathtrace/path_trace_entry.slang`,
  `light/sun.slang` and `contracts/light.slang`. Eighteen mutation probes (flag
  bit, size shift, entry decode, primary gate, underwater gate, blocked snap,
  projection sign, cell scaling, foliage frame and cell, y-shear, stable seed,
  stream sync, rotation cell, receiver default, host clamp, host flag OR) were
  each caught, and every mutated file was restored by sha256.
- Three contract tests were re-aimed at the preserved invariants:
  `RtSlangRayModeTest` reads `coneAround(axis, radius, drawSeed)`,
  `RtSlangSamplePerPixelTest` reads the named `sampleWord`, and
  `RtSlangNrdMotionTest` expects the pixel-shadow flags in the same OR chain.

Full `:common:test --rerun-tasks`: 151 classes, 865 tests, 0 failures, 6
skipped, `BUILD SUCCESSFUL` (42 s). All Slang entry passes compile;
`path_trace` is 1 112 528 B SPIR-V, 26 bindings, 112 B push constants (unchanged).

Open: no owner-verified frame shows the staircase yet, and the look is only
meaningful once `pixel_shadows` is on in the video settings. Glass cost, the
distant-block light fallback, the NEE NaN guard and the hot-loop/layout guards
remain open holes.

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `9b2f92a2e07f869d…`; the previous
(foliage) jar is kept as `….20261030-073842.bak` (sha256 `6b8ecf2055cfaae4…`).
The bundled `shaders/slang/spv/path_trace.spv` matches the fresh compile
(`1c4b00aba4…`, 28 SPIR-V entries in the jar). `bedray_core.dll`
(`9ca5cd88cc01f823…`) and `bedray_scene.dll` (`bf2caba452ef6445…`) are
byte-identical to the deployed natives, so no native changed.

## 2026-10-30 — Foliage filters the key light again (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

The deleted GLSL path treated a canopy as a coloured filter: the shadow walk
multiplied its transmittance by `0.60 * mix(1, hue, 0.45)` per leaf face, the
direct term added a back-lit transmission lobe outside the front-face gate, and
the key-light ray started on the sun side of the leaf. The Slang port had only
the traversal flags (`HIT_FLAG_FOLIAGE` / `HIT_FLAG_LEAF_CUBE`), so every plant
shaded like stone and a back-lit leaf was a black dot on a sunlit slope.

- `decodeSurface` copies `HIT_FLAG_FOLIAGE` into `MATERIAL_SOURCE_FOLIAGE`; the
  new `MATERIAL_SAMPLING_FLAG_FILTER_FOLIAGE` is set by exactly the three shadow
  wrappers (`traceShadowVisibility`, `...Medium`, `traceShadowBeam`), so camera,
  AO and sky walks keep the binary cutout and the hole skip still runs before a
  leaf layer is counted.
- `materialFiltersAsFoliage` accepts cutout, non-emissive plants only;
  `foliageLayerTransmittance` keeps the legacy 0.60 / 0.45 rule (~36 % through
  one leaf block), with the hue divisor floored so a black texel is a neutral
  filter instead of NaN.
- `pathTraceDirectTerm` adds `albedo * leafBack * 0.42 *
  smoothstep(0, 0.08, leafBack) * (1 - luminance(f0)) * (1.05, 1.25, 0.75)`
  outside the front-face gate, for the key light only, and starts the shadow ray
  one block along the sun-side normal for a front-face leaf cube or 1 cm along
  the sun for a card; under water the refracted beam origin wins.
- `SunMoonSampler` accepts a foliage receiver from either face (absolute cosine)
  instead of the shaded one, so a front-lit plant keeps its sample;
  `receiver.foliage` carries the tag with the same emissive exclusion the
  estimator uses.
- `RtSlangFoliageTransmissionTest` (12) pins the constants, a Java mirror of the
  arithmetic, both gates, the walk ordering, the two-sided cosine and the shadow
  origins. Fifteen mutation probes (two-sided cosine, beam and occluded setter,
  layer constant, tag copy, flag bit, both emissive gates, sun-side normal, cube
  step, underwater origin, estimator and no-shadow branch, front-face gate,
  silhouette ramp) were each caught, and every shader was restored by sha256.
  `RtSlangWaterBeamTest` was re-aimed at the preserved invariants
  (`shadowPosition` defaults to the receiver; `bsdf = bsdfEvaluate(... arrival)`).
- Full `:common:test --rerun-tasks`: 150 classes, 855 tests, 0 failures,
  6 skipped. All 27 Slang entry passes compile (`:common:compileSlang`, 14 s,
  112 B push constants on the path pass unchanged).

Open: no owner-verified frame shows the canopy yet. The F3 `leafBacklit` counter
is still unwritten by the Slang pass (it has no stats binding, so
`RtFrameStatsTest` keeps reporting n/a), and the native `bedray_core` path does
not forward a leaf counter either; both are follow-ups. Pixel shadows, distant
block light, the glass-cost guard and the NaN/hot-loop guards remain open holes.

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `6b8ecf2055cfaae4…`; the previous
(denoiser) jar is kept as `….20261030-071636.bak` (sha256 `303f7dc64981bb08…`).
The bundled `shaders/slang/spv/path_trace.spv` matches the fresh compile
(`6fffb58748…`, 1 093 832 B). `bedray_core.dll` (`9ca5cd88cc01f823…`) and
`bedray_scene.dll` (`bf2caba452ef6445…`) are byte-identical to the deployed
natives, so no native changed.

## 2026-10-30 — The denoiser is re-enabled on the Slang guides (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

Every active trace mode used to resolve the denoiser setting to OFF with
`slang_denoiser_not_wired`. The refusal dated from the first Slang wiring, when
the pass wrote no G-buffer; it outlived its reason. The Slang pass has written
the seven DLSS-RR guide channels and NRD's `IN_MV` since the guide/motion
batches, the pack, the denoiser and the compose are unchanged, and
`RtSlangGbufferGuidesTest` already pins every channel against the deleted
`rt_path.comp` layout. This batch removes the gate:

- `DenoiserResolution` owns the decision table as a pure function: setting
  (`path_denoiser`) × library usability × session failures → mode + reason,
  including the DLSS-RR→NRD fallback and the frame-generation/Streamline
  limitation. `RtFrameRenderer.resolveDenoiseMode` gathers the live
  availability, calls it and logs the result once per change.
- An enabled denoiser makes `wantGbuffer` true, so the pass builds the guide
  variant and the renderer binds the denoiser's seven images and the motion
  buffer. On the native backend a core without `GUIDE_OUTPUTS` (or
  `NRD_MOTION` for NRD) still refuses with its existing reason instead of
  running guide-less.
- Tests: new `DenoiserResolutionTest` (9) covers NRD and the `-Dvkrtx.denoiser`
  variant, a session failure, a missing library with its reason, DLSS-RR,
  frame generation owning NGX, the NRD fallback, OFF, and source guards that
  the two refusal strings are gone, `resolveDenoiseMode` no longer reads
  `RtConfig.mode()`, `wantGbuffer` follows the denoiser, and the motion-export
  check survived.

Tests: **843 tests, 149 classes, 0 failures, 0 errors, 6 skipped**, `BUILD
SUCCESSFUL` (`:common:test --rerun-tasks`). `RtSlangGbufferGuidesTest` (8) and
`RtSlangNrdMotionTest` (6) re-ran green with the batch.

Open: no owner-verified frame consumes the guides yet. The first live check is
`[VKB-RT] denoiser requested=… active=nrd_reblur|dlss_rr` plus a look for
ghosting and haloes; the guide layout and the resolution table are unit-proven,
not run. Reflections stay noisy under NRD (no specular denoiser input).

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `303f7dc64981bb08…`; the previous
(dead-slider) jar is kept as `….20261030-065439.bak` (sha256
`7b4bc92f0731c714…`). `bedray_core.dll` was rebuilt from this checkout and is
byte-identical to the deployed native (sha256 `9ca5cd88cc01f823…`), so the
natives did not change. With the default `path_denoiser` (NRD) the next run
should log `denoiser requested=NRD active=nrd_reblur` once the world loads.

## 2026-10-30 — The dead ray-count sliders are deleted (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

The ray profile traces one sample per pixel per frame, so the four per-effect
sample-count sliders counted nothing and moved no pixel. They are gone rather
than wired to a second meaning:

- `RtSettings` loses `raysShadowQuality`, `raysReflectionQuality`,
  `raysAoQuality`, `raysGiQuality`: the fields, the `integer(...)` registry
  entries, the getters (`shadowQuality()`, …) and every `applyPreset`
  assignment. `resetLevel` and the UI profile gating iterate the registries, so
  they picked the removal up without edits.
- The Expert page built a row per slider in `VulkanSodiumConfig`; it now builds
  five (reflection distance, AO radius, emissive sample count, emissive
  distance, GI distance), and `SettingsCatalog`'s labels and owned-row checks
  follow. The ray mode's remaining sample count is the block-light one.
- Autotest: `RtModesScenario`'s `q` modifier now sets `raysEmissiveQuality`;
  six scenarios drop their post-`LOW` `raysShadowQuality = 2` line.
  `RtModesChecks.resetLevels` exercises `raysEmissiveDistance`, and
  `RtShadingFixesTest` replaces the ULTRA quality assertion with "ULTRA's GI
  distance reaches farther than HIGH's".
- Old configs: `applyKeyValue` returns false for a key in neither registry and
  the callers ignore the result, so a `bedray.toml` that still lists the four
  keys loads silently and the next save writes them out. The sample
  `common/config/bedray.toml` no longer lists them; `docs/SETTINGS_AUDIT.md`
  records the removal with the reason.
- `RtModesDebug.reflectionSettings()` had no consumer left once the slider was
  gone, so it and its test are deleted.

Tests: **834 tests, 148 classes, 0 failures, 0 errors, 6 skipped**, `BUILD
SUCCESSFUL` (`:common:test --rerun-tasks`). One test fewer than the previous
batch: `reflectionSettingsShowConfiguredCostControlsOnlyWhenEnabled` tested the
deleted formatter.

Open: `rays_pixel_shadows` / `rays_pixel_shadow_size` still only select a stats
pixel and reach no renderer; they are the last stats-only rows and belong to
their own pass. No owner-verified frame yet.

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `7b4bc92f0731c714…`; the previous
(stage-2a) jar is kept as `….20261030-064728.bak` (sha256 `18876fa4393d77e0…`).
`bedray_core.dll` rebuilt from this checkout is byte-identical to the deployed
native (sha256 `9ca5cd88cc01f823…`), so the natives did not change. The Expert
page now lists five ray rows instead of nine; a `bedray.toml` written by the
previous build keeps loading.

## 2026-10-30 — The ray profile reads its own effect switches (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`.

Stage 1 left the six ray-effect switches as labels on a profile that traced
everything. This batch makes each one a choice the shader acts on:

- `path_tracer.slang` grows five `PATH_TRACE_OPTION_*` bits (2..6) and two
  per-lobe far planes. `NO_SPECULAR_BOUNCE` / `NO_DIFFUSE_BOUNCE` end that
  continuation with `PATH_TRACE_FLAG_TRUNCATED` - the existing "short path,
  still trusted" flag - after the vertex's own emission and NEE are already in
  the radiance. `AMBIENT_ONLY_BOUNCE` lets a diffuse continuation collect only
  the environment: an accepted geometry hit ends the path unshaded (sky-light
  occlusion). Transmission is deliberately never gated, so water and glass stay
  see-through with every effect off.
- `NO_KEY_SHADOWS` returns the key light's NEE term before the visibility walk;
  sun/moon only, block emitters keep their traced shadows. `HARD_KEY_SHADOWS`
  is `sun.slang`: the sampler draws the disc axis while keeping the true disc's
  source density, so flux is unchanged and only the penumbra disappears.
- The three push words that were reserved zeros (76, 88, 92) now carry the
  emitter collection range, the diffuse far plane and the specular far plane as
  floats; the C++ path-trace pass already copies them verbatim, and a zero still
  means "the ray far plane", so the path profile and the RC probe pass are
  unchanged.
- Host: `RtSlangPathPass.rayEffectFlags()` maps RT Shadows / Soft Shadows /
  Reflections / AO / GI to bits (GI supersedes AO); `diffuseBounceDistance()` is
  GI distance or the AO radius, `specularBounceDistance()` is Reflection
  Distance, `emitterBounceDistance()` is Emissive Distance + 8 (the list was
  collected within that range; the shader still caps the light radius at 24
  blocks), and `rays_emissive_quality` becomes `min(4,max(1,q))*8` RIS
  candidates. "RT Block Light" off now drops the emitter list from the trace
  entirely. The ray profile's ray far plane is `rtRadiusXz * 16`, the extent the
  scene was built for, instead of the path mode's 384.
- F3: `[RT-EFFECTS]` names the six switches and four ranges for the frame.

Tests: **835 tests, 148 classes, 0 failures, 0 errors, 6 skipped**, `BUILD
SUCCESSFUL` (`:common:test --rerun-tasks`). Four older source-scan guards moved
with the code: the NRD-motion line now ORs `rayEffectFlags()` into the same
word, the bounce ray asserts the continuation far plane, the RC outside-support
check reads the field block instead of a raw substring, and the TRUNCATED
inventory is six stops instead of four.

Open: the four remaining quality sliders of the ray mode
(`rays_shadow_quality`, `rays_reflection_quality`, `rays_ao_quality`,
`rays_gi_quality`) still do not change a traced image; they are dead controls
until the next batch deletes them or gives them a meaning. `rays_pixel_shadows`
/ `rays_pixel_shadow_size` remain stats-only. No owner-verified frame yet for
any of this.

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `18876fa4393d77e0…`; the previous
jar is kept as `….20261030-064129.bak` (sha256 `56b429aa791f902a…`).
`bedray_core.dll` was rebuilt from this checkout and is byte-identical to the
deployed native (sha256 `9ca5cd88cc01f823…`), so the natives did not change.
The line to look for is `[RT-EFFECTS] shadows=… soft=… reflections=…
diffuse=… emissive=… distances=refl…`; no owner-verified frame yet.

## 2026-10-30 — Ray Tracing mode runs as the Slang pass's ray profile (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`, commit `028d2d8` (9 files, +293/−149).

Ray Tracing mode had been refused with `slang_ray` since the legacy GLSL ray
pass went away, although the AOT path transport already produces its effect
set: sun shadows through NEE, reflections and one-bounce indirect light through
the BSDF sample, emissive blocks through their own NEE. The mode now builds the
same two Slang programs and differs only in the profile. `RtSlangPathPass`
takes a `rayMode`: `configure` pins two scattering events — the surface the
camera sees plus one indirect one, because `path_tracer.slang` counts the
primary as depth 0 — and one sample per pixel, and it bypasses the adaptive
governor. `describe` prints `ray_slang(...)` without the radiance-cascade axis.
Each profile reads its own switches (`rays_restir_*` + `rays_accumulate` for the
ray profile, `path_restir_*` + `accumulate` for the path profile), so neither
profile's rows are dead.

With the backend owning both profiles' reservoirs, the renderer's `rt_restir`
and `rt_restir_aux` ring, its size constants, pixel count, light fingerprint and
reset flag are deleted; the two `StatsBuf` address slots stay in the record
layout at zero (`RtStatsLayout` offsets unchanged). The denoiser refusal now
covers every active mode with one reason, `slang_denoiser_not_wired`: the NL
pack was only ever validated against the deleted GLSL G-buffer. `AO` and
`DIRECT` are debug shadings of that deleted pass and are refused under their own
stage names (`slang_ao`, `slang_direct`) instead of showing a lit image labelled
as a debug shading. A boolean-only `enable_hardware_ray_tracing` request still
resolves to `PATH_TRACED`, the fuller of the two profiles.

Open: the per-effect ray switches (`rays_shadows`, `rays_soft_shadows`,
`rays_ao`, `rays_reflections`, `rays_emissive`, `rays_gi` and their quality and
distance sliders) still do not change the traced image — they only label the F3
line and the `RtModesDebug` description — so those rows remain dead controls
until the ray profile reads them as path options.

### Evidence

Java: `:common:test --rerun-tasks` **831 tests, 0 failures, 0 errors, 6 skipped**
(the six are the DLL, benchmark-flag and real-shaderpack cases). New
`RtSlangRayModeTest` (6 tests) drives the controller through a recording
backend: the ray profile keeps 2 bounces / 1 sample while `path_spp`/`path_bounces`
say 8/5, reports its own mode, ignores the governor, reads its own reuse and
accumulate switches, refuses AO/DIRECT by stage name, and pins the renderer source
so the host reservoir ring cannot come back. Four existing string guards that
named the pre-profile source text were updated to the new lines. `FEATURE_MATRIX`
ray rows now say what this revision shows: L2 for the ray profile (the old L4
screenshots belong to the deleted GLSL pass), NOT_IMPLEMENTED for AO/DIRECT.

Deployed to both FreesmLauncher instances (`Bedray Fabric 26.3` and `26.3(1)`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar` sha256 `56b429aa791f902a…` and
`bedray_core.dll` sha256 `9ca5cd88cc01f823…`, previous files backed up under
`bedray-backups/`. The line to look for is
`[VKB-RT] ray effects on AOT Slang passes passes/path_trace.slang+passes/path_display.slang`
followed by `ray_slang(bounces=2,spp=1,...)`; no owner-verified frame yet.

## 2026-10-30 — the native core meters its own recording (L0 source, L1 build, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`, commit `8fa6548` (15 files, +734/−45).

The previous entry gave the reference backend a counter writer; on `bedray_core`
the radiance and accumulation images live inside the core, so the host could not
dispatch `passes/frame_stats.slang` over them and the F3 shares still printed
`n/a`. The core now records that program itself. The addition is layout-neutral:
`BedrayPathTraceDesc.program_stats` takes the former `reserved0`/`reserved1`
pair at offset 56, the struct stays 80 bytes, `program_trace_scene` stays at 64
and `reserved` at 72, and `static_assert`s in `core.cpp` hold those four numbers
where the Java `BedrayCoreLayouts.verify()` holds the same ones.

`bedray_core_record_path_trace_stats(core, command_buffer, stats_buffer,
stats_offset, stats_bytes, frame_index, error)` appends one dispatch to the
recording `bedray_core_render_path_trace` just made, because the images a
counter can read belong to that recording: the radiance image, the accumulation
half that recording's display stage folded (not the half it imported history
from), the borrowed depth view and the borrowed counter range. It records no
barrier, pool or pin — the display writes already sit behind the recording's
exit barrier, and the per-dispatch descriptor set is the one
`bedray_core_release_recording` already retires. It refuses instead of skipping:
an unknown command buffer, `program_stats == 0`, a null buffer, a range under
16 bytes, an offset that is not a multiple of four, or a range that overflows all
return `BEDRAY_ERROR_INVALID_ARGUMENT`, because a silent skip is
indistinguishable from a frame whose counters were always zero. `configure`
checks the four bindings (three storage images and the storage buffer) and the
8-byte `FrameStatsPush` the way it checks the trace and display manifests.

`BEDRAY_PATH_TRACE_FEATURE_FRAME_STATS (1u << 6)` advertises the stage. Either
side alone degrades honestly: a new jar on a core without the bit calls nothing
and reports the counters as unmeasured, and an old jar on this core sends
`program_stats = 0`, so no dispatch is recorded. `leaf_pct` remains `n/a` on
both backends — no program writes that word.

### Evidence

Native, configured and built fresh in `build/bedray-native/verify-x64` (VS 2022
BuildTools, x64, Release), CTest **24/24 passed, 0 failed, 0.70 s**. Six of those
tests are new in `path_trace_pass_test.cpp`: dispatch order and binding set after
display, the stage costing nothing until called (same dispatches, barriers,
images and buffers with and without the program), two outstanding recordings
each metering their own accumulation half, groups following the frame extent,
the five refusals plus the configure-time manifest check, and the recording
lifetime (`destroy` refuses until the release proves the meter, and a discarded
recording still owns it). `sampler_ownership_test` now also requires the feature
bit and that the export refuses a null core.

Java: `:common:test --rerun-tasks` **825 tests, 0 failures, 0 errors, 6 skipped**
(the six are the DLL, benchmark-flag and real-shaderpack cases). `RtFrameStatsTest`
is 12 tests, including `theNativeCounterStageIsNamedTheSameByBothSides`, which
pins the header's bit and offset to the Java mirror; `RtSlangWaterMediumTest` now
reads the whole feature-mask body instead of one exact line, so the new bit cannot
pass unnoticed.

`fabric/build/libs/bedray-fabric-mc26.3-0.2.0-alpha.jar` was built from this
source (`bedray_core_record_path_trace_stats` in `BedrayCoreNative.class`, the
`frame_stats` SPIR-V, manifest and reflection inside) and deployed with
`bedray_core.dll` to both testbed instances, each with a timestamped `.bak`:
jar `sha256 06b722fa…02c9e`, DLL `sha256 5db03def…22d2d`.

No owner-verified frame yet. What a playtest should show: on the native backend the
F3 line's `hit_pct` and `hist_pct` become measured shares again and auto exposure
has a sample to follow; on a core or jar that cannot meter, both stay `n/a`.

## 2026-10-30 — the frame counts its own pixels again (L0 source, L1 build, L2 unit, 13 mutation probes; no live frame yet)

Branch: `refactor/slang-pipeline`, commit `412d855` (12 files, +665/−29).

The four words at the head of a StatsBuf slot — `hits`, `samples`, `lumLogSum`,
`histAccepted` — were written inline by the GLSL ray pass and nothing replaced them
when the legacy pipeline was deleted. Two failures hid behind that: `RtExposure.observe`
never received a sample, so `path_auto_exposure` could not adapt and the exposure
batch above could only make the compensation slider live; and the F3 overlay divided
zeros, so `hit_pct` and `hist_pct` printed as if the frame had been measured.

`passes/frame_stats.slang` now derives the four words from images the path already
produces, and `RtFrameStatsSlangPass` dispatches it from `ReferencePathTraceBackend`
immediately after the barrier the composite already needs — no new image, no new
synchronization, no descriptor-set change:

- It reads the tracer's `radiance` (RGBA16F), the running mean `accum` (RGBA32F) and
  `depth` (R32F), and binds **only the sixteen-byte head** of the slot it writes, so
  the pass cannot reach the emitter address that lives behind the counters.
- The luminance word is the ABI the host already decoded — `64*(log2(lum)+16)`,
  clamped to its own fixed-point range at 2^-16 and 2^16 — so `RtExposure` and the F3
  average needed no change, and `RtFrameStatsTest` reads all three files instead of
  trusting one side.
- A pixel whose verdict alpha is zero, or whose radiance is NaN/Inf, is not added at
  all: one such frame would otherwise sit in a mean that lives for thousands of
  frames. This is the same bit `path_display.slang` rejects on, so the meter and the
  picture agree.
- One whole 4×4 tile in sixteen is metered, rotated by `pc.frameIndex`, so a still
  camera does not weigh the same pixels all session. At 1920×1080 that is 8 100 tiles
  and ~129 600 metered pixels per frame — 1/16 of the screen, the rate the legacy
  counters and the shares were calibrated for.
- The slot is handed over through two new borrowed fields (`RtFrameParams.statsBuffer`
  / `statsOffset`) set beside `statsAddress`, after the `vkCmdFillBuffer` that clears
  the head, so the pass writes into the slot the renderer leases and reads back.

`gradle/slang.gradle` pins `FrameStatsPush` (8 bytes: `frameIndex` at 0, `reserved` at
4). The compiled artifact is 4 712 B of SPIR-V, 1 178 words, four bindings
(`radiance`/`accum`/`depth` as `STORAGE_IMAGE`, `stats` as `STORAGE_BUFFER` with
`elementBytes` 4), 8 B push, 8×8×1 workgroup, capabilities `ImageQuery`+`Shader` and
`requiredCapabilities` 0 — no new negotiated capability, and the generated pass index
now lists 27 entries with `frame_stats` among them.

Honesty changes that came with it: `leafBacklit` had no writer either, and a zero in
that word read as a measured "no leaf is backlit" — `leaf_pct` now reports `n/a`. The
native core records the whole frame inside `bedray_core`, where the radiance and
accumulation images are its own, so it still has no counter writer; both backends
report "no measurement" rather than a fabricated number, and `PathTraceBackend.Frame`
documents which backend writes what.

### Evidence

`:common:compileSlang` green (27 passes). `:common:test`: **824 tests, 0 failures,
0 errors, 6 skipped** (the six are the native-ABI, benchmark and real-pack tests that
need a DLL, a flag or a shaderpack on disk). New `RtFrameStatsTest` (11 tests) covers
the round trip of the luminance word, the three-file formula pin, the counter offsets,
the 1-in-16 mask and its rotation, the rejection gate, the sixteen-byte bind, the
generated manifest plus three rejected mutations of it, the ordering after the
composite barrier, the slot handover, and `n/a` for the unwritten leaf counter.
`RtSlangSampleRejectionTest.nothingElseReadsTheLaneTheVerdictLivesIn` was rewritten as
`everyReaderOfTheVerdictLaneTreatsItAsAVerdict`: the meter is the second consumer of
the verdict lane, so the guard now names all three files that bind it and requires each
reader to reject on the bit or ignore it, never to weight by it.

### Mutation probes (13)

Ten against the meter: the luminance formula, the metered rate, a swapped counter slot,
the deleted verdict gate, alpha used as a weight, a descriptor bound to the whole slot,
the wrong accumulation half, `params.statsOffset` pinned to zero, the leaf counter
printed as a measurement, and a third consumer of `radianceView`. Three against the
head mirror: the Slang `FrameStats` field order, the Java `RtStatsLayout` field order,
and a renamed counter field. Each reported exactly the owning test red, and every file
restored to its pre-probe sha256.

Two probes first reported green and were **harness bugs, not weak guards**: the needles
spanned two lines, this checkout stores CRLF, and `str.replace` on the raw bytes
matched nothing, so Gradle ran against an untouched file and the green was true for the
source it was given. Re-run with the needle tried in both spellings and an assertion
that the old text is gone and the new text present before trusting a result: both went
red, each naming its own test. Probes whose needle sits on one line were unaffected,
which is why all ten probes of the first batch — single-line needles each — fired on the
first pass.

### Still open

The native path-trace core needs its own writer (or an ABI route that lets the host
dispatch this pass over core-owned images) — that is the follow-up task, and an
ABI-layout change needs the owner's word first. Nothing here is a live frame: no
Vulkan device, no game launch, no measured brightness change. The owner's playtest
still decides whether the exposure now adapts and whether F3 prints real shares.

## 2026-10-30 — the frame's exposure is spent where the picture is made (L0 source, L2 unit; no live frame yet)

Branch: `refactor/slang-pipeline`, commit `f131804` (7 files, +168/−12), with the
distribution-doc correction `248a298`. Recorded here two batches late: the fix
landed before the rays-per-pixel entry below, its log section did not.

The typed path applied no exposure at all. The display push literal was `1.0f`
(`ReferencePathTraceBackend`, offset 8 of the display block), the volumetric
composite pushed `1`, and `FLAG_AUTO_EXPOSURE` was read in three call sites that
nothing ever set — so `path_ev_compensation` brightened the denoiser's guide pack
while the traced image stayed where it was: one setting, two numbers.

`RtFrameRenderer` now decides the exposure once per frame, from the settings
switch (`RtSettings.autoExposure()`) and the compensation, through
`RtExposure.applied`, and stores it in `RtFrameParams.exposure`; the flag bit is
composed from the same switch, so the bit and the number cannot disagree. Every
consumer reads that field: the StatsBuf slot (`RtStatsLayout.EXPOSURE`),
`FrameConstants.exposure.x` — already the lane `BedrayPathTraceNative` forwards as
the native descriptor's `lighting.exposure`, so no ABI change — the Java display
push, the DLSS-RR and NRD packs' tonemap, and the light shafts composited after
tone mapping.

`248a298` rewrites `native/bedray_core/DISTRIBUTION.md`, which promised both DLLs
are staged next to each other. `gradle/bedray-distribution.gradle` says otherwise:
`fabricBedrayDistribution`/`neoforgeBedrayDistribution` depend on `buildBedrayNative`
and copy `nativeDll` plus the combined JAR, and `buildBedrayScene` is not in the
chain, so an instance fed only the task's output runs without `bedray_scene.dll`.

### Evidence

`RtSlangExposureTest` (new, 7 tests): the EV compensation is a power of two; a
measured scene moves the auto value and not the fixed one; the frame computes
exposure once; the switch reaches the frame flags; one lane feeds both backends;
the volumetric composite shares the picture's exposure; only the display step
spends it. `RtSlangVolumetricPassTest` and `RtSlangExposureTest` pass in a full
`:common:test`; L0/L2 only — no Vulkan device, no game launch, no measured
brightness change.

What was still missing at that revision, stated plainly because the commit says so:
adaptive exposure had nothing to adapt to. No Slang pass wrote the luminance sum,
the hits or the samples counters since the legacy GLSL ray pass was deleted, so
`RtExposure.observe` never ran and the meter sat at the ray-mode floor. The
compensation slider was the live half of the setting. The batch below closes the
measurement; neither has an owner-verified frame yet.

## 2026-10-30 — "Rays per Pixel" traces the rays it says it traces (L0 source, L1 build, L2 unit, mutation proof; no live frame yet)

Branch: `refactor/slang-pipeline`, commit `edb5650` (11 files, +305/−23).

`path_spp` was a silent no-op. The slider went 1..8, its value reached
`RtFrameParams.spp`, `RtPathTraceSlangPass.Frame` wrote it into
`FrameConstants.frame.z` (offset `FRAME+8`), the native descriptor carried it as
`samples_per_frame` and `path_trace_pass.cpp` copied it into `constants.frame[2]` —
and nothing read it. No file in the Slang tree mentioned `frame.z`, the Java passes
pinned `params.spp = 1`, `RtSlangFrameData` pinned `frame.samplesPerFrame = 1`, and
the F3 overlay printed "Primary rays: 4" while the GPU traced one. The whole ABI
already existed; only the constant writes and the missing reader were absent.

What landed is a mean over N stratified primary paths per pixel, in
`pathtrace/path_trace_entry.slang`:
`pathTraceSampleCount()` clamps the frame word to `1..PATH_TRACE_MAX_SPP` (8, now
mirrored by `RtPathTraceSlangPass.MAX_SAMPLES_PER_PIXEL`);
`pathTracePrimaryRay()` takes its jitter as an argument, and
`pathTracePixelJitter()` returns `f.camera.jitter.xy` unchanged for N=1 — so the
cheapest setting traces exactly the paths the previous build traced, host jitter and
all — and otherwise splits the pixel into an N×N stratified grid offset by
`noise2D`. Each ray gets its own seed
(`pc.sampleIndex * sampleCount + s`). `prevViewProjection` is not jittered, so
changing the per-sample offset cannot desync reprojection.
`PathTraceMean`/`accumulatePathTraceSample()`/`pathTraceMeanResult()` average
radiance, the primary vertex, view position, normal (renormalized), albedo,
specular F0, roughness, metallic, direct light, first-hit distance and motion, and
keep refused paths' radiance in a separate lane: the sample-rejection rule stays
`INVALID_SAMPLE | RC_UNRESOLVED` (never `TRUNCATED`), one `sampleTrusted` bit feeds
the alpha lane, the guides and both motion channels, and a pixel whose every ray was
refused reports the mean of the refused radiance with `alpha = 0` rather than
poisoning a running mean that lives for thousands of frames. Eight rays with one
broken number are seven good samples.

ReSTIR DI keeps its one-reservoir-per-pixel-per-frame contract: only ray 0 reads,
merges and publishes (`directReuse.enabled = s == 0u ? diRequested : 0u`), the rest
shade the same emitter list with plain next-event estimation, and
`lights.emittersReusedAtPrimary` follows the same bit so the extra rays cannot
darken the picture by losing the emissive branch. Every reservoir write — publish,
occlusion decay, empty record — is gated on `reuseActive()`, so turning reuse off for
rays 1..N-1 cannot blank what ray 0 published.

The reporting stopped lying on its own: `F3Sections` prints the *governed*
`RtAdaptiveQuality.spp()`/`.bounces()` rather than the raw setting, and
`RtSlangPathPass.describe()` now includes `spp=`. spp is deliberately not in the
pipeline-config hash: the adaptive governor moves it several times a minute, and a
running mean of frames traced at different sample counts is still a mean of unbiased
estimators.

Four guards were stale and were updated, not deleted: `RtAdaptiveQualityTest` had
*required* `params.spp = 1;` (it pinned the no-op; it now requires the governed call,
because rays go first in the ladder and a pinned one makes both the slider and the
budget silent), and `RtSlangNrdMotionTest`, `RtSlangRcFieldTest` and
`RtSlangSampleRejectionTest` each asserted the removed
`bool sampleTrusted = !pathTraceSampleRejected(result.flags);` spelling, which the
mean now computes internally. `RtPathTraceSlangPassTest` gained the `FRAME+8` layout
assertion, and the new `RtSlangSamplePerPixelTest` (6 tests) covers the host word
behaviourally (requesting 0/1/4/8/99 through `RtSlangFrameData.fill` yields
1/1/4/8/8) plus the loop, the N=1 equivalence, the reservoir gating and the reject
split.

Evidence, one run per thing, none concurrent: `:common:compileSlang` OK —
`path_trace` 1,038,264 → **1,060,976 B** (+22,712 B) and `path_trace_scene`
1,088,612 B; `:common:test` **806 tests, 0 failures, 0 errors, 6 skipped over 145
classes**; `:common:runShimTests` 34 passed, 0 failed; ten mutation probes over the
entry shader, `RtSlangFrameData` and `RtSlangPathPass` — frame word pinned to one,
pass pins the ray count, `describe()` claims one ray, shader ignores the frame word,
loop runs one iteration, every ray reuses the reservoir, a refused path enters the
accepted mean, a single ray loses the host jitter, all rays share one seed, the alpha
lane hardcoded trusted — **all ten caught** by a named test, each with the file
restored byte-identically (`sha256` verified) and `touch`ed. One probe was initially
missed because my own needle `",spp=1,"` did not occur in the mutated source
(`",spp=1,dist="`); the guard was fixed to a positive `",spp=" + p.spp + ",dist="`
plus a bare `,spp=1` negative and re-probed to caught.

Not proven, and stated plainly: **no owner-verified frame yet.** Two consequences to
watch in the game. The default of 2 now genuinely costs about twice what the previous
build cost, and 8 costs eight times — which is exactly what the adaptive governor was
built to relieve, and could not, because spp was pinned. And the multi-ray loop is
new code in the hottest shader in the mod: the mean's guide channels average over the
accepted rays, so a disagreement between DLSS-RR guides and the radiance lane would
show up as denoiser ghosting, not as a crash.

## 2026-10-30 — the legacy renderer is gone, and a trace request that names no mode opens the renderer that exists (L1 build, L2 unit, mutation proof; no live frame yet)

Branch: `refactor/slang-pipeline`, three commits: `1ea94ce` (deletion),
`27a4ca5` (water guards), `383ae71` (mode honesty).

`1ea94ce` removes the second renderer: 99 files, −13,476 lines. Gone are
`RtPipeline`, `RtShaderCompiler`, `RtShaderDiskCache`, `RtPathTracerPass`,
`RtRayEffectsPass`, `RtCompositePipeline`, `RtVolumetricPass`, `RtTestPipeline`,
`SimpleAccelerationStructureBuilder`, the four `rtx/raytracing` experiment-engine
classes, the 26 GLSL files under `shaders/glsl/`, and the `-Dbedray.pathTracer`
selector. Two things were split out of `RtPipeline` before it went, because
surviving code still needed them: `RtFrameBindings` (the legacy binding numbers and
the `Views` record) and `RtExtensionProbe.supportsOpacityMicromap()`. The Shaderc
surface left in the tree is seven users, of which only `fg_prepare.comp` and
`iris_depth.comp` (plus `shaders/spatial/`) are still compiled at runtime — the
inventory is frozen by `ShadercSurfaceTest`, which also forbids a runtime GLSL
compile in `rt/slang` and `RtFrameRenderer`.

What that deletion exposed is the subject of `383ae71`. Three entry points said
"trace" without naming a renderer and answered themselves with `RAY_TRACED`:
`VulkanRtxConfig.effectiveRtMode()`'s boolean fallback, the legacy
`enable_hardware_ray_tracing` toml key (read only by a file that has no `rt_mode`),
and the perf autotest's `-Dvkrtx.autotest.perfRt`. Post-deletion,
`RtFrameRenderer.createPass` refuses that mode (`RtFailure("slang_ray")`), so an
old config file or a perf run stopped producing a frame at all. All three now name
`PATH_TRACED`. A *named* `rays|ao|direct` request is deliberately left alone: that
is an explicit `experimental_rays` opt-in, and its refusal is pinned by
`SlangPathUnavailableTest` until the ray-effects passes get a Slang host.

`RtModesChecks` asserts those choices at the call site, and
`RaysHiddenTest.theTraceChoosersNeverNameTheRefusedMode` pins them as a scan of the
source: a chooser is the kind of line that walks back to the deleted renderer
whenever somebody adds a mode, and `PerfScenario.run()` needs a running game so it
cannot be called from a unit test. Three mutation probes, each a single revert back
to `RAY_TRACED` (the boolean fallback, the toml key, the perf flag), each with the
file restored byte-identically (`sha256` verified) and `touch`ed: **all three were
caught** by that named test. The guard commit itself is the fourth row of the table
below; the run on the restored tree measured 800 tests, 0 failures, 0 errors, 6
skipped over 144 classes, with the shim batch at 34 passed / 0 failed.

Evidence, one full run per commit, none concurrent:

| Commit | `:common:test` | `:common:runShimTests` |
|---|---|---|
| `1ea94ce` | 788 tests, 0 failures, 9 skipped | 34 passed, 0 failed |
| `27a4ca5` | 798 tests, 0 failures, 6 skipped | 34 passed, 0 failed |
| `383ae71` | 799 tests, 0 failures, 6 skipped | 34 passed, 0 failed |
| `31485e5` | 800 tests, 0 failures, 6 skipped | 34 passed, 0 failed |

`27a4ca5` adds `RtSlangWaterTest` (10 tests) over `surface/water.slang`, the water
rules in `surface/surface.slang`, `pathtrace/path_tracer.slang`,
`pathtrace/path_trace_entry.slang` and the Java side of the fluid identity
(`AutoPbr`, `OpticalMaterials`). It pins the owner's water rule as a test: the
style switch moves the waves and nothing else, so `waterSigmaT`, `waterMediumFrom`,
`waterSegmentTransmittance`, `waterInScatter`, `waterBeamTravel` and
`waterBeamTransmittance` must not contain a `waveMix` token, while the étendue
ratio stays outside the caustics switch. Six mutation probes on
`surface/water.slang`, each a compile-valid single revert (`--tests
"*RtSlangWaterTest"`, then the file restored byte-identically with `sha256`
verified and `touch`ed so the next build cannot reuse the mutated artifact): the
vanilla wave mix retuned, the wave dispersion law replaced by a linear one, the
caustic fold guard dropped, the étendue ratio frozen to 1.0, the absorption made to
read the style switch, and the light column taken from the straight sight line
instead of the refracted one. **All six were caught** by a named test.

First jar with no legacy code in it, built from this tree and installed in both
testbed instances: `bedray-fabric.jar` 15,273,193 B (`dfbdbcb4d1b2aa41…`),
`bedray_core.dll` 437,760 B (`685905c6028f90b8…`), `bedray_scene.dll` 224,768 B
(`bf2caba452ef6445…`). Neither instance had any BedRay artifact before, so no
timestamped `.bak` was due; each file was hash-checked after the copy.

Not proven, and stated plainly: **no owner-verified frame** from any of the three.
The jar is the first build whose path mode does not depend on the deleted code, and
that is a build fact, not a game fact. Two known open holes are recorded rather
than hidden: the ray-effects chain has no Slang host at all (`createPass` refuses
`slang_ray`), and the deleted GLSL guards left coverage holes listed in
[SLANG_MIGRATION.md](SLANG_MIGRATION.md) under "What the deleted GLSL guards
covered". A harness correction came out of the deploy: `fabricBedrayDistribution`
does **not** stage `bedray_scene.dll`, although `native/bedray_core/DISTRIBUTION.md`
says both distribution tasks copy it — the task was run separately and the DLL
copied by hand.

## 2026-10-30 — The underwater light arrives bent, and the waves focus it (L0 source, L1 build, L2 unit, mutation proof; no live frame yet)

Branch: `refactor/slang-pipeline`. An underwater receiver sampled the sun along the
straight sight line to the disc. That line is not the path the light took: it bent
where it crossed the fluid face, so the shading direction, the absorbed column, the
crossing loss and the occluders above the sheet were all measured along the wrong
route — and a flat floor under a wavy sheet got no caustics at all.

A plane interface has a closed-form answer, which is what made this closable in
Slang alone: no `FrameConstants` field, no push bit, no C++ and no Java change.

What landed:

| Layer | Change |
|---|---|
| `surface/water.slang` | `WaterBeam`, `waterBeamTravel` (flat sheet, or the wave face when it still faces the beam), `waterBeamToLight` (depth from the medium's `surfY`, column along the refracted direction, entry point, `solidAngle = cos(air)/cos(water)`), `waterBeamTransmittance` (Schlick at the flat face), `waterBeamAtDepth` + `waterBeamFocus` (the 2x2 Jacobian of entry → footprint by finite difference over three wave normals, reciprocal clamped `[0.1, 3.0]`, a fold takes the floor) |
| `surface/visibility.slang` | `SURFACE_VISIBILITY_FLAG_REFRACTED` and `traceShadowBeam`: the column leg with the medium-aware filter stopping a bias short of the sheet, the sky leg with the plain filter starting a bias above the crossing, lifted along the light; one explicit Fresnel, both legs proven through `surfaceVisibilityProven` |
| `pathtrace/path_tracer.slang` | the beam is built for `Sun`/`Moon` only, and the direct term shades `-beam.direction`, walks the beam when it exists and the straight column otherwise, and multiplies the estimator by `beam.solidAngle * waterBeamFocus(...)` on the frame's own `portalTime` |
| `surface/surface.slang` | the fluid decode takes `material.f0` from `WATER_INTERFACE_F0`, so a traced crossing and the analytic one cannot drift |
| harness | `verify_water.slang` now reads all of the beam entries and its bounds |

Evidence: `:common:compileSlang` with `slangc` 2026.20 (24 roots; `path_trace.spv`
1,038,264 B and `path_trace_scene.spv` 1,065,900 B on the rebased tree),
`verify_water.slang` compiles standalone (56,708 B), `:common:test --rerun-tasks`
reports **896 tests, 0 failures, 0 errors, 3 skipped** (160 classes) with the new
`RtSlangWaterBeamTest` 9/9 and the extended `RtSlangRayOffsetTest` 2/2. Before the
rebase onto the RC/guide branch the same batch measured 863 tests in 153 classes,
also green.

Mutation proof — 16 probes, each a single revert of one wiring point, the named
guard failing, then the file restored byte-identically (`sha256` compared): the
inverted étendue ratio, the entry point's sign, the flipped index ratio, the removed
crest guard, the focus as the area instead of its reciprocal, `WATER_INTERFACE_F0`
moved, the column walked without the medium, the sky leg left on the sheet, the
crossing loss dropped, the raw visibility bit read outside its predicate (caught by
`RtSlangVisibilityOverflowTest`), the lobe asked about the air sight line, the beam
weight dropped, the beam offered to any light, and a second copy of the interface
number in the decoder, the column allowed past the light it was sampled from, and
the sky leg let off the filter budget. **16/16 caught.**

Two harness lessons, both fixed in the probe script rather than in the guards. The
source guards read shader files straight off disk, so Gradle does not see the input
change and `:common:test` can answer UP-TO-DATE with a stale XML — the probe now
forces `--rerun` and refuses to trust a run whose `> Task :common:test` line is not a
real execution. A probe that only matches `startswith("> Task :common:test")` also
hits `:common:testClasses`; the check is anchored on the exact task token.

Cost, measured and kept: the two walk instantiations `traceShadowBeam` adds are
+426 KB / +440 KB of SPIR-V on the path-trace roots (+70 % over the parent
revision, which `git archive` + the same `slangc` line reproduces at 611,764 /
625,864 B — and the pre-RC revision the jar deployed on 2026-10-29 carries,
610,276 / 624,376 B, compiles the same way). Routing both legs through one shared
callee, and through the existing `traceShadowVisibilityMedium` wrapper, were both
tried on the same sources and are not smaller (+12 KB / +13 KB). The caustic itself
is cheap: removing only the focus call saves 28 KB.

Limits, so a playtest is not asked to prove more than this: the beam bends the
direct light only — the sky seen from under water, emitted light and secondary rays
that leave the body still travel straight; the focus is bounded at 3x, so a real
caustic line is softened rather than exact; total internal reflection of the *beam*
cannot happen (air into water has no critical angle) but the wave face is only
sampled at the entry point, not along the column. This is L0/L1/L2 plus a mutation
proof — no owner-verified frame yet, and nothing here says the water looks right on
a GPU.
## 2026-10-30 — Rejected samples no longer publish denoiser guides (L1/L2, mutation proof; L3 no-regression)

`pathTraceSampleRejected` (`INVALID_SAMPLE | RC_UNRESOLVED`) is now computed once
in `pathtrace/path_trace_entry.slang`; the alpha lane, `outMotion`, the seven
DLSS-RR guides and NRD's binding-33 vec4 all read that one verdict. A refused
sample writes the neutral miss set (view depth 1e6, flat normal, zero signal)
and zero motion, so no output can contradict the zero weight in the radiance
lane. A truncated path stays trusted: it stopped short, but its primary vertex
is real.

Source revision `e267a2f` plus this change. The shader compiles and the Fabric
jar builds. `:common:test` passed **887 tests in 159 suites: 0 failures, 0
errors, 3 skipped**; CTest passed **24/24**. The new
`RtSlangNrdMotionTest.theRejectedSampleNeverPublishesGuidesOrMotion` was
mutation-tested: removing `!sampleTrusted ||` from the two neutral branches
fails it (and `theMotionRecordIsTheLegacyVec4`), restoring the guard passes.

Two live world runs on the installed jar and `bedray_core.dll` passed with the
native path tracer: `nativeScene=true` + `nativePathTrace=true`
(`backend=NativePathTraceBackend spatial=true`, `result=PASS`, three 2560x1440
shots, per-channel mean 157.4/158.9/165.9 and stdev 52.6/53.9/51.0), and
`nativePathTrace=true` alone (`result=PASS`, 577 frames). Both ran with
`denoiser=off`, so `gbuffer=off` and the guide bindings were not consumed: this
proves no regression in the shared entry, not live guide consumption. No new
crash dump appeared; `hs_err_pid26912.log` remains the 2026-10-29 spatial
artifact. The Java-host run was not completed because the user stopped
launching Minecraft, so no claim is made for it. L4 visual acceptance and owner
confirmation are not claimed.

## 2026-10-30 — Guide initialization precedes primary writes (L2)

NRD now prepares a new image generation before the primary pass writes its
guides. Both NRD and DLSS RR capture the exact instance's completion/discard
callbacks before preparation can emit commands; abandoning the primary pass
therefore rolls back first-use layout state even before denoiser recording.
Four focused tests passed with JDK 25/JUnit 5.10.2, covering ordering, first-use
discard and initializer ownership. No GPU execution is claimed.

RC/upstream integration revision `c90fce3` also passed all 26 AOT entries and
**882 Java tests: 873 passed, nine skipped**, zero failures/errors (2m16s).
Native CI [40979323913](https://github.com/bnfe12/bedray/actions/runs/40979323913)
passed 25 core + one scene + one lifetime tests, preserving upstream water and
shader-byte gate fixes. The merged revision is `2c8051d`.

## 2026-10-29 — Native RC finite-window reconstruction (L1/L2)

RC v2 now builds five dependent cone-transport levels inside the native opaque
path group and supplies the primary diffuse continuation. Emission remains with
primary NEE; the field carries reflected direct light. Sky requires a real
boundary proof and is not fabricated; field history stays disabled. The user
explicitly authorized RC shader mathematics. The ordinary path remains opt-in.

Native CI [40937161641](https://github.com/bnfe12/bedray/actions/runs/40937161641)
passed **25 core + one scene + one lifetime** tests on `7dfdf5c`; baseline
15+1+1 also passed. Clean source `67adc69` passed **861 Java tests: 852 passed,
nine skipped**, all 26 AOT entries (1m50s). Final source `eb7659a` rebuilt all
26 entries and passed **46 focused tests** after the geometric-offset correction
(55s). Native sources are identical to the tested CI revision. Numerical tests
include independent dense solid-angle integration, not GPU execution.
Contract, bounds, costs and limitations: [RC_RECONSTRUCTION_PLAN.md](RC_RECONSTRUCTION_PLAN.md).
No new GPU/game, visual, performance or owner acceptance is claimed.
## 2026-10-29 — An underwater camera now knows it is under water (L0 source, L1 build, L2 unit, mutation proof; no live frame yet)

Branch: `refactor/slang-pipeline`. The typed path tracer carried a water medium
that only a *transmission through a surface* could switch on, so a camera already
sitting in a water block traced every primary segment through clear air: the
deeper you swam, the less the picture changed, and the surface seen from below
refracted the wrong way and never mirrored.

Two facts made this closable without a layout change. The engine already answers
the question — `RtFogState.collect()` reads `camera.getFluidInCamera()` and sets
`medium = 1` for `FogType.WATER`, which is the same state that picks the water fog
— and `FrameConstants.frame.w` had bits 0-4 taken, so a fifth bit is additive
inside the same 608-byte block.

What landed:

| Layer | Change |
|---|---|
| Slang contract | `FRAME_FLAG_CAMERA_IN_WATER = 1u << 5u` and `frameCameraInWater()` |
| Slang path | `PathTraceOptions.primaryInWater`; `primaryWaterMedium()` walks up from the eye (64 blocks, 6 probes, cutout and invalid faces skipped) and takes the first up-facing `MATERIAL_SOURCE_FLUID` top; `tracePath` seeds the body, fetches the downwelling light and sets `PATH_TRACE_FLAG_WATER_MEDIUM` before the first segment |
| Slang BSDF | `bsdfSample(..., float incidentIor, ...)`, `eta = incidentIor >= dense ? incidentIor : incidentIor / dense` — the ratio exceeds 1 leaving the dense side, so `refract` can return zero and the TIR branch becomes the swimmer's mirror (critical angle 48.7 deg at 1.333) |
| Java host | `RtFrameParams.cameraInWater` from `RtFogState.medium == 1`; `RtSlangFrameData` writes `Frame.FLAG_CAMERA_IN_WATER` |
| Native core | `BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER (1u << 8)` host bit, mapped by `make_frame_constants` onto bit 5 of the frame word; admitted into `allowed_flags`; advertised as `BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER (1u << 4)` |

The last row was not optional. `make_frame_constants` builds its own
`FrameConstants` from `BedrayPathTraceFrameDesc`, so a bit written only into the
Java block would never reach the shader on the live native-scene path — and
`PathTracePass::render` rejects an unknown flag with `INVALID_ARGUMENT`, so the
host bit and the core's `allowed_flags` had to land in the same batch. The host
now masks the bit out when the loaded core does not advertise the feature, which
leaves an old DLL tracing with the eye in air instead of losing the frame.

Evidence: `:common:compileSlang` with `slangc` 2026.20 compiles all roots;
`:common:test` with `--rerun-tasks` reports 854 tests, 0 failures, 0 errors,
3 skipped (`RtSlangWaterMediumTest` 9/9, `RtSlangSampleRejectionTest` 10/10 after
its `bsdfSample` signature guard followed the new parameter); CTest reports 23/23
with `bedray_path_trace_pass_test` checking that the frame word carries
`1 | (1 << 5)` for an accumulating underwater frame and that
`CAMERA_IN_WATER | (1 << 30)` is still rejected.

Mutation proof — each wiring point reverted, the guard named in brackets failed,
then the file was restored byte-identically (`sha256` compared): the `eta`
expression in `bsdf.slang`, the `WATER_IOR` argument at the sampler call, the
`options.primaryInWater` line in `path_trace_entry.slang`, the contract bit number
and `RtFrameRenderer`'s verdict [three guards in `theEyeStartsInsideItsOwnWaterBody`
/ `theOpticalLobeRefractsFromTheMediumTheRayTravelsIn` /
`theHostPublishesTheCamerasFluidState`]; then, in a second pass, the host bit in
`RtSlangPathPass`, the compat mask in `BedrayPathTraceNative` and the feature-mask
entry in `core.cpp` [`theHostPublishesTheCamerasFluidState` again]. The C++ mapping
was mutated on its own and `bedray_path_trace_pass_test` failed at the frame-word
check. Restoring a native file with `cp -p` keeps the *old* mtime, so MSBuild
re-ran the mutated binary until the file was touched — the second green CTest run
is after that.

Limits of this record. `primaryWaterMedium` costs up to six extra closest-hit
probes per primary ray only while the eye is submerged, and the common open-water
case stops at the first hit; no timing was taken. When the walk finds no face the
fallback puts the surface at the eye, which can understate depth but cannot invent
column. `PathTraceOptions.primaryInWater` is deliberately not a `PathTracePush`
flag bit because that word mirrors host ABI bits one for one. What is *not* done
in the water item: the NEE beam under water is still a straight shadow ray, so it
carries no Snell bend and no caustics, and `RtSettings.waterStyle` is not wired
into the typed path. No owner-verified frame of any of this exists yet; the play
test is "dive, look up at the surface from below, and see the mirror and the
depth colour change", which nothing here has observed.

## 2026-10-29 — A replay verdict is not a live frame: the gate loses its name list, the scene march is unblocked (L1 driver replay, L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`. The open question was whether
`volumetric_march_scene` may reach the driver at all: it faulted the headless
replay, so the gate refused it and light shafts stayed off. This entry settles
that the replay carries no information, and says how much risk that leaves.

Sweep of all 24 AOT modules through the four Vulkan steps
`bedray_core_register_program` performs, under three device configurations:

| Probe device settings | Faulting modules |
|---|---|
| queried features (everything the GPU advertises), instance apiVersion 1.3 | `path_trace`, `path_trace_scene`, `rc_probe_trace`, `restir_spatial`, `velocity_reproject`, `volumetric_march_scene` |
| the engine's minimal calloc'd feature chain + its 8 device extensions | identical six |
| same, instance apiVersion 1.2 | identical six |

The verdict does not move with the device, so the difference from the game is
not a feature bit or an API level. What does separate the two sets, perfectly:

| Opcode (SPIR-V) | In all 6 faulting modules | In any of the 18 clean ones |
|---|---|---|
| `OpConvertUToPtr` (68), `OpPtrAccessChain` (166), `OpPtrEqual` (185) | present | absent |

Device-address pointer arithmetic. And the game compiles exactly that, on the
same GPU, in the very session that died: `26.3(1)/minecraft/logs/latest.log`
registers `path_trace` (26 bindings), `path_trace_scene` and `restir_spatial` at
20:50:16, then crashes after `shafts_pass=true` on `volumetric_march`. Two crash
signatures, one shared caller frame — the game's leaf is `nvoglv64.dll+0x13f4ae`
reading `0x4` (the `OpUndef` handle), the replay's is `+0x140178` reading `0x0`
under `vkCreateComputePipelines`. Same function, different null source: the
replay reproduces *a* compiler fault, not *the* one that killed the game.

Conclusion, and it narrows what a gate may claim: refusing a module because a
replay faulted is refusing on an unexplained harness difference. `SlangPassDriverGate`
now holds the byte rule only — `REPLAY_REFUSED`, `blockedNames()` and the
name-only `blocker(name)` are deleted, and the class comment records why, because
a future reader is the one most likely to re-add the list.

| Check | Expected | Observed |
|---|---|---|
| `OpUndef` descriptor handles, all 24 built modules | 0 | 0 for all 24 (Python scan over the deployed bytes) |
| `volumetric_march_scene` descriptor contract vs the legacy march | same | same: `tlas` 0, `sections` 2/64 B, `accumImage` 11, `outDepth` 12, `frame` 16/608 B, push 96 B, 8×8×1, capabilities 517 |
| `SlangPassDriverGateTest` + `SlangRuntimeOwnershipTest` | green | 9 + 3 tests, 0 failures |
| mutation proof of the new anti-name-list guard | red | a `java.util.Set.of("volumetric_march_scene")` field added to the gate fails exactly `a_replay_fault_alone_never_refuses_a_module`; file restored byte-identical (sha256 `2046f3fe…` OK) |

Two method errors made and corrected while getting here, both of which had
already produced a fake finding:

- I read SPIR-V `Decoration::Binding` as 3 and `Offset` as 33. They are 33 and
  34 (`DescriptorSet` 34, `Offset` 35). The wrong table made 23 of 24 modules
  look like they declared descriptors the manifest never lists; the corrected
  scan shows the two marches identical. No conclusion from the earlier audit
  stands — including the one I was about to report.
- The replay's `VK_EXT_debug_utils` callback treated `pCallbackData` as the
  message `char*`, when it is a `VkDebugUtilsMessengerCallbackDataEXT*`, so the
  driver's diagnostics printed as mojibake for every run since the harness
  existed. Decoded properly, the loader only reports inserting `VK_LAYER_RTSS`
  and `VK_LAYER_OBS_HOOK` and NVIDIA says nothing before the fault — there is no
  compiler message to chase.

**Not proven:** no live frame has yet registered either march module — the
atmosphere's Slang volumetric passes have never been connected in a session the
owner ran (`04:14` log: `Slang volumetric passes are not connected; god rays are
unavailable`). The unblocked scene march is a deliberate, stated risk: if the
driver does fault on it, the JVM dies without a log line, and the fix is to put
the refusal back on that one name with the crash log as its evidence. The same
exposure now applies to `rc_probe_trace` (not wired into the frame loop yet) and
`velocity_reproject` (nothing registers that name). L1 + L2, no owner-verified
frame.

## 2026-10-29 — P0-5 and P0-6: the two audit items that said "нужен тест" and had none (L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`. Both items were closed in the branch months
ago by a code change and then verified source-only, with the doc itself asking
for a regression test. Now each has one, and each guard was shown to bite.

| Guard | Mutation | Result |
|---|---|---|
| `RtSlangSectionIndexTest > theRangeOffsetIsAppliedExactlyOncePerVertexBase` | a second `range.firstQuad +` in `geometryVertexBase` — the P0-5 defect itself | FAILED |
| `... > anIndexPastTheDeclaredRangesResolvesToNoRange` | `return int(geometryIndex);` instead of the range clamp | FAILED |
| `... > theBlasRangeOffsetsTheSameQuadStartTheShaderAdds` | `range.firstVertex = 0u;` in `acceleration_scene.cpp` | FAILED |
| `... > theHeaderSaysTheRangeStartCountsQuads` | "first quad" → "first vertex" in `bedray_scene.h` | FAILED |
| `RtSlangRestirSpatialTest > aMergedRecordKeepsItsOwnReceiverAndRefusesANonFiniteWinner` | a statement added after `reservoirs[ownSlot] = merged;` — the publish stops being last | FAILED |
| same test, second assertion | a fourth early `return;` in front of the publish — the P0-6 bug shape | FAILED (`expected: <3> but was: <4>`) |

All four mutated files (two shaders, one `.cpp`, one header) were restored from
byte copies and verified by sha256; the restored tree is green (17 tests across
the two classes, 0 failures) and `git status` shows no residue in `native/`.

One structural finding from the proof: the native half of the P0-5 agreement was
not an input to `:common:test`. Editing `native/bedray_scene/src/*.cpp` left the
task `UP-TO-DATE`, so the guard could pass against a stale tree — the same hole
that closed P2-8. `common/build.gradle` now lists `bedray_scene/src/*.cpp` next
to the `CMakeLists.txt`/`include/**`/`nrd_bridge` inputs, and the `firstVertex = 0u`
mutation is what proves the file is read on a real run.

Evidence level L2 (no-GPU unit) plus L1 for the Slang compile of the restored
tree. These are source-contract guards: they pin the agreement between the two
representations, not a rendered frame. No owner-verified frame claim here.

## 2026-10-29 — The march's unassigned entity handle, and why a replay verdict is not a refusal (L1 compile, L2 unit, driver replay, no live frame yet)

Branch: `refactor/slang-pipeline`, on top of `24b0617`. Two owner-reported
crashes (`Краш`) in the testbed instance were the same fault: `hs_err_pid12888.log`
(16:54) and `hs_err_pid12088.log` (20:50) both take
`EXCEPTION_ACCESS_VIOLATION (0xc0000005), reading address 0x0000000000000004`
with `# Problematic frame: # C [nvoglv64.dll+0x13f4ae]`, and both Java stacks end
at `RtSlangVolumetricPass.<init>` → `SlangRuntime.program` →
`BedrayCoreNative.registerProgram`, immediately after `[VKB-ATMO] … shafts_pass=true`.
The defect is in `passes/volumetric_march.slang`: the adapter assigned `tlas`,
`sections` and `instanceMask` on a `LegacySectionTraversal`, leaving the struct's
`EntityGeometry entities` resource fields unassigned. Slang lowers that to an
`OpUndef` descriptor pointer, `marchLightShaft` reaches `decodeHit`, and the
entity branch dereferences the undefined handle — even though the host marches
with `instanceMask = 1`, so an entity could never be committed.

Proof by mutation, on the same module, bindings and layout (headless replay of
the four Vulkan steps `bedray_core_register_program` performs, RTX 5060 Ti):

| Variant | What it adds | Replay |
|---|---|---|
| `m5_legacydecode` | section decode only | `4/4 pipeline ok` |
| `m7_undefentity` | the same plus an unassigned `EntityGeometry` leg | `Problematic frame: # C [nvoglv64.dll…]` |

Fix and gate:

- `traversal/legacy_section_traversal.slang` gains `LegacySectionWorldTraversal`
  — same TLAS, same `legacyHitToSurface` decode, no entity leg, and
  `surfaceMotion` left at the interface default (a section does not move within a
  frame). `volumetric_march.slang` uses it.
- `SlangPassDriverGate.undefinedDescriptorPointers(byte[])` counts `OpUndef`
  results whose type is a pointer into a descriptor storage class
  (`UniformConstant`/`Uniform`/`StorageBuffer`), and `SlangRuntime.program`
  refuses such a module before it reaches the driver, naming
  `-Dbedray.slang.passes=<name>` (or `all`) as the override. The gate reads bytes
  instead of a name list, so a shader fix lifts its own refusal.
- `RtAtmosphere` no longer creates its own `BedrayCore`. The branch already grew
  `SlangRuntimePool` (one core per `VkDevice`, refcounted leases) in `fb4d5d8`,
  so the atmosphere now takes a lease from it and hands that lease back in
  `destroy()`, after its volumetric and bloom passes let go of their recordings,
  and the renderer releases its own lease only after `atmosphere.destroy()` in
  both shutdown branches. A second core on the same device was the first
  hypothesis for this crash and the replay disproved it — the fault reproduces
  with a single core — but the shape is still wrong (two descriptor pools, two
  pipeline caches, and a shutdown order that can close a core while another
  holder still pins a recording), so `SlangRuntimeOwnershipTest` pins exactly one
  creator (the pool) plus the release-before-close order. `RtAtmosphere` also
  holds a `shaftsUnavailable` flag now: a pass whose programs refuse must cost
  one `LOGGER.warn`, not an exception on every frame.

| Check | Expected | Observed |
|---|---|---|
| `slangc` on the patched adapter | exit 0 | exit 0, 26008 B module, 5 bindings unchanged |
| `OpUndef` descriptor pointers, all 19 AOT artifacts, pre-fix tree | only the crashing module | `volumetric_march` 2, every other module 0 |
| same, rebuilt tree | 0 | 0 for all 19 |
| driver replay after the fix | no fault | `volumetric_march 4/4 pipeline ok`, `volumetric_apply 4/4 pipeline ok` |
| `:common:test` | 0 failures | 837 tests / 146 files, 0 failures, 0 errors, 3 skipped |
| mutation proof of the new guards | red | reverting one line to `LegacySectionTraversal traversal;` fails `no_bundled_module_reads_an_unassigned_descriptor_handle`, `the_march_module_is_clean_after_losing_its_entity_leg` and `onlyTheSectionProviderChangesAtTheTwoBindingBoundaries`; restored byte-identical (sha256 OK) and green again |

What this batch also corrects, and it matters more than the shader fix: the
gate first shipped as two hard-coded name lists taken from a single headless
replay (12 allowed / 7 refused). The game's own log disproves three of the
refusals — `26.3(1)/minecraft/logs/latest.log` at 20:50:16 records
`registered SlangProgram[path_trace, 26 binding(s), 112 B push]`,
`[path_trace_scene, …]`, `[restir_spatial, 8 binding(s), 64 B push]`,
`[path_display]`, `[sodium_dequantize]`, `[particles_composite]`, and the other
instance adds the four `bloom_*`. Left as-is, those lists would have switched
path tracing off in the Java host — the opposite of what the owner was testing.
The interim rule that survived that correction (byte scan plus
`REPLAY_REFUSED = {volumetric_march_scene, rc_probe_trace, velocity_reproject}`)
was itself wrong as a method, and the next entry below is what replaced it: a
replay verdict turned out to be uncorrelated with the game, so the gate is now
the byte scan and nothing else.

Evidence level: L1 (compile), L2 (unit), plus a driver replay on the owner's GPU.
**No owner-verified frame yet.** `volumetric_march_scene` still refuses, so
`RtSlangVolumetricPass` cannot construct and light shafts stay off this build
(fail-soft in `RtAtmosphere`, one `LOGGER.warn`); path tracing is unaffected.

## 2026-10-29 — Native AOT denoiser preparation (L1/L2)

Source `1d7a04bbea7003bf031b77360c894198f488f97f`, synchronized with audit
revision `24b0617`: `:common:test` passed **834 tests, 825 passed, nine skipped,
zero failures/errors**, all 24 AOT entries compiled with Slang 2026.20 (1m46s).
Windows native CI [40936173315](https://github.com/bnfe12/bedray/actions/runs/40936173315)
passed 24 core + one scene + one lifetime test; baseline 15+1+1 also passed.
Velocity and NRD/RR pack/compose now use coarse native AOT recording, shared
device-runtime leases and exact completion/discard. Details and remaining
ownership scope: [NATIVE_DENOISER_PREPARATION.md](ai-agent-addenda/NATIVE_DENOISER_PREPARATION.md).
No new GPU/game, vendor execution, image comparison or owner acceptance is claimed.

## 2026-10-29 — P1-6, P1-8, P1-9 and P1-11: four audit items that had an analysis and no test (L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`, on top of `54f94a0`. Four P1 items carried a
2026-10-28 "source-only" verdict whose own text said *для уверенности нужен
тест*. Each of them also had a second problem: the register's summary sentence
was a claim about the tree that nothing re-checked. This batch turns those
sentences into tests, and corrects two of them.

What is now pinned:

- **P1-6 (`RtSlangHistoryAnchorTest`, 3 tests).** `SurfaceHit.worldPosition` is
  `float3(section.origin.xyz & 1023) + localPosition` in the section backends and
  `result.position + cameraAnchor` for entities, so it is a neighbourhood proxy,
  not an identity. The test enumerates every `X.worldPosition` reader and writer
  in `shaders/slang/**` after stripping `//` comments — readers are exactly
  `{experimental/restir_pt.slang, surface/surface.slang}` — and separately asserts
  that no root module mentions `restir_pt` and that outside `experimental/` the
  only file containing `historyValidate(` is its own definition in
  `contracts/history.slang`. That last part is the honest headline: **no live pass
  validates history at all today**, so the wrapped anchor cannot reach a frame.
- **P1-8 (`RtSlangSampleRejectionTest.theBelowHorizonVndfSampleIsAZeroAndNotABrokenPath()`).**
  A VNDF reflection that lands below the shading horizon (`lightLocal.z <= 0.0`)
  returns before `L`, `weight`, `pdf` and `valid` are written, and the tracer
  accumulates emission and NEE *before* calling `bsdfSample`, so the path is short,
  not broken: it sets `PATH_TRACE_FLAG_TRUNCATED`, not `INVALID_SAMPLE`, and stays
  in the accumulation mean. The test checks that ordering by index, not by prose.
- **P1-9 (`RtSlangCutoutAdvanceTest`, 3 tests).** The hole branch has to move the
  origin and shorten the interval by the *same* `advance`, `primaryAdvance` may
  only grow on the camera ray, `firstHitDistance` stays `primaryAdvance + hit.t`,
  and the advanced `makeRay` must not add a second `rayBias` (with
  `mediumStart = 0.0` so the water segment still measures from the new origin).
- **P1-11 (`ShadercSurfaceTest`, 2 tests).** The register said one Shaderc user
  was left. There are nine, and they are not all residue: `RtShaderCompiler` plus
  its four legacy callers, three upscaler/frame-gen passes, the Iris/pack GLSL host
  (four files, which *cannot* be AOT-compiled because the GLSL is user-authored),
  and the PoC entry. The frozen set is now the inventory, and the second test
  forbids `rt/slang/**` and `RtFrameRenderer` from referencing
  `org.lwjgl.util.shaderc` at all — `lwjgl-shaderc` is `compileOnly`
  (`gradle/loader-module.gradle:44`), so its runtime presence is a host assumption
  the Slang path must not acquire.

| Check | Expected | Observed |
|---|---|---|
| `:common:test` full suite after the batch | 0 failures | 819 tests, 0 failures, 0 errors, 3 skipped (810 before) |
| M1 `surface.hit.worldPosition` read added in `pathtrace/path_tracer.slang` | P1-6 reader guard fails | `onlyTheDocumentedAnchorsReadTheWrappedPosition() FAILED`, nothing else |
| M2 `__exported import "experimental/restir_pt.slang"` in `bedray.slang` | reachability guard fails | `theModuleThatKeysHistoryOnTheAnchorIsStillUnreachable() FAILED` |
| M3 the three-line wrap warning deleted above the field | warning guard fails | `theFieldAnnouncesThatItIsNotAUniqueIdentity() FAILED` |
| M4 `if (lightLocal.z <= 0.0) return result;` deleted | P1-8 guard fails | `theBelowHorizonVndfSampleIsAZeroAndNotABrokenPath() FAILED` |
| M5 `result.radiance += throughput * surface.material.emission;` deleted | P1-8 guard fails | same test, and only it |
| M6 (`M7`) `result.weight = float3(1.0);` written before the horizon return | P1-8 guard fails | same test, and only it |
| cutout branch mutations (4, previous run): origin moved without shortening `tMin`; `makeRay(..., 0.0, ...)`; `primaryAdvance` growing off the camera ray; double bias in `firstHitDistance` | P1-9 guards fail | one test killed per mutation |
| `org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize()` inserted in `SlangRuntime.create` | both P1-11 guards fail | `runtimeGlslCompilationStaysOnTheFrozenSurface() FAILED` + `theSlangPathNeverReachesForShaderc() FAILED` |
| restore of every mutated file | byte-identical | `path_tracer.slang` `c757558b…`, `bsdf.slang` `a0fa8468…`, `hit.slang` `627becc7…`, `bedray.slang` `c5c314de…`, `SlangRuntime.java` `7199f4e7…`; `git status` clean for all five |

Two findings from this batch are negative and are recorded as such in the
register: `bedray_frame_lifetime::FrameLifetime` should *not* be wired (every
destroy site is already guarded by the `recordings_`/`Pins` maps and the
`begin_use`/`complete_use` pair, so a second reference count would create two
competing truths), and `bedray_history`'s C-API would duplicate the one-bit reset
scheme the frame already runs — closing P1-1 by importing dead code is not
closing it.

Not shown here: L1 beyond the suite's own `compileSlang` task, L3, L4, L5. These
are source-contract guards — they prove the ordering and the reachability, not
that a frame looks right, and no live frame was captured on this revision.

## 2026-10-29 — P2-8 CRT/ABI audit, and why the source-contract gates were not running (L0 source, L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`, on top of `57f8f2f`. P2-8 was the last audit
item with a heading and no status line at all: it claimed a `/MT` vs `/MD`
conflict between the two native projects. It is closed as "no conflict, pinned
by test", and the run turned up a defect in the verification gate itself.

What the source says:

- Both projects set `MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"`
  (`bedray_core/CMakeLists.txt:107`, `bedray_scene/CMakeLists.txt:53` and `:61`),
  and `/MD` / `MultiThreadedDLL` appear nowhere under `native/` — not in the
  DLLs, not in the ten test executables, not in `bedray_render_graph`. The
  static CRT is deliberate: the shipped `bedray_core.dll` must not require a
  VC++ redistributable.
- Static CRT is only safe while one module's heap never frees another's memory.
  That holds by ABI, not by habit: every `BEDRAY_API` declaration in
  `native/*/include/*.h` returns `BedrayStatus`, `uint32_t` or `void` — 28
  exports, no exceptions. Buffers the native side fills are addresses the JVM
  allocated in its own `Arena` and pass in as `out` parameters, so no pointer
  ever crosses back.

Two new guards in `NativePlatformGateTest`, `everyNativeTargetUsesTheSameStaticCrt()`
and `noExportHandsTheHostSomethingToFree()`. The export scan counts what it
examined and fails if that count is zero, so relocating the declarations out of
its reach cannot silently turn the guard into a no-op.

| Check | Expected | Observed |
|---|---|---|
| `:common:test --tests '*NativePlatformGateTest*'` | 0 failures | 6/6 pass |
| `/MD` injected as `MSVC_RUNTIME_LIBRARY "MultiThreadedDLL"` on the `bedray_core` target | guard fails | `everyNativeTargetUsesTheSameStaticCrt() FAILED` |
| fake `BEDRAY_API void* BEDRAY_CALL bedray_core_take_ownership(BedrayCore*)` added to `bedray_core.h` | guard fails | `noExportHandsTheHostSomethingToFree() FAILED` |
| restore | byte-identical | `native/bedray_core/CMakeLists.txt` sha256 `1408d440…` before and after, `git status` clean for the path |

**The gate was not running.** The first mutation reported `BUILD SUCCESSFUL`
with zero tests executed: `:common:test` treats `native/**` and
`../gradle/slang.gradle` as invisible, because these tests read them by
relative path from `common/` and never declare them as inputs. Only
`--rerun-tasks` exposed it. That is not a curiosity — a native build-file
regression lands in exactly the state where the guard that would catch it is
UP-TO-DATE. Fixed by declaring `inputs.files(rootProject.fileTree('native') {
include '*/CMakeLists.txt', '*/include/**', 'nrd_bridge/src/*.cpp' })` plus
`gradle/slang.gradle` on the test task; re-verified without `--rerun-tasks`, the
`/MD` mutation now fails the run on its own. Files under
`common/src/main/resources/shaders/**` were already covered transitively, since
they reach the test classpath through `processResources`.

Second method note: `git show HEAD:<path> > <path>` is **not** a byte-exact
restore in this worktree. `core.autocrlf=true`, so the blob is LF while the
checked-out file is CRLF; the content matched but sha256 did not
(`2448624…` vs `1408d440…`), which would have shown up as a whole-file
line-ending diff in a commit. The CRLF form is `git show … | sed 's/$/\r/'`,
and the hash comparison is what proves it.

No live-frame claim here: this is an ABI and build-configuration audit, L0/L2.

## 2026-10-29 — Radiance Cascades merge physics: what is and is not left (L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`, on top of `4fdc0ca`. Tool: `slangc` 2026.20
via `-Pslangc=…`. No shader or host source change — this run re-tests P0-4's
claim that the confidence-weighted average "persists in the codebase".

P0-4 is correct that `rcLegacyProbeIrradiance` still merges the two intervals
as `(near.rgb*near.a + far.rgb*far.a) / (near.a + far.a)`, and the typed
compose `front.radiance + front.transmittance * back.radiance` is the physics.
What the audit did not say is that the averaging form has no path to a frame:

- `bedray.slang` exports `rc_atlas_layout`, `interval_transport`,
  `segment_store`, `rc_trace`. `radiance_cascades_store.slang` (the float4
  facade) is reachable only from `bedray_legacy.slang`.
- No `.slang` in the tree calls `rcStoreInterval` or `rcAccumulateInterval`
  outside the module that defines them, so the averaging resolve has no writer
  for the atlas it reads.
- In the legacy format `.a` really is a sample count clamped to
  `RC_MAX_CONFIDENCE = 64.0`, not opacity — so "treat `.a` as transmittance" is
  not a fix; the format cannot express occlusion at all. That is why the typed
  `RCSegment{radiance, transmittance, confidence, flags}` exists.
- `passes/rc_probe_trace.slang` compiles and ships in the jar
  (`spv/rc_probe_trace.spv`, manifest, reflection), but zero references to it
  exist in `common/src/main/java`, and the only native mention is a
  `render_plan.cpp` feature name. **No RC pass is dispatched.**

So the remaining P0-4 work is frame integration, not merge physics. Closing it
needs a host that binds the typed atlas and a frame to look at — L3/L4, which
does not exist here.

Result:

| Check | Expected | Observed |
|---|---|---|
| `:common:test --tests '*RtSlangRadianceTransportTest*'` | 0 failures | 4/4 pass: transport form, no divisor on the radiance statement, opaque-front early return before the composition, `min(confidence)`, and the legacy-unreachability scan |
| Mutation proof | all four fail | Reverting `rcComposeSegments` to the confidence-weighted mean, deleting the `all(front.transmittance == float3(0.0))` early return, turning `min` into `+`, and exporting `radiance_cascades_store.slang` from the default root → 4 tests completed, 4 failed |
| Restore | no residue | `interval_transport.slang` and `bedray.slang` back, 4/4 green again |

Two notes on method. The first draft of the reachability guard asserted
`bedray.slang` must not contain the string `radiance_cascades_store.slang` and
**failed on a comment** — the module name appears in the root's own header docs.
The guard now matches the import statement, which is the thing that actually
exports it; a substring check on a name that also appears in prose is not a
check. And the hashes of the restored `interval_transport.slang` differ between
this worktree and `C:\BedRay` (`5da26d73…` vs `a78f4fde…`) purely because
`git archive` writes CRLF while the live tree holds LF; `diff
--strip-trailing-cr` reports no difference, so the guards ran against the same
text the committed file contains.

## 2026-10-29 — Union trace guards are load-bearing (L2 unit, mutation proof)

Branch: `refactor/slang-pipeline`, on top of `39c8653`. No source change: this
run proves the three guards already in `native/bedray_core/src/path_trace_pass.cpp`
actually decide the outcome. CMake Release, `ctest -R bedray_path_trace_pass`
per mutation.

| Guard | Mutation | Result |
|---|---|---|
| `binding_count != 18u && != 25u && != 26u` accepts the 26-binding Java-built program | drop `!= 26u` | FAILED (SEGFAULT) |
| `program_has_binding(core, program, 33, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)` | replace with `(void)motion;` | FAILED (clean) |
| `create_dummy(16u, motion_dummy_id_, …)` for the NRD `IN_MV` stand-in | gate with `if (false)` | FAILED (SEGFAULT) |

Two of the three die by segfault rather than a clean assertion: when
`configure` refuses, `bedray_path_trace_pass_test` continues and dereferences a
pass that was never set up. So these prove the guards are load-bearing — a
regression there is caught — but they do not identify which `CHECK` breaks, and
the harness's own failure reporting is worse than it should be for the
rejection paths. The middle case fails cleanly because it is a per-program
validation, not setup.

Restored `path_trace_pass.cpp` is byte-identical to the committed file
(`37cca8b35742ca7d…`), rebuilt after `touch` (a restored file keeps an older
mtime and MSBuild would otherwise relink the mutated object), and the full
native suite is back to 21/21.

## 2026-10-29 — Borrowed Slang programs stay borrowed (L2 unit)

Branch: `refactor/slang-pipeline`, on top of `39e0365`. Tool: `slangc` 2026.20
via `-Pslangc=…`; `:common:test` only, no native rebuild (the change is Java).

P1-14 said `SodiumDequantize.destroy()` closed the `SlangProgram` that
`SlangRuntime.program()` caches, so the next world load got the dead instance
back and its freed handle went into a fresh `BedraySodiumNative`. `1cc8ce6`
(PR #35) had already removed that `close()` and replaced the field with a
`nativeHandle()` capture, so the defect text was stale; what was missing was any
rule that keeps it from coming back, and one live hole.

`SlangProgram.close()` calls `library.destroyProgram(core, handle)` but
`nativeHandle()` returned `handle` regardless of `closed`. A consumer that
borrowed a closed program therefore received a freed handle with no error — the
failure surfaces as GPU-side corruption rather than an exception, which is how
the original crash presented. `nativeHandle()` now throws when closed. Every
call site takes the handle in its constructor and passes it straight to a native
adapter, so the guard can only fire on the poisoned-cache path.

Source changes covered by this run:

- `SlangProgram.nativeHandle()` refuses a closed instance.
- `SodiumProgramLifetimeTest` pins three things: `destroy()` contains neither
  `program.close()` nor a `SlangProgram` import, `SlangRuntime` is the only
  writer/reader/invalidator of the `programs` map, and the handle guard precedes
  the return.

Result:

| Check | Expected | Observed |
|---|---|---|
| `:common:test --tests '*SodiumProgramLifetimeTest*' '*SodiumDequantize*'` | 0 failures | 3 new guards + 2 layout tests pass (benchmark skipped) |
| Mutation proof | guards fail on pre-fix shape | `destroyKeepsTheBorrowedProgramAlive` and `aClosedProgramRefusesToHandOutItsHandle` both FAILED with `program.close()` restored and the guard removed; the mutated decoder needed a `SlangProgram` field so it compiled like the pre-fix version instead of failing the build |
| `:common:test` full | 0 failures | 804 tests / 139 suites, 0 failures, 0 errors, 6 skipped (XML aggregate), so the handle guard breaks no consumer |
| Restore | byte-identical | `SlangProgram.java` `b5394515…`, `SodiumDequantize.java` `8f00ebff…` in both trees |

L2 only. The reload crash needs a world exit and re-entry on a real device;
nothing here proves that a live reload now survives.

## 2026-10-29 — Ray-offset fixes and the release fan-out (L1 compile, L2 unit)

Branch: `refactor/slang-pipeline`, on top of `b98f54a`. Tool: `slangc` 2026.20,
`-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major
-I common/src/main/resources/shaders/slang`. CMake Release + CTest from the
isolated worktree; cmake is not on PATH, taken from
`build/bedray-native/windows-x64/CMakeCache.txt` `CMAKE_COMMAND`.

Three audit defects, closed in source:

- **P1-15** `pathtrace/path_tracer.slang` — a bounce started at
  `hit.position + bsdf.direction * options.rayBias`, so at grazing angles the
  origin stayed inside the triangle it had just left, and the fallback used the
  shading normal, which a normal map can tilt past the face. It now offsets
  along `safeNormalize(surface.hit.geometricNormal, normal)` with the sign
  chosen by `dot(bsdf.direction, bounceNormal)`, and `tMin` remains `0.0`.
- **P1-16** `surface/visibility.slang` — both shadow wrappers offset the origin
  by `bias` *and* passed `bias` as `tMin`, so the occlusion test began
  $2\times\text{bias}$ off the surface and light leaked through carpets, glass
  and slabs. `tMin` is now `0.0`; the origin keeps the single offset. That is
  the convention the bounce ray and the legacy `traceVisibility` already use
  (`rt_common_sampling.glsl:7` passes a fixed epsilon against an offset origin,
  never the caller's bias twice). Note the audit's
  `light/visibility.slang` path no longer exists.
- **P1-17** `native/bedray_core/src/core.cpp` —
  `bedray_core_release_recording` returned on the first failing owner, leaving
  later owners holding a recording for the rest of the session and
  `bedray_core_destroy` refusing to shut down. Release is now a fan-out through
  `release_every`, which visits every owner and reports the first failure. The
  template lives in the anonymous namespace: inside the file-wide `extern "C"`
  block MSVC rejects it with `C2894`. The reachable leak was `bloom → volumetric`
  (`PathTracePass::release_recording` always returns `BEDRAY_OK`, and an unknown
  recording returns `BEDRAY_OK` too).

Result:

| Check | Expected | Observed |
|---|---|---|
| `slangc` on the passes that include both files | 0 errors | `path_trace`, `path_trace_scene`, `restir_spatial`, `rc_probe_trace` all compile to SPIR-V |
| `:common:test --tests '*RtSlangRayOffsetTest*' '*RtSlangWaterMediumTest*' '*RtSlangVisibilityOverflowTest*'` | 0 failures | 11 tests / 3 suites pass, both new `RtSlangRayOffsetTest` cases green |
| Mutation proof P1-15 | guard fails on pre-fix tracer | `theBounceLeavesAlongTheGeometricFace` FAILED, `aShadowRayIsOffsetExactlyOnce` PASSED |
| Mutation proof P1-16 | guard fails on pre-fix visibility | `aShadowRayIsOffsetExactlyOnce` FAILED, `theBounceLeavesAlongTheGeometricFace` PASSED |
| CTest `build/bedray-native/windows-x64` | 21/21 | 21/21 pass, including the `release_every` case in `descriptor_cache_test.cpp` |

After each mutation the fixed file was copied back and `touch`ed, because
`cp -p` preserves the older mtime and MSBuild then relinks the *mutated* object
instead of rebuilding it. Both restored files were checked byte-identical by
sha256 against the working tree (`path_tracer.slang`
`c757558bb926a596…`, `visibility.slang` `f38b3986a8f733df…`).

This is L1/L2 for the optics lane. A shadow ray and a grazing reflection are
numerically indistinguishable in a unit test; the visible claims — no light
through a carpet, no self-intersection on water or ice at grazing angles —
need a frame the owner has to look at.

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
| CTest (`build/bedray-native/windows-x64`) | 19 tests | 19 passed | re-run on the pushed HEAD `01b8d3b` in an isolated worktree; includes `bedray_path_trace_pass_test` with the 18/25/26-binding contract and stand-in counts |

Mutation proofs: changing the shader trust threshold `1.0e-8` to `0.0` fails
`theMotionRecordIsTheLegacyVec4`; replacing the host's
`wantsMotion != (boundMotion.buffer() != 0)` check with `false` fails
`theHostAndThePushKeepTheirPromise`. Both files were restored byte-identical
(sha256 verified).

No clean full-suite claim was available while this batch was first written: one
run raced a concurrent, uncommitted spatial-reuse edit in the same worktree (its
three `RtSlangRestirSpatialTest` failures are stale-compile artifacts — the same
test passes against the settled sources), and two later attempts were killed by
host memory exhaustion (JVM `hs_err`, "insufficient memory", while another
session's Gradle build ran on the same 16 GB machine). The gap is now closed on
the settled branch: the spatial entry above records a clean `:common:test` run
(754 tests / 122 suites, 0 failures) on the pushed HEAD after PR #29, and the
C++ side was re-run for this entry in an isolated worktree checked out at
`01b8d3b` — CMake Release build OK and CTest 19/19, with
`bedray_path_trace_pass_test` covering the 18/25/26-binding union contract.

This is L1/L2 for the motion lane. No GPU dispatch has ever run with
`PATH_TRACE_OPTION_NRD_MOTION` set — the C++ backend never sets either option
bit — so the NRD layout has not consumed these values on a device, and no
owner-verified image of the motion channel exists. Live frames of the Slang path
do exist now (see the spatial entry), but they exercise the trace and spatial
stages, not the guide or motion writes. The guide capture happens before the
display pass may reject a sample, so a rejected pixel can carry a motion record
too; the denoiser's own history rejection is expected to absorb that once a live
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

## 2026-10-29 — real native NRD motion output

Base `4ffc145` includes the consolidated audit and all preceding native passes.
Additive NRD_MOTION feature/export forwards the seven guide images plus the
existing 32-byte BedrayPathTraceBuffer range through the same opaque path graph.
Binding 33 now receives the real borrowed host vec4-per-pixel buffer. Both option
bits, complete guides, nonzero buffer and sufficient range are validated before
pins/commands; inactive bindings still use a pinned stand-in. Old calls explicitly
reject enabled motion writes rather than letting a full frame write into that
one-vec4 stand-in. No existing ABI-v2 layout or shader arithmetic changes.

Java packs the range with overflow checks, requires matching feature/export and
validates buffer/flag agreement before configuring the pass. Explicit native
SLANG + NRD can now be selected; AUTO remains gated on live acceptance. Host
buffer ownership and downstream denoiser lifetime remain under the existing
exact recording proof.

[Native CI 40842844371](https://github.com/bnfe12/bedray/actions/runs/40842844371)
passes on `42c737270da84e0d9052f04e0c3e584b4f907df2`: candidate core21 + scene1 +
lifetime1, baseline core15 + scene1 + lifetime1; ABI-v2 probes exit0. Tests cover
missing/short/invalid targets before commands, exact range binding, both scene
variants, inactive descriptor replacement, partial failure/discard and synchronous
graph forwarding. No C++ warnings reported.

Full Java suite passes: 787 tests, 778 passed, 9 skipped, zero failures/errors
(65s). The extra case verifies heap/native motion-range packing, both guards,
reserved words and multiplication overflow. A final equivalent host-condition
cleanup recompiles and passes the targeted guide/motion/packet checks (21s).
All 19 AOT entries retain their compiled contracts. No GPU/game, visual or
performance evidence; native remains opt-in.

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

## 2026-10-30 — Hot-loop and layout guards for the typed path (L1 + L2, no GPU)

Branch: `refactor/slang-pipeline`, commit `7d33541`.

Closes the last open holes left by the GLSL guard deletions (#81): the
primary-path hot-loop invariants and the std430 layouts that still have a
Slang consumer.

Runtime change (one, value-preserving): `surface/surface.slang` decodes with
the legacy `wantNormal` switch again. Both walks of `surface/visibility.slang`
and `pathtrace/path_tracer.slang`'s `primaryWaterMedium` pass `false`, so a
shadow or probe walk no longer pays for the atlas normal, the AO texel, the
entity auto-normal or the water wave normal. Every value those walks read
(material, geometric normal, tint) is produced on both paths, and a 5-arg
overload keeps the shaded callers byte-for-byte unchanged.

New guards, all mutation-proved (each mutation caught by the named test,
each file restored and verified by SHA-256):

- `RtSlangHotLoopTest` (7): one `decodeSurface` per accepted hit, one
  `SurfaceSample` local, emission added once, the primary guide record filled
  at the first accepted surface, `environment.radiance` only in the miss
  branch, an ambient-only bounce `break`ing at geometry, two key-light draws
  (reservoir and plain branches), the reservoir reset by
  `emitters.fingerprint()`, and the `wantNormal` gate structure.
- `RtSlangBlockLightReachTest` (7): `d^-1.5` → `0.84090 * d^-1.25` seam at
  two blocks, the 24-block squared window, the rsqrt form with no `pow`, the
  candidate gate before both atlas fetches, the hoisted
  `lightRadius = min(maxDistance, EMITTER_RADIUS)` in both batch entries, and
  the sqrt-ladder index PMF.
- `RtSlangLayoutAbiTest` (8): std430 parse of `FrameConstants` (608 B, every
  section offset), `PathTracePush` (112 B, 16 members, offsets 48/64/76/80/96),
  `DIReservoir` (64 B), `BlockEmitterRecord` (96 B = 24 floats),
  `FrameStatsPush` (8 B), the four counter words against the host's 16 B, and
  the legacy 128-byte push against `RtFrameParams`. The same-size
  `diState`/`entityState` swap is caught by `gradle/slang.gradle`'s build pin
  and, with that task skipped, by the unit test. `RtStatsLayoutTest` has no
  field successor on purpose: the AOT manifests prove `frame_stats.slang`
  binds one 4-byte `uint` per counter, and no pass imports the legacy
  `FrameStats`/`PushConstants` declarations (importers are
  `backend_query.slang` and `bedray_legacy.slang`, neither naming them), so
  the slot tail is host-only scratch.

The `wantNormal` restructuring moved the water call four columns right, so
`RtSlangWaterMediumTest.thePlayerSwitchesReachTheFrameWord` now matches the new
indentation; no assertion changed meaning. `RtStatsLayout`'s javadoc no longer
claims a full-struct mirror.

Evidence:

- L1: `:common:compileSlang` compiled all 27 passes with `slangc` 2026.20,
  `BUILD SUCCESSFUL`.
- L2: full `:common:test`, `910 tests, 0 failures, 0 errors, 6 skipped`
  (XML totals), `BUILD SUCCESSFUL` in 23 s.
- Nine mutation probes, all caught: entity gate, atlas gate, shaded overload,
  one visibility walk, `EMITTER_RADIUS`, sky branch, fingerprint, and the push
  reorder (build pin + unit test).

Still missing: an owner-verified frame. No jar from this commit has been
played yet; shader compiles and unit tests are not a runtime check.

Deployed for playtest (source-archive build of `7ddba13` + `libs/`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar`, sha256
`6183c04d1f2d4547a54767b7d5c660d9dedc2216dd6c1b469d82ec8b37fcf4e6`, to both
"Bedray Fabric 26.3" and "26.3(1)" (previous jar kept as
`*.20261030-090243.bak`). Natives are unchanged from the earlier live
`bedray_core.dll` build.

## 2026-10-30 — The refused-sample share reaches the F3 line (L0 source, L1 build, L2 unit)

Branch: `refactor/slang-pipeline`.

`passes/frame_stats.slang` meters one whole 4x4 tile in sixteen and adds only the
samples it trusts, so the share it refused was already implied by the slot's own
numbers - no host code read it. `RtStatsMetering` now mirrors the pass's tile walk
(edge tiles clipped, the rotation taken unsigned, which is the same modulo because
2^32 is divisible by sixteen), and `RtFrameRenderer` prints
`reject_pct = 100 * (metered - samples) / metered` on the `rt_pass` line. The value
is `n/a` when the slot carries no measurement, or when the sample count exceeds the
mask - which would mean the host's copy of the shader disagrees with the pass that
ran. The readback metadata now carries the extent and the frame index the pass
metered with (`params.frame`, the same word pushed to the counter pass).

Evidence:

- L1/L2: full `:common:test --rerun-tasks`, `913 tests, 0 failures, 0 errors,
  6 skipped` (XML totals), BUILD SUCCESSFUL in 52 s; all 27 Slang passes were
  compiled by `slangc` 2026.20 in the same run.
- `RtFrameStatsTest` (15, three new): the host mask equals a brute-force
  enumeration of the shader's own pixels for 1x1, 6x3, 256x128 and 63x65 across
  all sixteen rotations; the share is 3.125 % for 64 refused pixels of 2048 and
  `n/a` for zero metered, zero trusted or an over-counted slot; the renderer
  passes the slot's own extent/rotation and prints the field.
- Three mutation probes, all caught by the named guard: the edge-tile x-clip
  dropped, the frame rotation dropped, and the over-count refusal dropped. Every
  mutated file came back byte-identical (SHA-256 checked).

The ledger's stale rows were reconciled in the same batch: the Big Four table now
records the RC v2 field as integrated (five dependent dispatches, bindings 34/35,
192 MiB default budget, switch and axis properties) and ReSTIR PT as a quarantined
in-tree module rather than one outside the checkout; Next steps 1, 2, 4a and 5 say
what is actually closed and what still needs an owner-verified frame.

Still missing: an owner-verified frame, and per-reason counters (invalid versus
truncated, visibility overflow) which need a new binding/ABI in the C++ pass.

Deployed for playtest (source-archive build of `3fe438c` + `libs/`):
`bedray-fabric-mc26.3-0.2.0-alpha.jar`, sha256
`6af033afea19dcca8e07bf9926394570c05309ab43ed908832255a1e150971e6`, to both
"Bedray Fabric 26.3" and "26.3(1)" (previous jar kept as
`*.20261030-092850.bak`). Natives are unchanged from the earlier live
`bedray_core.dll` build. What a playtest should show: `rt_pass ... reject_pct`
next to `hit_pct` on the F3 line, `n/a` while the slot has no measurement.

## 2026-10-30 — Native frame-generation producer merged (L1 build, L2 unit)

Branch: `refactor/slang-pipeline`, merge commit `2825ec4` of
`origin/codex/migration-fg-producer` (`646aa9c`). The branch adds the producer-only
ABI `bedray_fg_prepare.h` (104-byte `BedrayFgPrepareFrameDesc`, static asserts at
8/24/32/48/80/96), `passes/fg_prepare.slang`, and `fg_prepare_pass.cpp`, which
validates the ABI and the program contract (8-byte push, four bindings:
combined-image-sampler depth at 0, storage-image motion at 1, storage-image depth
and motion outputs at 2/3), then records barrier -> flipped `vkCmdBlitImage` ->
the AOT dispatch -> barrier. No textual conflicts.

Evidence:

- L1 Slang: `fg_prepare: 1868 B SPIR-V, 4 binding(s), 8 B push constants, 8x8x1
  threads`, exactly the contract the C++ pass checks before dispatching.
- L1 native: Release x64 build of `bedray_core` succeeded; `bedray_core.dll`,
  `bedray_abi_probe.exe` and the ownership tests linked.
- L2 native: CTest `100% tests passed, 0 tests failed out of 25`, including the
  new `bedray_fg_prepare_pass_test` (expects the recorded events
  `b l d b`, one flipped blit and one dispatch between the barriers).
- L2 Java: full `:common:test --rerun-tasks`, `913 tests, 0 failures, 0 errors,
  6 skipped` (XML totals), BUILD SUCCESSFUL in 45 s. The baseline is unchanged by
  the merge.

Still missing: the host is not wired to the new producer. `FrameGenGpu` still
compiles `fg_prepare.comp` at runtime through Shaderc; the next increment replaces
that with `bedray_core_render_fg_prepare` and deletes the GLSL. No owner-verified
frame.

## 2026-10-30 — Frame generation records its producer through the native core (L0 source, L1 build, L2 unit + mutation)

Commit `ffaf760`. `FrameGenGpu` no longer compiles a shader. It borrows the
`fg_prepare` artifact from `SlangRuntimePool`, checks the manifest against the C++
contract before the first frame (8-byte push, 8x8x1 threads, four bindings with
combined-image-sampler depth at slot 0, storage images at 1/2/3), and calls
`bedray_core_render_fg_prepare` with a `BedrayFgPrepareFrameDesc` whose layout
`BedrayCoreLayouts.verify()` pins to the header's 104 bytes and static-assert
offsets. `FrameGenRuntime` and `Fsr3FrameGen` now stash the returned retire
handle and hand it to `encoder.queueForDestroy(retire::run)` after the fence;
record and `vkEndCommandBuffer` failures discard the recording instead. The
runtime GLSL `fg_prepare.comp` is deleted; without the DLL export or the AOT
artifact the constructor throws, the failure is logged and sticky, and frame
generation does not run — there is no Shaderc fallback. `ShadercSurfaceTest` now
expects `iris_depth.comp` as the only remaining resource shader.

Evidence:

- L1 Slang: `fg_prepare` compiled into the JAR (`shaders/slang/spv/fg_prepare.spv`,
  1868 B) with reflection, manifest and source alongside.
- L1 native: Release x64 `bedray_core.dll` rebuilt from `ffaf760`; the
  `bedray_core_render_fg_prepare` export is present in the PE export table.
- L2 Java: full `:common:test --rerun-tasks`, `919 tests, 0 failures, 0 errors,
  6 skipped` (XML totals), BUILD SUCCESSFUL. The new
  `FgPrepareNativeWiringTest` adds six tests: the host calls the core instead of
  Shaderc; the descriptor offsets are the header's; the program contract is
  checked before dispatch; the legacy shader stays deleted; both callers release
  after fence, discard when never submitted; and a behavioral test feeds real
  `SlangPassManifest` records (push 12, three bindings, wrong descriptor type,
  16x16x1) and expects each to be refused.
- L2 native: CTest `100% tests passed, 0 tests failed out of 25` (unchanged; the
  native pass is the same one merged in `2825ec4`).
- Mutation proof: removing the thread-grid check from `FrameGenGpu` turned
  `FgPrepareNativeWiringTest` red; the file was restored byte-identical
  (sha256 `9587100f1584...48a72e`).

Deployed for playtest (both "Bedray Fabric 26.3" and "26.3(1)", previous files
kept as `*.20261030-100946.bak`): JAR built from a `git archive ffaf760` snapshot
plus `libs/`, sha256
`067146f0aeabf04edce64aa1fdb56762786d23abf8ebac0fa26bdd6f23f3b660`; DLL
`bedray_core.dll` sha256
`8ed33b8021368f80e26d4b42d10bbb61cfa38b82042fbce035f0d4c0dd17ea07`. What a
playtest should show: FSR3/DLSS-G generating frames with upright motion vectors
and depth reaching the frame-generation pipeline; if the export or artifact is
missing the F3 line reports frame generation unavailable instead of a GLSL
fallback.

Still missing: an owner-verified generated frame, and the shader-side inputs for
the remaining Shaderc survivors (`iris_depth.comp`, the spatial upscaler kernels,
the Iris pack host).

## 2026-10-30 — The Iris depth converter is an AOT Slang pass (L0 source, L1 build, L2 unit + mutation)

Commit `a4cbc04`. `iris_depth.comp` — the last first-party GLSL in the resource
tree — is deleted. `passes/iris_depth.slang` reproduces the conversion exactly
(encoding 0 scale/bias, 1 window clamp, 2/3 linear ray distance, column-major
inverse view, `far/(far-near)` mapping, non-finite depth becomes 1) and
`IrisDepthConverter` now loads it with `SlangPass.load("iris_depth")`, checks
`requireManifest` (128-byte push, 8x8x1 threads, binding 0 combined `rasterDepth`
sampler, binding 1 `r32f` `irisDepth` storage image), runs the same
`SlangPassDriverGate` as core-registered passes, and builds the module from the
AOT words. The pipeline still belongs to the Iris bridge, which is host code, not
the renderer. No GLSL or Shaderc fallback: without the artifact the constructor
throws and depth conversion does not run.

Evidence:

- L1 Slang: `iris_depth: 4800 B SPIR-V, 2 binding(s), 128 B push constants,
  8x8x1 threads` (1200 words), exactly what the bridge writes and dispatches.
- L2 Java: full `:common:test --rerun-tasks`, `924 tests, 0 failures, 0 errors,
  6 skipped` (XML totals), BUILD SUCCESSFUL. The new `IrisDepthAotTest` covers the
  bridge loading the AOT pass instead of Shaderc, the deleted GLSL, the shader
  contract (push block, bindings, thread grid, every encoding branch, the
  column-vs-row matrix convention), the built artifact against `requireManifest`,
  and four refused manifests (120-byte push, 16x16 threads, wrong slot types).
  `ShadercSurfaceTest` now requires an empty `shaders/glsl/` tree.
- Mutation proof: deleting the thread-grid check from `requireManifest` turned
  `aManifestThatDisagreesWithTheBridgeIsRefused` red; the file was restored
  byte-identical (sha256 `31186b396a26...fdf17fb`).

Deployed for playtest (both "Bedray Fabric 26.3" and "26.3(1)", previous jar kept
as `*.20261030-104721.bak`): JAR built from a `git archive a4cbc04` snapshot plus
`libs/`, sha256
`a6ae897e0297ef50fc6a5fa639876971cb11c4f77c766f1038eac8f1c6caa7c0`, containing
`shaders/slang/spv/iris_depth.spv` (4800 B) and no `shaders/glsl/` entry. Natives
are unchanged; both instances still carry `bedray_core.dll` sha256
`8ed33b8021368f80e26d4b42d10bbb61cfa38b82042fbce035f0d4c0dd17ea07`. What a
playtest should show: Iris shader packs see the same converted depth as before
(fog, shadows and pack effects unchanged); a jar built without the Slang SDK logs
`iris_depth is not in this build` once instead of falling back to GLSL.

Still missing: an owner-verified frame, and the two Shaderc users that compile
third-party or user-supplied GLSL (the `shaders/spatial/` upscaler kernels and the
Iris pack host).

## 2026-10-30 — Render-graph docs corrected to the shipped state (L0, CTest re-run)

Four documents still said the native core neither constructs nor executes the
render graph. That stopped being true at `5fb4ad1` (2026-10-28):
`configure_path_trace` builds a `FrameGraphOwner`, the whole native path-trace
group is one opaque `path_trace.frame` node compiled from the reported
capability mask, `bedray_core_render_path_trace` records it through
`FrameScheduler`, and `bedray_core_release_recording` proves completion through
`release_proven`; configure, pass destroy and core destroy refuse outstanding
leases. `docs/SLANG_MIGRATION.md`,
`native/bedray_render_graph/README.md`, `RENDER_GRAPH_NOTES.md` and
`VULKAN_INTEGRATION.md` now say that, and keep the accurate parts: every other
pass records directly, the history bridge is linked but not driven, no GPU or
game frame through the graph has been captured, and the capability mapping lives
only in `FrameGraphOwner::plan_capabilities`.

Evidence: the Release x64 build directory that produced the deployed
`bedray_core.dll` re-ran CTest: `100% tests passed, 0 tests failed out of 25`,
including `bedray_frame_graph_owner` and `bedray_history_submission`. No native
source changed in this batch. No owner-verified frame.

## 2026-10-30 — ReSTIR PT: change of measure derived, implemented and CPU-validated (L1 slangc, L2 unit)

`experimental/restir_pt.slang` used to reuse a stored first edge as if the anchor
had not moved: the merge applied only the target ratio, so a candidate sampled at
one receiver was weighted by that receiver's solid-angle density. v3 derives the
correction for the fixed-first-vertex shift and restricts reuse to the class
where it holds. With `G(x) = |cos at the first vertex| / r^2`, the same vertex
sample has density `q_new = q_old * G_old / G_new` at the new anchor, so the
merge multiplies the target ratio by `J = G_new / G_old`
(`ptFirstEdgeShiftJacobian`), and the resolve normalisation by the target
density cancels — the target is a variance control only. Reuse is limited to a
certified diffuse first vertex (`RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST`); sky and
emitter first edges, delta and glossy first vertices are rejected because their
suffix radiance or density depends on the incoming direction. Duplicate
rejection now compares a stable `sampleId` instead of a first-vertex position.
The record grew only its first vertex, 48 to 52 B, filling existing tail
padding: the path stays 80 B and the reservoir 112 B.

Derivation, rejected classes, documented approximations (ratio caps 8/5, edge
cosine 1e-3, target cosine floor 0.05) and the remaining integration gates are
in `docs/RESTIR_GI_DERIVATION.md`. The module stays quarantined: no root
imports it, there is no pass or host, and no GPU run exists.

Evidence:

- L1 Slang: a temporary probe root (kept outside the repository) imports the
  module, instantiates `ptSpatialReuse`, `ptResolveFiltered` and `ptResolveVisible`
  and calls every other entry; the pinned `slangc` (2026.20) with
  `-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major` emits
  323,420 B of SPIR-V with no warnings.
- L2 Java: `RestirPtChangeOfMeasureTest`, four tests. Algebra on one candidate
  resolves to `1/q_new` within 1e-12 and is invariant to a 7.25x target scale.
  A two-million-sample Monte Carlo against a closed-form indirect integral
  (`0.6 * 0.5 * 0.5 = 0.15`) measures `0.149937 ± 0.000172` with the Jacobian
  and `0.170995` without it (the v2 merge is 14% high). Source guards pin the
  flag, both geometry functions, the merge ratio, the rejection lines, the
  `sampleId` test and the two reuse call sites.
- Full `:common:test` with the pinned slangc: `928 tests, 0 failures, 0 errors,
  6 skipped` (XML totals), BUILD SUCCESSFUL.
- Mutation proof: replacing the temporal reuse's `originDelta` with
  `float3(0.0)` (which would silently drop the change of measure whenever the
  camera moves) turned exactly
  `theShaderImplementsTheDerivedShiftJacobian()` red; the shader was restored
  byte-identical, sha256 `a7b51eef353f5f02c80145e83364fbc53eff957f0e68dff6779db02413896e12`.

No native build, CTest or deploy: no root imports the module and no runtime
behavior changed. No owner-verified frame.

## 2026-10-30 — ReSTIR PT v4: attempt accounting matches the reservoir normalisation (L1 slangc, L2 unit)

v3's change of measure was correct, but its reservoir was still biased by the
other half of the same theorem. `ptAddPath` returned early unless
`ptPathReusable` held, so a sky, emitter, delta or glossy first edge never
reached `M`, and `W = wSum / (M * p-hat)` normalised by the number of *recorded*
reusable hits instead of the number of attempts. That conditions the estimate on
a recording and scales it by `1/P(recorded)`. With `K` recorded attempts out of
`N`, each recording with probability `p`, the v3 estimator has expectation
`Pr(K > 0) * A / p = (1 - (1-p)^N) * A / p`, where `A` is the integral an
unbiased estimator targets. In the half-sky wall scene (`p = 1/2`, `A = 0.15`)
that is 0.225 at `N = 2` (+50%) and 0.28125 at `N = 4` (+87.5%); the isolation
model measured 0.224808 ± 0.000333 and 0.281228 ± 0.000539, against the
analytic 0.15 measured by the counting variant (0.149496 ± 0.000330).

v4 splits admission from reuse. `ptAddPath` now admits every `ptPathValid` path
with `first.pdf > 0`, which is what makes `M` the number of attempts; the
certified-class gate stays in `ptMergeReservoir`, `ptTemporalReuse` and
`ptSpatialReuse`, where a stored density is actually transported. A
non-contributing attempt cannot corrupt the reservoir: its target sits at the
`ptTargetPdf` floor (`max(bsdfPdf, 1e-8) * cos`) and a sky record carries
`bsdfPdf = 0`, so its weight is ~1e-8/q and its resolved contribution ~1e-8.
The same floor proves the merge reset
`if (!ptReservoirHasSample(current)) current = ptEmptyReservoir();` unreachable
for a legitimate accumulator: every admitted attempt adds a strictly positive
weight, so after any add `wSum > 0`. The reset is kept for empty and corrupt
records only, and the derivation now says so (`docs/RESTIR_GI_DERIVATION.md`
§6, sections 6-9 renumbered, README, `SLANG_MIGRATION.md` and the P1-7 audit
entry updated).

Evidence:

- L1 Slang: the same temporary probe root (outside the repository) imports the
  edited module and calls every entry, including `ptSpatialReuse`,
  `ptResolveFiltered` and `ptResolveVisible`; the pinned `slangc` (2026.20)
  with `-target spirv -profile cs_6_5 -entry main -matrix-layout-column-major`
  emits 323,028 B of SPIR-V with no warnings (v3: 323,420 B).
- L2 Java: `RestirPtReservoirInvariantTest`, four tests. Source guards pin the
  split (`ptAddPath` admits on `ptPathValid` + `first.pdf > 0` and never names
  `ptPathReusable`; the merge keeps it). A 300,000-trial comparison of two
  identical fresh reservoirs whose only difference is the admission rule puts
  the counting one within 4 standard errors and 1% of 0.15 and the v3-gated
  twin within 2% of `1.5 * 0.15`. Temporal reuse stays on 0.15 both transported
  into an empty reservoir and merged with the fresh one, and a deterministic
  merge checks `W_merged = W_source * J` exactly (1e-12) with `|J-1| > 0.05`.
- Full `:common:test` with the pinned slangc: `932 tests, 0 failures, 0 errors,
  6 skipped` (XML totals; the previous 928 plus the four new tests), BUILD
  SUCCESSFUL.
- Mutation proof: restoring the v3 gate
  (`if (!ptPathReusable(path)) return reservoir;`) in `ptAddPath` turned exactly
  `admissionCountsEveryAttemptWhileReuseStaysInTheMerge()` red and left the
  other seven ReSTIR PT tests green; the shader was restored byte-identical,
  sha256 `4c31aeede4968a07c8658d41adbad0d02151897b6cc3c5c5f5b4e8d323a461ef`.

No native build, CTest or deploy: no root imports the module and no runtime
behavior changed. No owner-verified frame.

## 2026-10-30 — ReSTIR PT temporal stage goes live behind `path_restir_pt` (L1 slangc, L2 unit + mutation, CTest; no owner-verified frame)

Branch `refactor/slang-pipeline`, commit `b2a563a` (35 files, +1366/−112).

What changed: the first ray of a pixel records its diffuse continuation's first
edge (`PATH_TRACE_OPTION_RESTIR_PT`, bit 17) and `restir/pt_temporal.slang` hands
it to a per-pixel path reservoir at binding 36. The reservoir owns exactly the
certified-diffuse, non-emitting first vertex; that suffix leaves the path and
returns as `f_d * cos * L * W`. Sky, emitter and uncertified first edges, the
other lobes and DI's NEE stay in the path. Every draw of an eligible receiver
counts in `M` (`ptAddEmptyAttempt`), and the merge reset now drops only a corrupt
accumulator. History is keyed on the reprojected camera-relative receiver, not on
`SurfaceHit.worldPosition` (P1-6). Derivation: `docs/RESTIR_GI_DERIVATION.md` §11.

A measured finding: binding the record exposed that its std430 layout is
144 B (first vertex 64, path 112), not the 112 B the module had claimed since v2.
The first compile with the new `gradle/slang.gradle` pin failed with
`'ptReservoirs' uses RestirPtReservoir of 144 B, the host contract is 112 B`; the
pin, the Java stride, the native stand-in, the docs and the settings text now all
say 144 B (597.2 MB at 1080p for the two halves).

Evidence:

- L1 Slang (`slangc` 2026.20, the AOT flags): `path_trace` 1,339,664 B /
  27 bindings, `path_trace_scene` 1,381,380 B / 27, `rc_path_trace_scene`
  1,674,148 B / 29, push 112 B, no warnings. Baseline before the batch:
  1,118,892 / 1,155,372 / 1,448,252 B (+20 %, mostly the filtered resolve walk).
  `OpUndef` scan: one float `OpUndef` per module, the same as the baseline; zero
  on handles or pointers.
- L2 Java: `RestirPtTemporalStageTest` (11 tests: the identity on 200,000 random
  cases to 1e-9 with the cap hits counted, attempt accounting through the merge,
  ownership in expectation over 400,000 frames, the ring size, the measured
  nested layout read from the reflection, and source guards) plus updated guards
  in `RtSlangHistoryAnchorTest`, `RtRestirSettingsTest`, `RtPathTraceSlangPassTest`,
  `SlangPassLibraryTest`, `RtSlangRcFieldTest`, `RtSlangDistantBlockLightTest`,
  `RtSlangNrdMotionTest`, `RtSlangSamplePerPixelTest`. Full
  `:common:test --rerun`: `944 tests, 0 failures, 0 errors, 3 skipped` (XML; the
  three skips are the CPU benchmark and the two real-pack surveys).
- Mutation proof (`--rerun`, each file restored and re-hashed):
  M1 drop the non-candidate `ptAddEmptyAttempt` → only
  `everyNonCandidateIsStillAnAttempt`; M2 restore the v4 merge reset → only
  `everyNonCandidateIsStillAnAttempt`; M3 keep the suffix in the path → only
  `theReservoirOwnsOnlyTheCertifiedDiffuseContinuation`; M4 drop `INV_PI` → only
  `theResolvedTermIsTheDiffuseLobeOfTheReceiver`; M5 let every ray record → only
  `theTracerRecordsTheFirstVertexBehindItsOwnOptionBit`; M6 give the sampler its
  own reflectance formula → only `theResolvedTermIsTheDiffuseLobeOfTheReceiver`;
  M7 admit uncertified first vertices → only
  `theReservoirOwnsOnlyTheCertifiedDiffuseContinuation`. Restored sha256:
  `pt_temporal.slang` d202ac3b…0d30ec2, `restir_pt.slang` 611608969b…727f60,
  `path_trace_entry.slang` 34180e8c…c8501d, `bsdf.slang` 8672deb0…81ca5c03 — all OK.
  A first run of the probes reported nothing because Gradle treated the test task
  as up to date (shader sources are not test inputs); the probes were re-run with
  `--rerun` and only those results are recorded here.
- Native: Release x64 `bedray_core` built in `native/bedray_core/build` (CTest
  `100% tests passed, 0 tests failed out of 21`) and in
  `build/bedray-native-readiness` (`... out of 24`). The new
  `test_restir_pt_binding_is_a_stand_in` checks the 27-binding program is
  accepted, a non-storage binding 36 is refused, 36 gets a 144-byte stand-in and
  bits 17..21 are cleared while bit 16 survives. Mutation: forwarding the flag
  word unmasked failed exactly `path_trace_pass_test.cpp:1248`; restored sha256
  `a0b87589…69739a2` OK.

Deployed for playtest (both "Bedray Fabric 26.3" and "26.3(1)", previous files kept
as `*.20261030-230121.bak`): JAR built from a `git archive b2a563a` snapshot plus
`libs/`, sha256 `466345476592f12e32e4fdf6ef9f2cf9a974c396613083d18bd2947ed90e9775`;
`bedray_core.dll` from `native/bedray_core/build/Release` (the tree equals
`b2a563a`), sha256 `f652ad75f72b1ffd0f273a3ecac8173ac13e6bea730c6bc85932d4b30df33be4`.
`bedray_scene.dll` is unchanged.

Not verified: no GPU smoke run (the game was not launched by the agent; another
instance was running), so neither the registration of the 27-binding programs in
`latest.log` nor the absence of a device fault with the switch on is proven. What
a playtest should show: with `path_restir_pt` off (default) the image and the
`path_slang(...)` log line are as before plus `restir_pt=off`; with it on (Path
Tracing, ReSTIR Direct Light on) the log reports `Slang ReSTIR PT reservoirs WxH x2
= N MiB` and `restir_pt=fresh` then `temporal`, the first frame after a reset looks
the same as with it off, and indirect light on diffuse walls/floors settles faster
while moving. No spatial stage yet. No owner-verified frame.

## 2026-10-31 — ReSTIR PT spatial stage pools neighbours into the next frame's history (L1 slangc, L2 unit + mutation; no owner-verified frame)

Branch `refactor/slang-pipeline`, commit `93aa115` (22 files, +879/−36).

What changed: two new passes, `restir_pt_spatial` (legacy section table) and
`restir_pt_spatial_scene` (native scene table), run after the trace on a frame
whose trace ran the ReSTIR PT temporal stage and with the new child row
`path_restir_pt_spatial` on (default on). They declare the trace pass's own
bindings by number (14: TLAS, sections, three atlases, frame, block materials,
six entity buffers, the ring at 36) and its 112-byte push block, so
`RtPathTraceSlangPass.recordPtSpatial` binds the trace's views and pushes the
same bytes. Each pixel reads its record and four neighbours' from the half the
trace wrote, merges them through the module's `ptSpatialReuse` and writes the
result into the other half; `RestirDiState.spatialPublished` counts that as a
write, so the next trace imports the pooled half. The pass shades nothing: the
next frame's temporal stage resolves a pooled winner with `ptResolveFiltered`
(one frame of lag, like the DI spatial pass). The temporal stage now stores the
receiver anchor in a record without a sample (`ptAnchorReceiver`). Derivation:
`docs/RESTIR_GI_DERIVATION.md` §12.

Evidence:

- L1 Slang (`slangc` 2026.20, the AOT flags): `restir_pt_spatial` 136,864 B /
  14 bindings, `restir_pt_spatial_scene` 142,452 B / 14, push 112 B;
  `path_trace` 1,347,712 B / 27, `path_trace_scene` 1,389,428 B / 27,
  `rc_path_trace_scene` 1,682,196 B / 29 (+8 KB each for the anchor write and the
  shared material-options function). `OpUndef` scan: one float per module, zero
  on handles or pointers; `SlangPassDriverGate.permits` is asserted for both new
  modules in the test.
- L2 Java: `RestirPtSpatialStageTest` (8 tests: the neighbour pattern is four
  distinct non-self pixels on 200,000 rotations under both half-way rounding
  conventions; read-one-half/write-the-other; a non-receiver publishes its own
  record; every neighbour goes through `ptSpatialReuse` and the stage resolves
  nothing; the temporal stage stores the receiver; both roots match the trace's
  bindings, types, element sizes and push block in the compiled manifests and
  pass `requirePtSpatialManifest`; the ring state keeps the pooled half; the
  backend runs the stage only behind the temporal one) and
  `RtRestirSettingsTest.pathSpatialReuseIsAChildOfPathReuse`; one updated string
  guard in `RtSlangEntityTest`. Full `:common:test --rerun`:
  `953 tests, 0 failures, 0 errors, 3 skipped` (XML).
- Mutation proof (`--rerun`, each file restored and re-hashed, all OK):
  S1 write the half that is read → only `theStageReadsTheTemporalHalfAndPublishesTheOther`;
  S2 drop `ptAnchorReceiver` → only `theTemporalStageStoresItsReceiverEvenWithoutASample`;
  S3 drop `pt.spatialPublished()` in the backend → only
  `theBackendRunsTheSpatialStageOnlyBehindTheTemporalOne`; S4 drop the `frame++`
  in `spatialPublished` → only `theRingCountsTheSpatialPublishAsAWrite`;
  S5 a non-receiver drops its record → `aPixelThatIsNoReceiverPublishesItsOwnRecord`
  and `theStageReadsTheTemporalHalfAndPublishesTheOther`; S6 the host promises 13
  bindings → only `bothRootsShareTheTraceBindingsAndPushBlock`; S7 the controller
  ignores the new switch → only `pathSpatialReuseIsAChildOfPathReuse`; S8 radius
  step 1.0 → only `theNeighbourPatternIsFourDistinctOtherPixelsForEveryRotation`.
- Native: no native source changed in this batch; CTest was not re-run.

Deployed for playtest (both "Bedray Fabric 26.3" and "26.3(1)", previous files kept
as `*.20261031-002047.bak`): JAR built from a `git archive 93aa115` snapshot plus
`libs/`, sha256 `00e6fd9a8311abbe737823937db3bd54eb1c99303df12c31aabfa022c816aedd`;
`bedray_core.dll` unchanged from the previous deploy, sha256
`f652ad75f72b1ffd0f273a3ecac8173ac13e6bea730c6bc85932d4b30df33be4`.

Not verified: no GPU run. Neither the registration of the two spatial programs
nor the absence of a device fault is proven. What a playtest should show: with
`path_restir_pt` on, `latest.log` has no `ReSTIR PT spatial stage off for ...`
line and the mode log reports `restir_pt=temporal+spatial`; turning
`path_restir_pt_spatial` off reports `restir_pt=temporal` and resets the history
once. With both on, indirect light on diffuse walls should settle faster than with
the temporal stage alone while the camera moves; a still frame cannot show it.
No owner-verified frame.

## 2026-10-31 — F3 splits rejects by reason and counts shadow-walk overflows (L1 slangc, L2 unit + mutation, CTest; no owner-verified frame)

Branch `refactor/slang-pipeline`, commit `0a90bb6` (21 files, +401/−34).

What changed: a new binding 37 (`outPathStats`) in `path_trace`,
`path_trace_scene` and `rc_path_trace_scene` is the frame's whole 576-byte stats
slot, bound at the slot's own 64-byte-aligned offset. The trace adds four words at
140..143 (`RtStatsLayout.PATH_REJECTS`, the last sixteen bytes of the slot):
refused pixels with a non-finite fault, pixels with a truncated path, pixels with
a shadow walk past its filter budget, and metered pixels, over the same rotated
1-in-16 tiles `frame_stats` meters. The tracer raises the new sample flag
`PATH_TRACE_FLAG_VISIBILITY_OVERFLOW` (bit 13) where the direct term's shadow walk
reports `SURFACE_VISIBILITY_FLAG_OVERFLOW`; the pixel mean keeps the union of every
sample's flags. The host zeroes the block per frame and prints `invalid_pct`,
`trunc_pct` and `vis_overflow_pct` on the `rt_pass` line, only when the metered word
equals `RtStatsMetering`'s own count (n/a otherwise). The native core accepts 37 as
an additive storage buffer and binds a one-word stand-in, which the shader refuses
to write, so the split reads n/a on that backend.

Evidence:

- L1 Slang (`slangc` 2026.20): `path_trace` 1,349,892 B / 28 bindings,
  `path_trace_scene` 1,391,608 B / 28, `rc_path_trace_scene` 1,684,376 B / 30,
  push 112 B; `OpUndef` one float per module, zero on handles or pointers.
- L2 Java: `RtPathRejectBreakdownTest` (7 tests: the block's offset and the shader's
  word index agree, the slot is filled exactly; the trace's tile walk is
  `frame_stats`' walk; each word counts what its name says; the tracer raises the
  overflow flag at all three direct-term call sites; every root declares 37 and
  the compiled manifest has a 4-byte storage buffer there; the host prints only a
  measurement its mask agrees with; the host binds the whole slot, zeroes the
  block, reads it back and prints it). Updated count/string guards in
  `SlangPassLibraryTest`, `RtSlangRcFieldTest`, `RtSlangNrdMotionTest`,
  `RtSlangEntityTest`. Full `:common:test --rerun`:
  `960 tests, 0 failures, 0 errors, 3 skipped` (XML).
- Mutation proof (`--rerun`, each file restored and re-hashed, all OK):
  R1 word index 136 → only `theBlockIsTheLastSixteenBytes…`; R2 drop the flag
  union → only `theFourWordsCountWhatTheirNamesSay`; R3 drop the overflow raise →
  only `theTracerRaisesTheOverflowFlag…`; R4 trust a metered word the mask
  disagrees with → only `theHostPrintsTheSplitOnlyFor…`; R5 drop the per-frame
  zeroing and R6 hand over only the 16-byte head → only
  `theHostBindsTheWholeSlot…`; R7 unrotated tiles → only
  `theTraceMetersTheTilesFrameStatsMeters`; R8 overflow counted as invalid → only
  `theFourWordsCountWhatTheirNamesSay`.
- Native: Release `bedray_core` in `native/bedray_core/build` (CTest
  `100% tests passed, 0 tests failed out of 21`) and `build/bedray-native-readiness`
  (`... out of 24`). New `test_path_stats_binding_is_a_stand_in`: a non-storage 37
  is refused, a 28-binding program is accepted and 37 gets a 4-byte stand-in.
  Mutation: dropping 37 from the resource count failed exactly
  `path_trace_pass_test.cpp:1275` and `:1277`; restored sha256
  `8cacde28…48f0764` OK, rebuilt and passing.

Deployed for playtest (both "Bedray Fabric 26.3" and "26.3(1)", previous files kept
as `*.20261031-005546.bak`): JAR from a `git archive 0a90bb6` snapshot plus `libs/`,
sha256 `096fd4d32152b1b6dbccd0dbc49863e4f43118ecd0b57c1be59edc058063e55a`;
`bedray_core.dll` from `native/bedray_core/build/Release` (tree equals `0a90bb6`),
sha256 `40ff573dde0d6f3040f50b56fb82998f0ebf277f6b7b23c694ea0c017018106e`.

Not verified: no GPU run. What a playtest should show: on the default (Java)
backend the F3 `rt_pass` line carries numbers for `invalid_pct`, `trunc_pct` and
`vis_overflow_pct` (not n/a), `invalid_pct` never above `reject_pct`, and
`vis_overflow_pct` above 0 only where a shadow ray crosses more than the walk's
filter budget of glass or leaf layers; with
`-Dvkbridge.rt.nativePathTrace=true` the three read n/a. No owner-verified frame.
