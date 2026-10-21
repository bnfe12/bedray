# Development branches

The canonical source repository for current renderer work is [Bedray](https://github.com/bnfe12/bedray).
Keep unfinished work in branches of this repository rather than creating another repository or a copy
of the same renderer. Local linked Git worktrees share the repository history and are not independent forks.

| Workstream | Responsibility | Current scope |
|---|---|---|
| `main` | Published Bedray baseline | Kept separate from the experimental branches |
| Software voxel traversal | Software traversal/companion API | Bounded live opaque diagnostic and synthetic scene; full materials/light transport pending |
| Iris Vulkan compatibility | Iris GLSL compatibility/post layer | Opt-in fullscreen post subset; general shaderpack compatibility pending |

Current branch names and commit activity are listed on the repository's [branches page](https://github.com/bnfe12/bedray/branches).

These are development branches. Local candidate builds and named tests are distinct from published
releases and live gameplay acceptance. Compute has explicit CPU and offscreen GPU verification commands;
game capture/presentation and cross-vendor support remain separate gates. No tested-support claim follows
automatically from source compilation.

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
