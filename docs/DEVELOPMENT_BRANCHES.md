# Development branches (source only)

The canonical source repository for current renderer work is [Bedray](https://github.com/bnfe12/bedray).
Keep unfinished work in branches of this repository rather than creating another repository or a copy
of the same renderer. Local linked Git worktrees share the repository history and are not independent forks.

| Branch | Responsibility | Current scope |
|---|---|---|
| `main` | Published Bedray baseline | Kept separate from the experimental branches |
| `codex/software-voxel-tracing` | Software traversal/companion API | Bounded live full cubes with atlas albedo and synthetic scene; general shapes/light transport pending |
| `codex/iris-vulkan` | Iris GLSL compatibility/post layer | Fullscreen post, options/profiles, typed camera/depth, current-frame MRT; general shaderpack compatibility pending |
| `codex/vulkan-cache-dxgi-present` | Shared shader/pipeline caches and presentation compatibility | Independent source work; stage-aware Iris SPIR-V caching and LWJGL compatibility require integration |

The voxel and Iris work here is source-only. No JAR, release, shaderpack ZIP or tested-support claim is
published for this work. Compile-only checks describe compilation; they do not establish gameplay.
Runtime tests remain future gates rather than being silently treated as passed.

All branches are protected against force-push and deletion. The default branch requires pull requests
with resolved review conversations. No bypass actors are configured; ordinary commits to development
branches remain available. Deliberate branch retirement requires an explicit rules change.

For eventual integration, preserve one host/device/queue/target/config owner. The world renderer records
one color result; Iris is a subsequent optional post stage. A depth-reading post pack requires depth
guides matching that exact color producer. Shared mixin/UI hooks must be reconciled once on merge.
Companion algorithms stay behind the versioned API instead of importing private host implementation.

Do not merge historical development repositories wholesale over the public baseline: review their unique
changes first and record the source commit when selectively porting useful work. Repository archival and
branch deletion are separate maintenance decisions; preserve a private SHA inventory before cleanup.
