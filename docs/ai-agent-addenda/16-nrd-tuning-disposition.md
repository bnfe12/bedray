# 16 — NRD ReBLUR / ReLAX tuning: disposition + measurement gate

Status (2026-11-12): **both denoisers wired end-to-end and A/B-selectable; params at defaults; no tuning
applied without a measurement.** Deliverable for catalog step "NRD ReBLUR/ReLAX tuning" (item 6). Every
claim below is a verified file:line fact; the tuning itself is gated on a same-session GPU A/B (L4).

Hard rules honored: nothing is "working" without L4; default off / defaults unchanged until numbers
exist; no speculative param knobs were added; the public ABI is untouched.

## Verdict

NRD REBLUR is the landed, measured default (**−18.4% gpu_rt**, `GI_STACK_REPORT.md`). NRD RELAX is fully
wired and selectable but **unmeasured on this workload**. Tuning either denoiser's parameters is allowed
**only** through an A/B capture; no parameter has been changed speculatively. The ReLAX arm was added to
the bench this task so the three-way denoiser A/B (RR / REBLUR / RELAX) can run in a quiet GPU window.

## Verified facts (zero-trust, file:line)

* **Both modes exist and parse.** `rt/denoise/DenoiserMode.java:11-12` `NRD_REBLUR("nrd_reblur",1)` /
  `NRD_RELAX("nrd_relax",2)`; `parse()` accepts `nrd_relax`/`relax` (`:61`); `isNrd()` covers both (`:29`).
* **Native applies default settings + primary-lobe config for BOTH.** `native/nrd_bridge/src/vkb_nrd.cpp`
  `createDenoiser`: REBLUR branch `:687-694` (`nrd::ReblurSettings` + `configure_primary_lobe_signals` +
  `SetDenoiserSettings(REBLUR_DIFFUSE_SPECULAR)`), RELAX branch `:696-704` (`nrd::RelaxSettings` + the same
  primary-lobe config + `SetDenoiserSettings(RELAX_DIFFUSE_SPECULAR)`). The shared signal config is layout-
  verified by `native/nrd_bridge/tests/primary_lobe_settings_test.cpp:20` (both `ReblurSettings` and
  `RelaxSettings`).
* **Java overrides REBLUR only.** `rt/denoise/NrdDenoiser.java:187` creates with `mode.nativeModeMask()`;
  `:188-191` writes `NrdReblurParams.defaults()` and calls `nat.setReblurSettings` **only when
  `mode == NRD_REBLUR`**. RELAX therefore keeps the native default `RelaxSettings` (no Java override).
  `NrdNative.java:79,227` binds `vkb_nrd_set_reblur_settings`; there is **no** Java relax-setter binding.
* **ReLAX selection is the expert startup override.** `-Dvkrtx.denoiser=nrd_relax`
  (`DenoiserConfig.java:8,47,53`) combined with the persisted `path_denoiser=nrd` resolves to `NRD_RELAX`
  via `DenoiserResolution.resolve` case NRD (`:60`: `requestedFlag.isNrd() ? requestedFlag : NRD_REBLUR`).
  Already unit-tested: `DenoiserResolutionTest.theStartupFlagChoosesTheNrdVariant` (`:35-43`).
* **The UI offers OFF / NRD / DLSS_RR only.** `RtSettings.PathDenoiser` has no RELAX member; NRD means
  REBLUR. ReLAX is deliberately an expert `-D` override, not a player row — consistent with "tuning only
  via A/B", not a toggle that pretends to be a tuned default.
* **REBLUR params are the single source of truth, hardcoded.** `NrdReblurParams.defaults()` =
  `(30, 6, 63, 3.0, 0.1, 20.0, 1.0, 30.0, 30.0, 50.0, 0.02, true)` (NRD 4.17/4.18 values). The hit-distance
  A, B, C go to **both** `rt_pack_nrd.comp` (push constant) and NRD and **must match** or temporal
  hit-distance accumulation is wrong (`NrdReblurParams.java` class doc). Not `-D` injectable.
* **Injectable knobs that already exist.** `-Dvkrtx.denoiser` (mode), `-Dvkrtx.denoiser.range`
  (`CommonSettings.denoisingRange`, default 4096 blocks), `-Dvkrtx.denoiser.view=denoised|split|noisy`
  (`split` keeps the left half raw for the before/after screenshot), `-Dvkrtx.nrd.projFlipY`
  (`DenoiserConfig.java:8-11,24,65,74`).
* **Bench ReLAX arm added this task.** `tools/automation/bench_gi_stack.py` `VARIANTS["denoiser_nrd_relax"]`
  = the identical PT-on stack as `denoiser_nrd` plus `vkrtx.denoiser=nrd_relax`;
  `DENOISER_AB = [denoiser_rr, denoiser_nrd, denoiser_nrd_relax]`. L2: `test_bench_gi_stack.py` (9 cases).

## Why no tuning is applied now (evidence, not preference)

* **No measured need.** REBLUR already delivers the landed −18.4% gpu_rt win. Whether RELAX, a different
  `denoisingRange`, or different REBLUR hit-distance/blur/accumulation values beat that on this voxel
  workload is **unmeasured**. Changing a default without a capture would violate the "no numbers, no
  change" rule.
* **Param-value knobs are not injectable today.** REBLUR params are hardcoded (and coupled to the pack
  shader); RELAX params are native defaults. Adding `-D` knobs for them is enabling instrumentation that
  is justified **only if** the mode/range A/B shows a need for finer tuning. Not added speculatively.
* **Quality, not just speed.** A denoiser tuning can trade ghosting/blur for fps. The A/B must judge
  quality (split-view before/after, or the opt-in converged-reference error) at equal spp, not fps alone.

## Measurement plan (gate before any tuning)

Same-session A/B on the fixed-seed autotest world, RD16 **and** RD32, 1080p, upscaler fixed, no FG:

1. **Mode A/B:** `denoiser_nrd` (REBLUR) vs `denoiser_nrd_relax` (RELAX) vs `denoiser_rr` (RR). Identical
   PT-on GI stack; only the lit-view denoiser differs.
2. **Range sweep (optional):** `-Dvkrtx.denoiser.range` around the 4096 default on the winning mode.
3. **Record:** `gpu_rt_ms` median/p95 (`frames.csv`), `rt_pass` EWMA `work_ms`, reserved/peak VRAM, and
   denoiser quality via `-Dvkrtx.denoiser.view=split` before/after screenshots (L5 evidence).
4. **Adopt** a mode/param change only on a measured net win at equal-or-better quality; otherwise keep
   REBLUR defaults and record the negative. Any REBLUR hit-distance change must update `NrdReblurParams`
   **and** `rt_pack_nrd.comp` together. L4 required; no fps claim without a capture.

## Disposition

No speculative tuning or param knobs were written. REBLUR stays the measured default; RELAX is wired,
selectable via the expert override, and now has a bench arm. The mode/range A/B and any param tuning are
deferred to a quiet GPU window (the other session's game must be closed first). This note + the verified
wiring + the bench arm are the step deliverable.

## Provenance

Facts read from this worktree, branch `bedray-gi-stack-20261112` (scene ABI 3). The only real remote for
this task is the owner's repository `https://github.com/bnfe12/bedray.git`; no proxy or substitute address
is used.