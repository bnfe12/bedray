# Denoise and SSR source review — Bedray alpha 0.2.0

Scope: uncommitted `denoise/{atrous,temporal}.slang`,
`screenspace/ssr.slang` and their entry passes. This is source inspection,
not evidence of compilation, GPU output or in-game behaviour. The user has
deferred tests and builds until the migration is complete.

## Coordinate with the shader owner

Keep these modules owned by their current author to avoid concurrent edits.
The owner should correct the following before integration, without running
slangc, tests, a build, or Minecraft:

1. **History validation fabricates data.** `passes/temporal_accumulate.slang`
   fills `sample.positionDepth.xyz` with the current `worldPos`, then calls
   `historyValidate` against the same value. Position validation is therefore
   always zero and cannot reject disocclusion. Supply a genuine previous
   position (with an unwrapped/rebased coordinate contract) and a material or
   stable geometry identity. If those resources do not exist, explicitly
   invalidate history or declare the missing contract; do not claim the
   position/material checks work.
2. **Previous-depth comparison.** The pass compares previous-frame depth to
   current-frame `geom.w`, even after camera motion. The comparison must use a
   depth in the same previous-camera coordinate system and convention as the
   stored history. `reprojection.depth` currently comes from `clip.w`, which
   cannot be assumed equal to arbitrary linear view-depth conventions; define
   and derive the exact mapping.
3. **Non-finite values and normals.** Current radiance is sanitised only for
   the center pixel, not the 3x3 neighbourhood, historical color, normal,
   depth, age or projection. A NaN comparison can bypass rejection and then
   poison temporal state. Reject invalid history and define a safe response for
   invalid current geometry and samples. Do not normalize zero/invalid vectors.
4. **Alpha/age contract.** Temporal stores history age in alpha, while the
   `passes/atrous.slang` output always sets alpha to 1, including the sky path.
   If filtered output is reused as history this loses age every wavelet pass.
   Either preserve center alpha, use a separate age resource, or define a
   different history source explicitly. Do not claim variance/moment-aware
   SVGF without the necessary first/second moments and history policy.
5. **SSR parameter layout.** `SsrPush` contains two 4x4 matrices and four
   scalars: at least 144 bytes before any reflection alignment. The current
   Java manifest rejects push constants over 128 bytes, and Vulkan guarantees
   only 128 bytes minimum. Move camera matrices to an explicit uniform buffer
   or another reflected resource; retain a compact push constant block.
6. **SSR depth and coordinate conventions.** `ssrProject` assumes clip.w is
   linear view depth; `ssrViewPosition` assumes a particular projection, z
   direction and clip-depth convention; normal is documented as view-space.
   These assumptions need one explicit shared camera/viewport contract, with
   reverse-Z, projection flip, behind-camera and sky handling. Reject invalid
   step count, distance, thickness, roughness and non-finite samples. Bisection
   must maintain a real front/back bracket: a previous sample over sky or behind
   an unrelated surface is not automatically a valid bracket.
7. **Compatibility of passes and core.** The new entries use separate
   `Texture2D`/`RWTexture2D` resources. Check them against the actual reflected
   descriptor types and native ABI only during the later verification stage;
   do not rewrite the pass as a GLSL/Java fallback.

After source corrections, provide a concise contract table per resource:
coordinate space, units, format, alpha semantics, producer, consumer and reset
conditions. Document unresolved scene motion/material identity, and explicitly
identify all functions and parameters that require owner approval. Do not claim
an uncompiled update works or send generated binaries to the repository.
