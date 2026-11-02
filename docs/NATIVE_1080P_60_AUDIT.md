# Native 1080p / 60 FPS technology audit — 2026-11-02

Owner target: RTX 5060 Ti 8 GiB, Ryzen 5 5600XT, 16 GiB RAM, native 1920x1080,
stable 60 application-rendered FPS. No DLSS SR, FSR, XeSS, other upscaling,
reduced/dynamic internal resolution or generated-frame substitution. Native
anti-aliasing and denoising do not change the required render dimensions.
The whole-frame budget is 16.67 ms; a trace-pass timing alone cannot prove it.
This machine reports Intel UHD, so no matching-hardware measurement is available.

Source audited after PRs #58/#59, at `97abf820047be032e33ad7a1caf4a3b53998defb`.
The following priorities are engineering judgments from that source and the
linked primary references, not measured Bedray speedups or prerequisites that
every renderer must adopt.

## Additional gaps relevant to performance

| Priority | Mechanism / actual state | Concrete next obligation |
|---|---|---|
| P0 | Whole-frame regression measurement: engine `PerfMonitor` already has CPU/GPU segments and frame-interval averages; `RtGpuTimer` has nonblocking available GPU spans. The automation runner is not connected to these producers | Record raw application-frame intervals plus effective resolution, mode, upscaler and FG state; collect average, p95/p99, slow-frame counts and scene/settings metadata. Do not infer 1% lows from 0.5-second averages |
| P0 | Unified memory budget/residency: the game has VMA; native buffer/image owners have separate limits and native scene allocates its own memory | One native allocation/budget service, queried heap budget/usage, suballocation, bounded scratch reuse and exact accounting of active plus retained generations. Do not pass a foreign host VMA pointer across an unverified implementation ABI |
| P0 | AS compaction and build/refit policy: native `acceleration_scene.cpp` builds ordinary BLAS/TLAS, but no compaction-size query/compacted-copy path exists. Native flags currently reflect only `ALLOW_UPDATE`; Java terrain already asks for fast trace | Measure static BLAS fast-trace/compaction separately from dynamic builds/refits and traversal quality. Implement query/copy/generation retirement with host completion proof. Compaction adds build/copy work; it is not an automatic FPS gain |
| P0 | Local light proposals / ReGIR: camera-global emitter importance and ReSTIR DI exist; receiver-local grid proposals do not | Spatial candidate construction with a correct proposal PDF, global fallback support and secondary-surface policy; test disocclusion/invalidation. Avoid spending more rays to compensate for poor proposals |
| P1 | NRD primary-lobe split/distances now exist; primary lobe selection is still white noise | Coordinate primary sampling for reconstruction-neighborhood coverage, including probability compensation and affected PDFs. Keep unsupported RC/PT/medium transport explicit until a valid guide estimator exists. RR distance/reflected motion remains separate work |
| P1 | SER now exists, but filtered visibility also reaches reordered `traceClosest`; large surface/path state can remain live across reorder | Profile compute vs ordinary raygen vs SER with identical samples/settings. Inspect spills/live state, move unnecessary decode/state beyond reorder, and test omitting visibility reorder. Keep the best measured backend per device/profile |
| P1 | Native scene OMM attachment is absent, although Java OMM capture exists | Transfer opacity generations/attachments and animated-alpha invalidation into the native scene strategy; retain candidate-alpha fallback and verify foliage appearance before measuring cost |
| P1 | General low-discrepancy / spatiotemporal sampling policy: current paths use `rand01`; camera jitter is not a complete BSDF/light sampling sequence | Evaluate stratified/Owen-scrambled or spatiotemporal sequences by sampling dimension and motion behavior. This targets noise per ray; changing RNG alone need not reduce GPU time |
| P1 | Radiance guidance: current RC is a finite camera-centred world field, not SPWI/RCPG; no directional proposal/product PDF exists | Compare bounded current RC, screen-space probes, guiding and SHaRC-like alternatives on the same captures. Implement one valid estimator rather than accumulating several caches in 8 GiB |
| P1 | Variable active-ray worklists, indirect dispatch and measured compact storage are absent from the live integrator | Evaluate ordinary indirect/work compaction before DGC. Measure extra queue/buffer traffic against SER/megakernel occupancy. Keep PDF/reservoir weights at justified precision |
| P1 | Whole-frame graph/resource declarations and measured barrier narrowing remain incomplete | Move opaque effect boundaries into explicit dependencies without another history authority. Broad barriers currently protect correctness; narrow them only with complete producer/consumer proof |
| P2 | Shared AOT/native NoRT, true Hybrid/HDDA and zero-mesh capture remain incomplete | Complete these as alternative backends with geometry/material parity. A voxel backend is not presumed faster than RT triangles on this RTX without comparison |

