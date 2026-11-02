# Architecture alternatives review — 2026-11-02

Source baseline: `61ea2dae91237aa017d262f0e0df26bf73655f45`, checked against
origin. Owner requested this review before further implementation. The target
remains native 1920x1080/60 real FPS on RTX 5060 Ti 8 GiB, Ryzen 5 5600XT and
16 GiB RAM, without upscaling or reduced/dynamic internal resolution.

These are proposals and comparison criteria. They do not adopt new backends,
change rendering math, assert measured improvements or complete the manifesto.
The preceding [technology audit](NATIVE_1080P_60_AUDIT.md) still records actual
implementation gaps; this review asks whether its proposed solutions should
also change.

## Keep the foundation

Keep Java 25 for game integration, coarse FFM calls, C++ resource/recording
owners, Vulkan and AOT Slang. Preserve host device/queue/submission/completion
authority and existing ABI-v2 layouts. Language/API replacement does not
address the confirmed costs. Preserve working capture, materials, motion,
reflection and lifetime contracts while replacing individual mechanisms.

Use one producer and one retirement/history authority per mutable resource.
The proposed module flow is capture -> scene representation -> intersection
-> surface decode -> light proposal -> integration -> denoiser signals ->
display. Scheduling, allocations and actual resource dependencies belong to
the surrounding native frame graph, with host completion proof.

## Reconsider these specific mechanisms

| Area | Source observation | Proposed improvement / comparison |
|---|---|---|
| Intersection and SER | `ReorderingTraversal` obtains a complete `SurfaceHit` before reorder; legacy/native tracers decode geometry before returning. The filtered shadow walk also calls this closest-hit path | Prototype a small internal intersection token and late surface decode. Apply a compile-time scheduling policy after the query completes and before unnecessary decode. Give filtered visibility only the data it needs while preserving cutout/glass order. The driver may already eliminate/move some state: confirm actual spills in Nsight |
| Geometry representation | Hardware triangles are live. Pure HDDA/Hybrid is not yet a validated faster representation on this RTX | Compare three strategies behind shared capture/material contracts: existing triangle AS; software HDDA for opaque full cubes; hardware AABB broad phase with local voxel DDA. Ray queries permit application-generated procedural intersections, but this third strategy is only a proposal. Partial blocks, fluids, cutouts and entities retain accurate residual geometry |
| Entity acceleration | `RtEntityBlas` rebuilds captured entity geometry every frame with fast-build preference | Classify static geometry, rigid instances and deforming meshes. Reuse a BLAS for rigid motion through transforms where capture identity/topology permits; compare refit vs rebuild for deformation. Validate previous-position motion and topology changes; do not refit unrelated geometry or assume refits stay optimal indefinitely |
| Light proposals | `RtEmitters` ranks by camera importance; `blockEmitterPickIndex` samples the sorted list with u-squared probability. Its within-quad candidates already use shifted R2 stratification | Compare receiver-local ReGIR/grid proposals with a hierarchical light BVH, retaining a global-support fallback and exact proposal PDF. Existing sampling is not claimed mathematically invalid. A tree can suit nonuniform lights; a grid can amortize repeated local queries. Neither is preselected as fastest |
| Indirect-light cache | The current finite RC returns irradiance/pi through `IPathDiffuseField`; it is not SPWI/guidance | Compare current RC, screen-space probes, Split RC and a bounded SHaRC prototype. SHaRC caches outgoing radiance: it cannot be substituted into an irradiance contract without defining the sampled quantity and avoiding repeated material factors. Select one default using quality, update/query time and total retained memory; do not enable several caches together by default |
| Sampling | Primary BSDF selection remains white noise; camera jitter and emitter R2 already exist | Define sampling dimensions explicitly. Treat NRD primary-lobe neighborhood coverage separately from general spatiotemporal blue-noise/low-discrepancy directions. Blue noise alone does not guarantee a missing lobe in every 3x3 area. Preserve lobe probabilities, PDFs, RIS weights and random-stream ownership |
| Memory and orchestration | Native resources/scene have separate allocation limits; the live graph has one opaque `path_trace.frame` node | Consolidate the native budget/allocation service and expose real scene, cache, trace, reuse, denoiser and display dependencies incrementally. Start with bounded pools/scratch reuse and generation accounting. Alias only proven non-overlapping allocations. Keep existing history ownership until a pass is explicitly transferred |
| Verification | CPU/native contract tests and 16 executed production-Slang arithmetic cases are real; no matching-device image/FPS run has been added | Add an opt-in reference integrator/capture mode and raw linear-HDR regression scenes alongside guide/debug captures. Compare sampling/cache/reuse changes against a converged reference before judging performance at a fixed acceptable error. Add raw-frame capture and native-mode assertions; repair the disconnected benchmark prototype |

Hardware-assisted AABB/DDA is an architectural hypothesis, not a proof that
one procedural box per section is a good granularity. Traversal overlap,
occupancy, candidate count, updates and residual mesh costs must be measured.
Pure HDDA remains important for NoRT even if another representation wins on RTX.

SHaRC's integration guide describes update, resolve and query passes. Its
example three buffers contain 8+16+16 bytes per entry; 2^22 entries therefore
mean 160 MiB of those buffers alone, before other resources and lifetimes.
That is a sizing example, not a Bedray memory/performance result. NRC is a
separate vendor/Tensor-Core candidate, not the universal baseline.

## Proposed execution order for this performance priority

1. Establish trustworthy native-resolution raw-frame/GPU/memory capture and
   deterministic quality references. Keep all slow frames in the evidence.
2. Address aggregate memory pressure, AS lifetime/build policy and unnecessary
   capture/decode work, then coordinate NRD sampling.
3. Compare local light proposals, indirect caches and the compact-intersection
   SER variant one at a time, with the same scene, quality and memory budget.
4. Adopt only measured winners, then finish the remaining functional backends
   and modes from the full stack ledger.

No fixed 2 GiB pool, pointer-only push layout, direct Metal backend, PTLAS,
CLAS, DGC, FP16-everywhere or several queues is required merely to improve this
Windows/8-GiB profile. These remain separate strategies/feature obligations;
each needs a real workload, enabled features and integration ownership. New
queue families require the actual host submission/completion contract. No
speedup percentage or native-60 guarantee follows from any of these choices.

Primary references checked for this review:

- [Ray-query procedural intersections](https://github.khronos.org/SPIRV-Registry/extensions/KHR/SPV_KHR_ray_query.html)
- [NVIDIA RTX acceleration-structure practices](https://developer.nvidia.com/blog/best-practices-for-using-nvidia-rtx-ray-tracing-updated/)
- [NVIDIA SER and live-state analysis](https://developer.nvidia.com/blog/path-tracing-optimization-in-indiana-jones-shader-execution-reordering-and-live-state-reductions/)
- [Dynamic hierarchical many-light sampling](https://research.nvidia.com/labs/rtr/publication/moreau2019manylight/)
- [RTXDI/ReGIR integration](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/Integration.md)
- [Spatiotemporal blue-noise research](https://research.nvidia.com/publication/2022-07_spatiotemporal-blue-noise-masks)
- [Split Radiance Cascades, July 2026 / revised October 2026](https://arxiv.org/abs/2607.20384)
- [SHaRC integration and buffer sizes](https://github.com/NVIDIA-RTX/RTXGI/blob/main/Docs/SharcGuide.md)
- [RTXGI SHaRC/NRC hardware distinction](https://github.com/NVIDIA-RTX/RTXGI)
