# Branch layout

There are two long-lived branches:

* `main` is the maintained branch for the Slang AOT renderer and the native GPU scene. Changes reach it only from `experimental`
  through pull requests, and the native Windows CI runs for pushes and pull requests targeting `main`.
* `experimental` is the test branch. All new work is written here (or on a short-lived topic branch merged into it first),
  checked, and then merged into `main`.

Every other line of work is kept as a tag `archive/<old-branch-name>` and is not a base for new work. Restore one
with `git switch -c <name> archive/<old-branch-name>`.

The previous Java/GLSL renderer remains in Git history at commit
`9c985fa4c56894e9370e7ef8d6120d63b345ad26`. That revision is an ancestor of `main`.

See the [implementation plan](STACK_IMPLEMENTATION_PLAN.md) for the remaining renderer work and GPU acceptance checks.

## Archived lines that are not (fully) in `experimental`

On 2026-11-13 `main` was rebuilt from a new tree and older branches were joined with record-only merges ("keep new-main tree").
Git therefore reports them as merged although their files are not in `main`. Compare trees, not ahead/behind counts.

| Archive tag | What it holds that `experimental` lacks |
|---|---|
| `archive/bedray/modular-20261112` | Native splits of `scene.cpp`, `core.cpp`, `path_trace_pass.cpp`; `RtPassFactory`, `RtArena`, `RtCameraHistory` and other owners written for the old `RtFrameRenderer`. Redo on the current tree: `main` has since re-modularised several of the same areas (`RtRenderLog`, `RtFrameStatsTracker`, `WorldCameraHistory`) with a different design. Taken so far: the `rtpipeline` unification and `RtDenoiserLifecycle` |
| `archive/bedray/modular-reroot-20261113` | `WorldCameraHistory` / `WorldLevelProjection` extraction against the older tree |
| `archive/bedray/nether-lighting-stability-20261114` | Lava-footprint and cold-emitter stabilisation (`7103d3f2`): large shader and `RtEmitters` change that conflicts with the light-level/peak rework in `main`; needs a shader build to merge. The Nether capture scene (`archive/bedray/nether-current-integration-20261114`) duplicates `perfDimension=nether` already in `experimental` |
| `archive/codex/reuse-scene-build-buffers` | Staging and BLAS scratch reuse (native `scene.cpp`, conflicts with the compaction code; needs a native build to merge) |
| `archive/feat/slang-compute-migration-20261113` | Slang toolchain and voxel pilot on a much older base |
| other `archive/bedray/*`, `archive/fix/*`, `archive/codex/*`, `archive/refactor/*`, `archive/feat/*` | Older snapshots; their useful changes were adapted into `main` on 2026-11-13 |
