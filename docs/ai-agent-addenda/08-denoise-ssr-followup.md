# Denoise and SSR follow-up — source review

The 2026-10-26 edits were inspected in the shared dirty checkout without
building or running tests. The owner retains these modules. Do not modify other
agents' files and do not run slangc, tests, builds or Minecraft until the user
starts the verification stage.

The initial corrections remove fabricated position validation, preserve
temporal alpha across the à-trous pass, and move SSR matrices to a constant
buffer. The following issues remain in the current source:

1. **Temporal non-finite inputs.** `temporalValidate` checks `isnan` but not
   `isinf` on `history.a` and `historyGeom.w`; in particular a positive
   infinite previous depth passes the depth comparison because both the
   absolute difference and tolerance can become infinite. Check finite age,
   previous depth, expected depth, and all normals before normalizing. A finite
   normal's squared length can itself overflow; check that too. Zero-age history
   should not be accepted as a valid sample. Invalid data must reset history.
2. **SSR bisection still treats sky as front.** In the refinement loop,
   `if (sd > 0.0 && md - sd > 0.0) hi = mid; else lo = mid;` moves `lo` for
   `sd <= 0`, despite the stated rule. Also `prevT` begins at zero with no
   proof that the sample is in front. Keep an explicit valid bracket state;
   for a sky or invalid sample during refinement use a documented conservative
   miss or another sound bracketing method rather than declaring it in front.
3. **SSR non-finite inputs.** `ssrParamsValid` does not bound roughness cutoff
   to <=1 or check infinity for it, and the trace checks `isnan` but not
   `isinf` for roughness/origin/direction. Entry-pass normals can normalize an
   infinite vector. Depth samples and projected coordinates need finite checks;
   avoid divisions by zero/invalid `v.w`. Define behaviour when matrix data is
   invalid, and constrain roughness to its actual domain.
4. **Depth and camera contract.** `clip.w == -viewZ` follows from a particular
   perspective projection, not from an arbitrary view-projection matrix, and
   absolute 32-bit world positions lose precision at large Minecraft coordinates.
   The shared `HistoryHeader` and producer of `positionImg` do not yet establish
   the asserted producer contract. Specify and integrate actual projection,
   jitter, viewport orientation and camera rebasing before using history.
   Reverse-Z does not by itself define a unique convention for clip.w; avoid
   disabling support based on that label alone.
5. **À-trous invalid normals and alpha.** A normal with an infinite component
   has infinite length, passes `len > 0.5`, then can become NaN after division.
   Check finite components and length. Define the behaviour of non-finite alpha
   rather than preserving it into subsequent temporal history. Validate
   `stepSize` and the sigma parameters at the host contract boundary.

Keep limitations explicit: movement, material identity and genuine historical
world position are not yet tracked, so this is not fully validated temporal
reprojection and is not complete SVGF. Update the owner-facing resource table
and report exact unresolved producer contracts. Existing compilation artifacts
from previous versions do not validate the current edits.
