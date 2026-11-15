# Frame interval of every earlier native_perf capture was capped by vanilla's AFK limiter

**Date:** 2026-11-15 · **Base:** `6573867c` (origin/main) + harness fix · **Device:** NVIDIA GeForce RTX 5060 Ti 8 GiB,
native 1920x1080, RD16, DLSS RR, ReSTIR DI + PT on, bilinear/no upscaler, no frame generation, VSync off, `maxFps` 260.

## What was wrong

Every capture in `docs/measurements/` reports a median frame interval of about 33.3 ms (30 FPS) even when the GPU total is
17-24 ms. Cause: the Minecraft options of the test instance carry `inactivityFpsLimit:"afk"`; vanilla's
`com.mojang.blaze3d.platform.FramerateLimitTracker` (javap of the 26.3 client jar) returns `AFK_LIMIT = 30` once
`Util.getMillis() - latestInputTime > AFK_THRESHOLD_MS = 60000` (and 10 FPS after 600000 ms). The autotest harness never
sends input, so after about a minute the game was capped at 30 FPS. In the first capture of this investigation the first
~3 s ran at 26 ms and the remaining 27 s sat at exactly 33.4 ms, independent of GPU time.

Consequences for older numbers: GPU-time medians (`gpu_total`, `gpu_rt`, spans) are unaffected. Every statement derived from the
**frame interval or FPS** is not: "the frame is denoiser/CPU-bound at ~30 fps" in `GI_STACK_REPORT.md` and the `target_met_in_capture`
and 1%-low fields of the older JSONs describe the limiter, not the renderer.

## Fix

`AutotestHarness.onRenderFramePost` reports input to `Minecraft.getFramerateLimitTracker().onInputReceived()` every frame (the
harness is the scripted player). `native_perf` now records `effective_frame_limit`, `throttle_reason` and `inactivity_fps_limit`
in the manifest and the `capture_armed` log line.

Proof (L4, real runs, `latest.log` of each run): `capture_armed ... effective_frame_limit=260 throttle_reason=NONE`; frame
interval median 25.6-27.9 ms instead of 33.3 ms.

## Corrected baseline (same setup, 4 captures, 1075-1170 frames each, all `valid_native_capture=true`)

| run | interval median | gpu_total median | trace | pt_spatial | denoise (RR) |
|---|---|---|---|---|---|
| A1 | 27.85 ms | 26.06 ms | 12.78 | 3.73 | 6.58 |
| B2 | 27.62 ms | 25.84 ms | 12.71 | 3.75 | 6.54 |
| B3 | 25.62 ms | 24.22 ms | 11.65 | 3.65 | 6.17 |
| A4 | 25.58 ms | 24.13 ms | 11.70 | 3.57 | 6.17 |

Real throughput is 36-39 FPS (not 30), and frame interval exceeds GPU total by about 1.4-1.8 ms.
GPU time inside the RT pass: trace ~11.7-12.8 ms, ReSTIR PT spatial ~3.6 ms, DLSS RR denoise ~6.2-6.6 ms, atmosphere ~0.5 ms,
world/TLAS ~0.4 ms. Between the first and the last run the whole frame got about 1.9 ms faster with identical settings, so a
single run per arm cannot resolve differences below ~2 ms; use ABBA ordering.

## Candidate tested and rejected: `PREFER_FAST_TRACE` on static section BLAS

Native scene BLAS builds carried only `ALLOW_UPDATE`/`ALLOW_COMPACTION`. Adding `PREFER_FAST_TRACE` to the never-refitted
section BLAS (size query and build through one helper, env switch for A/B on one DLL) measured in ABBA order
(A = flag off, B = flag on): A mean gpu_total 25.10 ms, B mean 25.03 ms (-0.07 ms, -0.3 %), trace 12.24 vs 12.18 ms. That is
inside the run-to-run drift, so there is no measurable gain on this GPU/scene and the change was **not kept**.

## Candidate tested and rejected: slangc `-O3` (default is `-O1`)

