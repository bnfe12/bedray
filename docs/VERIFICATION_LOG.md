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
Java execution is claimed. P0-3/5/6 remain separate shader-owner review items;
P0-4 requires shader-owner investigation. This PR changes no shader code and
does not certify shader math.

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
