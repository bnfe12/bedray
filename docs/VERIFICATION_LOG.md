# Migration verification log

Evidence levels follow [FEATURE_MATRIX.md](FEATURE_MATRIX.md): L0 source,
L1 build, L2 no-GPU unit tests, L3 game startup, L4 visible effect, L5 owner
confirmation. Historical results do not validate a different source revision.

## 2026-10-28 — native CI and P0-2 exception containment

Base: `refactor/slang-pipeline` at
`aaaf00cb4ad90887739c2f4813841c13f14b62e1` (remote clone confirmed).
Branch: `codex/native-ci-abi-exceptions`.

### Source review

Read: `SLANG_MIGRATION.md`, `PATH_TRACE_CPP_INTEGRATION_PLAN.md` (including
sections 9/10), `DEVELOPMENT_BRANCHES.md`, `AUDIT_DEFECTS_AND_ISSUES.md`,
`FEATURE_MATRIX.md`; native core `RECORDING_LIFETIME.md`, scene `SCENE_NOTES.md`,
history `C_ABI.md`, render graph `RENDER_GRAPH_NOTES.md` (including live-path
gap); addenda 01, 02, 03, both 04 files, 06, 09, 10 and 11. The addenda are
historical handoffs; the current task explicitly authorizes scoped native CI
and tests and does not inherit their old verification freeze.

P0-2 is confirmed at the base: buffer/image/history C wrappers lacked outer
exception boundaries. Several core exports also lacked them. The patch adds
function-try-blocks with `bad_alloc` and catch-all mapping. Resource-owner
creation publishes its handle only after successful construction/validation.
No ABI structure size, field offset, function signature or existing numeric
status changes. Two additive wrapper errors distinguish unexpected exceptions:

| Boundary | Allocation exception | Unexpected exception |
|---|---|---|
| Core status exports | `BEDRAY_ERROR_OUT_OF_MEMORY` | `BEDRAY_ERROR_INTERNAL` |
| GPU buffer/image wrappers | `BEDRAY_GPU_OUT_OF_MEMORY` | `BEDRAY_GPU_INTERNAL_ERROR` (0x101) |
| History wrappers | `BEDRAY_HISTORY_WRAPPER_OUT_OF_MEMORY` | `BEDRAY_HISTORY_INTERNAL_ERROR` (0x103) |

The GPU/history status domains remain independent of `BedrayStatus`; they are
not numerically cast to it. Version exports return zero on an unexpected
exception. Existing core void exports contain exceptions but cannot return a
status without an ABI change. They remain an explicitly documented limitation.
`VkResult` and successful outputs are valid only on their documented return
paths; a wrapper exception is not a Vulkan driver failure. The boundary does
not promise rollback after arbitrary internal mutation, catch invalid-pointer
accesses, or recover from a violation of an internal `noexcept` contract.

The new allocation-injection test links the actual GPU/history C wrappers into
one process and replaces its throwing `operator new`. It injects `bad_alloc`
and a non-standard exception into all three owner-creation exports, checks the
exact status and null output, then checks successful creation/destruction.
Core sampler tests inject both exception kinds through fake Vulkan callbacks
and check status/error agreement and containment during destruction. Existing
tests continue covering lifecycle, descriptors, scene, history and scheduler.
The ABI probe and the standalone dynamic C resource test are now registered
with CTest as well as built.

P0-1: `SlangProgram.nativePushConstants` and
`SlangPassLibraryTest.heapAndDirectPushConstantsHaveNativeStorageAndKeepTheInputPosition`
already exist at the base. Heap and direct input coverage is present; no fresh
Java execution is claimed. Source recheck of P0-3 finds branch probability
multiplication and the sole-branch return, guarded by the existing
`RtSlangBlockEmitterTest.theBranchProbabilityIsFoldedIntoTheDensity`. P0-5 now
uses geometry-local primitive IDs with `geometryIndex` selecting the range,
adding `firstQuad` once; the scene contract test checks range-local AS builds.
P0-6 publishes the merged reservoir when the original sample survives,
guarded by the existing `RtSlangRestirSpatialTest` source checks. These are
source observations, not executed Slang or fresh Java evidence. P0-4's old
float4 confidence-weighted resolve still exists and requires shader-owner
investigation alongside the typed segment path; do not connect the two as if
their contracts matched. This PR changes no shader code and does not certify
shader math.

### Reproducible native CI

`.github/workflows/native-windows.yml` runs on `windows-2022` via
`workflow_dispatch` or pushes to this work branch. Separate baseline and
candidate jobs use the exact base SHA and the triggering candidate SHA.
No Java, Minecraft, proprietary `libs/`, driver or GPU is required.

Pinned inputs:

