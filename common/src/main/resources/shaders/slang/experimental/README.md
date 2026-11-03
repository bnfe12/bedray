# Experimental Slang modules

`compileSlang` only compiles `passes/**/*.slang`. One module here is now reached by those passes:
`restir_pt.slang` is imported through `restir/pt_temporal.slang` by `path_trace`, `path_trace_scene` and
`rc_path_trace_scene`, behind the default-off `path_restir_pt` switch, and through `restir/pt_spatial.slang` by
`restir_pt_spatial` and `restir_pt_spatial_scene` (child switch `path_restir_pt_spatial`). It stays in this directory until its GPU
smoke gate closes.

## `restir_pt.slang`

ReSTIR path-reuse prototype. It is kept for reference, not as an enabled technique:

- v3 restricts reuse to a certified diffuse first vertex and implements the change of measure for the
  fixed-first-vertex shift: `q_new = q_old * G_old / G_new`, folded into the merge as `G_new / G_old`.
  The derivation, the rejected path classes, the documented approximations and the CPU validation are in
  `docs/RESTIR_GI_DERIVATION.md`.
- v4 splits admission from reuse. `ptAddPath` counts every valid attempt, because `M` in
  `W = wSum / (M * p_hat)` is the number of draws from the first-edge density; v3 gated the add path on
  `ptPathReusable`, which conditioned the estimate on a recorded hit and scaled it by `1/P(recorded)`
  (measured at +87.5% with four attempts in a half-sky scene). `ptPathReusable` stays in the merge,
  temporal and spatial steps, where a stored density is actually transported.
- The estimator is validated on the CPU (`RestirPtChangeOfMeasureTest`, including a two-million-sample
  Monte Carlo against a closed form, and `RestirPtReservoirInvariantTest`, which pins the admission/reuse
  split and the attempt-accounting expectation) and the module compiles through the pinned `slangc` with
  the AOT flags.
- v5 (the live temporal stage): `ptAddEmptyAttempt` counts a draw the stage does not own, the merge reset only
  drops a corrupt accumulator (`ptReservoirIsAccumulator`), and history is keyed on
  `RestirPtReceiver.historyPosition` instead of the 1024-wrapped `SurfaceHit.worldPosition`. The record layout was
  measured: 144 B per pixel (std430), not the 112 B earlier versions claimed. Derivation §11.
- The host (`ReferencePathTraceBackend`, binding 36), the manifest pins and the contract tests exist; the native core
  binds a stand-in. There is no GPU smoke run and no spatial stage yet.

Before this file can move back to `restir/`, it needs at least one GPU smoke run with `path_restir_pt` on.
