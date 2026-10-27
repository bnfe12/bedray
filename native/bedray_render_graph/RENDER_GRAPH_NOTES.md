# Bedray render graph — integration notes

Status: L1 source/build/contract-test candidate. Nothing in this document is a
claim of GPU, driver, live Minecraft or performance validation. The graph and
the Vulkan adapter are CPU planners; they create no Vulkan objects, submit no
work and wait on nothing.

## Reflection manifest schema and semantic resource contract

The build-time Slang reflection adapter emits a text manifest. The parser is
Vulkan-independent and leaves its output unchanged on failure.

```
bedray-reflection 1
pass <pass-name> entry=<entry-point>
binding <pass-name> <semantic> <kind> set=<u32> binding=<u32> count=<u32> name=<shader-name>
push <pass-name> offset=<u32> size=<u32>
```

- The first non-comment, non-blank line must be exactly `bedray-reflection 1`.
  Other versions fail with `unsupported_version`.
- One directive per line; `#` starts a comment; spaces and tabs separate tokens;
  CRLF input is accepted. Malformed arity, keys, numeric values or unknown
  directives fail with `syntax`.
- `kind` is one of `uniform_buffer`, `storage_buffer`, `sampled_image`,
  `storage_image`, `combined_image_sampler`, `acceleration_structure`.
- Pass names are unique. `binding`/`push` directives may precede the pass they
  name in the file, but the pass must exist in the manifest after parsing;
  otherwise parsing fails with `unknown_pass`. Duplicate passes fail at parse.
- Validation requires exactly one manifest pass for every graph pass and vice
  versa (`missing_pass`, `unknown_pass`), a non-empty entry point, and calls
  `resolve_bindings` for each pass (`invalid_binding`).

The semantic contract:

- A `ResourceDesc::semantic` is the stable identity shared with the shader
  manifest (for example `scene.tlas`, `history.radiance`, `output.color`).
  Semantics are non-empty and unique within a graph.
- Each reflected binding names a semantic that must be a declared use of that
  pass. The binding kind must match the resource kind; read-only kinds require
  `Access::read`, storage kinds accept read/write/read_write; the use stage must
  be compute. Descriptor set must be 0 and descriptor count must be 1 in the
  current ABI.
- Binding names, `(set, binding)` pairs and semantics must be unique inside a
  pass. The result is a one-to-one mapping from every graph resource use of the
  pass to exactly one shader binding; an unbound use fails with
  `missing_binding`, and any size mismatch fails with `invalid_binding`.
- Declared push-constant ranges must match the graph pass ranges exactly as an
  unordered multiset. Values are not stored here; the host or native recorder
  supplies them at dispatch time.
- The graph compiler independently enforces 4-byte alignment, non-empty ranges,
  no overlap, and the 128-byte Vulkan minimum push-constant budget.
- The parser does not read SPIR-V and does not prove that the driver accepts the
  reflected layout. It validates only the build-time manifest against the graph
  declarations.

`validate_reflection_manifest` returns resolved bindings ordered by graph pass
ID; entry points are preserved for pipeline creation. No descriptor set, shader
module or pipeline is created at this stage.

## Pass/resource dependency and barrier model

Graph construction is revisioned. Every `add_resource`/`add_pass` increments a
revision; a `CompiledGraph` is a snapshot carrying its owner and revision and is
rejected by `is_current` after any mutation. Compilation is a scheduling-time
operation, not a per-frame operation.

Compilation:

- Validates unique non-empty resource semantics and pass names, resource kinds,
  nonzero allocation IDs that do not alias another resource, capability masks,
  use access/stage combinations, image layouts (`0` is invalid for an image and
  required for non-images) and duplicate uses within a pass.
- Computes the transitive closure of explicit `after` dependencies, rejects
  out-of-range dependencies, self-dependencies and cycles, then emits a
  deterministic topological order by ascending pass ID among ready passes.
  Insertion order is never treated as synchronization.
- Rejects read/write and write/write pairs that have no ordering relationship,
  and image layout changes between two otherwise unordered uses. Ordered
  producer/consumer pairs become `Hazard{resource, source, destination, before,
  after}` entries. A read is rejected unless the resource is
  `initialized_on_entry` or has an ordered preceding write.
