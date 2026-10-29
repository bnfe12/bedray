# Recording lifetime contract — ABI v2

The host owns command buffers, their submission, synchronization, and reset.
The native core records Bedray work and must not infer completion from a frame
number, ring size, command-buffer handle, or the return from recording.

For every successful `bedray_core_render_frame` call the core keeps one record
for the supplied command buffer. Each dispatch uses its own descriptor pool and
set so another dispatch cannot mutate its descriptors. Calls with no
descriptors still keep a reference to the pipeline and its program.

The host calls `bedray_core_release_recording` on the render thread only after
one of the following is true:

1. Every submission of this recording has completed according to the host's
   fence or equivalent synchronization, and the recording will never be
   resubmitted.
2. The recording was never submitted and the host has discarded or reset it.

Release removes all Bedray dispatch records for that command-buffer handle.
It must precede recording another generation of the same handle. Releasing an
unknown handle is a no-op. The handle alone is not completion evidence.

`bedray_core_destroy_program` stops new dispatches immediately, but defers
Vulkan object destruction while recordings reference the program. Core
destruction fails while any recording remains. The Java FFM wrapper therefore
keeps the native library loaded when the host closes too early. Both submitted
and discarded recordings must be released before device teardown.

ABI v2 keeps the v1 struct sizes but adds the release function and changes
`bedray_core_destroy` to return a status; an ABI v1 native library must not be
loaded by the v2 Java wrapper. There is no inferred compatibility from equal
structure sizes.

## Path-trace pass recordings (internal pass, ABI v2 coarse calls)

`PathTracePass` (`src/path_trace_pass.cpp`) is the native path recorder
above the single-dispatch calls. It keeps one recording per command buffer in
`recordings_` and pins six private images plus the selected buffer uses (dummy
buffers, frame upload and an active native DI ring). Current 25-binding trace
programs additionally pin three one-texel guide stand-ins of R32F, RGBA16F and
RGBA8 when guide writes are disabled. They satisfy inactive bindings only.
Their transitions are recorded before use and invalidated on discard; retirement
still follows exact recording proof. Legacy 18-binding programs are accepted
independently for both scene-table variants.

The additive `bedray_core_render_path_trace_guides` call accepts the separate
64-byte `BedrayPathTraceGuides` descriptor, guarded by ABI version/size and the
GUIDE_OUTPUTS feature. All seven non-zero host views and the G-buffer option are
required together. Those views are borrowed GENERAL images at the frame extent;
their host owner must retain them until the same exact completion/discard proof
as output/depth. The pass binds them directly in immutable dispatch descriptors,
does not pin/transition the inactive stand-ins, and never destroys host images.
The same opaque graph callback consumes both input PODs synchronously; it retains
only the existing command-buffer recording. History and barrier authority remain
in the pass. The original call still rejects enabled writes without real targets.

- `bedray_core_release_recording` remains the only release call: the core drops
  its dispatch records and the pass completes its image/buffer pins in the same
  function. An unknown command buffer is a no-op.
- A failure before the first dispatch was recorded — image creation, the
  FrameConstants upload, a pin failure — is provably discardable: the pass
  completes its own pins and leaves no recording.
- A failure returned by `bedray_core_render_frame`/`_render_phases` (trace,
  spatial phases or display) may already have recorded work, so the pass keeps
  the recording and its pins. The host must discard the whole command buffer,
  then call `bedray_core_release_recording`; there is no partial release.
