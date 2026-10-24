# Iris dual-path design (Bedray / vulkan-rtx)

Date: 2026-10-21. Status: approved by the owner in chat on 2026-10-21.
Base plan: `docs/IRIS_DUAL_PATH_PLAN.md` (I0–I6, selector contract, safety rules — still authoritative).
Removed scaffold context: commit `1d42ee7` deleted a dead 14k-line shaderhost/ dirtrt scaffold (no callers); this spec rebuilds the subsystem as living code with stages that each ship.

## Decision

Two shader hosts behind one selector (`shader_host = native | iris` in bedray.toml):

- `native` — the current Bedray Vulkan RT path tracer; unchanged baseline, must stay fully usable when Iris is absent.
- `iris` — executes a selected Iris/OptiFine-style shaderpack (GLSL) on Vulkan by translation, targeting **BSL / Complementary** as the compatibility matrix (owner decision 2026-10-21).

Both hosts consume the same RTX frame contract: scene/TLAS, G-buffer, sun visibility, sky dome, emitters, denoiser, upscaler. A host never owns device creation and never calls bridge internals.

## Execution strategy (hybrid, approved)

Packs like BSL/Complementary are deferred: `gbuffers_*` programs only write albedo/normal/depth; `composite*/final` passes do the lighting and post. Therefore:

1. Pack geometry programs are NOT executed — Bedray RTX G-buffers (albedo, normals, depth, LabPBR material) replace them.
2. Pack `composite*`/`final` passes are translated and executed on Vulkan:
   - GLSL → GLSL 460 source rewrite through a generated shim header: `gl_ModelViewMatrix`/`gl_ProjectionMatrix` → UBO, `gl_FragData[i]`/`gl_FragColor` → explicit attachments (`colortex0..15`), `texture2D`/`texture3D` → `texture()`, legacy `varying`/`attribute` → in/out with locations, `gl_TexCoord` → varying.
   - Compilation through the existing runtime shaderc seam (`RtShaderCompiler` — extend, do not fork).
3. Pack lighting inputs map to RTX data instead of building OpenGL resources:
   - `shadowtex0/1`, `shadowcolor0/1` ← sun visibility from the RT trace (rendered to a 2D map per frame);
   - block light ← ReSTIR emitter results / baked block fill;
   - sky/environment ← RT sky dome radiance.
   - Pack shadow passes and shadow maps are never built.
4. Pass declarations, blend states, attachment formats and order come from the pack's `shaders.properties` and program set (`pass_graph`), executed through Vulkan pipelines with explicit attachment views.

## Capability states (honest, no scaffold advertised as a feature)

`READY`, `NOT_INSTALLED`, `UNSUPPORTED_PACK_FEATURES`, `COMPILE_FAILED`, `DEVICE_UNSUPPORTED`, `DISABLED_FOR_SESSION`.
The settings UI shows the real state per pack; a pack that does not render says so. No placeholder boxes presented as screenshots (existing settings-preview rule).

## Milestones (each ships a testbed jar + VKB-RT-GPU measurement)

- **M1 = I0+I1**: host selector, capability plumbing, pack discovery (zip/dir scan, metadata, content-hash cache), honest status in the Bedray settings; native path untouched.
- **M2 = I2**: GLSL preprocessor (pack-local includes, bounded macro expansion) + compiler seam; composite/final of the in-repo mini testpack actually execute over the RT frame (first visible pack post chain).
- **M3 = I3**: OpenGL pass semantics: colortex attachment set, blending, pass order, pack `#define`/option directives.
- **M4 = I5**: light mapping: shadowtex ← RTX sun visibility map, block light ← emitters, sky ← dome.
- **M5 = I4**: LabPBR/MER pack resources → RTX material table.
- **M6 = I6**: BSL/Complementary compatibility matrix from runtime evidence, per-pass fallback policy, unsupported-feature report (pack, pass, source, feature).

No milestone advertises full pack compatibility before M6 evidence exists.

## Future mode (owner request 2026-10-21, NOT a priority — do not build in M1–M6)

`iris_pt` combined mode: Bedray path-traced lighting + pack post-processing. Pass-graph consequence of the M3 design: lighting passes of the pack (deferred shading composites) become skippable; native PT lighting writes `colortex0`; post passes (tonemap/bloom/DOF/stylization) always run. Keep the pass graph data-driven from M3 on so this mode is a configuration, not a fork.

## Safety and fallback

- Pack reads confined to the selected pack root / cache dir; no native code, no arbitrary file loads from packs.
- A failed pack compile disables only the iris host for the session (state `COMPILE_FAILED`), message with pack/program/line, native host remains available.
- A failed pass is skipped or substituted per declared fallback policy; the frame never crashes.
- No Iris dependency in `core` (bridge); Iris classes stay `compileOnly` from `libs/`.
- No vendor DLLs in jars (unchanged rule).

## Testing

- Unit tests: pack scanner (zip/dir, metadata, hash cache), GLSL shim rewriter (golden in→out for the mini testpack), pass-graph parser, capability state machine — all loader-free like the existing suite.
- Structural shader tests continue to guard the native path (must be untouched by iris code paths).
- Runtime evidence per milestone: [VKB-AUTOTEST] logs + VKB-RT-GPU spans; M6 adds per-pack compatibility notes (BSL first, then Complementary).

## Ownership / worktree

All iris-host code lives in `common` (io.github.andrexarlay.vulkancompat.rt.iris.* or shaderhost equivalent), built in the `wt-rt-reflection-pbr` worktree with explicit file staging; `core` only gets the already-existing IrisCompat glue if anything.
