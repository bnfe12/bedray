# Native path-trace integration decisions

These decisions implement the conservative opaque-node option in
`native/bedray_render_graph/RENDER_GRAPH_NOTES.md`. They describe the opt-in
native path; L1/L2 checks do not establish GPU/game behavior or visual parity.

1. **Allocator ownership.** Existing native buffer/image owners allocate
   dedicated memory on the host device. One shared path pass owns its image
   generations and per-recording uploads/pins. A scheduler slot is a lease,
   not another allocator or a second copy of the images. Resizing retires the
   old generation until every referencing recording is explicitly released.
2. **Pass shape and resource identity.** `RecordingKind::opaque_group` wraps
   `PathTracePass::render`, including trace, optional two-checker spatial and
   display. It has no pretend single-dispatch shape/push block or graph-local
   bindings for its private images. Its real 18/6/9 descriptor and push contracts
   are validated by the existing program registry/pass before recording. The
   native scene flag selects the existing `path_trace_scene` contract. Output
   and depth views remain host-owned, GENERAL and retained through completion.
3. **Barrier authority.** The opaque pass owns private image transitions and
   entry/inter-dispatch/exit barriers. The graph orders the whole node and emits
   no additional barrier stream for it. Opaque nodes cannot declare private
   graph resource uses or push ranges. Ordinary dispatch-node behavior remains
   separate; a future cross-node image consumer needs an explicit boundary
   contract rather than pretending views are VkImage handles.
4. **History authority.** `PathTracePass` remains the sole authority for its
   accumulation parity, reset and history-depth images. Those same images are
   not registered with `HistoryRegistry`, and the scheduler supplies no second
   history validity/parity rule. The general registry and its completed-only
   producer gates remain available/tested for passes that actually adopt that
   protocol. No registry submission/timeline is fabricated for this path.
5. **Release callback and opaque boundary.** Existing ABI v2 coarse calls are
   sufficient: configure, render and release an opaque core/command-buffer
   recording. Internal scheduler tickets never cross the ABI. The host's exact
   generation callback proves completion or deliberate unsubmitted discard;
   `FrameScheduler::release_proven` releases that exact ticket. It does not
   derive a completion high-water mark from a frame number or invent a queue
   serial. The scheduler's callback calls `bedray_core_release_recording`; a
   guarded reentrant path releases descriptors and pass pins once. Release
   failures retain the ticket/slot for retry. Unknown outcomes stay pinned.
6. **Capabilities.** Core's actually enabled RQ/AS/BDA/Int64 mask is mapped
   explicitly to graph capability bits. A missing required capability is
   unsupported before recording. Algorithm availability is not inferred from
   a GPU name. Optional spatial reuse continues to be represented by an absent
   program handle, not an invented hardware feature.
7. **Slots and queue.** The native frame owner leases up to eight slots, matching
   the scene ABI maximum, with one shared pass and one host graphics queue
   family. Each command-buffer recording holds a unique ticket; reuse while
   outstanding or slot exhaustion returns IN_USE. No fixed frame-delay release,
   second queue, ownership transfer, submission or wait is introduced.
8. **ABI and compatibility.** ABI v2 structure layouts and function signatures
   stay unchanged. These are internal C++ policies, not ABI v3. Java native path
   remains opt-in until owner live validation. Depth remains unit-ray distance
   with the existing miss sentinel; exposure is applied once in display. Sky,
   G-buffer/denoiser and shader transport parity are separate shader-owner
   contracts; no new GLSL fallback or fake parity is introduced.

This connects the native path to an actual graph/scheduler call. It does not
claim that every other effect is a graph node, that arbitrary downstream image
consumers can use the opaque pass's private history, or that unimplemented
shader algorithms become available through graph wiring.