- Records minimal/maximal boundary uses (`entries`, `exits`) for resources with
  no preceding/following use. These are obligations for the host/Vulkan adapter,
  not barriers by themselves.
- A pass may specify an explicit queue family. All explicit families in one
  graph must agree; mixed families fail with `unsupported_multi_queue` until
  ownership transfers and cross-queue synchronization are modelled. `any_queue`
  means the host's recording queue.

Hazards are pairwise ordering constraints, not a barrier command stream. Emitting
one barrier per hazard would use stale old-layouts for transitive uses. The
Vulkan adapter must coalesce constraints and track the immediately preceding
layout and access state itself.

The `bedray_graph_vulkan` adapter:

- Recompiles the canonical plan and requires the same pass order, validating
  that the supplied snapshot was not fabricated.
- Accepts one explicitly ranged colour image binding per image resource, rejects
  duplicate resources or duplicate `VkImage` handles, requires a concrete
  aspect/level/layer range, and tracks image layouts from the supplied initial
  layout.
- Emits an image transition only when the desired per-pass layout differs from
  the tracked layout, using the tracked layout as `oldLayout`.
- Records a conservative `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` /
  `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` memory dependency before every pass.
  This is intentionally broad and unmeasured; it is not a performance claim.
- Rejects undefined reads, unsupported layout/stage combinations, missing
  bindings, null arguments, stale graphs and any graph mutation during recording.
- Calls the pass callback in plan order; a failed callback fails the recording,
  and the host must discard the entire command buffer recording.
- Does not begin/end a render pass, submit, present, wait idle, allocate, own a
  device/queue/loader, perform queue-ownership transfers, or handle external
  entry/exit image layouts. Cross-queue dependencies, memory aliasing proof and
  physical resource identity remain host obligations.

Image uses supported by the adapter: `GENERAL` for any non-acceleration-build
use; transfer-only reads and writes in `TRANSFER_SRC_OPTIMAL` /
`TRANSFER_DST_OPTIMAL`; all other uses only as `SHADER_READ_ONLY_OPTIMAL` reads.
Acceleration-build-stage image uses are rejected. Buffer and acceleration
structure uses are ordered by the graph but have no adapter-side barrier
encoding; their dependencies must be recorded by the pass callback or the host.

## RenderPlan to pass-graph mapping

`RenderPlan` has a traversal mode and a bitmask of algorithm features.
`enabled_capabilities` is a `PlanCapability` mask supplied by the host from
features actually enabled on the logical device. No GPU-name checks or
preprocessor trees are involved.

- Traversal is a hard requirement. `hardware` requires
  `hardware_ray_tracing | ray_query`; `hybrid` requires
  `hybrid_traversal | ray_query`; `bitmask_hdda` requires
  `bitmask_hdda | ray_query`. If the mask is missing a required bit, resolution
  fails with `unsupported_traversal` and reports the missing bits.
- Features are optional. Each unsupported feature is reported in
  `disabled_features` with its missing capability bits and only that feature
  pass is omitted. Resolution still succeeds so the Java-owned vanilla/Sodium
  composition path can remain active.
- Unknown traversal enum values and unknown feature bits are hard errors
  (`invalid_traversal`, `invalid_feature`). A plan cannot silently add features
  that the rest of the renderer does not know.
- Canonical pass order is
  `scene.trace -> restir.di -> restir.pt -> radiance_cascades.trace -> denoise
  -> upscale -> display`. `scene.trace` requires the traversal capability set;
  feature passes require their own feature capability; `display` requires none.
  `PlanResolution::required_capabilities` is the union of the traversal set and
  enabled feature sets.
- `plan_passes` only declares pass kinds, names and capability requirements. The
  caller must add each pass to a `RenderGraph` with concrete resource uses,
  explicit `after` dependencies, queue family, dispatch size and push-constant
  ranges, then compile and validate the reflection manifest.

The current plan covers the primary tracing chain only. Multi-stage bloom
(mips/upsample/apply), volumetric march/apply and the raster entity composite
are not yet represented as `PlanPassKind`s; that is an open integration
decision, not an implemented capability.

## History invalidation and per-frame lifetime

`HistoryRegistry` is the authority for persistent history resources and queue
timeline accounting. One registry exists per device lineage; it stores borrowed
integer identifiers and performs no Vulkan calls. Calls are serialized on the
render thread.