All shaders rebuilt with `-O3` (smaller SPIR-V, jar 18.7 vs 19.5 MB). ABBA: gpu_total 25.09 ms (-O1) vs 25.70 ms (-O3), +2.4 %,
trace 12.50 vs 12.24 ms (-2.1 %). No gain; not kept.

## Lead with measurable potential: cutout holes restart the BVH traversal

`traceTriangleIntersection` (traversal/triangle_query.slang) commits every non-opaque candidate without an alpha test; the
surface layer (pathtrace/path_tracer.slang, the `surfaceIsHole` branch of the surface-step loop) then decides that the texel is a
hole and the ray is **traced again from the TLAS root** just past the candidate. The fixed-seed scene has about 43 % cutout
geometry (veg_quads ~17-18 M).

Upper bound, measured: a throw-away build with `alphaCutoff = 0` (no texel is ever a hole; the image is wrong and the build is
not kept) against the normal build, ABBA, background captures, 4 valid runs:

| | normal | no holes | delta |
|---|---|---|---|
| gpu_total | 23.99 ms | 21.67 ms | -2.33 ms (-9.7 %) |
| trace | 11.57 ms | 9.92 ms | -14.3 % |
| pt_spatial | 3.53 ms | 3.09 ms | -12.6 % |
| frame interval | 25.64 ms | 23.46 ms | -8.5 % |

This is a ceiling, not an expectation: the no-hole build also removes the shading of every transparent texel and ends paths
earlier (leaves become solid). A real fix (alpha test of the candidate inside the ray-query loop, committing only non-hole
triangles; or opacity micromaps) keeps the alpha fetch and saves only the restarts. It needs the atlas and the block-material
rules inside the traversal module, and must reproduce `surfaceIsHole` exactly (the restart also skips `pathTraceHoleStep` behind
a hole, and hole steps count against `maxSurfaceSteps`). Not implemented; any implementation needs a same-seed image comparison
with the denoiser before it can be called quality-neutral.

## Implemented and rejected: cutout-hole pre-filter in the candidate loop

The lead above was implemented: `SceneTraversal` rejected, inside the nearest-hit ray-query loop, exactly the terrain candidates
the surface layer calls holes (material mode Cutout from the range pass or the vegetation tag, not Translucent, and atlas alpha
at the interpolated UV, level 0, below `pc.alphaCutoff`), so a ray no longer committed the hole and restarted from the TLAS root.
Enabled in the path-trace passes and ReSTIR PT spatial, off for the RC passes; shaders compiled with unchanged binding counts,
`:core:test` and `:common:test` passed.

Measured ABBA against the same commit without it, background captures, DLSS RR, PT on (3 valid of 4; the invalid one was
still used only as a spread check):

| | base | pre-filter | delta |
|---|---|---|---|
| gpu_total | 24.54 ms | 24.22 ms | -0.32 ms (-1.3 %) |
| trace | 11.84 ms | 11.61 ms | -1.9 % |
| pt_spatial | 3.78 ms | 3.59 ms | -5.0 % |
| frame interval | 26.02 ms | 25.81 ms | -0.8 % |

The spread between runs of the same arm is about 0.5 ms, so this is at noise level, far below the -9.7 % ceiling: most of the
ceiling came from the no-hole build removing the shading of transparent texels and ending paths early, not from avoided
restarts. The change was **not kept** and no image comparison was done (it only matters for a change that is worth keeping).

## Not measured / open

* One GPU, one scene (fixed seed, one camera pose); no frame-time percentiles beyond the capture analyzer's.
* No visual comparison was needed for the rejected candidate (geometry identical); nothing in the shipped renderer changed.
* Remaining cost sits in the RT trace (~47 % of GPU), DLSS RR (~25 %) and PT spatial (~15 %); no quality-neutral reduction of
  them has been measured yet (the cutout pre-filter above measured -1.3 %, noise level).
* Captures from `bench_gi_stack --background` waive the focus requirement (`focus_waived=true`); one background capture matched
  the focused runs of the same build (gpu_total 24.3 ms vs 24.1-26.1 ms).
