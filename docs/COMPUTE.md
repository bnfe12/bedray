# Bedray Compute: current development milestone

Bedray Compute is an experimental companion mod with two traversal diagnostics:

- **Synthetic scene**: a fixed 32 x 24 x 32 voxel scene, sunlight and traced voxel shadows.
- **World occupancy diagnostic**: a 48 x 48 x 48 region of loaded Minecraft sections around the current camera. Static full-occlusion cubes use face-normal colors; unsupported nonempty cells are magenta and unloaded data is blue. These markers are hit by rays rather than silently becoming air. This view does not claim texture/model/PBR accuracy.

This is a traversal/integration milestone, not a playable software path tracer. Live Minecraft capture/presentation still requires an in-game acceptance run.

The shader uses ordinary Vulkan 1.0 compute, storage images and storage buffers. It does not request ray tracing, acceleration structures, ray queries, device addresses, Int64, vendor SDKs or DLLs. This is an API requirement, not a promise that every GPU can run the Minecraft Vulkan backend or achieve usable performance.

## Install a development candidate

Use a matching Bedray host **0.1.1-alpha.2 or newer** and exactly one companion for the same loader. The live-scene API is absent from the older release and the first synthetic-only candidate. Do not install both loader variants or another copy of the API. Default JARs contain no native libraries and perform no binary downloads.

In Bedray's own configuration screen, open the Ray Tracing category, choose **Compute Scene**, then enable **Compute Diagnostic**. Alternatively `-Dbedray.compute.demo=true` requests the default synthetic scene. Requests are session-only; they do not change existing hardware RT configuration. Disable temporal upscaling and Frame Generation before enabling either diagnostic: they do not supply matching depth/motion signals. The status row reports disabled, requested, failed or commands recorded; command recording is not proof of presentation.

World capture reads **one 16 cubed section per rendered frame**, completing a sweep in 27 frames. It uses only the client's loaded chunk lookup with creation disabled. It publishes a complete immutable region, retains a previous complete region while it still contains the camera, and clears snapshots on world changes. Periodic sweeps refresh block edits and chunk load/unload changes; snapshot age is deliberately visible in the status. The snapshot is assembled over multiple frames, not an atomic world-tick capture.

Only cached block state/occlusion properties are queried. Partial shapes, fluids, animated/dynamic shapes and non-solid models remain explicit excluded markers. Unloaded sections remain unknown. Out-of-build-height sections are empty. There is no forced chunk generation and no inferred texture alpha. Repeated unchanged sweeps do not upload a new revision. Changed grids are copied into Vulkan command buffers in bounded update chunks with read/write barriers; the CPU does not overwrite buffers used by in-flight frames. Camera matrices and bob-origin offsets are paired, and world coordinates are rebased in double before conversion to float.

The normal renderer is retained when the companion is absent, disabled, incompatible with the selected temporal features, or cannot initialize. Initialization failures are latched until the request is toggled. A failed cleanup requires restarting the client rather than reusing stale device handles. Resize, world exit, mode changes and device close submit pending host commands before releasing provider resources. The host owns the device, graphics queue, swapchain and command submission.

## Build

Use the normal [build prerequisites](BUILDING.md), Java 25 and the local game/loader compile libraries. Machine-specific library paths stay outside the repository.

```text
gradlew :compute-common:test :neoforge:jar :fabric:jar :compute-neoforge:jar :compute-fabric:jar
```

The host JARs are in `neoforge/build/libs` and `fabric/build/libs`. Companion JARs are in `compute-neoforge/build/libs` and `compute-fabric/build/libs`. The public API is compiled from `renderer-api`; only the host loader JAR includes its classes.

## Focused verification

`compute-common:test` checks bounded/immutable grid storage, little endian GPU layout, memory budgets, DDA slabs, exact faces, tied edges/corners, half-open ray bounds and comparison with an independent occupied-box oracle. It also compiles the actual shader for Vulkan 1.0 and rejects SPIR-V capabilities outside Shader/ImageQuery.

```text
gradlew :compute-common:vulkanProbe
```

This explicit offscreen GPU probe creates a Vulkan 1.0 device with **zero enabled device extensions and optional features**. It dispatches the actual pipeline, reads 192 x 128 hit IDs back, and compares them with the CPU reference. It also tests the world-camera push layout and successive revisions on the same pipeline, including moved/removed cubes and excluded/unknown markers. It allows at most 0.1% numerical boundary mismatches, each requiring a matching nearby CPU ray within 0.001 pixel; exact counts are printed. It renders the synthetic shaded scene to `compute-common/build/reports/compute/voxel-vulkan.png`. It fails if no suitable device exists. This does not launch Minecraft, validate its capture/presentation/lifecycle, or prove acceptable performance on GPUs without RT hardware.

The offscreen probe uses a separate device because it is an isolated test. The in-game companion never creates one. Tests do not deploy JARs into any game instance.

## Next implementation gates

1. Validate loaded-world capture and the current camera/bob path in Minecraft; exercise repeated world changes, reanchoring, block edits and chunk unloads using matching-build logs.
2. Add source-based section invalidation and measured capture/upload budgets. Preserve explicit unknown/excluded classifications; never turn them into ordinary air.
3. Add previous-frame camera/history contracts before accumulation or temporal reconstruction.
4. Port texture/color/emission/material lookup through the API, with explicit treatment for partial shapes, alpha, fluids, signs and entities.
5. Add world light transport, accumulation, denoising and matching temporal guides before enabling temporal upscaling or FG.
6. Validate in-game toggles, resize, repeated world entry/exit, device teardown, artifact provenance and NVIDIA/AMD/Intel hardware. Measure memory/frame time and publish an exact compatibility matrix.

See the [full specification](SOFTWARE_RENDERER_MOD_SPEC.md) for the longer roadmap and merge criteria.
