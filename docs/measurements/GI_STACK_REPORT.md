# Bedray GI / performance stack — final report (Этап 5)

**Date:** 2026-11-12 · **Branch:** `bedray-gi-stack-20261112` · **Head:** `0eca5d7b` (native core ABI 3)
**Device:** NVIDIA GeForce RTX 5060 Ti, 8 GiB (`memory_bytes=8274313216`), base probe mask `0x677`
(runtime `enabled_mask` `0xe77` raygen / `0x1e77` SER — bits 11/12 conditionally enabled at startup),
driver 1.4.357 / NVIDIA 621.14 · **Workload:** fixed-seed autotest world (seed 1234567890123, pose
-41.5/89/2.5, yaw 135 pitch 12), native 1920x1080, RD16 unless noted, BILINEAR request / no upscaler /
no frame generation, 10 s warmup + 30 s record.

## Goal and rules

Bring the cheapest GI/performance stack to a working state, Vulkan-only, clean and modular, with every
technology surfaced in settings. Hard rules honored throughout:

- **Vulkan-only.** Anything whose implementation needs DirectX/DX12 work is out of scope. (NRD is built
  SPIR-V-only; DLSS RR runs via NGX Vulkan; nothing in this report required D3D12.)
- **Nothing counts as working without L4** (effect proven in a real game run). **No FPS claim without a
  measurement.** Default off until numbers exist.
- **Additive public ABI only**; device features enabled only at startup before `vkCreateDevice`; ABI 2 and
  ABI 3 never mixed; explicit-path `git add` only; other sessions' worktrees untouched.

Verification levels: **L0** code · **L1** build · **L2** tests · **L3** game starts · **L4** effect in a real
run · **L5** owner confirmed. This report claims L4 where a live run proves it, and says so plainly where a
result is only L2 or unmeasured.

## Executive summary

