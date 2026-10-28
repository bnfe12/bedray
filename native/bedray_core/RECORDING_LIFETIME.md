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

`PathTracePass` (`src/path_trace_pass.cpp`) is the core's only internal recorder
above the single-dispatch calls. It keeps one recording per command buffer in
`recordings_` and pins six images and up to five buffers (four dummies plus the
per-recording FrameConstants upload) on it.

- `bedray_core_release_recording` is the completion release: the core drops its
  dispatch records and the pass completes its image/buffer pins in the same
  function. An unknown command buffer is a no-op. The additive
  `bedray_core_discard_recording` below invalidates history first and then uses
  the same release path; nothing else frees a recording.
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
- The ABI assumes calls are externally synchronized on the render thread.
- Program handles are monotonically assigned per core and are not native
  addresses. Destroying a program invalidates the handle for new dispatches;
  already-recorded work retains the actual program until release.
- `SlangRuntime.create` requires the host to supply the actual enabled feature
  mask. Physical device support is not proof that a logical device enabled a
  feature. The current host has not yet wired an authoritative enabled-feature
  mapping; never substitute a physical-support query for that fact.
- The ABI v2 sources build as the Windows x64 Release `bedray_core.dll` and the
  contract tests pass (`bedray_path_trace_pass_test`, 2026-10-28); no GPU or
  live-game correctness claim is made.
