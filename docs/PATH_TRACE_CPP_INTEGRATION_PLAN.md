# Path tracing C++ pass — integration plan

Status: **source-only plan**. No build, test, `slangc` run, Minecraft launch or
deployment was performed for this document, and no Java renderer file or shared
ABI was edited. A plan is not evidence that a C++ path-trace pass compiles or
renders.

Scope: move the wired Java path-tracing host (`RtSlangPathPass` plus the
package-private `RtPathTraceSlangPass`) into a C++ pass that owns its pipelines,
descriptors, images, frame uploads and barriers, while the host API stays coarse.
The Slang programs `passes/path_trace.slang` and `passes/path_display.slang`
remain the algorithm. This document records the current state, target ownership,
resource lifetime, synchronization, the scene/shader contract gap, and the
decisions that need an owner before code is written.

Related documents:

* [SLANG_MIGRATION.md](SLANG_MIGRATION.md) — whole-pipeline status and next steps;
* [BLOOM_CPP_INTEGRATION_PLAN.md](BLOOM_CPP_INTEGRATION_PLAN.md) — the same
  ownership exercise for the post chain, including the image-allocator decision;
* [../native/bedray_scene/SCENE_NOTES.md](../native/bedray_scene/SCENE_NOTES.md) —
  the candidate native scene and its lifetime rules;
* [../native/bedray_core/RECORDING_LIFETIME.md](../native/bedray_core/RECORDING_LIFETIME.md) —
  recording and program retirement.

Constraints:

* no second Vulkan device, queue, swapchain, submission or present path;
* no `vkDeviceWaitIdle`, queue wait or fixed frame delay as a lifetime
  mechanism in the new path;
* Java keeps only Minecraft/Fabric integration, game data capture, the host
  command buffer and the submission/completion evidence;
* do not add a Java-owned renderer or extend the legacy GLSL path as the target;
* unknown submission outcomes pin resources; they are not recycled after a
  frame count.

## 1. What exists today

The two AOT programs are committed and selected by Path Tracing mode:

* [path_trace.slang](../common/src/main/resources/shaders/slang/passes/path_trace.slang)
  traces one path per pixel into an RGBA16F radiance image and writes the
  primary-hit distance into an R32F depth image. It imports
  `LegacySectionTraversal`, which dereferences the 64-byte `LegacySection` table
  and the planar arena already uploaded by `RtWorld`;
* [path_display.slang](../common/src/main/resources/shaders/slang/passes/path_display.slang)
  folds the sample into a running mean, applies exposure and tone curve, and
  writes the renderer's RGBA8 `rt_output`.

The Java adapter
[RtSlangPathPass.java](../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java)
constructs the trace pass at line 109, chooses accumulation/reset state in
`configure`, dispatches both programs at lines 242–264, and releases the native
recording at lines 343–358. The low-level host
[RtPathTraceSlangPass.java](../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java)
owns the radiance image and the constant/table buffers, validates the nine-binding
manifest, and records the trace dispatch.

`RtFrameRenderer` selects this adapter in Path Tracing mode; `AUTO` falls back
to the legacy GLSL `rt_path.comp` when the AOT passes, the required device
capabilities, or a G-buffer denoiser block it. The dedicated Slang runtime is
created with the actual enabled capability mask
([RtFrameRenderer.java:1455](../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L1455)).
The mask-0 runtime in `RtAtmosphere` belongs to bloom only.

Current host resources:

| Binding | Slang type | Current resource | Notes |
|---|---|---|---|
| 0 `tlas` | acceleration structure | `RtWorld` TLAS | instance transforms are camera-relative |
| 1 `outImage` | RGBA16F storage | Java `rt_path_radiance` | `STORAGE_BIT \| SAMPLED_BIT`, `GENERAL` |
| 2 `sections` | `StructuredBuffer<LegacySection>` | `RtWorld` 64-byte section table | legacy traversal input |
| 3 `albedoAtlas` | combined sampler | `RtWorld` block atlas view + nearest sampler | sampler is Java-owned |
| 9 `materialAtlas` | combined sampler | LabPBR `_s` view | same sampler |
| 10 `normalAtlas` | combined sampler | LabPBR `_n` view | same sampler |
| 12 `outDepth` | R32F storage | renderer `rt_depth` | host-owned, `GENERAL` |
| 16 `frame` | 608-byte uniform buffer | ring of three Java buffers | one upload per frame |
| 17 `blockMaterials` | 64-byte structured buffer | one zeroed element, `materialCount = 0` | dummy until material tables exist |

The display program binds `radiance` (0), the RGBA32F `accum` (1) and the
host `rt_output` (2). The trace push block is 40 bytes; the display push block
is 16 bytes. Both programs use 8x8x1 workgroups. The trace program requires
`CAP_RAY_QUERY | CAP_BUFFER_DEVICE_ADDRESS | CAP_SHADER_INT64` (manifest mask
517).

Depth contract: `firstHitDistance` is a distance along the unit primary
direction, not linear view depth. A true primary miss writes `1e6`, which is the
sky sentinel bloom (`>= 1e5`) and the composite already understand. Invalid or
truncated primary paths set `PATH_TRACE_FLAG_INVALID_SAMPLE` and never add
environment radiance. The Java velocity path already declares this convention
as `DEPTH_RAY_DISTANCE`; a consumer that needs linear view depth must convert
explicitly.

Known limits of the current Java host:

* no G-buffer, so NRD and DLSS-RR cannot run on this pass; Path Tracing mode
  disables the G-buffer denoiser while Slang is active;
* no auto exposure; `FrameConstants.exposure` is fixed at 1 and the display
  push also uses 1;
* a two-colour gradient sky derived from one game sky colour, not `rt_sky.glsl`;
* primary rays are built from the camera basis at the origin; the TLAS is
  camera-relative, so this is consistent, but the view-bob offset is absent;
