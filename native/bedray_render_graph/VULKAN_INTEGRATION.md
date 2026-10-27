# Vulkan recording adapter integration contract

Status: source candidate. The core CMake build includes the graph and its
Vulkan adapter by default, and the adapter plus its fake-command-buffer
contract test build and pass at L1. `core.cpp` still does not construct or
execute the adapter, so this is not evidence of a rendered frame and no GPU or
driver validation has been performed. A standalone graph build enables the
Vulkan adapter explicitly and supplies Vulkan headers.

The Minecraft host owns the Vulkan device, command buffer, queue, submission,
and presentation. The adapter receives the existing `vkCmdPipelineBarrier`
function pointer and records into the host's command buffer; it never creates
a device or submits work.

Before recording, the future renderer must:

1. Build the graph from enabled passes and compile it with the capabilities
   actually enabled on this device. Each declared write that initializes a
   later read must really cover the entire resource used by that read.
2. Verify the reflected descriptors against each pass and bind actual resources
   with the correct formats, usages, ranges and device generation. All graph
   resources must remain alive until host-proven GPU completion. Physical memory
   aliasing is forbidden even when allocation identity is unknown to the graph.
3. Supply each colour image, its whole used subresource range and the actual
   layout established by the host before the graph. Each image must be owned by
   the host queue family. Make earlier cross-queue writes available using the
   required semaphores and ownership transfer before the graph begins. An
   initial undefined layout discards contents and is allowed only for a first
   use declared write.
4. Call `prepare_vulkan_recording`, then record its passes in the same order
   through `record_vulkan_graph`. The recording callback must not submit, enter
   a render pass, mutate the graph or change tracked image layouts. Any error
   after recording begins requires discarding the entire command buffer and
   cancelling associated, not-yet-submitted history reservations.
5. Once recording succeeds, use `final_images` as the tracked layouts for
   subsequent transitions. Establish external consumer dependencies, including
   ownership transfer and semaphores when necessary. A recorded barrier is not
   proof of submission or GPU completion.

`record_core_dispatches` is an optional C++ bridge for graphs whose every pass
is one existing `BedrayFrameDesc` compute dispatch. It requires exactly one
frame descriptor per pass and the same host command buffer throughout, then
calls a supplied `bedray_core_render_frame` function pointer from the graph
callback. The frame descriptor and pointed-to arrays must remain valid through
the call. It preserves the failing pass and core error for the caller. If any
dispatch fails after recording began, the caller must discard the whole
recording and follow the core's `release_recording` lifecycle; the bridge does
not roll back GPU commands. It does not build frame descriptors, allocate
scene images, validate shader semantics, or select the graph. It is not yet
called by `bedray_core_render_frame` or a Minecraft frame hook.

The adapter emits broad `ALL_COMMANDS` memory barriers before each pass. It is
not tuned and does not claim an optimized synchronization schedule. In
particular, it does not consume `HistoryRegistry` tickets or queue serials.
`completed_only` remains the only history write-selection policy supported by
the integrated design until actual cross-submission dependencies and host
submission outcomes are connected and reviewed. A history read still needs an
actual dependency on its producer and a correct layout.

`HistorySubmission` is a CPU-side source candidate for the ticket boundary.
After `begin` and successful `attach`, the owner marks the submission attempt
immediately before calling the host's queue submit, then calls `finish` with
the known outcome. If a scope exits with an open ticket, the guard always
records unknown, even if the caller forgot to mark a submission attempt. Use
explicit `finish(rejected)` only when the host proves nothing was submitted.
Recordings that were prepared but never attached remain the caller's
responsibility to cancel. A failed attach before submission requires rejecting
the shared ticket and resolving every unattached recording. The guard cannot
observe a Vulkan call, prove a queue outcome, establish a semaphore or barrier,
or recover if its registry is destroyed first. It is not yet called by the
frame path; its submission semantics are covered by the L1 contract test. Its
completion helper checks only
the history producer serial, not memory visibility or image layout.