- Vulkan-Headers v1.4.321, commit `2cd90f9d20df57eac214c148f3aed885372ddcfe`.
- Slang v2026.20, `slang-2026.20-windows-x86_64.zip`, SHA-256
  `e6dd32bbddf1720ebb4e679d55e9635ce792865a5bd84f3d2cd9af28647c0174`.
  Its version probe verifies availability only; native CMake does not consume
  Slang, and this workflow does not compile or execute shaders.
- Checkout/upload actions are pinned by full commit SHA in the workflow.

CI driver (identical on baseline and candidate):

```powershell
./driver/.github/scripts/native-ci.ps1 -SourceRoot source `
    -Headers deps/Vulkan-Headers/include -BuildRoot evidence/build
```

For core it enables graph, history, GPU resource and standalone shared C ABI
tests, builds Release x64, runs CTest with `--no-tests=error`, and runs the ABI
probe. It then independently configures/builds/tests scene and frame lifetime.
The driver logs commands and exit codes. Text-only artifacts retain dependency
versions, build/configure logs and CTest JUnit output for both revisions.
Generated SDKs, SPIR-V, DLLs, libraries and JARs are not committed or uploaded.

### Results at preparation time

- `git rev-parse HEAD`: base SHA above, exit 0.
- `git diff --check`: exit 0.
- Local CMake, CTest and C++ compiler: not found in PATH or standard Visual
  Studio/CMake installation locations; no local build/test was run.
- CI execution: pending branch publication; no success is inferred from the
  existence of a workflow. Source changes are L0 until a successful matching run.
- No Java build/test, shader compilation, GPU/validation-layer run, Minecraft
  session, performance measurement or owner live acceptance.

The native path remains opt-in. No graph/history live path is enabled and no
queue/device/submission/wait operation is introduced. If the baseline fails,
preserve its logs and resolve that baseline separately before claiming a
candidate regression or moving on to live integration.

### Executed CI result — run 40712959065

[Windows CI run](https://github.com/bnfe12/bedray/actions/runs/40712959065)
completed successfully on 2026-10-28. Candidate commit:
`0a076fb1cfb2195521174a3b0b03bb61539f6d2c`.

| Revision | Core CTest | Scene CTest | Frame lifetime CTest | ABI probe |
|---|---:|---:|---:|---|
| Base `aaaf00cb4ad90887739c2f4813841c13f14b62e1` | 12/12 | 1/1 | 1/1 | v2, exit 0 |
| Candidate `0a076fb1cfb2195521174a3b0b03bb61539f6d2c` | 15/15 | 1/1 | 1/1 | v2, exit 0 |

Every configure/build/CTest command exited 0 on both revisions. Candidate's
additional CTest entries are `bedray_abi_probe`, `bedray_abi_exception` and
`bedray_gpu_dynamic_c`. The core sampler test also executed its new exception
cases. Both dependency checksum/version steps and text artifact uploads passed.
Baseline compiled without C compiler warnings reported in the job log;
candidate reported three C4389 signed/unsigned comparison warnings in the new
test. A follow-up makes those comparisons explicitly use the public status type;
that source change requires its own matching run before extending this evidence.

Evidence for the tested native modules at the named commits is **L2**, with no
GPU. The whole Slang/C++ migration remains incomplete; Java, shader compilation,
driver behaviour, real resources and game rendering were not checked.

### Clean follow-up — run 40713467738

[Windows CI run](https://github.com/bnfe12/bedray/actions/runs/40713467738)
completed successfully for candidate
`32794834789a974b5726603b4ffb9bd721882d51` on 2026-10-28.

- Baseline: core 12/12, scene 1/1, frame lifetime 1/1; ABI v2 probe exit 0.
- Candidate: core 15/15, scene 1/1, frame lifetime 1/1; ABI v2 probe exit 0.
- Both job logs contain no C compiler or linker warnings/errors. All logged
  configure/build/test commands exit 0; dependency and evidence-upload steps pass.
- CMake/CTest 3.31.6, MSVC 19.44.35229.0, Windows SDK 10.0.26100.0,
  Visual Studio 17 2022 x64 Release on `windows-2022`.
- Native evidence remains L2. No additional Java, shader or game evidence.

The following journal-only commit does not alter tested code or CI scripts;
its `[skip ci]` marker avoids rerunning identical native tests solely to record
their result. Review code evidence against the exact candidate SHA above.

## 2026-10-28 — host recording completion bridge

Integration base: `16a675afb13a775c7b706d2eec0799ca6a2d2087` (PR #9 merged
after preserving the newer audit commits through `5fc969e`). PR #9 was rebuilt
successfully after conflict resolution in
[run 40716123286](https://github.com/bnfe12/bedray/actions/runs/40716123286).

`RtFrameRenderer` now retires one captured owner set through the same encoder's
post-submission destruction callback, rather than releasing by reused command
buffer address. The CPU `RecordingCompletion` ticket rejects handle reuse while
pending; an old callback cannot free a newer generation. Unsubmitted abandon
releases immediately. Exceptions during execute or callback registration keep
UNKNOWN resources for host-proven completion. Independent release failures
retain their own strong references and can be retried without repeating already
successful owner releases. Scene and Sodium resources use the same callback;
the particle callback captures its recorded composite owner. Scene close remains
retryable when release/destroy fails.

The existing host drain now creates a host fence for the pending shared encoder
submission, submits through the host and awaits its completion. Timeout/failure
retains resources. No native submit/wait or `vkDeviceWaitIdle` is introduced;
the old device-wide wait in this host drain is removed.

Verification (no GPU):

- JDK 25.0.3, Gradle 9.5.1; local launcher-provided 26.3/Sodium/Iris JARs kept
  in ignored `libs/`, Slang 2026.20 verified against the SDK SHA-256 above.
- Baseline and candidate `:common:compileJava`: exit 0, both completed.
- Standalone `javac`/`java` RecordingCompletionChecks: exit 0. Covers completion,
  discard, unknown execute, enqueue failure, duplicate/stale tickets, late
  callbacks, release failure/retry and retention of open recordings during idle.
- `:common:test --tests '*RecordingCompletionTest' --tests
  '*SlangPassLibraryTest.heapAndDirectPushConstantsHaveNativeStorageAndKeepTheInputPosition'
  --tests '*BedrayScene*Test'`: exit 0, **2 tests executed and passed** (the last
  selector matched no test class). Existing Unsafe warnings in Iris test sources
  remain. The invocation sets `BEDRAY_LAUNCHER_LIBRARIES`,
  `-Pneoforge_libs_dir=<local downloaded public loader dependencies>`, `SLANG_DIR`,
  and scopes `-PslangPasses=path_trace.slang,path_trace_scene.slang,path_display.slang,restir_spatial.slang,bloom_*.slang,sodium_dequantize.slang`.
- Those nine AOT programs compile and pass the build-time reflection/layout
  checks. No shader source was changed by this host-lifetime patch.
- Evidence: L1 Java/AOT compilation and L2 CPU lifecycle/heap-direct checks.
  No GPU dispatch, validation layers, game startup or rendered effect checked.

## 2026-10-28 — scene embedded in core and BDA allocation safety

Base `4236c75ab72fded1347dd19d88f38a3804742a38` includes the host-completion
bridge (PR #11). Scene's two implementation sources are now compiled once into
an object target and embedded in core; the standalone scene DLL is retained
for ABI testing. FFM uses the validated core path for scene exports; the player
distribution builds/packages core alone. Gradle inputs include all linked native
modules. No existing C ABI layout/signature changes, no Vulkan loader link,
second device/queue or new submission API.

P0-8 is confirmed in the baseline scene allocator: a BDA usage flag was not
accompanied by the memory allocation flag. The patch attaches the device-address
allocation flags only for buffers that use BDA and rejects a zero returned
address with cleanup before publication. The fake driver checks each memory
binding's usage/allocation flags (including staging without BDA), and the scene
test exercises zero-address failure without recording AS builds. A new dynamic
core test checks all ten scene exports and ABI v2 sizes from the actual core DLL.

Windows native CI now also runs on `codex/migration-*` branches and compares
against this integration base. Native compilation/tests are pending publication
for this patch. No GPU/driver/Minecraft or visual result is claimed.

Executed result: [run 40718040730](https://github.com/bnfe12/bedray/actions/runs/40718040730),
candidate `5e892753d62eeb72a85f732b3ffca9880af08db2`: core **17/17**,
standalone scene **1/1**, frame lifetime **1/1**, ABI probe v2 exit 0.
Baseline core 15/15 + scene 1/1 + lifetime 1/1. Both job logs contain no C/C++
compiler warnings/errors; configure/build/CTest, dependency checks and artifact
uploads all succeed. The embedded scene export probe passes against core DLL.
Local `:common:compileJava` also exits 0 with the same ignored local dependencies
as the host-completion batch. Scoped native evidence is L2, Java compilation L1.
The following journal-only commit skips identical native CI.

## 2026-10-28 — native opaque frame graph and scheduler

Base `7a06dd7b996815fdf3c2177aec6b0cf328260065`. The native coarse path now
constructs `FrameGraphOwner`, compiles one opaque recording group for the real
`PathTracePass::render` callback and leases its recording through FrameScheduler.
The existing completion/discard C ABI release reaches the scheduler's exact
ticket and calls back into `bedray_core_release_recording` to drop descriptors
and pass resource pins. A guarded reentrant path avoids a second ticket release.
Configuration/destruction refuse outstanding graph tickets, including failed
partial recordings. Failed release callbacks retain their ticket/slot for retry.

[MIGRATION_INTEGRATION_DECISIONS.md](MIGRATION_INTEGRATION_DECISIONS.md) records
all eight live-path decisions: pass-owned allocator/private barriers/history,
opaque dispatch group, enabled capability mapping, exact host proof, eight
leases over shared pass image generations, one graphics family, unchanged v2
coarse ABI. No synthetic serial or frame index is used as completion proof.

New no-GPU tests exercise opaque-node barrier suppression, capability rejection,
slot exhaustion, duplicate recording rejection, partial recording failure,
release failure/retry and unknown-handle idempotence. Scheduler tests now require
lease retention when release fails and reject stale exact-ticket proof.
Matching Windows build/CTest results are pending publication. No GPU, driver,
Minecraft, performance or visual evidence is claimed; opt-in stays unchanged.

Executed result: [run 40720124561](https://github.com/bnfe12/bedray/actions/runs/40720124561),
code `5fb4ad1aababe224013ed908eb108358c047fbb0`: core **18/18** (including the
new frame-graph owner), standalone scene **1/1**, frame lifetime **1/1**.
All configure/build/CTest commands exit 0; ABI probe remains v2. Candidate job
log has no C/C++ compiler warnings/errors. Workflow's pinned comparison baseline
is `4236c75` (15/15 + 1/1 + 1/1), while this PR is based on `7a06dd7` whose
native code was separately checked by the preceding scene run. Native evidence
is L2 for the named code. The following journal-only commit skips identical CI.

## 2026-10-28 — native DI reservoir ownership

Base `6fb04a27a2255d1a18e57e9609bef6cd5f14389b`. Adds an optional feature-mask
query and host-only NATIVE_DI/RESET_DI bits without changing ABI v2 structures
or shader push/binding layouts. PathTracePass allocates and pins its two-parity
ring, clears it through the borrowed command buffer and owns history/parity.
Resize/disable retire allocations through the existing completion-proof owner.
Java sends reset requests and scene captures; older DLLs keep the external-ring
contract. The native path remains opt-in. Buffer ranges now validate overflow,
reserved words and complete material/emitter/reservoir records before recording.

Fake-driver checks cover native clear/history/parity, reset, resize with three
outstanding recordings, disable retirement and conflicting host ownership.
Windows native compilation/CTest and local Java compilation are pending.
No GPU, driver, game, performance or visual result is claimed.

Executed result: [run 40723147943](https://github.com/bnfe12/bedray/actions/runs/40723147943),
code `7dc6521`: core 18/18, scene 1/1, frame lifetime 1/1; no native test
failures. Local `:common:compileJava` exits 0 (Java L1). Native evidence is L2;
no live claim. Journal-only follow-up skips unchanged CI.

## 2026-10-28 — single native scene owner

Base `0181549b3537659c6d83bbc3ac655d81cff53ebd`. Opt-in native scene mode now
paces CPU section captures and serializes POD directly into bedray_scene. It
allocates no Java geometry/BLAS arenas, TLAS, decode pipeline, scratch, instance
table or entity BLAS. The existing Java mode remains the reference when native
scene is not selected/available at initialization. A failure after native scene
activation propagates, retains pending updates and never silently omits geometry
or switches to unallocated Java resources. Emitter scanning visits accepted CPU
captures. Native AS counters and actual VkDeviceMemory bytes feed telemetry.

An additive resident-bytes query leaves ABI v2 layouts intact. Entity-only/empty
section sets publish a real zeroed 64-byte section descriptor while section_count
stays zero. Driver tests compare reported resident bytes against all actual fake
allocations, including retired/pinned generations, and check entity-only tables.
A POD packing test checks pass-local indices and vertex attributes. Native scene
is closed after host drain before unloading core; world teardown no longer calls
vkDeviceWaitIdle. Native CI and Java/AOT verification are pending; no GPU/game
or visual evidence is claimed.

Executed result: candidate `ae45bf9`, [run 40724808514](https://github.com/bnfe12/bedray/actions/runs/40724808514)
passes core 18/18 + standalone scene 1/1 + frame lifetime 1/1, ABI v2 probe.
No C/C++ compiler warnings/errors in either job log. The first candidate run
40724238754 correctly caught an old table-test assertion (one upload); publishing
the valid empty section descriptor adds a second upload. That expectation was
updated and the complete native run repeated successfully. Local Java compile
and all 17 AOT passes compile successfully. Targeted JUnit: 19 recording/manifest/
path-contract tests plus 4 scene API/POD packing tests pass. Existing Iris test
Unsafe warnings and JOML deprecation diagnostics remain. Evidence: native/CPU L2,
AOT/Java L1. No GPU/game result. Journal-only follow-up skips unchanged native CI.
