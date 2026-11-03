# Temporal history review — ownership and submission safety

Source review of `native/bedray_history` at 2026-10-26, not a build or a GPU
validation. The module remains owned by its author. Do not run tests, build,
slangc or Minecraft until the user authorizes verification. Preserve the dirty
worktree and do not modify the shared ABI/render graph while correcting this.

## Findings requiring correction before integration

1. **Cross-signal aliasing is not checked.** `set_valid()` in
   `history_slots.cpp` compares images only within one `HistoryImageSet`.
   `HistoryRegistry::configure` does not compare a new set against images in
   other active signals or the still-in-flight retired sets. Thus two signals
   can register the same `VkImage`, or overlapping ranges of the same memory,
   while each signal independently considers its slots safe. Detect/reject
   declared cross-signal overlap transactionally. Design an explicit owner
   contract or retained retirement records for in-flight old images; do not
   claim unseen external/sparse aliasing can be detected.
2. **Post-submit error can lose GPU-use accounting.** `HistorySlots::submitted`
   is documented as called after the host has accepted submission, but rejects
   zero/non-increasing/completed serials while retaining the pending token.
   If submission actually happened, it is no longer safe to cancel and forget
   that use. Separate a pre-submit serial reservation/validation step from the
   post-acceptance transition, or introduce a fail-closed unknown-use state that
   prevents recycling/retirement until the host proves safety. Document the
   host ordering and error cases; never pretend an accepted submission was
   cancelled.
3. **Queue serials are not truly registry-wide.** New `HistorySlots` begin with
   zero `completed_`/`submitted_`, while `complete` only broadcasts to signals
   currently registered. A signal added after a prior completion can accept an
   already-completed/old serial. Conversely, two signals can independently
   accept serials in the wrong global order. Store and validate the authoritative
   per-device/per-queue state at the registry or a host-owned timeline boundary;
   handle multiple signals legitimately sharing one submission serial.
4. **Device-loss recovery is per signal, not atomic.** After `device_lost()`,
   each `configure` independently clears its local lost flag and zeroes serials;
   other signals can still be lost while `complete()` mutates configured ones
   and returns false. A new configuration is not evidence of a new logical
   device. Require a registry-level new device generation and coordinated queue
   epoch; preserve old-device retirement information until its ownership is
   resolved. Do not describe destroying live child handles after the parent
   VkDevice is destroyed as safe: ownership and device-loss teardown must be
   agreed with the Vulkan owner.
5. **Budget result is misleading on reconfiguration.** On `over_budget`, the
   old configuration remains in `entry->slots`, yet `detail` is set to
   `not_configured` and the documentation says the method has no history.
   Choose and document one atomic policy (prefer preserving the existing
   configuration on failure), and report the actual state. Include retired but
   not-yet-freed memory in an owner-visible total if `budget_bytes` is meant to
   bound actual resident memory; a separate host allocator budget is not the same
   guarantee.
6. **Token exhaustion and hash identity.** `next_token_++` can wrap to zero or
   reuse a stale token. Fail closed at exhaustion, or use an epoch that cannot
   collide with an outstanding/stale token. FNV hash equality rejects duplicate
   IDs, but without preserving the original name it cannot distinguish a hash
   collision from a duplicate registration; describe this accurately.

## Render graph / core handoff

`native/bedray_render_graph` is currently a source-only single-queue planner.
Its hazards are ordering constraints, not ready-to-emit Vulkan barriers; graph
entry/exit accesses must be connected to the actual host resources and states.
It does not implement multi-queue waits, ownership transfers, history slots or
completion. Therefore `same_queue_barrier` and `cross_queue_semaphore` cannot
yet be asserted by this graph. Until their adapter exists, integration must use
only `completed_only` and still provide a producer dependency before reading.

Please update `HISTORY_CONTRACT.md` and list any API changes needed by the core
owner. Include source-level timelines for aliasing, a submission followed by
an invalid serial, a signal added after a completed queue serial, device loss
with multiple signals, and failed budgeted reconfiguration. Do not run tests;
report the corrections as source-only.
