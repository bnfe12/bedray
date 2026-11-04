# SR residency and native release audit — original findings

The implementation of these findings is described in
[DLSS_RESOURCE_LIFETIME.md](DLSS_RESOURCE_LIFETIME.md). The observations below are
the pre-fix audit, preserved as rationale; RTX live allocation/FPS verification
remains open.

These are source findings. This document does not claim a measured allocation
size, an observed SDK failure in the owner's run, or an implemented release fix.

## Unused resident SR feature

`DlssRuntime.ensureFeature` is already lazy: only `DlssBackend.dispatch` creates
the SR feature. The initial RR bootstrap or a previous SR mode can nevertheless
leave an SR feature resident. Steady single-RR presentation bypasses SR, while
the unchanged READY request does not retire that handle.

The future release must retire only that feature, preserving READY,
activeInput/mode, effective featureMode, output dimensions, initEpoch and NGX
initialization. After `TemporalUpscaler.onFrameBegin`, before world recording,
use the same pure `WorldResolutionPlan.choose` inputs as the world route. The
phase-only `rrResolvesOutput()` is false at frame head and is not a valid guard.
Existing feature retirement proves the velocity/SR command buffer through
`WorldTargetManager.drainTemporalRecordings`. Leaving RR changes the resolution
plan/history reset and can lazily recreate SR.

## Release acknowledgement must be repaired first

Direct NGX `vkb_dlss_release_feature` currently erases the Feature registry entry,
destroys parameters and deletes the object before checking the SDK release
result (`native/dlss_bridge/src/vkb_dlss.cpp`). Java correctly retains its handle
on failure, but native has already lost that handle and a retry cannot release
it. The registry, parameters and release state must survive SDK failure; cleanup
must follow acknowledgement. RR delegates to the same release function.

SL SR/RR release functions (`native/sl_bridge/src/vkb_sl_dlss.cpp`) clear valid
before `slFreeResources` and ignore its result. They must preserve state until
SDK acknowledgement. There is an important initial state: wrapper creation
only sets options; GPU viewport resources may not exist before the first
evaluate. Track `resourcesMayExist` separately for SR and RR, marking it before
an evaluation attempt, including a failing attempt. A never-evaluated pseudo
feature can retire without freeing a nonexistent SDK viewport. See the pinned
[Streamline resource callback](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/source/plugins/sl.dlss/dlssEntry.cpp#L884-L902).

SR release targets feature DLSS/viewport0; RR uses its separate RR feature on
viewport0, and FG viewport1. Streamline dispatches resource release to the
[selected feature callback](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/source/core/sl.api/sl.cpp#L429-L440).
Optimal-size queries depend on runtime initialization, not the SR feature handle.

## Required checks

Verify one release per RR transition, preserved READY/settings/queries, retained
ownership and successful retry after fence/SDK errors, lazy SR recreation with
history reset, and independent RR/FG resources. Test create-before-evaluate,
partial evaluation failure and acknowledged cleanup with pinned SDK headers and
actual wrappers. SDK/runtime builds and RTX acceptance remain required; the
generic retirement tests alone do not validate these native wrappers.
