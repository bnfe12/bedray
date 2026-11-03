# Bloom C++ pass — integration plan (addendum 06)

Status: source-only plan. No build, test, slangc run, Minecraft launch or
deployment was performed for this document, and no Java renderer file or shared
ABI was edited. A plan is not evidence that a C++ bloom pass compiles or runs.

Scope: move the bloom and sun-glare chain from the Java host integration in
`RtBloomPass` / `RtAtmosphere` into a C++ pass that owns its pipelines,
descriptors, mip images and barriers, while the host API stays coarse and the
Java side keeps only Minecraft integration. This document records the current
state, the intended ownership, resource lifetime, dependencies, and the
contracts that do not exist yet and need an owner decision.

Constraints from addendum 06 and the repository rules:

* no second Vulkan device, queue, swapchain or submission path;
* no `vkDeviceWaitIdle` in the new frame path;
* no new Java-owned renderer and no new GLSL fallback as the target design;
* do not edit `RtBloomPass`, `RtAtmosphere`, `RtFrameRenderer` or the shared
  ABI while ownership is being coordinated; this plan is the deliverable, not
  the code change;
* unknown submission outcomes must keep resources pinned, not be recycled
  after a frame count.

## 1. What exists today

The four Slang entry points are committed in `06ca754` together with the
Java-side host integration. The bloom-related files touched by that commit are
the four `passes/bloom_*.slang` entries, `bloom/bloom.slang`,
`RtBloomPass.java`, `RtAtmosphere.java`, `RtFrameRenderer.java` (call site and
recording release), the shared `SlangRuntime`/`SlangProgram`/`SlangPassLibrary`/
`SlangPassManifest`/`SlangPass` registration classes, `BedrayCoreNative` and
`BedrayCoreLayouts`, the bootstrap/mixin that expose Vulkan features, and
`build.gradle` / `common/build.gradle` for the AOT compile task. Only the Slang
algorithm files are reusable as-is; the rest is either shared infrastructure
that must stay coarse or the temporary host orchestration this plan replaces.

| File | Current role | Target ownership |
| --- | --- | --- |
| `common/src/main/resources/shaders/slang/bloom/bloom.slang` | Reusable behaviour: prefilter, 13-tap down, tent up, apply, sun glare, sky cutoff, sanitisation | C++ pass algorithm (unchanged source) |
| `.../slang/passes/bloom_prefilter_down.slang` | Entry point, bindings `srcTex`=0, `dstImg`=1, `depthTex`=3, 128-byte push | C++ pass, AOT program |
| `.../slang/passes/bloom_down.slang` | Entry point, bindings `srcTex`=0, `dstImg`=1 | C++ pass, AOT program |
| `.../slang/passes/bloom_up.slang` | Entry point, bindings `srcTex`=0, `dstImg`=1, in-place mip update | C++ pass, AOT program |
| `.../slang/passes/bloom_apply.slang` | Entry point, bindings `srcTex`=0, `finalImg`=2, `depthTex`=3 | C++ pass, AOT program |
| `common/src/main/java/.../rt/world/RtBloomPass.java` | GLSL pipeline objects for the fallback, host-owned RGBA16F mips and two samplers, the 12-dispatch Slang orchestration, 128-byte push packing, manifest checks and `vkDeviceWaitIdle` on resize | Retired after the C++ pass is verified; the Java fallback must not be extended |
| `common/src/main/java/.../rt/world/RtAtmosphere.java` | Ordering (god rays, then bloom), game settings, sun NDC, `SlangRuntime` creation with mask 0, recording release hook | Keeps game settings and frame ordering; calls one coarse C++ post entry |
| `common/src/main/java/.../rt/world/RtFrameRenderer.java` | Creates `rt_output` (RGBA8, GENERAL) and `rt_depth` (R32F, GENERAL), calls the atmosphere, releases recordings | Keeps host resource ownership and the command buffer; no per-dispatch bloom detail |
| `common/src/main/java/.../rt/slang/SlangRuntime.java`, `SlangProgram.java`, `SlangPassLibrary.java`, `SlangPassManifest.java` | Load AOT SPIR-V from the jar, register programs, dispatch through ABI v2 | Registration-only integration; no per-frame descriptor work |
| `common/src/main/java/.../rt/core/BedrayCoreNative.java`, `BedrayCoreLayouts.java` | ABI v2 binding: one program, one descriptor set, one dispatch per call | Proposed coarse bloom/post call needs an ABI decision, see section 9 |
| `native/bedray_core` | Records dispatches, per-dispatch pool/set, `release_recording`, deferred program destruction | Gains the internal C++ pass or links a `bedray_post` module |

