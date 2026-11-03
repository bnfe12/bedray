# Full stack implementation and decision record — 2026-11-02

The owner expanded the task beyond transferring existing functionality: finish
the deferred technologies/modes and check whether better approaches exist.
This supersedes the earlier scope deferral in `SLANG_MIGRATION.md`; it does not
declare those features implemented. Baseline: `78a4836`. Three read-only audits
checked the actual source, not the old percentage scorecard.

Follow-up progress on 2026-11-02: shared pipeline cache/lifetime repairs are
merged, as is the optional [ray-generation/NV SER backend](ai-agent-addenda/EXPERIMENTAL_SER.md)
(PR #58, native-tested `fe75d59`). The [primary-lobe signal implementation](ai-agent-addenda/PRIMARY_DENOISER_SIGNALS.md)
adds the previously missing split/distances and symmetric composition, with
explicit unsupported-transport bypass and remaining vendor/quality work.
These advances do not complete the other unchecked obligations below. The
owner's performance acceptance target is 1080p at 60 real frames/s on RTX 5060
Ti 8 GiB, Ryzen 5 5600XT and 16 GiB RAM. The owner explicitly requires native
1920x1080, with no upscaler, reduced/dynamic internal resolution or generated
frames counted toward the target. It has not been measured here. The follow-up
[native-60 technology audit](NATIVE_1080P_60_AUDIT.md) prioritizes actual frame
and memory costs and records additional missing mechanisms/tooling defects.
The subsequent [architecture alternatives review](ARCHITECTURE_REASSESSMENT.md)
proposes changing module boundaries and comparing alternatives, including
light BVH vs ReGIR and hardware-assisted voxels vs triangles/HDDA. These are
unmeasured proposals requested before further implementation, not adopted
backend choices or additional implementation claims.

The manifesto remains the feature target. Its fixed performance claims are not
acceptance evidence. A requirement which conflicts with API semantics must be
implemented through a valid architecture rather than copied literally.

## Current source and remaining work

| Area | Actual baseline | Work still required |
|---|---|---|
| Tier 1 / No-RT | Loaded-section streaming, material/model payloads, section/cell DDA, shadows, limited diffuse bounce, reflection and water transport now execute through typed Slang AOT and the shared native core; see [NoRT execution](ai-agent-addenda/NORT_AOT_EXECUTION.md) | Full native scene/history/image ownership, multilevel occupancy, direct full-cube capture, RC-compute, bitmask AO, depth/normal/motion output and a defined SSR/HDDA fallback |
| Tier 2 / Hybrid | Generic voxel/hardware tracer contract and compile harness | Live scene partition/capture, voxel/mesh identity and nearest-hit arbitration, pass/backend selection and mode switching. The current `hybrid` alias selects hardware ray mode; it is not the promised hybrid implementation |
| Tier 3 / PT | Hardware path tracing, DI/PT reuse, water/BSDF, native finite-window RC, guide output and vendor adapters | Complete denoiser signal ownership, view-space RC/guidance decision, ReGIR, froxels, distant LOD and optional hardware backends |
| Zero-Mesh / HDDA | The companion consumes voxel/model data; shared Slang voxel interfaces are not instantiated by live AOT entries | Loaded-chunk-only palette capture, conservative full-cube classification, partial/transparent mesh handling, multilevel occupancy and a single producer/consumer layout |
| Light sampling | Block-emitter NEE, DI reuse, stable-ID tables and receiver-local light-BVH/global mixture proposals | GPU quality/cost validation, optional ReGIR comparison, secondary-ray reservoir policy and motion-aware dynamic light capture; see LOCAL_LIGHT_PROPOSALS.md |
| RC / RCPG | Five-level camera-centred 3D cubemap field with bounded budget and temporal reuse | Screen-space probe placement/reprojection or another measured layout, angular product sampling and a documented estimator. Current RC must not be relabelled SPWI/RCPG |
| Atmosphere / LOD | Native two-dispatch volumetric shafts and existing fog | Froxel injection/integration/reprojection, explicit Rayleigh/Mie coefficients, clipmap residency and coarse data generation; no fabricated distant geometry |
| Memory | Shared core-owned native buffer/image/scene pools with BDA/dedicated/atom rules, aggregate cap, exact retained-generation accounting and optional heap-budget telemetry | GPU measurement, migration of remaining host/vendor allocations where appropriate and proven transient aliasing; see NATIVE_MEMORY_SERVICE.md |
| Frame graph / history | One live opaque path-trace group, graph/scheduler libraries and exact recording proof; private pass history remains authoritative | Whole-frame resource boundaries, dependency/subresource declarations and migration of remaining effect groups. No second history authority over the same images |
| GPU scheduling | Ordinary compute dispatch and host-owned completion | GPU worklists/indirect dispatch first where useful; optional DGC and timeline integration with the actual host submission authority |
| Hardware geometry | Ordinary mesh AS, Java OMM attachment; native scene lacks that attachment | Native OMM generations, optional PTLAS/CLAS backends and matching resource/descriptor contracts |
| Pipeline creation | AOT and shared persistent cache borrowed by the native/Java pipelines, with synchronized access and exact lifetime | GPU creation-time measurement; optional supported pipeline-binary strategy |
| Numerical/storage optimizations | Some typed utilities and existing transport | Measured packing/FP16/subgroup variants with separate feature contracts, range/error tests and unchanged baseline results |
| Denoising / post | Primary-lobe NRD signals, RR reflection guide/camera recovery, single RR output with FG OFF, native RR jitter/coherent primary samples, HDR exposure range and bloom | FG-compatible single RR output, RR-specific size policy/SR residency, live quality/calibration, declared tone-map modes and bounded firefly policy; see RR_OUTPUT_RECONSTRUCTION.md |
| Developer tools | AOT, F3 diagnostics, GPU timestamps, bounded raw native/gameplay captures and offline tail analysis | Transactional Slang hot reload, actual heatmap outputs, bounded GPU calibration and profiler integration |
| Other platforms | Windows is the validated build target; non-Windows native configure is an explicit unsupported opt-in | Actual platform dependency/build/packaging checks. A direct Metal backend requires host resource/command integration, not just an MSL compiler flag |

## Remaining requirement ledger

This expands the matrix into implementation obligations; an unchecked item is
not completed by a declaration or by a related feature. Several existing
features still need their own game/visual acceptance.

| Manifest requirement | Implementation/acceptance still needed |
|---|---|
| 1.1 Voxel Fast | Shared AOT/native compute entry, zero hardware RT dependency, real depth/normal/motion, RC-compute, SSR/HDDA policy and bitmask AO |
| 1.2 Hybrid | One capture partition for opaque full cubes and residual mesh; world and dynamic nearest-hit/visibility/motion consumers |
| 1.3 Ultra | Preserve existing PT/DI; finish guide contracts, RC guidance and optional OMM/froxels |
| 2.1 Two-level masks / Morton / Zero-Mesh | Producer/consumer layout, summary mask, palette invalidation and measured material-payload locality; do not change one side alone |
| 2.1 Partial geometry | Keep stairs, slabs, fences, cutouts, fluids and model geometry accurate; do not mark them as opaque voxel cubes |
| 2.2 Dynamic BLAS / PTLAS / CLAS | Keep existing dynamic capture; implement isolated native geometry strategies with actual queried limits and generation lifetime |
| 2.3 OMM / alpha fallback | Native attachment, animated-alpha generation policy and explicit supported/enabled/implemented checks |
| 3.1 Light grid / area emitters / disocclusion | Receiver-local proposals with full support/PDF; preserve existing area sampling and prove reuse/invalidation before adaptive radius/clamps |
| 3.2 SPWI / RCPG / ReGIR | Decide measured probe layout, implement screen/world reprojection and directional proposal; secondary-surface light sampling with a defined estimator |
| 3.3 SSS / cone shadows | Material units/thickness and energy contract; binary occupancy alone is not a distance field or cone filter |
| 3.3 Water / layered transparency | Validate existing Snell/absorption/caustics/continuation at underwater and layered interfaces, then bound added transport without inventing volume geometry |
| 4.1 Froxels | Density/light injection, logarithmic depth, integration and reprojection; quality-budget dimensions rather than one mandatory grid |
| 4.2 Far LOD | A real distant-data source, loaded/resident validity, clipmap update and near/far transition; RC does not create geometry |
| 5.1 Pointer-only 128-byte push | Existing push layouts are 112/64/32/etc. Change only through a validated shader/host contract and additive ABI; do not call current layouts pointer-only |
| 5.2 Reverse-Z | Audit the actual game projection/depth attachment and each consumer; ray-distance R32F is not a Reverse-Z hardware depth buffer |
| 5.2 VMA/page pool/aliasing | One internal allocation service, actual memory requirements, bounded pools, retained generations and proven non-overlapping resources |
| 5.3 Timeline / DGC / SER | Host submission outcomes, variable worklists/indirect commands and a compatible optional ray pipeline; no native submit/wait authority |
| 5.4 Pipeline binaries | After shared cache, negotiate the optional extension and compatibility keys; preserve ordinary pipeline creation |
| 5.5 Direct Metal | Separate host backend/resource/command strategy and platform builds; MSL output alone is insufficient |
| 6.1 Octahedral normals | End-to-end storage format/encoding including vendor guide adapters, error bounds and measured bandwidth |
| 6.2 FP16 | Separate arithmetic/storage capabilities; finite/range/error gates for each field, especially weights/PDFs |
| 6.3–4 Subgroups / branchless DDA | A working HDDA workload first; subgroup-size/reconvergence correctness and measured benefit over scalar traversal |
| 7.1 Moving blocks | Atomically transfer representation ownership for falling blocks/pistons and retire old captures by exact proof |
| 7.2 Handheld light | Game capture, explicit radiometric units/shape and DI candidate proposal; no nanosecond guarantee |
| 7.3 Dirty epochs | Section-level change provenance through scene capture, history lookup and affected-pixel invalidation |
| 7.4 Animation | Per-material atlas frame/tick contract for animated strips, including alpha/OMM invalidation; no blanket first-frame bake claim |
| 7.5 Chunk borders | Explicit unknown-residency transport/fog policy which does not turn missing geometry into trusted open sky |
| 8.1 NRD/RR | Correct lobe/hit-distance/material guides and real vendor output validation; existing enums/adapters are insufficient |
| 8.2 DLSS/FSR/XeSS/FG | Preserve integrated adapters and producer/consumer lifetime; exercise mode/resize/failure paths on actual devices |
| 8.3 Firefly suppression | Compare SDK anti-firefly and estimator variance before adding a biased mandatory median/percentile filter |
| 8.4 AgX/ACES/Filmic/Neutral | Real operators/selection with one HDR-to-display authority, including display-authored sky; existing curve is not ACES just because a property says so |
| 8.4 Karis bloom | Existing native/AOT implementation; preserve and measure its HDR/display ownership with new operators |
| 9.1 F8 reload | Candidate compilation, reflection validation, transactional program generation and failure rollback |
| 9.2 Heatmaps | Real traversal-step/reservoir/motion outputs and presentation, with opt-in diagnostic overhead |
| 9.3 Auto PBR | Existing material classification; validate representative blocks/resources and explicit override precedence |
| 9.4 Calibration | Bounded elapsed-time/completed-GPU samples, warmup and retained preset on incomplete/failed calibration |
| 9.5 FFM | Keep ordinary coarse calls; only measured, eligible no-upcall leaves may use critical linkage |
| 9.6 Tracy/harness | Actual profiler instrumentation and optional transport integration; existing timestamps/autotest are not Tracy |
| 10 Verification | Exact source/build/test records plus real GPU/game evidence; no universal correctness, frame-time or VRAM percentage guarantee |

## Design choices after checking alternatives

1. **Cache before pipeline binaries.** Reuse the existing host cache owner with
   a per-call native borrow. `vkCreateComputePipelines` may not access the cache
   after return, so this introduces no GPU drain or cache pin on a program.
   Persist a bounded, checksummed envelope and validate the Vulkan header/device
   before passing bytes to the driver. Add pipeline binaries later as an optional
   strategy under this owner, with extension enablement and compatibility keys.
   Neither approach guarantees zero first-start compilation or zero stutters.
   [Cache lifetime](https://docs.vulkan.org/refpages/latest/refpages/source/vkCreateComputePipelines.html),
   [header](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCacheHeaderVersionOne.html),
   [pipeline binary](https://docs.vulkan.org/features/latest/features/proposals/VK_KHR_pipeline_binary.html).

2. **Budgeted growing pools before a fixed 2 GiB slab.** VMA is a candidate
   implementation of the internal allocation service; its virtual allocator
   alone allocates no GPU memory. Pool classes need actual memory requirements,
   alignment, BDA/dedicated flags, map/coherency policy and exact retirement.
   Keep dedicated allocation for exceptional resources. Choose block sizes from
   budgets and measurements. Adjacent pass names do not prove aliasability;
   previous/current reservoirs can be simultaneously live across frames.
   [Virtual allocator](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/virtual_allocator.html),
   [custom pools](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/custom_memory_pools.html).

3. **A real universal path before vendor backends.** Tier 1 must not require
   ray-query/AS. Tier 2 preserves full-cube versus partial-mesh geometry and
   alpha/medium semantics. OMM is an optional native geometry attachment with
   ordinary candidate alpha testing as fallback. SER requires a compatible
   ray-generation pipeline; it cannot be enabled as a switch on the current
   compute ray-query entry. Compare a separate RT-pipeline backend with explicit
   wavefront work organization before adopting one. PTLAS/CLAS need their own
   build and descriptor contracts; supported/enabled/implemented are separate
   states. No GPU-name heuristic substitutes for those checks.
   [SER scope](https://docs.vulkan.org/features/latest/features/proposals/VK_EXT_ray_tracing_invocation_reorder.html),
   [PTLAS](https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_partitioned_acceleration_structure.html),
   [CLAS](https://docs.vulkan.org/features/latest/features/proposals/VK_NV_cluster_acceleration_structure.html).

4. **Preserve signal ownership before new lighting algorithms.** Before ReGIR
   or guidance, specify the contributions of camera-medium scattering, primary
   emission/direct light, primary diffuse and primary specular continuations.
   Guide split/recombination must preserve raw HDR, including water and multiple
   samples. NRD hit distance excludes camera-to-primary distance. A split must
   account for DI/PT reuse and RC rather than classify final colour by material.
   [NRD integration requirements](https://github.com/NVIDIA-RTX/NRD#integration).

5. **No unconditional critical FFM downcalls.** Java 25 requires an extremely
   short call in every case and forbids callbacks into Java. `true` means heap
   access, not a general fast-call switch. Driver compilation, allocating native
   recorders and callback-based adapters keep ordinary downcalls. Reduce and
   measure interface overhead through coarse calls; specialize only proven
   eligible leaves.
   [Java 25 contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#critical(boolean)).

6. **Transactional pipeline generations for hot reload.** Build a candidate
   off the live generation, validate reflection/layout/capabilities and swap
   only after success. Old programs/resources stay pinned through completion;
   failed compilation keeps the current image. Shader Objects are a separate
   optional backend, not a prerequisite. Metal output likewise does not replace
   Vulkan device/command/resource integration.
   [Shader Objects](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_shader_object.html),
   [Slang targets](https://shader-slang.org/slang/user-guide/targets).

7. **Optimization follows evidence.** FP16 arithmetic and 16-bit storage need
   separate enabled features; keep geometry/PDF/weights at precision justified
   by range/error tests. Subgroup algorithms must support subgroup-size and
   reconvergence constraints. DGC is a candidate for actual variable worklists,
   not an assumed improvement over one fullscreen dispatch. A calibration run
   uses elapsed-time/sample limits, completed GPU timings and a fallback preset;
   120 frames are not a guarantee of two seconds.
   [Float16 features](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceShaderFloat16Int8Features.html),
   [DGC](https://docs.vulkan.org/features/latest/features/proposals/VK_EXT_device_generated_commands.html).

8. **Compare RC representations rather than asserting one universal winner.**
   SPWI uses screen-space probes and world-space intervals; hardware tracing is
   a valid implementation, not a contradiction of the method. The July 2026
   Split Radiance Cascades paper (revised October 11) instead proposes sparse
   world probes and ray splitting from visible surfaces. Compare memory,
   off-screen coverage, thin-wall leakage, motion and temporal stability on the
   same Bedray captures before replacing the current bounded field. No Bedray
   performance/quality superiority for either candidate is established yet.
   [SPWI thesis](https://odr.chalmers.se/items/3ee9fb4e-1880-46c2-802a-a660e38dc9ee),
   [Split RC](https://arxiv.org/abs/2607.20384).

## Execution order and acceptance

1. Repair confirmed current-path defects: cache lifetime/use, FAST-cloud forward
   intersection, denoiser exposure and camera-medium direct contribution. Specify
   full denoiser signal split/hit-distance ownership before changing that ABI.
2. Wire shared Slang/native Tier 1 to the real loaded-section capture, with a
   ray-query-free plan; then implement Tier 2 partition and live selection.
3. Consolidate memory allocation and expose real whole-frame resource boundaries
   to the graph. Add aliasing only for proven non-overlapping generations.
4. Integrate spatial light sampling and the chosen RC layout/guidance, then
   froxels and distant LOD with explicit residency/history contracts.
5. Add optional native OMM and appropriate GPU worklists. Evaluate SER/PTLAS/CLAS,
   DGC and pipeline binaries behind capability-specific backends without changing
   baseline correctness or requiring them for universal mode.
6. Finish hot reload, debug heatmaps, profiler/calibration and real platform
   backends/packaging. Maintain one module owner per mutable resource/proof.

Each implementation needs a live call path, reflected/ABI validation, failure
behavior and diagnostic context, suitable L1/L2 checks and exact revision logs.
Uncalled helpers and enum values alone do not finish a feature. GPU images,
frame-time improvements, VRAM savings, hardware support and game acceptance
require actual measurements on the relevant host/device. Native core continues
to record only: host owns device, queues, submission and completion.
