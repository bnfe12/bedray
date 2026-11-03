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
| P0 | Raw capture now connected: `native_perf`/FrameCapture records application-frame intervals, native/PT/display/focus/FG evidence and delayed GPU segments matched by frame ID; offline analyzer rejects incomplete/invalid captures | Collect matching-hardware captures and image-quality references. GPU total excludes submission/present; no VRAM-budget capture or hardware performance result yet |
| P0 | Unified memory budget/residency: the game has VMA; native buffer/image owners have separate limits and native scene allocates its own memory | One native allocation/budget service, queried heap budget/usage, suballocation, bounded scratch reuse and exact accounting of active plus retained generations. Do not pass a foreign host VMA pointer across an unverified implementation ABI |
| P0 | AS compaction and build/refit policy: native `acceleration_scene.cpp` builds ordinary BLAS/TLAS, but no compaction-size query/compacted-copy path exists. Native flags currently reflect only `ALLOW_UPDATE`; Java terrain already asks for fast trace | Measure static BLAS fast-trace/compaction separately from dynamic builds/refits and traversal quality. Implement query/copy/generation retirement with host completion proof. Compaction adds build/copy work; it is not an automatic FPS gain |
| P0 | Local light proposals / ReGIR: camera-global emitter importance and ReSTIR DI exist; receiver-local grid proposals do not | Spatial candidate construction with a correct proposal PDF, global fallback support and secondary-surface policy; test disocclusion/invalidation. Avoid spending more rays to compensate for poor proposals |
| P1 | NRD primary-lobe split/distances now exist; primary lobe selection is still white noise | Coordinate primary sampling for reconstruction-neighborhood coverage, including probability compensation and affected PDFs. Keep unsupported RC/PT/medium transport explicit until a valid guide estimator exists. RR distance/reflected motion remains separate work |
| P1 | SER now reorders a compact intersection before full surface decode; native/legacy table adapters share the nearest-triangle query. Filtered visibility still reaches reordered `traceClosest`, and path state can remain live | Profile compute vs ordinary raygen vs SER with identical samples/settings. Inspect spills/live state and test omitting visibility reorder. Keep the best measured backend per device/profile |
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

## Replaced benchmark prototype

The disconnected launcher/log parser and external Tracy-launcher prototypes
were removed. They used a nonexistent client task, unconsumed properties and
nonexistent raw telemetry markers, measured startup and could delete a game
log or turn missing samples into zeros.

Use [the capture instructions](../tools/automation/README.md). `native_perf`
sets session-only native PT/FG-off, loads for 20 seconds, warms up for 10 seconds
of valid world rendering, and captures 30 seconds without removing slow or
invalid frames. CSV and manifest writes happen asynchronously after sealing;
the scenario waits for persistence. Unique directories and CREATE_NEW preserve
existing evidence. Partial/overflow/timeout/query failures remain explicit.
The analyzer has no game/process lifecycle responsibilities.

Tracy raw-Vulkan GPU zones, presentation correlation, budget accounting and
image-quality acceptance remain separate integration work. The denoiser field
is a requested setting, and the revision hint is caller supplied.

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