The Java class currently implements two behaviours in one place: the AOT Slang
chain when the runtime and all four manifests are available, and the legacy GLSL
`rt_bloom.comp` otherwise. Only the Slang algorithm work is reusable. The
fallback selection, host descriptor tables, mip ownership and the resize wait
are the integration that addendum 06 asks to separate and then replace.

The four programs already execute through the native core; Java does not
compile them at runtime. What remains on the Java side is orchestration:
`SlangProgram.dispatch` calls `bedray_core_render_frame` once per stage, with
host-supplied image views, samplers and push bytes. Moving pipelines and
descriptors to C++ therefore does not require a new shader language or a new
compiler path; it requires a C++ owner for the per-frame chain and its private
resources.

## 2. Target ownership split

Java (host) keeps:

* `VkInstance`, `VkDevice`, one graphics queue, the command buffer and all
  submission/fence evidence;
* `rt_output` (RGBA8, `GENERAL`) and `rt_depth` (R32F, `GENERAL`) until a C++
  resource owner exists for them; the first pass keeps them host-owned and only
  creates private bloom mips;
* game-derived parameters: unjittered camera-relative inverse view-projection,
  camera bob origin, key-light direction, glare strength, sun NDC, tone-map
  flag, Y flip, threshold, knee, strength and scatter;
* the decision to call the post chain and the completion/release call for the
  command buffer;
* settings validation at the boundary (finite values, ranges) before the call.

C++ bloom pass owns:

* four pipelines and pipeline layouts (already created by the core from
  registered programs) and the fixed samplers;
* six RGBA16F mip images, their image views and device memory, created lazily
  for the current extent;
* per-recording descriptor sets for all 12 dispatches, using the core's
  existing per-recording pool model;
* the 128-byte push block packing from named parameters;
* the entry barrier, the compute-to-compute barriers inside the chain, the mip
  UNDEFINED-to-GENERAL transitions and the exit barrier;
* mip generation retirement driven by `release_recording`, never by a frame
  count and never by `vkDeviceWaitIdle`;
* status reporting for unsupported extent/limits, allocation failure and
  invalid parameters.

The render graph and `FrameScheduler` are not part of the first C++ pass
landing. They become the owner of pass ordering and completion once a bloom or
post pass kind exists in `RenderPlan` and the host submits a graph; until then
the pass records its own conservative entry/exit barriers and the existing
host order is kept.

## 3. Proposed C++ pass interface

The first implementation should live in a new internal native module
(`native/bedray_post/`, tentative name) linked into `bedray_core`. It is not a
new DLL with its own device or submission path; it uses the core's device
function table and command buffer.

Internal interface (C++ only):

```cpp
struct BloomParams {
    float inv_view_proj[16];   // column-major, unjittered, camera-relative
    float sun_dir[3];
    float glare_strength;
    float camera_origin[3];
    float flip_y;              // 1.0 = NDC y is flipped
    float threshold;
    float knee;
    float strength;            // 0 disables the bloom chain in apply
    float star_amount;         // reserved, currently unused by the shader
    float tonemapped;          // 1.0 = final image is already tone mapped
    float scatter;
    float sun_ndc_x;
    float sun_ndc_y;
};

class BloomPass {
public:
    // Programs are registered handles owned by the core. Samplers are fixed
    // C++-owned objects; no host sampler handles are exposed.
    BloomPass(CoreDeviceFns fns, uint64_t program_prefilter_down, uint64_t program_down,
              uint64_t program_up, uint64_t program_apply);
    BedrayStatus record(uint64_t command_buffer, uint64_t output_view, uint64_t depth_view,
                        uint32_t width, uint32_t height, const BloomParams& params,
                        BedrayError* error);
    void release_recording(uint64_t command_buffer); // called by core release
    BedrayStatus device_idle();                      // proven idle only
    void device_lost();
    void destroy();
};
```

