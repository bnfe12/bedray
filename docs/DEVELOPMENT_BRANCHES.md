# Development branches and main integration

The canonical source repository for current renderer work is [Bedray](https://github.com/bnfe12/bedray).
Keep unfinished work in branches of this repository rather than creating another repository or a copy
of the same renderer. Local linked Git worktrees share the repository history and are not independent forks.

| Workstream | Responsibility | Current scope |
|---|---|---|
| `main` | Shared source baseline | Unreleased consolidation; current integration has no build or runtime evidence |
| Software voxel traversal | Software traversal/companion API and integration | Bounded textured baked models, cutouts, world lighting and raster preservation; Iris through `086b98a` integrated in one frame loop; physical GI/reflections remain open |
| Iris Vulkan compatibility | Iris GLSL compatibility/post layer | Fullscreen post, options/profiles, typed camera/depth, current-frame MRT, startup wiring, ray-tracing pack compile/pipeline (no trace) and an opt-in custom ray-query stage; general shaderpack compatibility pending |
| Vulkan cache and DXGI presentation | Shared local caches and optional presentation policy | Included, with the LWJGL cache fixes and atomic compiler-profile capture; Iris has a separate compiler frontend |

## Main source checkpoint: 2026-10-26

The owner requested consolidating the development branches into `main` without running tests. This source checkpoint is
unreleased 0.1.1-alpha.13. **No build, shader compilation, automated tests, GPU dispatch, game session or benchmark was run
for this integration.** Previous build records, screenshots and alpha.1 release artifacts do not validate the combined tree.
Source integration does not establish visual correctness, performance, general shaderpack compatibility or vendor support.

Iris and cache/DXGI are already ancestors of the newer renderer branches. Their draft PRs against the old main baseline
did not represent independent missing implementations. The software-voxel and voxel-engine refs are identical.
The independent leaves are ReSTIR/barriers, the latest Voxel follow-up, unified ReSTIR and the RT-port Iris scenario.
Their histories are retained by the consolidation; older branches remain available as historical checkpoints.

| Source branch at integration | Source commit |
|---|---|
| `main` before integration | `dcdb822c18da632634561c03480ef87c4f987094` |
| `codex/iris-vulkan` | `086b98aaa771574393df0932abbff981ddc7ffc1` |
| `codex/software-voxel-tracing` | `42c369a1896da46435d8f5eb9bfdcbce0494b56e` |
| `codex/voxel-engine-20261025` | `42c369a1896da46435d8f5eb9bfdcbce0494b56e` |
| `codex/voxel-settings-integration-20261025` | `b618c3d70aebd853b47d816d912e2e71572030f8` |
| `codex/vulkan-cache-dxgi-present` | `7a993707a90ff12bf582b9658b59746c43a27836` |
| `feat/iris-host-bsl-20261025` | `1f632d851da4beac17cf129ae3a72c66a7db30ba` |
| `feat/iris-rt-shader-host-20261024` | `2a719db2dad5febcf0c2438adeddf36cc0dd64eb` |
| `feat/restir-barriers-20261025` | `b3b3ab6c98ef048c95d37b4c6502e0a1ff2eef4d` |
| `feat/voxel-visual-pass-20261024` | `7fa149674852ad9356aad7aaaf5d82e58f12972b` |
| `integration/unified-20261025` | `5a44675967baa3bbc3a1028f7782aedf5c922e46` |
| `port/rt-updates-20261024` | `d0f10de0840b563918476727dbe3d04e69199c35` |

Two conflicts concerned the same ReSTIR history clamp and its existing source assertion. Resolution retains the newer
shared `RESTIR_M_CAP` / `RESTIR_TEMPORAL_RATIO_CAP` implementation and its safer denominator, preserving the cap of 8.
The Voxel light-invalidation/water-segment follow-up and the Iris scenario are included.

Additional integration fixes:

* Camera and secondary interface walks retain one TLAS query per straight segment and collect the nearest 16 compact
  interface candidates. They complete the query, determine the nearest terminal, and fold only preceding interfaces in
  distance order. Realistic water terminates the camera segment before its Snell bend. Unresolved deeper transmission
  is suppressed instead of leaking sky. Bounded sorting/storage has not been profiled.
* Path Tracing also reuses sorted 16-entry hit batches through thin interfaces, while preserving its existing 24-interface
  limit, first-visible G-buffer, per-layer medium integration and reflection rules. Snell bends and shaded bounces discard
  the batch; deeper stacks gather another bounded batch rather than restarting traversal for every pane face.
* Background shader warmup and foreground compilation use one immutable extension/target profile for source, cache key
  and shaderc. A null shaderc options handle fails before native use.
* Water diagnostics and docs distinguish Snell/medium approximation, straight thin glass and approximate colored shadows.

The current source is the shared baseline. Local packaging and live-game acceptance remain separate future work.

All branches are protected against force-push and deletion. The default branch requires pull requests
with resolved review conversations. No bypass actors are configured; ordinary commits to development
branches remain available. Deliberate branch retirement requires an explicit rules change.

The combined implementation preserves one host/device/queue/target/config owner. The world renderer records
one color result; Iris is a subsequent optional post stage. A depth-reading post pack requires depth
guides matching that exact color producer. Shared mixin/UI hooks must be reconciled once on merge.
Companion algorithms stay behind the versioned API instead of importing private host implementation.

Consumers of borrowed host target views require retirement before those views are destroyed or
replaced. A next-frame extent/view comparison cannot by itself establish that ordering. Shared target
cleanup submits queued references and retires dependent descriptors in the target pre-close hook;
a failed retirement stops ordinary replacement. This ordering covers depth-reading Iris passes and
raster/depth composition in Compute; live reload/resize acceptance is still required.

Do not merge historical development repositories wholesale over the public baseline: review their unique
changes first and record the source commit when selectively porting useful work. Repository archival and
branch deletion are separate maintenance decisions; preserve a private SHA inventory before cleanup.