| stage | deliverable | highest level | verdict |
|---|---|---|---|
| 0 — bench stand | capture orchestrator + controlled RD + baseline | **L4** | working; all later numbers come from it |
| BLAS compaction (#7/#15) | deferred compaction + transactional query slots | **L4** | RD16 ~1747 MiB reserved; RD32 ~3607 MiB **fits 4096 MiB**, RT stable |
| ReSTIR PT (#8) | 6 correctness gaps closed + Enhanced | **L4** | PT proven running; **+24.7% gpu_rt, +569 MiB** vs PT off |
| OMM 2-state (#9) | feasibility + integration design | L0/design | **deferred** — Remix disables OMM for terrain; benefit unproven |
| SER A/B (#10/#1) | compute vs raygen vs SER steady-state | **L4** | raygen **−7.8% gpu_rt** vs compute; SER steady-state **+114% gpu_rt (2.14x slower)** — keep OFF/opt-in |
| PTLAS (#11/#3) | regional update + TLAS-inclusive GPU span | **L2** | regional in-place update present; `world` span captured; isolated `tlas_ms` owner-gated; partitioned **deferred** |
| Settings UX (#12/#10) | auto-policy + texture styles + honest status row | **L4** (+L2 rows) | auto-policy + 3 texture presets `result=PASS`; `gi_execution_status` + `dlss_rr_preset` rows L2 (live read-back GPU-gated) |
| Denoiser (#13/#4/#6) | NRD specular + DLSS RR + ReLAX arm | **L4** (+L2) | both run in-game; NRD REBLUR **−18.4% gpu_rt** vs RR; RR preset + ReLAX wired, A/B GPU-gated |

**Bottom line:** the cheap stack works end-to-end on the live device. The measured wins are BLAS compaction
(memory, enables RD32), the raygen PT execution path (−7.8% GPU), and NRD REBLUR as the native-resolution
denoiser (−18% GPU vs RR). ReSTIR PT is a quality feature that costs +25% RT and +569 MiB. OMM and partitioned
PTLAS are deferred with evidence, not guessed. SER is wired and executing, but its quiet-window steady-state A/B
measured it **2.14x slower** on the trace pass (+114% gpu_rt, −42% FPS), so it stays OFF/opt-in. No FPS guarantee is
claimed beyond the measured medians below.

## 13-item closure map (honest L-levels)

Every marathon item is closed or dispositioned with evidence. **L4** = effect proven in a live run; **L2** = unit
tests pass; **L0/design** = feasibility/disposition only. "GPU-gated" = the remaining live capture needs a quiet,
exclusively-focused window (another session shares this GPU); it is **not** claimed as done.

| # | item | highest level | disposition / evidence | commit |
|---|---|---|---|---|
| 1 | SER steady-state A/B (quiet window) | **L4** | measured **2.14x slower gpu_rt**, keep OFF/opt-in; `ser_steady_ab/` | `8cb19831` |
| 2 | OMM 2-state measured gate | L0/design | **deferred** — no measured terrain win; Remix disables OMM for terrain; doc 13 | — |
| 3 | PTLAS GPU timestamps + gate | **L2** | TLAS-inclusive `world` span captured (`tlas_gpu_ms_upper_bound`) + blas regex fixed; isolated `tlas_ms` needs native split (owner-gated); partitioned deferred; doc 14 | `43eb618a` |
| 4 | DLSS RR preset D/E/F additive | **L2** | additive ABI wired, `dlss_rr_preset` row, default-off; **preset A/B GPU-gated** | `1cb48e32` |
| 5 | RR sharpness | disposition | **not wired** — NVIDIA RR API no-op (`vkb_dlss.h:240`); doc 15 | `e7fda225` |
| 6 | NRD ReBLUR/ReLAX tuning | **L2** | both wired, ReLAX selectable + bench arm added; no speculative tuning; **A/B GPU-gated**; doc 16 | `1cc5729b` |
| 7 | RC vs DDGI | disposition | RC retained; forbidden cache family out with reasons; doc 15 | `e7fda225` |
| 8 | Path guiding | L0/research | feasible Vulkan-only, greenfield, default off; implement only on measured win; doc 15 | `e7fda225` |
| 9 | Remaining-tech survey | L0/research | scheduling/memory + equal-cost sampling recommendations, no speculative code; doc 15 | `e7fda225` |
| 10 | Settings completeness audit | **L2** (+L4 auto-policy) | honest `gi_execution_status` row + `dlss_rr_preset` row + `SETTINGS_AUDIT.md`; auto-policy/texture L4 PASS (`9282366d`); **status-row live read-back GPU-gated** | `15b85347`, `6633de92` |
| 11 | L5 package (screenshots + repro) | repro done | `L5_REPRO.md` recipe written; **screenshots GPU-gated**; **L5 is the owner's act, not claimed** | `0eca5d7b` |
| 12 | Final report + index | this doc | report + `index.json` updated; honest L-levels; no FPS without measurement | (this commit) |
| 13 | Commit + push batches | done | explicit-path `git add`, verified remote, no force-push / `reset --hard` / merge-main | all above |

**Owner-only / GPU-gated remainder** (cannot be closed autonomously): L5 sign-off on every stage; before/after
screenshots; item 4 preset D/E/F A/B numbers; item 6 ReLAX-vs-ReBLUR-vs-RR A/B numbers; item 3 isolated `tlas_ms`
on block-edit/teleport (native ABI split, owner permission) ; any ABI-layout change. Reproduction recipe for all of
these: `docs/measurements/L5_REPRO.md`.

## Measured numbers (GPU timestamps; medians exclude submit/present)

All at RD16 native 1080p, ReSTIR DI on, DLSS RR denoiser unless the row says otherwise. `gpu_rt` is the ray
trace pass; `reserved` is peak native VRAM reserved.

| comparison | arm A | arm B | delta | source |
|---|---|---|---|---|
| ReSTIR PT off → on | gpu_rt 22.61 ms, 1747 MiB | gpu_rt 28.19 ms, 2317 MiB | **+5.58 ms (+24.7%), +569 MiB** | `restir_pt_rd16/` |
| compute → raygen (PT on) | gpu_rt 28.19 ms | gpu_rt 26.00 ms | **−2.19 ms (−7.8%)** | `ser_ab_rd16/` |
| raygen → raygen+SER (PT on, quiet focused, same session) | gpu_rt 26.50 ms | gpu_rt 56.68 ms | **+30.19 ms (+113.9%, 2.14x slower)** | `ser_steady_ab/` |
| DLSS RR → NRD REBLUR (PT on, same session) | gpu_rt 30.80 ms / total 31.54 ms | gpu_rt 25.14 ms / total 25.81 ms | **−5.66 ms (−18.4%)** | `denoiser_ab_rd16/` |
| BLAS compaction RD32 | — | reserved 3607 MiB / used 3464 MiB | **fits 4096 MiB budget**, RT stable | `compaction_rd32_clean/` |

Frame-interval medians sit at ~33–34 ms (~30 fps) across the RD16 arms: at RD16 the frame is denoiser/CPU-bound,
so GPU-time wins (raygen, NRD) do not fully reach the presented frame interval. This is why the report quotes GPU
timers, not FPS guarantees.

## Stage detail

### Этап 0 — measurement stand (L4)
`tools/automation/bench_gi_stack.py` + `capture_analysis.py`: launches the real instance, injects session
overrides through `instance.cfg` JvmArgs (never saved `bedray.toml`), runs one fixed-seed/fixed-camera
`native_perf` capture per variant, analyzes strict native-1080p acceptance plus medians, parses the log for
backend/memory/BLAS evidence, writes one JSON per variant. Controlled render distance keeps the scene inside the
native budget. **Every number in this report comes from this stand.** Without it, nothing downstream was attempted.

### BLAS compaction + query-slot ownership (#7/#15, L4)
Deferred compaction for static sections (`3482169e`) plus transactional compaction query-slot ownership
(`52e32426`, fixes #15). RD16 reserved ~1747 MiB (compaction saved ~352 MiB vs the uncompacted arm, `bf683c78`);
RD32 reserved ~3607 MiB **fits the 4096 MiB budget** with RT stable (`21c421c8`). This is what makes RD32 viable
on the 8 GiB card. Both captures pass strict native acceptance (`valid_native_capture=true`).

### ReSTIR PT (#8, L4)
Six correctness gaps closed and Enhanced enabled (prior work: DI pulsing/pooling `265ac2e8`, spatial DI p^-only
`8a0ce8f1`, hue-preserving indirect cap `6a9211c3`, eye-visible emission `9b02a42f`, trace-cost cuts `36828285`,
emitter reuse `ed7ca304`). The bench fix `1e0b65e3` keeps ReSTIR DI on so the PT arms actually run (PT is a child
of DI). L4 proof `328585ea`: PT runs in-game, **+24.7% gpu_rt and +569 MiB** (the PT ring is 2×w×h×144 B = 569 MiB
at 1080p). PT is a quality feature, not a perf win — the auto-policy gates it by budget.

### OMM 2-state (#9, deferred — design only)
`docs/ai-agent-addenda/13-omm-feasibility-design.md`. Feasibility verified (LWJGL 3.3.3 ships the EXT bindings;
core already detects `BEDRAY_CAP_OPACITY_MICROMAP` bit 7), full additive integration designed, **implementation
deferred**. Evidence: Minecraft Remix explicitly disables OMM for high-volume terrain; the RD16 scene is 43%
cutout and the software candidate-alpha loop already handles it correctly; position-only BLAS vertices mean OMM
baking needs a large additive ABI change for a likely-neutral win. Off until a foliage/glass scene measures a win.

### SER A/B (#10/#1, L4 — steady-state negative measured)
`docs/measurements/ser_ab_rd16/FINDINGS.md` (compute vs raygen) + `docs/measurements/ser_steady_ab/FINDINGS.md`
(quiet-window SER steady state). The raygen pipeline traces **−7.8% gpu_rt** vs compute RayQuery (26.00 vs 28.19 ms)
and ~2.3 ms less CPU; presented FPS unchanged (denoiser/CPU-bound). **SER engagement is proven at L4**
(`enabled=0x1e77`, `VK_NV_ray_tracing_invocation_reorder` enabled, `path_trace_scene.ser` rendering). The earlier
SER perf capture was blocked by foreground contention; a **quiet, exclusively-focused window** has now produced a
validated same-session steady-state pair (both `valid_native_capture=true`, `focus-hold refocused 0`): SER is
**2.14x slower** on the trace pass — gpu_rt median **26.50 → 56.68 ms (+113.9%)**, frame interval 33.49 → 58.21 ms,
average FPS **29.69 → 17.16 (−42%)**. The independent in-log rt_pass EWMA agrees (25.3 → 55.0 ms); memory unchanged;
the raygen arm had *more* vegetation geometry yet won, so scene variance cannot explain the gap. **SER stays OFF /
opt-in (`bedray.rt.ser`); do not default it on.** raygen (without SER) is the recommended PT execution path. No 60 FPS
guarantee is claimed (`target_met=false` for both arms).

### PTLAS / regional TLAS (#11, regional present; partitioned deferred)
`docs/ai-agent-addenda/14-ptlas-feasibility-design.md`. The cheap, compatible part is **already done**: idle-skip,
in-place UPDATE when membership is unchanged, stable `instanceCustomIndex`, host dirty events. Full partitioned
TLAS (`VK_NV_partitioned_acceleration_structure`, NV-only; LWJGL 3.3.3 lacks bindings) is **deferred**: TLAS is not
the measured bottleneck (the trace pass ~24 ms dominates a ~33.6 ms frame; idle frames already skip TLAS work).
**Measurement gate (item 3, `43eb618a`):** a TLAS-*inclusive* GPU timestamp already exists — the `world` span
brackets `RtWorld.update()` (BLAS+TLAS+entity builds, `RtFrameRenderer.java:707-713`); the bench now captures it as
`tlas_gpu_ms_upper_bound` (e.g. `ser_steady_ab` world ≈ 3.63 ms), and a stale BLAS log regex that silently dropped
`veg_sections`/`veg_quads` on current-format logs was fixed (regression-tested for both formats). A TLAS-*isolated*
`tlas_ms` still needs a native `prepareFrame` split (additive scene ABI change, **owner-gated**) plus a
block-edit/teleport scenario the static `native_perf` bench does not yet drive. Partitioned TLAS stays deferred
until that isolated stamp shows TLAS is a measurable frame fraction on edit/teleport scenes.

### Settings UX (#12, L4)
GI-stack auto-policy engine (`1aead078`, memory budget + feature gates) surfaced as an "Auto-select GI quality"
action (`cccafa66`); one-click texture styles Pixel-Sharp/Balanced/Soft (`83ef186c`); `gi_settings` autotest
(`ce9e86b8`). **L4 proof `9282366d`** (`gi_settings_l4/`): on the live device the auto-policy returned BALANCED
(spp=1 bounces=3 dist=512 di=true pt=true ser=false, "Fits the 1972 MiB reservoir budget"), the staged read-back
matched exactly (profile=CUSTOM), and all three texture presets staged correctly. A registration-gating bug found
at L4 (texture_lod_bias_offset was expert-only, so presets staged into a null option for normal users) was fixed
(`81c06ffa`) with a regression test; L2 39 tests pass. The auto-policy returned `ser=false`, which is the correct
default given the measured SER negative (`ser_steady_ab/`). (Earlier justification "mask `0x677` lacks bits 11/12"
was wrong: `0x677` is the base probe; bits 11/12 are conditionally enabled at startup, and SER does engage when
requested — `enabled_mask=0x1e77`. It is off by choice/measurement, not by missing capability.)

**Honest execution-tech status row (item 10, `15b85347` + `6633de92`, L2).** `gi_execution_status` reports each
technology's supported/enabled/implemented state from the **live enabled mask** (`GiDeviceProbe.probe()`), not
physical support: raygen note "Measured −7.8% gpu_rt vs compute RayQuery"; SER note "Measured 2.14x slower gpu_rt
(quiet steady-state A/B); off by default. Opt-in: `-Dbedray.rt.ser=true`"; OMM supported=micromap-capable /
enabled=false / implemented=false; PTLAS note "Regional in-place TLAS update is active". The mask framing was
corrected and regression-tested: `0x677` is the compute-path live enabled mask with bits 11/12 absent (raygen/SER
not requested), so on that path SER/raygen report supported=false / enabled=false / implemented=true — the code
exists, only the requested feature bit is absent. `GiExecutionStatusTest` 9/9. **L4 live read-back of this row and
of `dlss_rr_preset` is GPU-gated** (quiet window pending). The `dlss_rr_preset` row (item 4) is ADVANCED, gated on
DLSS RR; see the Denoiser section.

### Denoiser — NRD specular + DLSS RR (#13, L4)
`docs/measurements/denoiser_ab_rd16/FINDINGS.md`. **NRD specular is integrated end-to-end and Vulkan-only**
(`vkb_nrd.cpp:256-258` REBLUR/RELAX_DIFFUSE_SPECULAR denoise both lobes in one instance; SPIR-V-only NRD.dll,
`NRD_EMBEDS_DXIL/DXBC=OFF`). **DLSS RR core is integrated via NGX Vulkan**; tuning is partial. Both ran in-game
(`active=nrd_reblur` / `dlss_rr`, `result=PASS`, denoiser column stable → no fallback). Same-session A/B: NRD REBLUR
**−18.4% gpu_rt** vs RR in denoise-only mode at native 1080p (RR's advantage is when upscaling, not measured here).
Two RR tuning knobs dispositioned: **sharpness** is an NVIDIA API no-op for RR (`vkb_dlss.h:240` reserved 0.0) — not
a bug, do not wire; **preset D/E/F** is now **wired additively** (item 4): `VkbDlssRrCreateInfo.preset` repurposes
former `reserved[0]` @40 (sizeof 48 preserved), validated to 0/4/5/6, and sets the NGX
`RayReconstruction.Hint.Render.Preset.<Mode>` param only when non-default; surfaced as `dlss_rr_preset` (ADVANCED,
gated on DLSS RR). **Default = 0 leaves the NGX param untouched (exact prior behavior); no preset A/B captured yet,
so it stays default-off until numbers.** The denoiser choice is already a setting (`path_denoiser`, ADVANCED).

**NRD ReBLUR/ReLAX tuning disposition (item 6, `1cc5729b`, L2 + doc 16).** Both NRD variants are fully wired:
`vkb_nrd.cpp:687-704` applies default `ReblurSettings`/`RelaxSettings` plus `configure_primary_lobe_signals` for
**both** lobes; the Java side (`NrdDenoiser.java:188-191`) overrides REBLUR only. ReLAX is selectable today via
`path_denoiser=nrd` + the expert override `-Dvkrtx.denoiser=nrd_relax` (`DenoiserResolution` case NRD → `NRD_RELAX`,
unit-tested in `DenoiserResolutionTest`). The bench gained a `denoiser_nrd_relax` arm so the three-way
RR-vs-ReBLUR-vs-ReLAX A/B can run (`DENOISER_AB`, `test_bench_gi_stack` 9/9). `NrdReblurParams.defaults()` is
hardcoded and shared with `rt_pack_nrd.comp`; injectable knobs are `-Dvkrtx.denoiser/.range/.view/.nrd.projFlipY`.
**No speculative parameter tuning was done** — REBLUR stays the measured −18.4% default; any param change is gated
behind a same-session, GPU-timestamped A/B (mode A/B + range sweep). Full reasoning:
`docs/ai-agent-addenda/16-nrd-tuning-disposition.md`. **The ReLAX A/B numbers are GPU-gated** (quiet window pending).

## Recommended cheapest stack (this hardware)

- **Execution:** raygen PT pipeline (measured −7.8% GPU vs compute). SER opt-in, **off by default (measured 2.14x
  slower gpu_rt in the quiet-window steady-state A/B, `ser_steady_ab/`)**.
- **Denoiser:** NRD REBLUR at native resolution (measured −18% GPU vs RR); DLSS RR when upscaling low→high.
- **GI quality:** ReSTIR DI on; PT gated by the auto-policy budget (PT adds +25% RT / +569 MiB — a quality tier,
  not the cheap default). The `Auto-select GI quality` action picks the tier from device memory + features.
- **Memory:** BLAS compaction on (enables RD32 within 4096 MiB).
- **Off / deferred:** OMM (no measured terrain win), partitioned PTLAS (TLAS not the bottleneck), SER (measured 2.14x
  slower — off/opt-in), RR preset D/E/F (wired additively, default-off pending A/B).

## Honest limitations

- **FOCUSED artifact:** the strict native acceptance gate requires the window focused for the whole capture. On a
  contested desktop the `FOCUSED` flag (bit 6) drops after settle, so several captures report
  `valid_native_capture=false` even though every rendering-validity flag holds and the denoiser/quality are stable.
  The GPU-timestamp medians remain sound indicative evidence (focus does not change rendering cost); they are **not**
  a validated FPS guarantee. The compaction and PT captures did pass the strict gate.
- **Cross-session variance:** the same DLSS_RR+PT config measured gpu_rt 28.19 ms in the PT bench and 30.80 ms in the
  denoiser bench (different sessions). This is why the denoiser A/B was run as a **same-session pair**; cross-run
  deltas are not compared.
- **SER perf measured negative** (2.14x slower, off/opt-in), OMM/PTLAS partitioned **not implemented**, RR preset
  **wired additively but default-off pending A/B** — all dispositioned with explicit measurement gates, not silently
  skipped.
- **GPU-gated captures pending** (another session shares this GPU; the strict gate needs a quiet, exclusively-focused
  window): item 4 RR preset D/E/F A/B, item 6 ReLAX-vs-ReBLUR-vs-RR A/B, item 10 `gi_execution_status`/`dlss_rr_preset`
  live read-back, item 3 isolated `tlas_ms` on block-edit/teleport (also needs an owner-gated native ABI split), and
  item 11 before/after screenshots. The bench arms and settings rows are wired and L2-tested; only the live numbers
  are outstanding. None is claimed as measured.
- **L5 (owner confirmation) is pending** for every stage; this report establishes L4, not owner sign-off. The
  reproduction recipe (deploy, bench commands, screenshot method) is `docs/measurements/L5_REPRO.md`; L5 itself is
  the owner's act and is not faked here.

## Dispositions and research (items 5 / 7 / 8 / 9)

Full evidence and reasoning: `docs/ai-agent-addenda/15-gi-stack-dispositions.md`. Summary:

- **RR sharpness (item 5) — not wired, by design.** `vkb_dlss.h:240` marks the RR eval `sharpness`
  field `reserved for API consistency (0.0)`; NVIDIA RR has no sharpness input, so a slider would be a
  no-op control (forbidden by the settings honesty rule). If sharpening after RR is ever wanted, the
  only legitimate route is a separate CAS/RCAS post-pass (the kernel already used for `fsr_sharpness`),
  gated behind its own A/B — not folded into the RR API. No measured need exists; nothing implemented.
- **RC vs DDGI (item 7) — RC retained; forbidden family out.** RC is the working finite camera-centred
  irradiance/π field (`IPathDiffuseField`). DDGI/NRC/SHaRC/CLAS/Mega Geometry/Brixelizer GI/surfel GI are
  owner-forbidden and not implemented; the addendum records the engineering reason for each (vendor
  lock-in, irradiance-vs-radiance contract mismatch, greenfield memory/lifetime surface). RC stays the
  default indirect estimator; ReSTIR DI/PT is the sampling layer on top.
- **Path guiding (item 8) — research only, default off.** Feasible Vulkan-only (Slang compute vMF/tree
  guide + learning pass + MIS), but the current RC has no directional proposal PDF, so it is greenfield;
  benefit unproven on mostly-diffuse voxel lighting. Implement only on a measured net win at equal
  converged-reference error. Not implemented.
- **Remaining-tech survey (item 9) — recommendations, no code.** Next perf effort should go to
  scheduling/memory (async-compute overlap, FP16/packed reservoirs, descriptor indexing) and
  equal-cost sampling quality (blue noise, NRD tuning), each behind a same-session A/B — not a new GI
  cache. Forbidden family excluded; work graphs / mesh shaders / Split RC explicitly not prioritized.

These four items are **closed as dispositions/research** (L0/design + literature), not as measured
features. No FPS or perf claim is made for any of them.

## Measurements index and commit map

Machine-readable index: `docs/measurements/index.json`. Per-run records and findings:

- `restir_pt_rd16/` — PT off/on (`328585ea`, bench fix `1e0b65e3`)
- `compaction_rd16/`, `compaction_rd32_clean/` — BLAS compaction (`bf683c78`, `21c421c8`; ownership `52e32426`)
- `ser_ab_rd16/FINDINGS.md` — compute/raygen/SER (`3ef525fa`)
- `ser_steady_ab/FINDINGS.md` — quiet-window SER steady-state A/B: **2.14x slower gpu_rt**, keep OFF/opt-in (item 1)
- `gi_settings_l4/` — auto-policy + texture presets L4 (`9282366d`; fix `81c06ffa`)
- `denoiser_ab_rd16/FINDINGS.md` — NRD vs RR + audit (`e36be207`); ReLAX arm added to the bench (`1cc5729b`, capture GPU-gated)
- `L5_REPRO.md` — owner reproduction recipe: deploy + bench commands + screenshot method (`0eca5d7b`); screenshots GPU-gated, L5 owner-only
- Design notes: `docs/ai-agent-addenda/13-omm-feasibility-design.md`, `14-ptlas-feasibility-design.md` (world-span correction `43eb618a`), `15-gi-stack-dispositions.md`, `16-nrd-tuning-disposition.md` (`1cc5729b`)

Bench stand: `1d26b616`, `aceb7707`, `5fff1fe3`; TLAS-inclusive world span + blas regex fix `43eb618a`; ReLAX A/B arm `1cc5729b`.
Settings engine: `1aead078`, `cccafa66`, `83ef186c`, `ce9e86b8`; honest execution-tech status row `15b85347`, `6633de92`; additive RR preset `1cb48e32`.