The host-facing C ABI addition is a proposal only; it must not be implemented
while the ABI is paused. It should be one coarse call per frame, not one per
mip or per dispatch:

```c
typedef struct BedrayBloomDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t program_prefilter_down; /* handles from bedray_core_register_program */
    uint64_t program_down;
    uint64_t program_up;
    uint64_t program_apply;
} BedrayBloomDesc;

typedef struct BedrayBloomFrameDesc {
    uint32_t abi_version;
    uint32_t flags;
    uint64_t command_buffer;
    uint64_t output_view;   /* RGBA8 view, GENERAL layout */
    uint64_t depth_view;    /* R32F view, GENERAL layout */
    uint32_t width;
    uint32_t height;
    uint32_t reserved[2];
    float inv_view_proj[16];
    float sun_dir[3];
    float glare_strength;
    float camera_origin[3];
    float flip_y;
    float threshold;
    float knee;
    float strength;
    float star_amount;
    float tonemapped;
    float scatter;
    float sun_ndc_x;
    float sun_ndc_y;
} BedrayBloomFrameDesc;

BedrayStatus bedray_core_configure_bloom(BedrayCore*, const BedrayBloomDesc*, BedrayError*);
BedrayStatus bedray_core_render_bloom(BedrayCore*, const BedrayBloomFrameDesc*, BedrayError*);
```

The C++ pass must build the 128-byte `BloomPush` from these named fields and
assert the Slang-side offsets at build time. Java must not write raw push bytes
or individual bindings. The exact ABI version, whether the parameters live in
the frame descriptor or in a separate settings block, and whether the core may
create its own images require the owner's approval (section 9).

If the owner rejects C++-owned images, the fallback is a narrower step: Java
keeps the mip images and passes their views once per frame, while C++ still
owns the chain, descriptors and barriers. That satisfies the descriptor and
barrier goals but not the mip-lifetime goal; it should be a documented
compromise, not silently substituted.

## 4. Resource lifetime and completion

Mip generation:

* A generation is one extent plus six images, six views and their memory. It is
  created on first use and reused on subsequent frames with the same extent.
* A recording that references a generation holds a reference. The reference is
  added before the first dispatch and removed by `release_recording` for that
  command buffer. The pool lifetime already follows this rule, so the mip
  generation follows the same completion evidence.
* On resize, a new generation is created and the old one is marked retired.
  The old generation is destroyed only when its reference count reaches zero.
  If an allocation fails, the pass reports `BEDRAY_ERROR_OUT_OF_MEMORY` and
  records nothing for that frame. A previous generation remains alive for
  recordings still referencing it, but it is never reused with a different
  extent.
* No `vkDeviceWaitIdle` and no fixed frame delay. A generation with an
  unresolved reference stays alive; the pass must not reuse its memory.
* `device_lost` abandons GPU memory accounting without claiming completion;
  `device_idle` may release retired generations only when every recording is
  already released (the core still refuses `bedray_core_destroy` while any
  recording remains).

Host images:

* `output_view` and `depth_view` remain owned by the host and must stay valid
  and unchanged until the command buffer's `release_recording` has run. The
  core does not destroy, recreate or own them.
* Both must be in `GENERAL` on entry, EXCLUSIVE sharing, with the exact format
  and extent supplied in the descriptor. No layout transition on host images is
  performed by the pass; if the host ever changes that contract, the entry
  barrier must be replaced by an explicit image transition.
* The output image must not alias a mip image. The graph currently has no
  aliasing check for external images; this is a host contract.

Pipelines, programs and samplers:

* The four program handles are registered once. Destroying a program defers
  actual destruction while recordings reference it (existing core rule).
* Samplers use fixed creation parameters: linear mag/min filter, linear
  comparison off, clamp-to-edge, mipmap mode nearest, minLod 0, maxLod 0 for
  the sampled mips; nearest mag/min for the depth reads. They are created by
  the pass and destroyed after all references are released.
* Descriptor pools are per recording, as in the current core. No set is mutated
  after recording or reused by a later frame.
