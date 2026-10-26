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

Current limitations:

- Minecraft 26.3's `VulkanCommandEncoder.queueForDestroy` is a candidate host
  completion bridge: bytecode shows `submit()` waits for a completed submission
  before rotating its destruction queue. `SlangRuntime.retireSubmittedRecording`
  exposes this route, but no game pass has wired it yet. It must be scheduled
  after the recording is added to the same encoder submission and before that
  submission is sent; unsubmitted discarded recordings use direct release.
  `VulkanCommandEncoder.destroy()` closes its submission builder, waits for its
  graphics queue, then drains the destruction queue. The inspected bytecode is
  from `libs/minecraft-26.3-client.jar`; other versions need separate review.
  Confirm error paths and encoder ownership during integration.
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
- This source has not been compiled or tested after the ABI v2 change; no GPU
  or live-game correctness claim is made.
