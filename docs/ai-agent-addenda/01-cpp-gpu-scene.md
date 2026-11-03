# AI Addendum 01 — C++ GPU Scene and Acceleration Structures

You are an isolated Bedray 0.2.0 agent. Your only responsibility is a new
C++20 GPU scene and acceleration-structure subsystem. Do not work on Slang
algorithms, Java host integration, render-graph scheduling, UI, or deployment.

## Non-negotiable architecture

- Java is the Minecraft host only: Fabric/NeoForge lifecycle, mixins, world
  capture, resource-pack/atlas access, configuration, diagnostics, and the
  render hook.
- C++ owns the renderer core: Bedray GPU resources, scene representation,
  BLAS/TLAS lifecycle, and all barriers for Bedray-owned resources.
- Slang owns rendering algorithms. Do not add descriptor bindings to Slang.
- The existing Java renderer is a read-only behavioural and data-format
  reference, not source to translate line-by-line.
- The C++ core uses the host's VkInstance, VkPhysicalDevice, VkDevice, queue,
  command buffer, and proc-address functions. It never creates a second Vulkan
  device, queue, swapchain, or submits/presents/waits idle.

## Isolation

- `C:\BedRay` is dirty and read-only. Do not modify it, run Git commands there,
  run Gradle, launch Minecraft, deploy, commit, reset, stash, or clean.
- Create and use only `C:\Users\andre\AppData\Local\Temp\opencode\agent-cpp-scene`.
- You may read these references from `C:\BedRay`:
  - `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtWorld.java`
  - `.../rt/world/RtInstanceWriter.java`
  - `.../rt/world/RtEntityBlas.java`
  - `.../rt/entity/RtEntityGeometryManager.java`
  - `.../rt/sodium/SectionGeometryRegistry.java`
  - `.../rt/sodium/DecodedSection.java`
  - `.../rt/sodium/SodiumMeshDecoder.java`
  - `.../rt/core/BedrayCoreNative.java`
  - `native/bedray_core/include/bedray_core.h`
- Read them for semantics, lifetime constraints, and data layout only. Do not
  copy their class layout or reintroduce their legacy renderer architecture.

## Deliverable

Build a self-contained candidate C++ subsystem in your workspace:

```
include/bedray_scene.h
src/scene.cpp
src/acceleration_scene.cpp
tests/scene_contract_test.cpp
SCENE_NOTES.md
```

The public surface must use C ABI-compatible POD input records and opaque C++
owners internally. It must be suitable for later inclusion in
`native/bedray_core/`, but do not edit the repository's ABI header yourself.

Required capabilities:

1. **Explicit ownership and lifetime**
   - Represent static sections, dynamic entities, material/light tables, BLAS,
     TLAS, scratch buffers, and per-frame scene snapshots as separate native
     resources.
   - Model `framesInFlight`; no global `vkDeviceWaitIdle` and no destruction of
     resources still reachable by an in-flight command buffer.
   - Accept batched section/entity updates from Java through contiguous POD
     memory. Never retain Java object references or FFM pointers past the call.

2. **AS update policy**
   - Static terrain: build or rebuild only when a section geometry revision
     changes.
   - Dynamic entities: update/refit where supported; rebuild only when topology
     changes.
   - TLAS: rebuild/update from current instance records without assuming any
     fixed descriptor-set number.
   - Make the policy capability-driven, not GPU-name-driven.

3. **Synchronization contract**
   - Emit only barriers for Bedray-owned buffers/images/AS.
   - State the required compute-upload -> AS-build -> ray/compute-read stages
     and access masks explicitly.
   - Do not synchronise unrelated Minecraft/Sodium resources and do not submit
     command buffers.

4. **Failure model**
   - No C++ exceptions cross any future C ABI.
   - Return status/error information suitable for `BedrayStatus`.
   - Treat unsupported RT features and device loss as recoverable host-visible
     outcomes, not process termination.

5. **No legacy/temporary path**
   - Do not call runtime Shaderc, create a Vulkan instance/device/queue, use
     Java-side renderer classes, add `#ifdef` variants, or create a fallback
     renderer in this component.

## Validation

- Compile the C++ code with MSVC/CMake and the same Vulkan-Headers-only model
  used by `native/bedray_core`.
- Add a no-GPU contract test using a fake command recorder or equivalent to
  prove update coalescing, topology-vs-transform policy, per-frame retirement,
  and that no submission/wait API belongs to the subsystem.
- Report exact commands, exit codes, files created, and limitations.
- This is L1 source/build evidence only; never claim live Minecraft or GPU
  validation.

## Required report

Write `SCENE_NOTES.md` in English with:

- public POD input/output contract;
- resource ownership graph and frame-retirement policy;
- required Vulkan functions and barrier semantics;
- mappings from read-only Java references to the new C++ concepts;
- open ABI decisions for the integrating agent;
- test/build evidence and remaining live-validation requirements.