- A device epoch is opened with `begin_device(generation)`; generations must
  increase and no retained record of an older device may remain.
- `configure(signal, images)` is transactional and checks the byte budget,
  aliasing and pending recordings. `invalidate` resets data that must not be
  read by the next recording; `invalidate_after_pending` allows the pending
  frame to read old data while preventing its output from being committed.
- Per frame: `prepare` returns a recording token and reset/read details; the
  recording is either attached to a submission ticket or cancelled.
- Submission protocol: `begin_submission(queue, serial)`, `attach` per signal,
  call `mark_submit_attempted()` immediately before `vkQueueSubmit`, then
  `finish(accepted | rejected | unknown)`. Destroying an open ticket records
  `unknown`; only a proven unsubmitted recording may be closed as `rejected`.
- Submission completion is proven by `complete(queue, serial)`, which the host
  must only call with actual queue/fence evidence. `pop_finished` returns images
  whose uses are complete so the Vulkan owner can destroy them. `device_lost`
  and `device_idle` define safe teardown; `pop_orphaned` hands back lost-device
  records.
- `history_read_producer_completed` proves only producer completion for a
  recording. It does not create visibility, layout transitions, queue-ownership
  transfers or lifetime guarantees, and a reset recording has no history read.

Graph-side rules:

- `history.*` images are ordinary graph resources; the graph orders accesses but
  does not allocate, alias or free them.
- The graph does not schedule queue waits or consume submission tickets. Until
  an integration layer connects tickets to recording, history reuse must not
  rely on this graph to fulfil `same_queue_barrier` or `cross_queue_semaphore`
  promises.
- Plans and recording plans do not own Vulkan handles. Every image, buffer,
  acceleration structure and descriptor must remain alive and unchanged until
  GPU completion; the host must not mutate a prepared plan.
- Per-frame transient GPU allocations are not implemented in the graph planner.
  The scene subsystem owns transient staging/scratch with its frame slots, and
  the core dispatch adapter owns transient descriptor allocation. A graph-wide
  transient allocator remains an integration gap.

## Java reference mapping

The Java classes are behaviour references; their manual descriptor tables and
pass structure must not be ported line by line.

| Java reference | Current Java responsibility | Target C++ responsibility |
| --- | --- | --- |
| `RtFrameRenderer` | Phase selection, one transient command buffer, pass orchestration, history resets, denoiser/upscaler selection, output composition | Java keeps world/phase selection and the command buffer; C++ core owns plan selection, pass order, Bedray barriers and Bedray resource lifetime |
| `RtPathTracerPass` | Path trace, RGBA32F accumulation, A-Trous filter, history reset on camera/settings change | `scene.trace` pass plus `denoise`; history signals and invalidation via the registry; no per-pass Java binding tables |
| `RtRayEffectsPass` | Primary rays, shadow/reflection/AO/GI, ReSTIR spatial reuse, spatial filter | `scene.trace`, optional `restir.di`, `denoise`; explicit hazards replace manual compute-to-compute calls |
| `RtCompositePipeline` | Screen-space raster entity composite: four combined samplers, one storage image, 80-byte push block, 8x8 dispatch | `display`/composite pass declared with sampled/storage image semantics and an 80-byte push range; host remains the compositor for vanilla/Sodium fallback |
| `RtBloomPass` | Four AOT Slang programs, 12 dispatches over mips, 128-byte push block, manifest checked against supplied resources | Manifest validation plus push ranges; multi-stage chain needs explicit graph passes or a dedicated bloom subgraph (open decision) |
| `RtVolumetricPass` | Half-resolution march plus depth-aware apply, temporal history, runs after the denoiser | Two post passes with explicit dependency and a history signal (not yet in `RenderPlan`) |
| `NrdPackPipeline` | Pack/compose with 14 storage-image bindings and a 56-byte push block | `denoise` pass with 14 storage-image semantics and a 56-byte push range; descriptor allocation stays with the native core |
| `RtVulkanPipelineCache` | Device/driver pipeline cache shared by all builders | Native core owns pipeline resources; the cache is not part of the graph planner and must be supplied to the eventual executor |
| `bedray_history` (native) | Device/serial history accounting | Used directly; the graph connects only through explicit resource uses and submission tickets |

## Open ABI decisions for the integrating agent

