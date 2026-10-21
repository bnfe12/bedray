# Bedray Compute: current development milestone

Bedray Compute is an experimental companion mod. Its first milestone renders a **synthetic 32 x 24 x 32 voxel scene**, with a fixed camera, direct sunlight and traced voxel shadows. It does not replace Minecraft world geometry yet. This is a traversal/integration diagnostic, not a playable software path tracer.

The shader uses ordinary Vulkan 1.0 compute, storage images and storage buffers. It does not request ray tracing, acceleration structures, ray queries, device addresses, Int64, vendor SDKs or DLLs. This is an API requirement, not a promise that every GPU can run the Minecraft Vulkan backend or achieve usable performance.

## Install a development candidate

Use a matching Bedray host **0.1.1-alpha.1 or newer** and exactly one companion for the same loader. This API is absent from the older 0.1.0-alpha.1 release. Do not install both loader variants or another copy of the API. Default JARs contain no native libraries and perform no binary downloads.

In Bedray's own configuration screen, open the Ray Tracing category and enable **Compute Synthetic Scene**. Alternatively set the JVM argument `-Dbedray.compute.demo=true`. The request is session-only; it does not change existing hardware RT configuration. Disable temporal upscaling and Frame Generation before enabling the diagnostic: the fixed synthetic camera has no matching world motion/depth signals. The status row reports disabled, requested, failed or commands recorded; command recording is not proof of presentation.

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

This explicit offscreen GPU probe creates a Vulkan 1.0 device with **zero enabled device extensions and optional features**. It dispatches the actual pipeline, reads 192 x 128 hit IDs back, and compares them with the CPU reference. It allows at most 0.1% numerical boundary mismatches, each requiring a matching nearby CPU ray within 0.001 pixel; exact counts are printed. It then renders the shaded scene to `compute-common/build/reports/compute/voxel-vulkan.png`. It fails if no suitable device exists. This does not launch Minecraft, validate its presentation/lifecycle, or prove acceptable performance on GPUs without RT hardware.

The offscreen probe uses a separate device because it is an isolated test. The in-game companion never creates one. Tests do not deploy JARs into any game instance.

## Next implementation gates

### Live-world CPU input (development)

The host now offers immutable `WorldSnapshot`, `SectionSnapshot` and `CameraSnapshot` data through
optional API callbacks. Enable collection with `-Dbedray.compute.captureWorld=true` **and** enable
the synthetic diagnostic normally. This collects CPU input only: it does not change the diagnostic image
into a live-world render. The existing shader still uses its synthetic scene and fixed camera.

Collection is independent of the hardware RT geometry gate. The host polls a 3×3×3 section window around
the camera, reads at most two sections per rendered frame and retains at most 27 sections (108 KiB of
occupancy payload). A stationary window is revisited within 14 frames; block edits therefore have bounded
polling latency rather than same-frame revision events. Only already loaded chunks are read with creation
disabled. Unloads and movement outside the window remove resident sections; missing sections are unknown.
Only conservative opaque full cubes are classified as occupied. Partial shapes, fluids, dynamic shapes and
other unsupported cells have a separate value, never an invented material ID or solid-cube fallback.

Snapshots carry world session and monotonic content revisions. Their full resident set replaces the prior
set; section objects are reused when occupancy does not change. The companion cache handles negative
coordinates and resets previous-camera history between sessions. Camera data preserves a double-precision
world position and inverse view/projection matrices from the unjittered world pass, including the host's
bob projection when enabled. No temporal validity or GPU upload is claimed yet.

Run the dependency-free CPU contract suite with JDK 21+ (or set `JAVA_HOME`):

```text
bash tools/test-world-snapshots.sh
```

It checks budgets, polling updates, unchanged revisions, unloads, movement, world resets, negative
coordinates, immutable storage and the companion's distinction between unknown and unsupported cells.
The Minecraft adapter still requires a full host build and in-game lifecycle validation.

### Remaining gates

1. Validate the new occupancy/camera adapter in-game; extend it with versioned block/material snapshots. Visible captured faces are not sufficient to infer full block occupancy.
2. Keep unloaded sections empty/unknown without synchronous world generation; handle revisions, unloads and bounded upload budgets.
3. Add the current/previous camera contract including head bob and origin rebasing, then live opaque full-cube traversal and debug hit views.
4. Port texture/color/emission/material lookup through the API, with explicit treatment for partial shapes, alpha, fluids, signs and entities.
5. Add world light transport, accumulation, denoising and matching temporal guides before enabling temporal upscaling or FG.
6. Validate in-game toggles, resize, repeated world entry/exit, device teardown, artifact provenance and NVIDIA/AMD/Intel hardware. Measure memory/frame time and publish an exact compatibility matrix.

See the [full specification](SOFTWARE_RENDERER_MOD_SPEC.md) for the longer roadmap and merge criteria.
