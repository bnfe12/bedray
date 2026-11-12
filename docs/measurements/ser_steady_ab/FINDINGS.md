# SER steady-state A/B — RD16, ReSTIR PT on, quiet focused window (2026-11-12)

Item 1 of the GI/perf plan: does NV Shader Execution Reordering (SER) pay for itself on the raygen path tracer in
**steady state**? This is the clean, exclusively-focused capture that `../ser_ab_rd16/FINDINGS.md` could not obtain
(that session's SER arm was invalidated by foreground contention). Measured on the RTX 5060 Ti (8 GiB), driver
1.4.357 / NVIDIA 621.14, native 1920x1080, render distance 16, DLSS RR denoiser, ReSTIR DI + PT + PT-spatial on,
adaptive quality off, nativeMemoryMiB 4096, fixed autotest world/pose (seed 1234567890123, -41.5/89/2.5, yaw 135
pitch 12). 10 s warmup + 30 s record per arm. **Same-session pair**, launched back-to-back by one bench invocation
(`raygen_pt_on` started 22:43:26 UTC, `ser_pt_on` 22:45:26 UTC), both `focus-hold refocused 0 time(s)` — the window
stayed focused for the whole record, so both arms pass the strict native gate (`valid_native_capture=true`).

## Result: SER is 2.14x SLOWER on the trace pass — measured negative

| metric | raygen (SER off) | raygen + SER | delta |
|---|---|---|---|
| pass program | `path_trace_scene.raygen` | `path_trace_scene.ser` | — |
| enabled mask | `0xe77` | `0x1e77` (+bit12 reorder) | — |
| ser_requested / hint | false / unavailable | true / **reorder** | SER engaged |
| **gpu_rt median** | **26.495 ms** | **56.680 ms** | **+30.185 ms (+113.9%, 2.14x)** |
| gpu_rt mean | 26.848 ms | 56.734 ms | +29.886 ms (+111.3%) |
| gpu_rt p95 | 28.370 ms | 58.055 ms | +29.684 ms (+104.6%) |
| gpu_total median | 27.168 ms | 57.357 ms | +30.189 ms (+111.1%) |
| rt_pass GPU EWMA median | 25.3 ms | 55.0 ms | +29.7 ms (+117.4%) |
| frame interval median | 33.493 ms | 58.208 ms | +24.715 ms (+73.8%) |
| average FPS | 29.69 | 17.16 | **−12.52 (−42.2%)** |
| 1% low FPS | 16.14 | 15.85 | −0.29 |
| frames / duration | 891 / 30.015 s | 515 / 30.004 s | — |
| reserved VRAM max | 2668.8 MiB | 2636.8 MiB | −32 MiB (noise) |
| used VRAM max | 2432.8 MiB | 2418.7 MiB | −14 MiB (noise) |
| tlas_ms (CPU prepareFrame) | 3.63 ms | 3.58 ms | ~same |
| denoiser active | dlss_rr | dlss_rr | same |
| native ABI | 3 | 3 | same |

Sources: `raygen_pt_on.json`, `ser_pt_on.json` (this directory). `gpu_rt`/`gpu_total` are GPU timestamp medians
excluding submit/present; `rt_pass GPU EWMA` is the independent in-log `[VKB-RT]` work-time estimator and corroborates
the timestamp delta (25.3 → 55.0 ms).

## Why this negative is robust (not a capture artifact)

- **Both arms are valid, focused, steady-state captures.** `valid_native_capture=true` for both; `focus-hold
  refocused 0`; the EWMA over ~64 whole-run samples (not just warmup) shows 55.0 ms for SER, so this is steady state,
  not shader-compilation warmup.
- **The raygen arm had the heavier scene, yet won decisively.** Vegetation quads max: raygen 26,538,729 vs SER
  20,755,084 (raygen +28%); sections 4283 vs 4276; TLAS instances 4284 vs 4277. SER is 2.14x slower *despite* raygen
  tracing more geometry — scene-content variance between the two ~2-min-apart runs cannot explain a +30 ms gap, and
  what variance exists biases **against** the winning arm.
- **Two independent GPU timers agree.** The capture timestamp median (26.50 → 56.68 ms) and the in-log rt_pass EWMA
  (25.3 → 55.0 ms) both show ~+30 ms / ~2.1x. Memory is unchanged, so this is pure trace-pass cost, not a budget spill.
- **SER genuinely engaged** (this is not a "SER silently off" non-result): device extension
  `VK_NV_ray_tracing_invocation_reorder (D)` enabled, `Enabling optional FeatureSet [Bedray NV Invocation Reorder]`,
  `ser_requested=true reordering_hint=reorder enabled=0x1e77`, SlangProgram `path_trace_scene.ser` registered and
  rendering. The slowdown is the cost of reordering on this workload, not a failure to enable.

## Interpretation (hypothesis, labeled as such)

SER reorders divergent ray invocations to recover hit-shader coherence. On this 2-bounce voxel path tracer at RD16 /
native 1080p the measured cost of the reorder pass + invocation overhead **exceeds** any coherence benefit, netting
~+30 ms on the trace pass. The workload is apparently coherent enough (or the fixed SER overhead on this driver/GPU
high enough) that reordering does not pay. This is a measurement, not a claim about SER in general: a different
scene (heavy incoherent foliage/glass, more bounces) could behave differently, but no such win was measured here.

## Disposition (item 1 closed)

- **SER stays OFF / opt-in (`bedray.rt.ser`). Do NOT default it on.** Measured 2.14x slower gpu_rt, −42% FPS on the
  quiet-window steady-state A/B. This supersedes the prior "SER perf unmeasured" status in `../ser_ab_rd16/` and
  `GI_STACK_REPORT.md`.
- **raygen (without SER) remains the recommended PT execution path** — it is the faster arm here and was already
  measured −7.8% gpu_rt vs compute RayQuery in `../ser_ab_rd16/`.
- No SER implementation or default change is warranted (plan rule: implement/default only on a measured win; this is
  a measured loss). The opt-in switch is left intact for owner experimentation on other scenes.
- **No 60 FPS guarantee is claimed.** `target_met_in_capture=false` for both arms (target 60 FPS; raygen ~30, SER ~17).
  The deliverable is the relative A/B delta on validated captures, not an absolute FPS target.

## Correction to prior capability-mask claim

`GI_STACK_REPORT.md` previously stated "ser=false is correct: mask `0x677` lacks bits 11/12". That was wrong: `0x677`
is the **base probe** mask; bits 11 (`CAP_RAY_TRACING_PIPELINE`) and 12 (`CAP_INVOCATION_REORDER_NV`) are
**conditionally enabled at startup** from `bedray.rt.raygen` / `bedray.rt.ser`. The live logs prove it: raygen
`enabled_mask=0xe77` (base + bit11), SER `enabled_mask=0x1e77` (+ bit12). SER is supported and engages on this device;
it is simply slower on this workload.