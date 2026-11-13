# Development branches and `main`

The canonical repository is [bnfe12/bedray](https://github.com/bnfe12/bedray). There is one long-lived branch, `main`
(see [BRANCH_LAYOUT](BRANCH_LAYOUT.md)): the Slang AOT renderer with the native GPU scene. The old Java/GLSL renderer is
history at commit `9c985fa4c56894e9370e7ef8d6120d63b345ad26`, an ancestor of `main`. Other repositories that once carried
the renderer (`vulkan-rtx`, `vulkan-rtx-bridge` and their release repositories) were deleted on 2026-11-13 and must not be used as a source.

## Working rules

* One task, one short-lived branch: `feat/<topic>-<date>`, `fix/<topic>-<date>` or `docs/<topic>-<date>`, cut from `origin/main`.
* Land it with a pull request into `main`. The native Windows CI (`.github/workflows/native-windows.yml`) runs for pull requests and pushes to `main`.
* Local linked Git worktrees share history; they are not forks. Do not create another copy of the renderer in another repository.
* Evidence goes to [VERIFICATION_LOG](VERIFICATION_LOG.md) (what was run, at which level) and, for measurements, to `docs/measurements/`.
* Delete a branch after its pull request is merged; the old `codex/*`, `vrtx/*` and `restir-orig` lines are history and not a base for new work.

## Workstreams in `main`

| Workstream | Responsibility | State |
|---|---|---|
| Slang renderer and native core | Typed passes, native scene, memory, frame graph, pipeline cache | Merged; GPU measurements in [GI_STACK_REPORT](measurements/GI_STACK_REPORT.md) |
| GI / performance stack | ReSTIR PT, raygen and SER execution, BLAS compaction, PTLAS, denoisers | Merged; open items are GPU- or owner-gated |
| Transparency and materials | Glass, water, optical profiles, Nether/End materials, exposure fixes | Merged; live acceptance pending |
| Iris compatibility | Post host and pack-level raster forward host | Merged (PR #84); in-game acceptance not recorded |
| Voxel / Compute | Software voxel provider nested in the player jar | Merged; see [COMPUTE](COMPUTE.md) |
| Sodium compatibility | Config API entrypoint, version matrix 0.9.2 to 0.9.3-beta.5 | Merged; contract and compile level |
| Modularisation | Extraction of single-purpose classes from the large renderer classes | Ongoing (PR #86, #88) |

## Consolidation record: 2026-11-13

PR #87 consolidated the development branches into `main`; PR #84, #85 and #86 brought in the Iris raster host, the GI stack and the
adaptation of older branch work to the new tree. Older lines were merged or recorded as "keep new-main tree" where they targeted the removed GLSL
renderer. Consolidation by itself proves nothing about image quality or speed: see the [feature matrix](FEATURE_MATRIX.md).
