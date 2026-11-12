# 14 — PTLAS / regional TLAS updates: feasibility + disposition

Status (2026-11-12): **regional in-place update already present; full partitioned TLAS deferred
(greenfield, NV-only, not a measured bottleneck).** Deliverable for catalog step "PTLAS / regional
TLAS updates": verified current state + feasibility + the evidence for not prioritizing it.

## What already exists (verified, file:line)

The TLAS is **not** a blind per-frame rebuild. `native/bedray_scene/src/scene.cpp`:

* `plan_tlas` (`:1042`) skips all TLAS work when the slot's version, instance count and view match
  (`need == false`, `:1054-1060`) — idle frames cost nothing.
* In-place **UPDATE** is chosen when `slot.tlas_valid && CAP_AS_UPDATE && instance_count unchanged`
  (`:1062-1064`); a full rebuild happens only on membership/count/view change (`:1073-1078`).
* `query_tlas_sizes` sets `ALLOW_UPDATE` (`acceleration_scene.cpp:549-551`); `record_tlas_build`
  emits `MODE_UPDATE` vs `MODE_BUILD` (`:727-729`). Flags `TLAS_UPDATED/REBUILT` (`scene.cpp:2233`).
* Stable instance identity: `section_slots` (section id -> append-ordered slot) is the
  `instanceCustomIndex` (`scene.cpp:190-193, :861, :893-895`). The host already tracks per-section
  change: `NativeSectionCapture.java:93-105` (identical-skip + `structureVersion`), removals
  `:117/:156`; `NativeSceneBridge.java:181-203` keeps the native id across re-mesh.

So the cheap, broadly-compatible part of "regional TLAS update" (skip idle, refit in place, stable
ids, host dirty events) is **done**. What is absent is *sub-TLAS* granularity: any membership change
bumps `scene.version` and rewrites the whole instance buffer; there is no dirty-region partition.

## Full partitioned TLAS: feasibility

* The real extension is **VK_NV_partitioned_acceleration_structure** (not EXT). SDK Vulkan-Headers
  declare it (`vulkan_core.h:25655`, structs 1000570000+). `VK_EXT_partitioned` does not exist.
* **LWJGL 3.3.3 has 0 `PartitionedAcceleration*` classes** (verified in `lwjgl-vulkan-3.3.3.jar`);
  the 3.4.1/3.4.3 launcher runtime jars have them. The native core uses its own `VulkanFns` loader,
  so C++ could call it without LWJGL, but the Java host owns device-feature gating and would need
  the binding (or a manual pNext struct) to enable it at startup.
* **Zero implementation today** — `partition`/`PTLAS` appear only in docs
  (`ARCHITECTURE_MANIFESTO.md:50`, `STACK_IMPLEMENTATION_PLAN.md:45,65,129-133,193`). It needs its
  own build/descriptor contracts and separate supported/enabled/implemented states (ledger 2.2).
* NV-only: mandatory fallback to the ordinary whole-TLAS update path on non-NV devices.

## Why deferred (evidence)

* **TLAS is not the measured bottleneck.** `tlas_ms` is CPU wall time of `prepareFrame`
  (`RtWorld.java:32,100-109`), not a GPU timestamp; no captured `tlas_ms` numbers exist in
  `docs/measurements`. The dominant cost is the trace pass: `rt_pass_gpu_ewma work_ms_median ~24 ms`
  of a ~33.6 ms frame at RD16 (`ser_ab_rd16/raygen_pt_on.json`). Idle frames already skip TLAS work.
* **Disproportionate risk for an unproven win.** A new NV-only descriptor/build contract, additive
  scene ABI, and generation-lifetime rules — for a cost that is not shown to matter here. The
  "2.88 -> 0.79 ms" figure in the task brief is an external demo, not a measurement on this workload.

## If pursued later (measurement gate first)

1. Add GPU timestamp queries around the TLAS build/update and capture `tlas_ms` on **block-edit**
   and **teleport** scenarios (the cases that actually change membership). Only proceed if TLAS is a
   measurable fraction of the frame there.
2. Gate `VK_NV_partitioned_acceleration_structure` at startup (host feature set), default off,
   supported/enabled/implemented checks, ordinary whole-TLAS update as the fallback.
3. Additive scene ABI for partition membership + per-partition version; rebuild only dirty
   partitions; keep stable `instanceCustomIndex` identity.
4. A/B `tlas_ms` and frame interval before/after; enable only on a measured net win (L4).

## Disposition

No speculative partitioned-TLAS code was written. The existing idle-skip + in-place update path is
left as-is (it is the cheap, compatible regional optimization). Full PTLAS is deferred pending a
measured TLAS bottleneck on edit/teleport workloads.