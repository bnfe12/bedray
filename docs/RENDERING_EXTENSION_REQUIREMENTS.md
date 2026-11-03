# Rendering extension requirements — Bedray 0.2.0

These are architecture requirements and follow-on work, not implemented or
verified features. Bedray's host integration remains Java where Minecraft
requires it; renderer policy, GPU resource management and scheduling belong in
the native core, with shader implementations in Slang. Minecraft's Vulkan
device, queue and presentation remain owned by the game.

## Sparse sampling and compact surface data

- Keep sampling density independent from the physical G-buffer encoding: sparse
  shading and compact pixel formats are separate choices. Represent the sampling
  pattern, render resolution, reconstruction method and required surface data
  explicitly in the render plan, with capability and memory-budget checks.
- Choose formats from actual consumers, precision needs and device format
  support. Track bytes per resource, all history slots, frames in flight and
  retained resources, not merely the nominal size of one G-buffer.
- Reconstruction, motion vectors, disocclusion and material identity need
  explicit producers and history reset rules. Do not claim equivalent image
  quality or VRAM savings until measured in representative Minecraft scenes.
- NVIDIA describes sparse rendering with a compact G-buffer and DLSS Ray
  Reconstruction in its Remix runtime; it is a reference, not an assumption
  that this architecture or vendor-specific method is available to Bedray:
  https://github.com/NVIDIAGameWorks/rtx-remix/releases

## Authored lighting zones

- Support an optional, data-driven source of spatial lighting profiles so map
  authors can control parameters such as fog and exposure, with deterministic
  overlap, priority, transition and dimension/unload behaviour. The format,
  multiplayer authority and integration with existing resource packs require
  a deliberate contract; command-block discovery is one candidate, not yet a
  defined or trusted protocol.
- Java may read world data and expose commands when required by Minecraft.
  Send normalized, bounded profile data over a coarse native interface; native
  policy resolves profiles and invalidates only the histories affected by a
  change. User and world settings precedence must be explicit.
- NVIDIA documents map-authored lighting profiles in its project. Class names
  and the exact command syntax in external descriptions are not established
  by this document: https://github.com/NVIDIAGameWorks/rtx-remix-minecraft

## Compatibility and lifecycle

- Treat Sodium chunk/mesh capture and other mod integration as first-class
  interfaces, including rebuilds, reload, resizing, dimensions and shutdown;
  never assume a single renderer implementation or a separate Vulkan device.
- Keep frame generation optional and capability-gated; handle device loss,
  queue ownership and resource retirement without affecting the game host.
  NVIDIA issue #33 is a report on a desktop RTX 5060 Ti, not evidence of a
  laptop-specific bug: https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues/33
- Explicitly specify coordinate orientation, projection/depth conventions and
  UI composition. NVIDIA's Proton report is useful as a scenario, not proof
  of the root cause or that native Vulkan Bedray has the same defect:
  https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues/41

## Repeatable performance tooling

- Keep developer tooling outside the shipped mod. Record deterministic camera
  paths, world and resource-pack identities, artifact hashes, device/driver,
  settings, warm-up and frame-time distributions. Include VRAM and allocation
  accounting when available and preserve logs for failed runs.
- Provide JFR and native/GPU profiler integration as optional adapters, not as
  mandatory runtime dependencies. Prefer isolated launch profiles and never
  overwrite a running instance. The user signs in; tools must not handle or
  store credentials.
- NVIDIA's Python/uv tooling, Prism launch workflow and JFR/Tracy integration
  are examples: https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/main/tools/README.md

Implementation priority: establish scene, frame and synchronization contracts
for the new renderer first; integrate optional sparse, zone and profiling
modules through those interfaces. No specific feature in this document has
been compiled, benchmarked, launched or accepted in game.