- `bedray_core_destroy_path_trace`, `bedray_core_configure_path_trace` and
  `bedray_core_destroy` refuse while a recording exists (the last through the
  pass's own `BEDRAY_ERROR_IN_USE`).
- An extent change retires the previous image set but does not destroy it while
  a recording pins it; `release_recording` collects the retired images. No
  `vkDeviceWaitIdle` is used and no frame count recycles a recording.
- On `VK_ERROR_DEVICE_LOST` the pass marks both owners lost; destroying it
  routes them through `release_after_device_loss`, which destroys the
  still-owned objects and clears the bookkeeping without claiming the device
  works.

Covered by `native/bedray_core/tests/path_trace_pass_test.cpp` (built and
passing, 2026-10-28).

## Bloom recordings (additive coarse calls)

`BloomPass` owns the complete existing prefilter/down/up/apply sequence, its six
RGBA16F mip images, transitions and entry/compute/exit barriers. Java supplies
the four registered AOT program handles, borrowed core samplers, host output/
depth views and unchanged 128-byte push payload. Each program requires the
existing binding contract; the Java adapter additionally checks 8x8x1 reflection.
The separate `bedray_bloom.h` descriptors carry ABI version and structure size;
existing ABI v2 layouts are unchanged. Matching bloom exports are mandatory.

One call records twelve dispatches for bloom or one apply dispatch for glare
only. Every dispatch still uses the core's per-dispatch descriptors. A recording
pins its exact mip generation before the first command. Partial recording failure
retains those pins for deliberate discard. Resize retires old images until all
readers release them. Discard of the current generation forces fresh images
before the next recording, since its layout transitions may not have executed;
discard of an older generation cannot invalidate a newer one.

The existing core completion/discard calls release bloom uses alongside other
passes. Close/reconfigure refuse outstanding recordings. Device loss stops new
recordings and retains the entire lost pool until all affected command buffers
receive host proof, then releases that pool together. No individual lost-owner
use operation is assumed to remain available. No frame delay, submission, wait,
new device or destruction of borrowed host views is introduced. Tests use a
shared fake driver and provide no GPU/game evidence.

Current limitations:

The host distinguishes successful completion from abandonment. The additive
`bedray_core_discard_recording` invalidates private image layout/history and DI
ring history/parity before using the same exact scheduler release path. A late
or unknown command handle cannot invalidate a newer pass recording. Host drain
with UNKNOWN execution conservatively uses abandonment cleanup; a completed
callback uses completion cleanup. Each ticket remembers its chosen disposition
and successful owner actions across retries. Native scene abandonment poisons
scene reuse until recreation, because committed uploads/AS builds may not have
executed. It does not free other outstanding frames.

- Minecraft 26.3's `VulkanCommandEncoder.queueForDestroy` is the host
  completion bridge: bytecode shows `submit()` waits for a completed submission
  before rotating its destruction queue. `RtFrameRenderer` now attaches one
  generation-specific `RecordingCompletion` callback after `execute`, covering
  atmosphere, path pass, Sodium decode, scene frames and the captured particle
  composite owner. An `execute`/enqueue failure retains an unknown recording;
  a deliberate never-executed discard can release immediately. It is scheduled
  after the recording is added to the same encoder submission and before that
  submission is sent; unsubmitted discarded recordings use direct release.
  `VulkanDevice.createCommandEncoder()` returns the shared encoder. Its fence
  snapshots the current submission index, and `awaitCompletion` waits for that
  index. Host drains now submit and await this fence, retaining resources on a
  five-second timeout instead of using `vkDeviceWaitIdle`. The inspected client
  jar is 26.3, SHA-256
  `4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d`;
  other versions need separate review. This is bytecode and no-GPU test evidence,
  not a live completion/driver validation.
- Per-dispatch pool allocation favours correctness and isolation. A future
  pooled allocator must retain the same completion and recording-generation
  semantics; do not reuse individual sets merely after a fixed frame count.
- External images, buffers, and acceleration structures remain owned by the
  host/scene. Their owners must retain them until the same completion event.
- Host entity capture follows that same event. `EntityCaptureBuffers` leases
  exclusive writable geometry slices and pins shared immutable atlas generations
  through `RecordingResourceOwner`. Slot exhaustion creates another generation;
  neither the three-slot capacity nor a frame delay permits reuse or destruction.
  `RtWorld.releaseRecording`/`discardRecording` release capture leases through the
  generation-specific `RecordingCompletion` action. An upload failure leaves its
  ticket pending until discard. Partial allocation/destruction keeps retryable
  ownership; close refuses outstanding tickets. Public upload overloads without a
  command buffer reject GPU work and retain only their null-memory compatibility
  behavior. These resources are still host capture storage borrowed by the native
  scene/pass, rather than a second scene or native history owner.
- Published emitter lists use `EmitterCaptureBuffers` and the same proof owner.
  A publication copies into fresh storage sized for the selected records; frames
  that share a list pin that immutable generation. An empty list allocates no GPU
  buffer. The frame's host ticket captures the exact RtEmitters instance, and
  retains it before either backend can read the list. Later publication cannot
  overwrite it, even with more than three outstanding readers/publications.
  Destruction refuses pending readers; late host callbacks cannot release a
  newer recording that reused the command-buffer address. The record layout,
  CPU selection/fingerprint rules and shader light sampling are unchanged.
- The ABI assumes calls are externally synchronized on the render thread.
- Program handles are monotonically assigned per core and are not native
  addresses. Destroying a program invalidates the handle for new dispatches;
  already-recorded work retains the actual program until release.
- `SlangRuntime.create` requires the host to supply the actual enabled feature
  mask. `SlangDeviceCapabilities.enabledMask` maps the game's enabled feature set
  through its mixin accessor and returns zero if unreadable. Physical device
  support is not proof that a logical device enabled a feature; never substitute
  a physical-support query for that fact. This mapping still needs live validation.
- The ABI v2 sources build as the Windows x64 Release `bedray_core.dll` and the
  contract tests pass (`bedray_path_trace_pass_test`, 2026-10-28); no GPU or
  live-game correctness claim is made.
