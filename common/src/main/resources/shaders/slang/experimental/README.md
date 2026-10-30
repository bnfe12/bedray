# Experimental Slang modules

Nothing in this directory is part of the AOT pass set or the runtime. `compileSlang` only compiles
`passes/**/*.slang`; a module here is not imported by a pass and is not validated by the shader manifest.

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
- There is still no host pass, no descriptor contract test and no GPU evidence.
- `render_plan.slang` may name the `ReSTIR_PT` algorithm in a plan, but the runtime does not select this module.

Before this file can move back to `restir/`, it needs a pass, a manifest-checked host and at least one GPU
smoke run. The change-of-measure and reservoir-property gates are closed; these are not.
