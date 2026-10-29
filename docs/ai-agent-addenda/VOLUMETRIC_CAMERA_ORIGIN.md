# Volumetric camera origin

The march and temporal endpoint now use the shared camera-relative eye origin:
`camera.origin + direction * distance`. The origin contains view bob; the height
push constant remains the raw camera's world Y, so vertical bob contributes once.
Reprojection adds current-minus-previous raw camera position before applying the
previous view-projection matrix, whose view already includes previous bob.

Both section adapters use the same entry body. The temporary host rejection of
nonzero bob is removed. Step counts, density/phase coefficients, integration,
visibility, exposure and temporal weights are unchanged. This coordinate
correction needs shader owner review; it introduces no new transport algorithm.

## Verification (2026-10-29)

Slang 2026.20 compiled the legacy/native march and apply entries without errors.
The no-GPU tests check xyz bob, camera rebasing, previous-view bob, world height
and zero-origin parity. Arithmetic fingerprints permit only the origin argument,
sample position and temporal endpoint changes.
The focused volumetric/FFM run passed all 14 tests (`BUILD SUCCESSFUL`, 19 seconds).

The complete `:common:test` run on this tree, including the independently prepared
Sodium adapter, reported 794 tests: 785 passed, nine skipped, zero failures/errors
(`BUILD SUCCESSFUL`, 59 seconds). These are L1/L2 results. There is no new GPU,
game, visual or owner acceptance evidence for this coordinate correction.