* Release order inside one recording: the core destroys that recording's
  descriptor pools first, then the bloom pass drops the mip-generation
  reference. A mip view is never destroyed while a live set can still reference
  it.

## 5. Barriers and layouts

The pass records into the host command buffer. It does not submit, present,
wait or create a render pass. The chain is:

1. Entry: a memory barrier from `ALL_COMMANDS` to `COMPUTE_SHADER` for
   `MEMORY_WRITE|MEMORY_READ` to `MEMORY_READ|MEMORY_WRITE`. This is
   deliberately conservative because the producer (denoiser, composite or
   legacy pass) is not known to the bloom pass. It mirrors the current
   `VkBarriers.fullMemoryBarrier` before `RtAtmosphere`, but is recorded by C++.
2. First use of a newly created mip: an image barrier from `UNDEFINED` to
   `GENERAL`, `srcStage=TOP_OF_PIPE`, `srcAccess=0`,
   `dstStage=COMPUTE_SHADER`, `dstAccess=SHADER_WRITE`, full subresource range.
   Contents are not preserved; every mip texel is written before it is read.
3. Between stages: a memory barrier `COMPUTE_SHADER -> COMPUTE_SHADER`,
   `SHADER_WRITE -> SHADER_READ|SHADER_WRITE`. This covers the 13-tap down
   chain, the in-place tent up (a thread reads and writes only its own mip
   texel; the source mip is a different image), and the read of mip 0 by apply.
4. Exit: a memory barrier `ALL_COMMANDS -> ALL_COMMANDS`,
   `MEMORY_WRITE|MEMORY_READ -> MEMORY_WRITE|MEMORY_READ`. The consumer of
   `rt_output` is the host copy/upscale path with stages the pass cannot name.
   When the display/post pass becomes graph-owned, this is replaced by the
   graph hazard and boundary contract.

No queue-ownership transfer is recorded. All images must be EXCLUSIVE and owned
by the single graphics family; the host already creates them that way. The pass
must not accept a CONCURRENT image or one owned by another family.

The apply stage reads `finalImg[t]` for sun visibility while other threads write
`finalImg[p]`. That is a same-dispatch read/write with no ordering guarantee in
the current Slang shader. When the pass moves to C++, decide explicitly whether
sun visibility reads the pre-bloom image (for example a separate small dispatch
or a saved copy) or whether the benign-looking race is documented as accepted.
This plan does not silently change the shader; it records the question for the
owner.

## 6. Dispatch chain contract

Fixed for the first C++ version, matching the committed shaders:

| Stage | Program | Source | Destination | Dispatch |
| --- | --- | --- | --- | --- |
| 0 | `bloom_prefilter_down` | `rt_output` + `rt_depth` | mip 0 | `ceil(mip0.w/8) x ceil(mip0.h/8)` |
| 1..5 | `bloom_down` | mip i-1 | mip i | `ceil(mip_i.w/8) x ceil(mip_i.h/8)` |
| 6..10 | `bloom_up` | mip i+1 | mip i (in place) | `ceil(mip_i.w/8) x ceil(mip_i.h/8)` |
| 11 | `bloom_apply` | mip 0 + `rt_output` + depth | `rt_output` | `ceil(width/8) x ceil(height/8)` |

* `LEVELS = 6`; `mip_i = max(1, ceil(size / 2^(i+1)))` for both axes.
* Workgroup size is 8x8x1 in every entry point. Group counts must be checked
  against the core's `max_compute_work_group_count_*`; exceeding a limit is an
  error, never a silent clamp.
* `bloom = false` with glare enabled records only the apply dispatch and
  passes `strength = 0`. The mip generation is still created because the apply
  program statically declares the `srcTex` combined-image-sampler binding and
  Vulkan requires a valid descriptor for it; only the chain dispatches are
  skipped. `bloom = false` and glare disabled records nothing and needs no mips.
* The push block is 128 bytes. `p0.z` (strength) is zero when the chain is
  skipped; `p0.w` (star amount) is reserved and currently unused.
* The apply stage's `srcTex` is mip 0 even when `strength` is zero; the
  descriptor may still bind it, but the shader must not sample it in that case
  (the current shader guards on `p0.z > 0`). A mip that was never written stays
  in `GENERAL` with undefined contents, which is legal because it is not read.
