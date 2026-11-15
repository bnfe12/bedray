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

## Not measured / open

* One GPU, one scene (fixed seed, one camera pose); no frame-time percentiles beyond the capture analyzer's.
* No visual comparison was needed for the rejected candidate (geometry identical); nothing in the shipped renderer changed.
* Remaining cost sits in the RT trace (~47 % of GPU), DLSS RR (~25 %) and PT spatial (~15 %); no quality-neutral reduction of
  them has been measured yet.
