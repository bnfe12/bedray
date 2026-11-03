# Branch layout — 2026-11-03

The owner requested Slang development in main and a complete copy of the
pre-migration Java/GLSL project in the second permanent branch.

| Branch | Contents |
|---|---|
| main | Slang AOT/native integration, including the previous main's log-privacy fixes |
| legacy | Exact previous main at 9c985fa4c56894e9370e7ef8d6120d63b345ad26, version 0.1.1-alpha.13 |

Legacy preserves the whole original project: all modules, Java renderer, runtime
GLSL shaders, resources, Gradle wrapper/build files, tests and documentation.
Its captured tree has 904 tracked files, 671 Java files, 36 GLSL-stage/source files
and zero Slang files. It is not a subset extracted from the Slang branch.
Local ignored dependencies remain outside Git, under the existing licensing rules.

Preservation is not a new acceptance claim for the old renderer. The untouched
legacy snapshot passes its 120 core tests, but its common suite reports 588
tests with 10 failures and 3 skips, including the reserved GLSL identifier
`filter` and old source-shape assertions. Both loader jars compile. These
existing failures were recorded without editing the preserved legacy source.

The former refactor/slang-pipeline history is preserved in main before its branch
reference is removed. Main-only privacy commits are merged with current Slang
ownership/discard behavior; removed legacy implementations are not resurrected.
The second branch's deletion/non-fast-forward protection moves to legacy.
Main continues to require pull requests. Native Windows CI runs for main pushes
and main pull requests; no release tag or GitHub Release is created by this move.

This changes branch ownership, not feature acceptance. GPU visual/gameplay/FPS
acceptance and the unchecked stack obligations remain open in
[STACK_IMPLEMENTATION_PLAN.md](STACK_IMPLEMENTATION_PLAN.md).
