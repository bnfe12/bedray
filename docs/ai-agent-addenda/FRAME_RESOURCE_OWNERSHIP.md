# Frame image and motion ownership

`RtFrameResources` owns the output/depth/composite image triplet, per-pixel
motion buffer, initialization state and exact recording pins. `RtFrameRenderer`
orchestrates preparation and borrows handles; it no longer allocates, swaps and
frees those resources in several separate methods.

Preflight runs before allocating the next command buffer. Replacement or
first-time motion allocation drains prior readers when necessary. The owner
also refuses replacement and close while any command is pinned. Its initializer
command is tracked separately: discard resets initialization, so the next
recording emits all required transitions again. A second command cannot consume
initialization whose completion has not been proven.

All three replacement images are allocated before old retirement starts.
Allocation failure preserves the old complete generation. Failed rollback
allocations stay in fixed owning slots and must be cleaned before another
replacement; cleanup errors remain suppressed on the original failure. Old
fields are cleared only after acknowledged destruction. No destroy call receives
null, including first frame, partial allocation and repeated close.

The renderer wraps preflight failures with stage, denoiser profile, previous and
requested extent, previous/current output view and replacement flags. Owner
rejections include generation, extent, initializer and pending command handles.
The existing frame failure boundary records the contextual cause. These are
error/transition diagnostics, with no new per-pixel or per-frame success logging.

Seven no-GPU failure-injection tests cover first allocation, repeated close,
partial allocation, rollback cleanup failure and retry, exact pins, abandoned
initial transitions, motion-only ownership and invalid extents. They exercise
the actual owner through a small allocator interface; they do not emulate a GPU.