* `RtPathTraceSlangPass.ensure` and `close` call `vkDeviceWaitIdle` before
  replacing or destroying the radiance image
  ([RtPathTraceSlangPass.java:416](../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java#L416));
  `RtSlangPathPass.prepare` also waits before recreating the accumulation image;
* the frame-constant ring has three slots and relies on the engine keeping at
  most two frames in flight; a third in-flight recording would overwrite a block
  the GPU may still read.

Evidence level: the pass sources and the generated SPIR-V were reviewed, and
the cutout/truncation fix was compiled to SPIR-V outside the repository for
`7d6c22a`; there is no fresh GPU, Minecraft or complete-tree evidence for the
current source.

## 2. Target ownership split

Java (host) keeps:

* `VkInstance`, `VkDevice`, one graphics queue, the command buffer and all
  submission/fence evidence;
* `rt_output` (RGBA8, `GENERAL`) and `rt_depth` (R32F, `GENERAL`) until a C++
  resource owner exists for them;
* Minecraft-derived inputs: camera basis and origin, jitter, sun/moon direction
  and colour, sky/atmosphere colours, exposure, path limits and the
  accumulation reset decision;
* atlas image views; the fixed sampler can move to C++;
* the decision to use path tracing and the single completion/release call for
  the command buffer;
* validation at the boundary (finite matrices/colours, positive extent, ranges)
  before calling C++.

C++ path-trace pass owns:

* the two pipelines/pipeline layouts created from registered programs, and the
  fixed sampler;
* the RGBA16F radiance and RGBA32F accumulation images, views and memory,
  created lazily per extent and retained until completion;
* per-recording descriptor sets and per-recording frame-constant uploads;
* the dummy `BlockMaterial` table until the scene material contract exists;
* the 40-byte trace push and 16-byte display push packing from named parameters;
* the entry barrier, the trace→display barrier, the display exit barrier and the
  `UNDEFINED→GENERAL` transitions for its own images;
* dispatch recording, generation retirement on `release_recording`, and status
  reporting for unsupported extent/limits, allocation failure and invalid
  parameters.

Slang owns the path algorithm and the display/tonemap math. The native scene
owns the TLAS, geometry and tables once the contract in section 4 is agreed.

The render graph and `FrameScheduler` are not part of the first landing. The
pass records conservative entry/exit barriers and keeps the existing host order
until a path-trace pass kind and an external-image boundary contract exist.
The graph's capability mask and the 10-bit Slang mask still need one mapping;
see `SLANG_MIGRATION.md` and the bloom plan.

## 3. Proposed C++ pass interface

The first implementation should live in a new internal native module
(`native/bedray_path_trace/`, tentative) linked into `bedray_core`, or inside the
core as an internal pass. It is not a new DLL with its own device or submission
path.

Internal C++ interface:

```cpp
struct PathTraceFrameParams {
    uint64_t command_buffer;
    uint64_t output_view;      // RGBA8, GENERAL, host-owned
    uint64_t depth_view;       // R32F, GENERAL, host-owned
    uint32_t width, height;
    uint32_t sample_index;
    uint32_t accum_frames;     // 0 = discard history
    uint32_t max_depth, max_surface_steps;
    float ray_bias, alpha_cutoff, max_distance, max_radiance;
    float sun_probability, exposure;
    float view[16], projection[16], view_projection[16];
    float inv_view_projection[16];
    float camera_origin[3], forward[4], right[4], up[4], jitter[4];
    float sun_direction[4], sun_color[4];
    float moon_direction[4], moon_color[4];
    float zenith[4], horizon[4], fog_color[4], fog[4], cloud[4];
    BedraySceneResources scene; // copied during the call
    uint64_t albedo_view, material_view, normal_view;
};

class PathTracePass {
public:
    PathTracePass(CoreDeviceFns fns, uint64_t program_trace, uint64_t program_display);
    BedrayStatus record(const PathTraceFrameParams& params, BedrayError* error);
    void release_recording(uint64_t command_buffer);
    BedrayStatus device_idle();  // proven idle only
    void device_lost();
    void destroy();
};
```

The host-facing C ABI addition is a proposal only and must not be implemented
while the ABI is paused. It should be two coarse calls: one to bind the
registered programs, one per frame. It must not expose one call per dispatch,
mip or binding.

```c
typedef struct BedrayPathTraceDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t program_trace;    /* handles from bedray_core_register_program */
    uint64_t program_display;
} BedrayPathTraceDesc;

typedef struct BedrayPathTraceFrameDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t command_buffer;
    uint64_t output_view;      /* RGBA8, GENERAL */
    uint64_t depth_view;       /* R32F, GENERAL */
    uint32_t width, height;
    uint32_t sample_index, accum_frames;
    uint32_t max_depth, max_surface_steps;
    float ray_bias, alpha_cutoff, max_distance, max_radiance;
    float sun_probability, exposure;
    /* camera basis, sun/moon, sky fields omitted here; fixed-layout fields */
    const BedraySceneResources* scene; /* copied during the call */
    uint64_t albedo_view, material_view, normal_view;
} BedrayPathTraceFrameDesc;

BedrayStatus bedray_core_configure_path_trace(BedrayCore*, const BedrayPathTraceDesc*, BedrayError*);
BedrayStatus bedray_core_render_path_trace(BedrayCore*, const BedrayPathTraceFrameDesc*, BedrayError*);
```

The exact struct version, whether the pass receives `BedraySceneResources` from
the host or queries a linked scene itself, and whether the core may create its
own images require owner approval (section 9). C++ must build the push blocks
from named fields and assert the Slang-side offsets at build time; Java must not
write raw push bytes or individual bindings in the target.

## 4. Scene and shader contract

This is the main unresolved contract, not a mechanical port.

The current `path_trace.slang` traversal expects `LegacySection`, a 64-byte
record with device addresses for positions, UVs, colours, lights, quads and an
origin, plus the planar arena. The candidate native scene exposes
[BedraySceneSectionRecord](../native/bedray_scene/include/bedray_scene.h#L260),
a 48-byte record with vertex/index/BLAS addresses, counts, stride and index
type, and opaque material/light tables. The two layouts are not interchangeable:

* the scene does not yet define the vertex attribute layout (position, normal,
  UV, tint/colour), so a hardware hit cannot be decoded into a `SurfaceHit`;
* the scene's material and light tables are opaque blobs; Slang needs stable
  record layouts, emitter identity/emission semantics and a revision rule;
* the scene does not expose a section/entity identity on the hit. The current
  legacy table lookup and `instanceCustomIndex` mapping need an explicit
  contract for material lookup, texture atlas coordinates and world position;
* `SurfaceHit.worldPosition` currently wraps section origins at 1024 blocks in
  both backends. It is not a globally stable identity for reprojection,
  temporal reuse or emitter lookup; the scene must provide an unwrapped or
  rebased position contract;
* traversal is material-agnostic today and does not set an emissive flag from
  material data. Emitter identity and emission must be resolved after material
  decoding, not inferred from `HIT_FLAG_EMISSIVE` on a raw hardware hit.

The target should add a `SceneSectionTraversal` (or a hardware-only
`SceneTraversal`) that consumes the native scene records and reflected tables.
It must not keep `LegacySectionTraversal` in the final path. Adding bindings ad
hoc to `path_trace.slang` is not allowed: the manifest, the Java validation and
the C++ descriptor contract all change together.

Ordering: `bedray_scene_prepare_frame` records the TLAS/table builds and copies
into the same host command buffer and must run before the trace dispatch. The
trace pass reads the TLAS and tables for that frame index; the scene resources
stay valid until `bedray_scene_release_frame` after proven completion. The
scene module owns its build/transfer barriers; the path pass must still record
the compute-read barrier required by the graph or conservative entry barrier.

Instance policy: the current shader masks instances with `0x01`. The scene
normalizes an entity `instance_mask == 0` to `0xFF` and lets the host choose
mask bits. The target must define whether path tracing sees all instances, a
separate TLAS, or a documented mask bit; it must not depend on an undocumented
default.

## 5. Resource lifetime and completion

* Radiance and accumulation generations are keyed by extent. A generation holds
  its images, views and memory. A recording that references it holds a
  reference added before the trace dispatch and removed by
  `release_recording`.
* On resize, a new generation is created; the old one is retired and destroyed
  only when its reference count reaches zero. If allocation fails, the pass
  reports `BEDRAY_ERROR_OUT_OF_MEMORY` and records nothing for that frame. A
  previous generation is never reused with a different extent.
* No `vkDeviceWaitIdle` and no fixed frame delay. A generation with an
  unresolved reference stays alive. `device_lost` abandons GPU memory accounting
  without claiming completion; `device_idle` may release retired generations
  only when every recording is already released.
* Frame constants must be per recording, or a ring sized by the frame
  scheduler's slots with a rule that refuses to overwrite a block still
  referenced by an in-flight recording. A three-slot ring with no in-flight
  check is not sufficient.
* `output_view` and `depth_view` remain host-owned, EXCLUSIVE, in `GENERAL`, and
  must stay valid and unchanged until `release_recording`. The pass does not
  transition or destroy them. The output image must not alias a C++ pass image.
* The dummy `BlockMaterial` table is allocated for one element and never read
  while `materialCount == 0`. When the real material table arrives, its element
  size and descriptor range change with the manifest; the host must not keep a
  64-byte assumption.
* Programs are registered once; destroying a program defers destruction while
  recordings reference it. Descriptor pools are per recording and are not
  mutated after recording.
* Release order inside one recording: the core destroys that recording's
  descriptor pools first, then the pass drops its image-generation reference.
  A view is never destroyed while a live set can still reference it.
* `FrameScheduler`'s release callback and `bedray_core_release_recording` must be
  one path, not two counters. Unknown submission keeps everything pinned until
  `device_idle` or `device_lost`.

## 6. Barriers and layouts

The pass records into the host command buffer. It does not submit, present,
wait or create a render pass.

1. Entry: a conservative memory barrier from `ALL_COMMANDS` to
   `COMPUTE_SHADER`, `MEMORY_WRITE|MEMORY_READ → MEMORY_READ|MEMORY_WRITE`,
   because the producer (scene build, denoiser, composite or legacy pass) is not
   named here. This mirrors the current `VkBarriers.fullMemoryBarrier` before
   the trace dispatch.
2. First use of a new radiance/accum generation: image barrier
   `UNDEFINED → GENERAL`, `srcStage=TOP_OF_PIPE`, `srcAccess=0`,
   `dstStage=COMPUTE_SHADER`, `dstAccess=SHADER_WRITE`, full subresource range.
   Contents are not preserved; every texel is written before it is read.
3. Trace → display: memory barrier `COMPUTE_SHADER → COMPUTE_SHADER`,
   `SHADER_WRITE → SHADER_READ` for radiance. The depth image is written by the
   trace and read by external consumers, so it must be covered by the exit
   barrier or a graph hazard; do not rely on the display dispatch.
4. Display → consumers: `ALL_COMMANDS → ALL_COMMANDS`,
   `MEMORY_WRITE|MEMORY_READ → MEMORY_WRITE|MEMORY_READ` for `rt_output` and the
   accumulation image. When the graph owns the final image copy and display,
   replace this with graph hazards and an external boundary contract.

All images are EXCLUSIVE and owned by the single graphics family. No queue
ownership transfer is recorded. The pass must reject a CONCURRENT image or one
owned by another family.

Depth convention: the pass writes a ray distance with a `1e6` sky sentinel, not
linear view depth. Bloom's `>= 1e5` cutoff and the velocity `DEPTH_RAY_DISTANCE`
path are valid only for this convention. A future denoiser or upscaler that
expects linear view Z needs an explicit conversion or a separate depth target;
the pass must not silently reinterpret `rt_depth`.

## 7. Dispatch and frame data contract

| Stage | Program | Source | Destination | Dispatch |
|---|---|---|---|---|
| trace | `path_trace` | TLAS + tables + atlases + frame constants | radiance + depth | `ceil(width/8) x ceil(height/8)` |
| display | `path_display` | radiance + accum | accum + `rt_output` | `ceil(width/8) x ceil(height/8)` |

* Trace push (40 bytes): `maxDepth`, `maxSurfaceSteps`, `sampleIndex`,
  `materialCount`, `flags`, `rayBias`, `alphaCutoff`, `maxDistance`,
  `maxRadiance`, `sunProbability`.
* Display push (16 bytes): `accumFrames`, `flags`
  (`ACCUMULATE`, `TONEMAP`), `exposure`, `maxRadiance`.
* Group counts are checked against the core's
  `max_compute_work_group_count_*`; exceeding a limit is an error, never a
  silent clamp.
* Exposure is applied exactly once, in `path_display`; the trace writes
  scene-referred linear radiance. The current host passes exposure 1 and
  `autoExposure = 0`; the C++ pass must preserve one application point or
  implement auto exposure explicitly. It must not apply exposure in both
  stages.
* Camera: the current shader builds a primary ray at the origin from
  `forward/right/up`, pixel jitter and `tanHalf/aspect`, and the TLAS instance
  transforms are camera-relative. The target must either keep a camera-relative
  TLAS and pass the rotation/basis, or pass a world-space camera origin and
  define the rebasing in the scene contract. The view-bob offset is part of the
  origin; it must not be silently dropped.
* Sky: `GradientSky` reads `FrameConstants.atmosphere`. The current host derives
  a two-colour gradient from one game sky colour. The target environment
  contract must name the producer and the colour space; it must not claim
  `rt_sky.glsl` parity until that shader’s behaviour is reproduced.
* Sun/moon: direction, colour, angular radius and `sunProbability` feed NEE.
  The C++ pass validates finite values and the probability range.
* Accumulation: the display pass is a running mean, not a denoiser. The host
  decides resets from camera movement, scene changes and settings. A C++ reset
  policy must use stable identity (camera/scene revisions) and must not infer
  history validity from a frame count.
* G-buffer denoisers: path tracing has no G-buffer, so NRD/DLSS-RR cannot be
  selected on top of it without a separate guide pass. The plan does not add
  one; the mode resolver must report the unsupported combination instead of
  silently disabling the denoiser. The existing Java `AUTO` fallback is
  reference behaviour, not the target selection policy.

## 8. Frame integration

Ordering stays: scene prepare → trace → display → bloom/composite/particles →
copy/upscale. The trace writes `rt_output` and `rt_depth`; bloom and the
composite read them. The pass does not touch NRD, DLSS-RR, motion vectors or the
G-buffer.

One coarse call per frame replaces the Java trace+display recording. The call is
made only when path tracing is selected and the pass is supported. A debug view
that the Slang pass cannot implement is an unsupported combination, not a
reason to silently return a different renderer in the target; until the C++
selector exists, the Java `AUTO` fallback remains the reference.

Completion integration:

* `bedray_core_release_recording` is called once per command buffer after
  proven completion or a proven discard; the pass’s image-generation reference
  is dropped in the same call.
* Destroying the core while a path-trace recording is registered remains
  `BEDRAY_ERROR_IN_USE`.
* A recording failure after the trace dispatch requires discarding the whole
  command buffer and releasing it; the pass must not leave a half-recorded pair
  without a release path.

The pass never calls `vkQueueSubmit`, `vkDeviceWaitIdle` or `vkQueueWaitIdle`,
including on resize.

## 9. Missing contracts and decisions for the owner

1. **Scene traversal and vertex layout.** `LegacySection` (64 B) is not
   `BedraySceneSectionRecord` (48 B). Define the vertex attributes, hit decoding,
   instance identity and the replacement traversal before changing
   `path_trace.slang`. This is the largest open item.
2. **Material and light records.** The scene tables are opaque. Define stable
   record layouts, revisions, emitter identity/emission and the block-material
   lookup. Do not rely on `HIT_FLAG_EMISSIVE` being set by hardware traversal.
3. **World-position identity.** Section origins currently wrap at 1024 blocks.
   Request an explicit unwrapped/rebased position or a stable identity for
   reprojection, temporal accumulation and emitter lookup.
4. **Camera and TLAS basis.** Keep a camera-relative TLAS or pass a world-space
   origin; include the view-bob offset and the same pixel/jitter mapping the
   legacy camera uses. State the NDC Y flip and jitter sign.
5. **Image allocation and ownership.** ABI v2 has no image allocator. C++-owned
   radiance/accumulation images need either an internal allocator with a
   documented ownership exception or the narrower host-owned-image fallback.
   Cross-reference the bloom plan’s decision; one allocator contract should
   serve both passes.
6. **Multi-dispatch pass and program delivery.** ABI v2 records one program, one
   set and one dispatch per call. Path tracing needs two dispatches. Add a
   coarse pass call or an internal executor; decide whether Java registers the
   two programs or the native build embeds them. No Java per-dispatch loop in
   the target.
7. **External image boundary.** `rt_output`/`rt_depth` are host-owned and enter
   in `GENERAL`. Name the producer stages, the consumer of the final copy and
   the single queue family; until then the conservative barriers are the only
   safe choice.
8. **Depth contract.** The pass writes ray distance with a `1e6` miss sentinel.
   Bloom and velocity already respect it. A future denoiser/upscaler that wants
   linear view Z needs an explicit conversion or separate target; record it
   before connecting one.
9. **Exposure and sky parity.** Auto exposure is absent and the sky is a
   two-colour approximation. Decide whether the first C++ landing keeps those
   limits (and reports them) or implements the full `FrameConstants.exposure`
   and `rt_sky.glsl` behaviour. Do not claim parity without evidence.
10. **Denoiser and G-buffer mode.** Path tracing cannot feed NRD/DLSS-RR
    without a G-buffer. The mode resolver must reject or report the combination;
    it must not silently disable the denoiser as a hidden fallback.
11. **Graph pass kind and capability mapping.** `PlanPassKind` has no
    path-trace/post kind, `PassDesc` carries one dispatch and no program handle
    or push bytes, and the graph’s 64-bit capabilities do not yet share the
    10-bit Slang mask. These must be designed before path tracing is declared a
    graph pass.
12. **Completion and validation.** The Java path is the only implemented
    reference; there is no C++ pass, contract test, GPU run or game run for it.
    Unknown submission outcomes must pin resources. This document records no
    fresh verification result.

### Follow-ups from the 2026-10-27 shader audit

Source-level findings that the C++ port must not inherit silently. None of them
has been checked on a GPU; the reference pass was only compiled with `slangc`.

* **Sun/moon are visible only through NEE.** `GradientSky` is a two-colour dome
  with no disk and is the pass's only environment sampler; `SunMoonSampler` is
  the only `ILightSampler`. A camera ray or a delta reflection aimed at the sun
  sees the gradient, not the disk. Adding a disk to `radiance()` requires MIS
  with NEE, or diffuse paths would count the sun twice.
* **Emission used to be dropped after a diffuse bounce.** The reference pass
  gated emission on the previous bounce being a camera or delta ray, assuming
  emitter NEE; the wired sampler covers only the sun and moon, so glowstone and
  lava hit after a diffuse bounce contributed nothing. `tracePath` now adds
  emission on every accepted hit. A port that binds an emitter sampler must
  restore the gate and use MIS, or emitters will be counted twice.
* **No emitter buffer in the pass.** `light/emitters.slang` defines
  `UniformEmitterSampler` and `EmitterList`, but `passes/path_trace.slang` has
  no emitter binding and the manifest has no emitter record. Emissive-face NEE
  is not connected.
* **Optical paths do not track the medium.** `bsdfSample` always uses
  `eta = 1 / ior` with the viewer-facing normal and `tracePath` ignores
  `BSDFSample.eta`; an exit interface refracts as if it were an entry. Slab
  absorption from `surfaceTransmittance` is used only by the visibility walk,
  not by camera paths. An enter/exit eta stack and medium absorption are
  required before optical transmission is physically correct.
* **Thin-pane transmission is not sampled by NEE.** `bsdfSample` and
  `bsdfPdf` now describe one mixture: a thin pane splits its diffuse branch
  between transmission and reflection by `transmission`, and an optical
  interface has no diffuse lobe because its transmission is delta.
  `bsdfEvaluate` still returns zero in the back hemisphere by design, so a
  light behind a pane contributes only through a BSDF-sampled path, never
  through NEE. The sun is not in the environment, so sunlight through glass
  remains missing until transmission NEE exists with a shadow ray that starts
  on the far side of the pane.
* **Visibility overflow is silent.** `traceFilteredVisibility` returns
  `visible = true` with the accumulated transmittance when the 16-layer budget
  runs out, so an opaque blocker behind sixteen filters leaks.
  `SURFACE_VISIBILITY_FLAG_OVERFLOW` exists but the path tracer never reads it;
  decide whether overflow counts as blocked or the budget must grow.
* **Invalid samples are diagnostic only.** `tracePath` sets
  `PATH_TRACE_FLAG_INVALID_SAMPLE` and still returns the radiance accumulated
  before the fault; `passes/path_trace.slang` writes that radiance without
  inspecting the flag, so the accumulator cannot reject the sample. Decide
  whether an invalid sample writes zero radiance or a rejection mask.
* **The Java frame block leaves three matrices identity.** `fillFrame` writes
  `invViewProjection` and `viewProjection` but never `view`, `projection` or
  `prevViewProjection`. `path_trace` derives primary rays from the explicit
  basis and does not read them today; a reprojection or velocity consumer must
  fill them (or use the history contract's matrices) before relying on them.
* **Entities are invisible to the path tracer.** `RtWorld.buildTlas` writes the
  entity BLAS instance with mask `0x02` (`RtWorld.java:1089`), while
  `passes/path_trace.slang` traverses with
  `PATH_TRACE_INSTANCE_MASK_WORLD = 0x01` (`path_trace.slang:110`). The
  reference path trace renders terrain only; mobs, item frames and other
  entities are absent. Decide whether the path pass should traverse `0x03` and
  how entity materials map onto the atlas before widening the mask.
* **Water is a flat thin pane.** The legacy triangle decode maps only the
  redstone and emissive source bits (`surface/surface.slang:237`); no code
  assigns `MATERIAL_SOURCE_FLUID` on this path and no water module is compiled
  (`bedray.slang` keeps `public import water;` commented out). A water hit
  decodes as `AlphaMode.Translucent` with `transmission = 1` and the flat face
  normal, so there are no wave normals, refraction or caustics. The host also
  passes `pc.flags = 0` (`RtSlangPathPass.java:266`), so the legacy
  `FLAG_WATER_REALISTIC`/caustics behaviour cannot be recovered by a flag
  alone; the fluid tag and a shading model have to be connected.

## 10. Migration sequence

1. **Freeze the reference.** Keep `RtSlangPathPass`, `RtPathTraceSlangPass`,
   `RtFrameRenderer`, `RtBloomPass`, `RtAtmosphere` and the shared ABI
   untouched while ownership is coordinated. Record the current behaviour; do
   not extend the GLSL fallback.
2. **Scene traversal contract.** Define the vertex/material/light records and
   the replacement traversal; add a source-level contract test for the shader
   table layout. No GPU calls.
3. **Pass skeleton.** Add the internal module with extent/generation logic,
   frame-constant ownership, push packing and a fake-Vulkan barrier/dispatch
   plan test. No real images or GPU calls.
4. **Allocator and coarse call.** Add the C++ image/memory/sampler/barrier
   function table and generation retirement, then the proposed coarse host
   call behind an opt-in switch with the Java path still default. No public ABI
   change until the owner approves versioning.
5. **In-game switch.** After the user explicitly starts verification, enable the
   C++ pass for Path Tracing mode, compare output against the Java reference,
   and fix only evidence-backed differences. Then remove Java image/descriptor/
   pipeline ownership and the GLSL fallback from the new path; report
   unsupported states instead of silently switching.
6. **Graph integration.** Add the path-trace/post pass kind, per-pass program and
   push contract, external boundary contract and scheduler release hook;
   replace the conservative entry/exit barriers with graph hazards once the
   graph owns the final image copy and display.

Exit criteria for each step: a source review plus a relevant contract test or a
recorded verification result. A compile is not a game check, and a plan is not a
compile.

## 11. Non-goals

* No second Vulkan device, queue, swapchain, submission or present path.
* No `vkDeviceWaitIdle` or queue idle as a lifetime mechanism.
* No Java-owned renderer or per-dispatch Java descriptor tables in the target.
* No new GLSL fallback as the target; the existing fallback is reference-only.
* No G-buffer/denoiser implementation in this plan.
* No claim that the current source compiles, runs or renders on a GPU.
