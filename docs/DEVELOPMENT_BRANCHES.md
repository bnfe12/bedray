# Development branches

The canonical source repository for current renderer work is [Bedray](https://github.com/bnfe12/bedray).
Keep unfinished work in branches of this repository rather than creating another repository or a copy
of the same renderer. Local linked Git worktrees share the repository history and are not independent forks.

| Branch | Responsibility | Current scope |
|---|---|---|
| `main` | Published Bedray baseline | Kept separate from the experimental branches |
| `codex/software-voxel-tracing` | Software traversal/companion API and integration | Bounded live full cubes with atlas albedo and synthetic scene; Iris source through `086b98a` integrated with one host frame loop; general shapes/light transport pending |
| `codex/iris-vulkan` | Iris GLSL compatibility/post layer | Fullscreen post, options/profiles, typed camera/depth, current-frame MRT; general shaderpack compatibility pending |
| `codex/vulkan-cache-dxgi-present` | Shared shader/pipeline caches and presentation compatibility | Independent source work; stage-aware Iris SPIR-V caching and LWJGL compatibility require integration |

These are development branches. The separate Compute alpha.6 host/companion candidates were locally
built and installed before Iris integration; those artifacts do not contain the combined implementation.
The combined source still needs its own compilation, package and game evidence. Local candidate builds
and named checks are distinct from published releases and live gameplay acceptance. Source integration
does not establish presentation, shaderpack compatibility or cross-vendor support.

All branches are protected against force-push and deletion. The default branch requires pull requests
with resolved review conversations. No bypass actors are configured; ordinary commits to development
branches remain available. Deliberate branch retirement requires an explicit rules change.

For eventual integration, preserve one host/device/queue/target/config owner. The world renderer records
one color result; Iris is a subsequent optional post stage. A depth-reading post pack requires depth
guides matching that exact color producer. Shared mixin/UI hooks must be reconciled once on merge.
Companion algorithms stay behind the versioned API instead of importing private host implementation.

Consumers of borrowed host target views require retirement before those views are destroyed or
replaced. A next-frame extent/view comparison cannot by itself establish that ordering. Shared target
cleanup must submit queued references and retire dependent descriptors before target closure; a failed
retirement must stop replacement. This is a required lifecycle gate for depth-reading Iris passes and
future raster/depth composition in Compute.

Do not merge historical development repositories wholesale over the public baseline: review their unique
changes first and record the source commit when selectively porting useful work. Repository archival and
branch deletion are separate maintenance decisions; preserve a private SHA inventory before cleanup.
