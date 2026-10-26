# AI Addendum 02 — C++ Render Graph and Frame Scheduler

You are an isolated Bedray 0.2.0 agent. Your only responsibility is a clean
C++20 render-graph and frame-scheduler subsystem for the native renderer core.
Do not work on scene/acceleration-structure ownership, ReSTIR PT, Radiance
Cascades tracing, Java host code, UI, or deployment.

## Non-negotiable architecture

- Java remains the Minecraft/Fabric/NeoForge host. It selects the render phase,
  owns the game Vulkan handles, and invokes one coarse native render call.
- C++ owns Bedray pipeline selection, descriptor/pipeline resources, pass
  ordering, transient Bedray resources, and synchronization for its own work.
- Slang supplies AOT SPIR-V entry points and reflection manifests. Runtime GLSL
  compilation and legacy preprocessor variants are forbidden in the new path.
- Existing Java classes are behaviour references only. Do not port their
  structure or manual binding tables line-by-line.

## Isolation

- `C:\BedRay` is dirty and read-only. Do not modify it, execute Git commands
  there, run Gradle, start Minecraft, deploy, commit, reset, stash, or clean.
- Work only in `C:\Users\andre\AppData\Local\Temp\opencode\agent-cpp-render-graph`.
- You may read these references from `C:\BedRay`:
  - `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java`
  - `.../rt/world/RtPathTracerPass.java`
  - `.../rt/world/RtRayEffectsPass.java`
  - `.../rt/world/RtCompositePipeline.java`
  - `.../rt/world/RtBloomPass.java`
  - `.../rt/world/RtVolumetricPass.java`
  - `.../rt/world/RtVulkanPipelineCache.java`
  - `.../rt/denoise/NrdPackPipeline.java`
  - `native/bedray_core/include/bedray_core.h`
- Read them only as an external specification of required effects and failure
  containment. Do not copy their Java architecture.

## Deliverable

Create a self-contained native candidate in the isolated workspace:

```
include/bedray_render_graph.h
src/render_graph.cpp
src/frame_scheduler.cpp
src/reflection_manifest.cpp
tests/render_graph_contract_test.cpp
RENDER_GRAPH_NOTES.md
```

It must be ready to integrate beneath `native/bedray_core/` but must not edit
any file in the repository. Use C++20 and Vulkan-Headers only; do not link or
own the Vulkan loader.

Required capabilities:

1. **Semantic resource graph**
   - Describe resources by semantic names and usage (for example
     `scene.tlas`, `output.color`, `history.radiance`, `denoise.normal`), not
     hard-coded descriptor-set or binding indices.
   - Consume a validated AOT Slang reflection manifest that maps those semantic
     resources to pipeline bindings. Reject missing, duplicate, unknown, or
     type-incompatible resources before command recording.
   - Keep reflection parsing/validation separate from Vulkan object creation.

2. **Pass and dependency model**
   - Support named compute passes with explicit read/write resource usages,
     queue-family constraints, push constants, and dispatch dimensions.
   - Topologically order passes; reject cycles and write/write ambiguity.
   - Derive barriers only between Bedray-owned resources. Do not touch global
     game state, submit work, present, or wait idle.
   - Use per-frame transient allocations and persistent history resources with
     explicit invalidation conditions.

3. **RenderPlan policy**
   - Select passes via capability-driven `RenderPlan`: Bitmask HDDA/Hybrid/HW
     traversal, ReSTIR DI/PT, Radiance Cascades, denoising/upscaling.
   - No GPU-name checks, `#ifdef` feature trees, or legacy GLSL variants.
   - Unsupported optional passes must yield a host-visible status and preserve
     the Java-owned vanilla/Sodium composition path.

4. **Native boundary**
   - The scheduler receives an already-recording command buffer from the Java
     host and may record only Bedray commands.
   - It owns no VkInstance/VkDevice/VkQueue and never calls submit/present/wait.
   - Exceptions must remain internal; produce structured errors suitable for
     `BedrayStatus` at the C ABI boundary.

## Validation

- Compile with MSVC/CMake and Vulkan-Headers only.
- Add a no-GPU contract test that constructs a multi-pass plan, validates its
  ordering/barrier plan, rejects an invalid reflection manifest, and proves a
  missing optional feature disables only that pass.
- State exact commands, exit codes, files created, and limitations.
- Evidence is L1 source/build/contract-test only; no claim of live GPU or
  Minecraft operation is allowed.

## Required report

Write `RENDER_GRAPH_NOTES.md` in English. Include:

- reflection-manifest schema and semantic resource contract;
- pass/resource dependency and barrier model;
- `RenderPlan` to pass-graph mapping;
- history invalidation and per-frame lifetime rules;
- mapping from Java references to new C++ responsibilities;
- open ABI decisions for the integrating agent;
- exact test/build evidence and live-validation gap.
