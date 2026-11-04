# NoRT AOT execution

The active companion renderer now calls the same native core used by Slang RT
passes. `renderer-api` minor 7 adds a borrowed `AotComputeExecutor`; `common`
implements it using `SlangRuntimePool`, and `compute-common` consumes only the
API. Its production dependency does not point back into `common`. Older API
providers retain their original render overload; the updated companion requires
host API 1.7. Rebuild and update the host and companion jars together.

Five reachable AOT entries specialize `NoRtRenderer<S,M,R>` over dense/streamed
scene data, synthetic/textured materials and present/absent raster inputs.
Actual modules split inputs, layouts, geometry, materials, traversal, lighting,
history and composition. There are no include chains or preprocessor variants.
The old companion `voxel.comp`, `ComputeShaderCompiler`, and Java shader-module,
pipeline-layout, descriptor-pool and compute-pipeline construction are removed.
Small input methods are explicitly inlined to specialize away absent resources;
synthetic rendering requires two descriptors and allocates no dummy inputs.

This is the working packed-scene renderer, not a claim that the previously
unused uint64 HDDA layout or full Voxel Fast mode is now integrated. The shader
retains the current 32-bit cell producer, 64-byte streamed header, 48-byte
directory entries, 32-byte cube faces, 96-byte model quads, 112-byte push block,
and 4304-byte environment. Reflection pins the structured layouts. Raw buffers
report a one-byte address unit, rather than inventing a structured-record stride.
History stays RGBA32_UINT. Native generic dispatch owns immutable descriptors and
pipeline lifetime; Java still owns scene uploads, images, barriers and history
authority. Public native ABI-v2 layouts are unchanged.

The native core baseline is Vulkan 1.1. This deliberately supersedes the old
companion's Vulkan-1.0 minimum; optional device features/extensions remain zero.
Generated NoRT SPIR-V is checked for only Shader and ImageQuery capabilities and
passes the production undefined-descriptor-pointer gate. Built-in
`packHalf2x16`/`unpackHalf2x16ToFloat` preserve packed history without adding
shaderFloat16 or shaderInt16 requirements. No ray queries, acceleration
structures, device addresses or explicit 16/64-bit arithmetic are used.

## Recording and recovery

`ComputeRecording` registers its ticket before work. False after uploads,
renderer exceptions and command-buffer-end failures abandon the whole CB and
prove discard; no prefix is executed. Execute or callback-enqueue exceptions
retain unknown outcomes until host drain. Six behavioral tests cover successful
deferred retirement and each failure stage. The host flushes, waits for device
completion and retires native recordings before closing provider resources and
the runtime lease. Steady-state frames never wait. A failed drain retains owners
and refuses reuse or borrowed-target retirement.

Discarded CPU upload publication, layout flags and temporal bookkeeping cannot
be reused. The provider generation is destroyed after host proof; scene capture
and provider snapshots are cleared for recapture. Recording failures log backend,
frame, request revision, command-buffer address, submission outcome, recovery
and the original exception. Existing native registration logs identify the pass.

## Verification and remaining work

The Vulkan probe uses a real Vulkan-1.1 device with zero enabled instance/device
extensions and optional device features, and the native renderer-core DLL.
It covers CPU/GPU hit comparisons, successive section updates, model/tint/light
decoding, 25-frame accumulation, depth-gated raster preservation, the actual
streamed backend at large positive/negative coordinates, world reset and missing
surface preservation. Native recordings are retired after each queue completion.
This is offscreen evidence on Intel UHD, not in-game RTX performance acceptance.

Run `:compute-common:vulkanProbe -PbedrayCorePath=<tested bedray_core.dll>` after
building the complete AOT index. The final PR records exact suite verdicts.
Full native scene/history/image ownership, shared typed RT surface/motion guides,
multilevel occupancy, RC-compute, Hybrid partitioning, transparent composition
and gameplay/1080p60 acceptance remain open in `STACK_IMPLEMENTATION_PLAN.md`.
