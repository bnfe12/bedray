# Path trace: follow-up after source review

This review is source-only; no new build, tests or game run were performed.
The reported scoped compilation and tests belong to the producing agent and do
not establish full migration or rendered output.

## Correct primary-ray distance after cutout holes

In `pathtrace/path_tracer.slang`, the cutout branch advances `ray.origin` by
`hit.t + options.rayBias`. A later accepted surface assigns `hit.t` directly
to `firstHitDistance`, but that value is relative to the new origin, not to
the initial camera ray. For example, a hole at distance 5 followed by a
surface at distance 10 is reported near 5 rather than 10. Preserve the
accumulated advance along the original primary ray and include it in the first
accepted hit distance; establish the ray normalization and bias convention
explicitly. Do not carry this distance across a scattering bounce as though it
were still the primary ray. Handle exhausted `tMax` and non-finite inputs in
the same contract rather than manufacturing a hit. Keep the sky sentinel for
true primary misses, but do not treat invalid/truncated paths as proven misses.

## Host and architectural direction

The user's target is Bedray 0.2.0 with a new modular C++ renderer and Slang
shaders; Java supplies the required Minecraft/loader integration. Do not add
GLSL/runtime-Shaderc fallback, a second Java renderer, a separate Vulkan device,
or Java-owned descriptor/pipeline management as a way to make the old frame
path run. Legacy code is reference material. On missing device capabilities,
report a clear unsupported state rather than silently switching to GLSL.

The current `LegacySectionTraversal` and legacy section table still need an
explicit decision and migration to the new scene ownership and ABI; a compiled
pass alone does not establish that the clean scene architecture exists. The
40-byte push layout, depth format and sentinel must be checked against all
actual consumers. In particular, a ray distance is not automatically linear
view-space depth, and the denoiser's depth contract currently differs.

The user's standing instruction defers additional builds and tests until full
migration. Coordinate any edits to files owned by another agent; do not reset
or overwrite the shared working tree. Report exact files and evidence level.