* Depth is the primary-ray distance, not linear view depth. The shader treats
  values `>= 1e5` as sky (`BLOOM_SKY_DISTANCE`); the path tracer writes `1e6`
  for a primary miss. Any future producer must preserve that sentinel contract
  or the apply stage must be revised; the pass must not assume arbitrary depth
  conventions.

## 7. Frame integration

The host order stays: trace/path -> denoise -> optional particle composite ->
bloom + glare -> copy to the engine target -> upscale/frame generation. Bloom
reads and writes only `rt_output` and reads `rt_depth`; it must not touch NRD,
DLSS-RR, motion vectors or the G-buffer, so the denoiser and upscaler guides
stay unchanged.

One coarse call per frame replaces the Java chain. The call is made only when
the pass is enabled and the mode uses lit output; debug views skip it. The
current `RtAtmosphere` settings/ordering and `RtFrameRenderer` release hook
remain the host-side integration until the graph takes over.

Completion integration:

* `FrameScheduler`'s release callback and `bedray_core_release_recording` must
  be one path, not two independent counters. A recording that used a mip
  generation is released exactly once; an unknown submission keeps the
  generation pinned until `device_idle` or `device_lost`.
* Destroying the core while a bloom recording is registered remains an
  `BEDRAY_ERROR_IN_USE` failure, exactly as for the other programs.
* A recording failure after the first bloom dispatch requires discarding the
  whole command buffer and then releasing it; the pass must not leave a
  half-recorded chain without a corresponding release path.

The same command buffer, queue and submission stay host-owned. The pass never
calls `vkQueueSubmit`, `vkDeviceWaitIdle` or `vkQueueWaitIdle`, including on a
resize path.

## 8. Dependencies to add to the native core

The core currently loads shader-module, descriptor, pipeline, push-constant and
dispatch entry points. A C++ bloom pass additionally needs, all through the
existing instance/device function pointers:

* `vkCreateImage`, `vkGetImageMemoryRequirements`, `vkBindImageMemory`,
  `vkDestroyImage`;
