# Development branches

The canonical source repository for current renderer work is [Bedray](https://github.com/bnfe12/bedray).
Keep unfinished work in branches of this repository rather than creating another repository or a copy
of the same renderer. Local linked Git worktrees share the repository history and are not independent forks.

| Workstream | Responsibility | Current scope |
|---|---|---|
| `main` | Published Bedray baseline | Kept separate from the experimental branches |
| Software voxel traversal | Software traversal/companion API and integration | Bounded textured baked models, cutouts, world lighting and raster preservation; Iris through `086b98a` integrated in one frame loop; physical GI/reflections remain open |
| Iris Vulkan compatibility | Iris GLSL compatibility/post layer | Fullscreen post, options/profiles, typed camera/depth, current-frame MRT, startup wiring, ray-tracing pack compile/pipeline (no trace) and an opt-in custom ray-query stage; general shaderpack compatibility pending |
| Vulkan cache and DXGI presentation | Shared shader/pipeline caches and presentation compatibility | Independent source work; stage-aware Iris SPIR-V caching and LWJGL compatibility require integration |

These are development branches. Separate Compute alpha.7 host/companion candidates contain the combined
source and require their own version-matched package and game evidence. The earlier alpha.6 artifacts
do not contain this integration. Local builds and named checks are distinct from published releases
and live gameplay acceptance. Source integration does not establish presentation, shaderpack compatibility
or cross-vendor support. The feature pull request remains separate from the published main baseline.

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
