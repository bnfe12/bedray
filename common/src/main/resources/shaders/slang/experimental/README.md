# Experimental Slang modules

Nothing in this directory is part of the AOT pass set or the runtime. `compileSlang` only compiles
`passes/**/*.slang`; a module here is not imported by a pass and is not validated by the shader manifest.

## `restir_pt.slang`

ReSTIR path reuse prototype. It is kept for reference, not as an enabled technique:

- The change of measure for reuse is not derived yet; the module itself documents that the reuse is biased.
- There is no host pass, no descriptor contract test and no GPU evidence.
- `render_plan.slang` may name the `ReSTIR_PT` algorithm in a plan, but the runtime does not select this module.

Before this file can move back to `restir/`, it needs a pass, a manifest-checked host, a property test for the
reservoir invariants and at least one GPU smoke run.