* `vkGetPhysicalDeviceMemoryProperties` (or the API 1.1 equivalent already
  available through the host's device);
* `vkCreateImageView`, `vkDestroyImageView`;
* `vkCreateSampler`, `vkDestroySampler`;
* `vkCmdPipelineBarrier` (core-like, not synchronization2; the host's command
  buffers do not guarantee that synchronization2 was enabled);
* `vkAllocateMemory`, `vkFreeMemory`.

Memory type selection is device-local, optimal tiling, with a dedicated
allocation per mip set for the first version. A pooled allocator can follow the
same recording-generation and completion rules later; it must not reuse memory
after a fixed frame count.

No new device, queue, swapchain or loader is involved. The scene module has a
private buffer/acceleration-structure allocator; it is not an image allocator
and is not shared. Reusing it would couple scene lifetime to post-processing
and is not recommended.

## 9. Missing contracts and decisions for the owner

1. **Image creation is outside ABI v2.** `bedray_core.h` says the core records
   dispatches and the host owns resources; it has no image allocator. C++-owned
   mips require either an internal allocator with a documented ownership
   exception, or the narrower host-owned-mip fallback in section 3. This is the
   main owner decision.
2. **No multi-dispatch pass in the ABI.** ABI v2 exposes one program, one
   descriptor set and one dispatch per `bedray_core_render_frame` call. Bloom is
   12 dispatches over four programs. A coarse `render_bloom` call (section 3) or
   an internal executor is required; no Java loop over dispatches is acceptable
   in the target architecture.
3. **Program delivery.** Either Java registers the four programs once through
   the existing registration call and passes the handles to the pass, or the
   native build embeds the generated SPIR-V and binding tables. The first reuses
   the working AOT pipeline and keeps the host coarse; the second removes the
   last Java registration step. Pick one before the ABI change.
4. **RenderPlan and render graph have no bloom/post pass kind.** `PlanPassKind`
   stops at `display`; `PassDesc` carries one dispatch, one push range and no
   program handle or push bytes. A bloom chain needs 12 graph nodes or a
   dedicated subgraph/pass with an internal 12-dispatch implementation. Dynamic
   extent (window resize) also changes dispatch sizes and mip resources, so a
   compiled graph and `FrameScheduler` binding become stale; rebinding is
   refused while frames are outstanding. These contracts must be designed
   before bloom is declared as a graph pass.
5. **External image boundaries.** The graph deliberately does not infer
   entry/exit layouts, external producers or queue-ownership transfers. Bloom
   enters with `rt_output`/`rt_depth` in `GENERAL` and leaves them in `GENERAL`.
   The producer stage of the final image, the consumer stage of the copy and
   the single queue family must be documented in the host contract; until then
   the conservative entry/exit barriers in section 5 are the only safe choice.
6. **Depth producer contract.** The path tracer writes a ray distance with a
   `1e6` miss sentinel; the legacy pipeline may produce a different convention.
   The apply shader's sky cutoff (`>= 1e5`) is valid only for the ray-distance
   contract. State the producer, units, sentinel and camera basis; do not infer
   linear view depth from `outDepth`.
7. **Camera and settings contract.** `invViewProj` is unjittered and
   camera-relative, `camera_origin` is the bob offset, `flip_y` describes the
   NDC orientation, and `tonemapped` selects the inverse tone curve. The sun NDC
   is produced by the host with the same flip. These are game-derived values and
   must be validated for finiteness and range at the boundary; NaN or infinite
   parameters are rejected, not dispatched.
8. **Sun visibility self-read.** The apply stage reads `finalImg` while writing
   it in the same dispatch. Define whether this is accepted behaviour or split
   the visibility read into a separate dispatch; the current source does not
   guarantee an ordering.
9. **Completion reporting.** The pass must map mip-generation references,
   descriptor pools and the core recording to one `release_recording` call. The
   `FrameScheduler` release callback, the core's `BEDRAY_ERROR_IN_USE` destroy
   rule and the unknown-submission rule already agree in principle; the
   integration must use them and not add a parallel frame counter.
10. **Validation gap.** There is no contract test, compile, GPU run or game run
    for a C++ bloom pass. The existing Java bloom path is the only implemented
    reference; this document records no fresh verification result for it, and it
    does not validate the C++ owner, the image allocator or the barriers
    described here.

## 10. Migration sequence

1. **Freeze the reference.** Keep `RtBloomPass`, `RtAtmosphere` and
   `RtFrameRenderer` untouched while ownership is coordinated. Record the
   current Java behaviour as the reference; do not extend the GLSL fallback.
2. **Native contract skeleton.** Add `bedray_post` with extent/mip-size
   calculation, generation reference counting and a fake-Vulkan barrier/
   dispatch plan test. No real images or GPU calls in this step.
3. **Allocator and samplers.** Add the core image/memory/sampler/barrier
   function table, lazy generation creation, retirement on
   `release_recording`, and tests for resize/retirement/OOM/device-loss paths
   with fake Vulkan functions.
4. **Program handles and coarse call.** Register the four AOT programs, add the
   internal pass and a proposed coarse host call, and wire it behind an
   opt-in switch with the Java path still default. No public ABI change until
   the owner approves the versioning.
5. **In-game switch.** After the user explicitly starts verification, enable
   the C++ pass for the lit/path mode, compare output against the reference,
   and fix only evidence-backed differences. Then remove Java mip/descriptor/
   pipeline ownership and the bloom GLSL fallback from the new path. Do not
   retain a silent GLSL fallback; report unsupported status instead.
6. **Graph integration.** Add the post/bloom pass kind, per-pass program and
   push contract, external boundary contract and scheduler release hook; replace
   the conservative entry/exit barriers with graph hazards once the graph owns
   the final image copy and display.

Exit criteria for each step: source review plus a relevant contract test or a
recorded verification result; a compile is not a game check, and a plan is not
a compile.

## 11. Non-goals

* No second Vulkan device, queue, swapchain, submission path or present path.
* No `vkDeviceWaitIdle` or queue idle as a lifetime mechanism.
* No Java-owned renderer or per-dispatch Java descriptor tables in the target.
* No new GLSL fallback as the target; the existing fallback is reference-only.
* No claim that the current source compiles, runs or renders on a GPU.