Froxels, distant LOD, extra tone maps and Metal remain functional/quality or
platform obligations; they do not establish 60 FPS. PTLAS, CLAS, DGC, pipeline
binaries, FP16 and subgroups are optional strategies requiring a matching
workload and enabled features. Their names on a checklist prove no benefit.
ReSTIR PT's current two-half 144-byte ring alone is about 569 MiB at 1080p,
so enabling every cache/reservoir by default is inappropriate for 8 GiB.

## Confirmed benchmark-tooling defects

`tools/automation/game_session.py` invokes `:fabric-26.1:runClient`, while root
settings declare `fabric`. Its `bedray.mode`, `bedray.preset`,
`bedray.benchmark.*` and `bedray.tracy.*` JVM properties have no matching
renderer consumers. Repeated `-Djvm.args=` is not an implemented argument
forwarding contract for the current loader task.

The parser expects `[VKB-FRAME]`/`[VKB-PASS]` producers absent from the renderer.
Actual telemetry is `[VKB-PERF]` window averages and RT `gpu_ms ... (n=...)`
spans. The runner starts its clock before startup/build finishes, does not
drain stdout while waiting, and tries to delete the existing game log. Missing
samples can become a zero-valued report rather than a rejected benchmark.
These are source findings, not a successful execution test. Do not run this
prototype for acceptance until its launch/protocol/lifecycle are repaired.

`tracy_session.py` can launch `tracy-capture`; it does not implement a Tracy
protocol client or prove Bedray Vulkan GPU zones. The game has jtracy/host
profiling facilities, but Bedray's raw-command-buffer GPU-zone integration is
not implemented. The old README's stronger claims have been corrected.

## Measurement and comparison

Use actual `PerfScenario` and `[VKB-PERF]` diagnostics for segment inspection,
with effective mode/resolution checked. The scenario defaults to RT off and
several upscaler steps; native PT testing requires an explicit native-only step
and RT enabled. Its averages do not form a per-frame percentile benchmark.

PresentMon collects presentation CSV on Windows/Vulkan; Nsight Graphics can
inspect the GPU pipeline and SER live state. For Vulkan/"Other", PresentMon
documents reduced timing instrumentation; HWS can affect GPU execution metrics.
Correlate with in-engine completed timestamps instead of treating external
GPU-busy estimates as exact pass timings. Tracy remains an integration task.
Heavy GPU validation must be off in the performance comparison.

Retain source/build, GPU/driver, scene/camera, quality knobs, 1920x1080 render
and output dimensions, effective upscaler off, FG off, warmup, duration,
VSync/FPS cap, raw samples, memory usage/budget and validation state. Compare
warm static views and chunk-streaming/motion scenes separately without dropping
their slow frames. Average FPS above 60 does not prove stable pacing; the
requested 60 FPS remains unmeasured here.

Primary references checked on 2026-11-02:

- [NVIDIA: SER and live state](https://developer.nvidia.com/blog/path-tracing-optimization-in-indiana-jones-shader-execution-reordering-and-live-state-reductions/)
- [RTXDI integration](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/Integration.md)
- [VMA budgets](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/staying_within_budget.html)
- [NRD reconstruction](https://github.com/NVIDIA-RTX/NRD/blob/v4.18.0/Include/NRDSettings.h)
- [PresentMon Vulkan/HWS limitations](https://github.com/GameTechDev/PresentMon)