- No new public C entry point is defined by this batch. `core_dispatch` is an
  internal bridge from a recording plan to `bedray_core_render_frame`; it is not
  exported. The integrating agent must decide whether the host submits a graph
  descriptor, an opaque plan handle, or a fixed per-frame plan selected by
  `RenderPlan`, and how errors map to `BedrayStatus`.
- Plans reference graph-local IDs and raw `VkImage` handles. ABI v2 has no
  representation for image ranges, descriptors, pipeline layouts or push
  constant values. A future ABI version must define ownership and lifetime for
  these handles and must not expose C++ containers.
- The host must continue to own `VkInstance`/`VkDevice`/`VkQueue`, the pipeline
  cache and the command buffer. Core must not submit, present or wait.
- Entry/exit image layouts, external producers/consumers and queue-ownership
  transfers are host contracts. The graph deliberately does not infer them.
- Multi-queue execution, cross-queue semaphores and history ticket integration
  need an ABI-level design; the current planner rejects mixed explicit families.
- Push-constant and dispatch values are host/native-executor inputs. The graph
  validates ranges and dimensions but does not store the bytes.
- Do not edit shared Java renderer files or the shared core ABI while ownership
  is coordinated; this batch intentionally stays inside native graph sources,
  tests and notes.

## Build and test evidence

Toolchain: MSVC 19.x via Visual Studio 17 2022 generator, Windows SDK
10.0.26100.0, x64, Vulkan-Headers only (no Vulkan loader linked by the planner).
Configured and built from `C:\BedRay\native\bedray_core`:

```
cmake -S . -B build -DBEDRAY_GRAPH_BUILD_TESTS=ON
cmake --build build --config Release
```

Result: `bedray_core.dll`, `bedray_abi_probe.exe`,
`bedray_render_graph.lib`, `bedray_graph_vulkan.lib`,
`bedray_graph_history.lib` and the test executables built successfully with no
compile errors. (Warnings were not exhaustively archived; targets compile with
`/W4 /permissive-`.)

```
ctest --test-dir build -C Release --output-on-failure
```

Result: 3/3 passed — `bedray_render_graph_contract` (0.10 s),
`bedray_history_submission` (0.01 s), `bedray_vulkan_recording` (0.01 s).

Covered by the no-GPU tests:

- Multi-pass graph ordering, producer/consumer hazards, entry/exit boundaries,
  graph mutation staleness, capability masks, cycle/self/forward dependencies,
  unordered writes, uninitialized reads, missing image layouts, mixed queue
  families, zero dispatch dimensions, overlapping/misaligned push constants.
- Valid reflection manifest parse and semantic binding resolution; rejection of
  unsupported versions, syntax errors, unknown/duplicate/missing passes, missing
  bindings, type mismatches, duplicate semantics, push mismatches, nonzero sets
  and missing entry points.
- `RenderPlan` optional-feature degradation (unsupported ReSTIR PT omitted while
  denoise remains), full canonical order, unsupported traversal, invalid feature
  bits and invalid traversal.
- Vulkan adapter preparation and recording with a fake command buffer, fake
  image handles and a stub `vkCmdPipelineBarrier`: layout tracking, undefined
  starts, barrier counts/layouts, stale graph rejection, invalid/duplicate image
  bindings, unsupported uses, callback failure, graph mutation during recording
  and null arguments.
- History submission ticket semantics (implicit unknown close, explicit
  rejection releasing the serial).

Files added or updated for this batch:

- `include/bedray/render_plan.hpp`, `src/render_plan.cpp`
- `include/bedray/reflection_manifest.hpp`, `src/reflection_manifest.cpp`
- `include/bedray/render_graph.hpp`, `src/render_graph.cpp`
- `tests/render_graph_contract_test.cpp`, `tests/vulkan_recording_test.cpp`
- `CMakeLists.txt` in `native/bedray_render_graph`
- `enable_testing()` in `native/bedray_core/CMakeLists.txt` so root CTest
  discovers subdirectory tests
- this file

Live-validation gap: none of this has been executed against a real Vulkan
driver, a real device, Minecraft, Sodium, a denoiser or an upscaler. The adapter
records only images in a limited layout set, uses broad barriers, supports one
queue, does not prove aliasing safety, and is not wired into the Java frame
path. The core DLL still exposes ABI v2 only. GPU performance and correctness
remain unmeasured.
