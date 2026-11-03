# Temporal history contract — Bedray alpha 0.2.0

Contract for `native/bedray_history`. The module is compiled into
`bedray_core.dll` and both layers are contract-tested (L1, 2026-10-28): the
C++ registry by `bedray_history_submission`, the exported C surface by
`bedray_history_c`. It is not wired into the frame path and no GPU run has
validated it. Where this module needs something another component does not
provide yet, section 7 says so instead of assuming it.

## 1. Components

| Header | Responsibility |
|---|---|
| `history_signal.hpp` | `SignalId` = FNV-1a 64 of a fixed, versioned name owned by the method. |
| `history_state.hpp` | `HistoryKey`, reset reasons, per-signal `HistoryResetPolicy`, `HistoryState`. |
| `history_timeline.hpp` | `QueueTimeline` (device generation, per-queue reserved/completed serials, lost/retired), `QueueUses`. |
| `history_slots.hpp` | `HistorySlots`: 2–4 image ring of ONE signal; read/write choice and use recording. No serials of its own. |
| `history_registry.hpp` | `HistoryRegistry`: signals, the single authoritative timeline, submissions, cross-signal aliasing, budget, retained retirement records, device lineage. |

Only the registry is meant to be called by the host. `HistorySlots` is its building block.

## 2. Signals and identity

- `add(name, policy, id)` derives `id = signal_id(name)` and stores the name.
  The same name a second time returns `duplicate_signal`. A different name with
  the same id returns `hash_collision`, so the method must rename. Hash
  equality alone is never taken as identity.
- Each signal has its own images, uses, committed key and reset policy.
  Shared are only facts true for every signal: the queue timeline and the
  device lineage.
- A new method registers new names. No existing code changes.

## 3. Keys and resets

These are unchanged from the previous revision. `HistoryKey` holds the device,
target, pipeline, scene and signal generations, the camera epoch, and the
signal's own extent. `kMandatoryResets` always apply. `sensitive_to` (target,
scene, camera, discontinuity) may be narrowed only by an owner who can prove
the change is irrelevant. `prepare` additionally requires
`key.device_generation == timeline.device_generation`.

## 4. Submission protocol

```
prepare(signal...)            per signal; picks read/write slots; nothing recorded
begin_submission(q, serial)   registry-wide: serial > every reserved serial on q
attach(ticket, signal, token, reset_applied)
                              per signal in this submission; refuses a recording that
                              did not honour a reported reset (reset_not_applied)
──── vkQueueSubmit by the host ────
end_submission(ticket, accepted | rejected | unknown)
                              cannot fail for the open ticket
complete(q, serial)           host-proven completion; <= last submitted serial, never regresses
```

- Several signals share one submission serial through one ticket.
- One ticket may be open at a time. All calls are externally synchronized.
- `cancel` works only for an unattached recording. Once attached, the
  recording's fate is the submission's outcome.
- **accepted**: uses are recorded on `(q, serial)`, then history advances.
  If `invalidate()` raced in, the use is still recorded and history restarts.
- **rejected**: the queue provably took nothing. No use is recorded, and the
  reserved serial is released (`submitted[q]` rolls back).
- **unknown**: the uses are recorded and flagged `unknown`. Such a slot can't be
  written or handed back as finished until `device_idle()` proves the whole
  device idle, or the device is lost. History is invalidated. Unrecognised
  outcome values are treated as unknown.
- Recording tokens are issued by the registry, are unique across all signals,
  and are never reused: not after `remove` + `add` of the same name, and not
  across device generations. A stale `(SignalId, token)` cannot match a later
  recording. A token is consumed only by a successful `prepare`. Tokens and
  ticket ids never wrap; at 2^64 they fail closed with `token_exhausted` or
  `tickets_exhausted`.

### Invalidation versus a recording in flight

A reset event (data became wrong) can arrive between `prepare` and
`vkQueueSubmit`. The recording prepared earlier may already read the old image
with `reset == none`. Two calls exist, and the caller chooses by meaning:

| Call | While a recording is pending or attached | Guarantee |
|---|---|---|
| `invalidate(id)` | refused (`see_detail`: `recording_pending` / `recording_attached`) | no recording submitted after a successful call reads the old history |
| `invalidate_after_pending(id)` | accepted | the pending recording may still read the old history and be submitted; its output is never committed; later frames reset |

Required caller action when `invalidate` is refused:

1. **Prepared, not attached**: `cancel(id, token)`, then `invalidate`, then
   `prepare` again. The new recording reports the reset.
2. **Attached, `vkQueueSubmit` not called yet**: do not submit. Call
   `end_submission(ticket, rejected)` (every signal in that ticket is rejected
   and must be prepared again), then `invalidate`, then prepare and submit.
3. **Already submitted**: the GPU work exists. Call
   `end_submission(ticket, accepted)` (or `unknown`). Accounting is kept and the
   old read has happened. Then `invalidate` affects only the next frame.
   Whether that frame's output is shown is the pass owner's decision. History
   cannot undo GPU work.

The deferred reset of `invalidate_after_pending` survives however the pending
recording ends:

| End of the recording | Uses recorded | History |
|---|---|---|
| `cancel` | none | reset applied |
| `end_submission(rejected)` | none | reset applied |
| `end_submission(unknown)` | yes, flagged unknown | reset (as for any unknown) |
| `end_submission(accepted)` | yes | output not committed, reset applied |
| `device_lost` | images orphaned | reset (`device_lost`) |

The next `prepare` therefore always reports `requested` and never offers the
old image for reading.

`invalidate_after_pending` is for events where reading the old data one more
frame is harmless, for example a budget-driven disable. Never use it when the
old data is wrong for the current frame.

## 5. Images, aliasing and retirement

- Image identity is the underlying `VkImage` plus `(memory, offset, size)`.
  `configure` rejects a set (`aliased_images`) when any image equals, or overlaps
  in declared memory, one of the following:
  - an image of any signal, including this signal's current set, which is about
    to become an in-flight record;
  - any retained record, whether finished-not-yet-popped or orphaned.
- Undeclared aliasing can't be detected: sparse binding, external memory, or
  an allocator reusing ranges without saying so. That is the Vulkan owner's
  responsibility.
- Replaced or removed images become **retained records** with their uses. The
  host destroys an image only after:
  - `pop_finished` hands its record back, meaning every use is proven complete
    on the current device; or
  - `pop_orphaned` hands back a record of a lost device. No completion is
    known; the Vulkan owner destroys these as part of its agreed device-loss
    teardown, before the parent `VkDevice`. This module does not claim any
    earlier point is safe.
- Write-slot choice: the committed slot is never written. A slot qualifies if
  all its uses are finished, or if its unfinished uses are allowed by
  `HistoryOrdering`. Unknown uses never qualify. Otherwise the result is
  `write_unavailable`.
- **The current render graph** (`native/bedray_render_graph`, compiled and
  contract-tested but not wired, single queue, ordering constraints rather than
  emitted barriers) can't
  honour `same_queue_barrier` or `cross_queue_semaphore`. Until a barrier
  adapter exists, integration must use `completed_only`, and still give the read
  a dependency on `(read_producer_queue, read_producer_serial)`.

## 6. Budget and device lineage

- `budget_bytes` bounds `configured_bytes() + retained_bytes()`. That is every
  history image the host may still hold. The host's allocator budget is a
  separate guarantee.
- `configure` is transactional. On `invalid_images`, `aliased_images`,
  `over_budget`, `out_of_memory`, `signal_busy` or `device_unavailable`, the
  signal keeps exactly its previous configuration and history. Because the old
  set stays retained after a successful reconfiguration, the budget must have
  room for old and new together.
- The device lineage is registry-wide:
  - `begin_device(gen)` requires no active device, `gen` greater than every
    earlier generation, and no retained record left.
  - `device_lost()` closes an open ticket as unknown. Every signal's images
    become orphaned records, and existing records become orphaned too. The
    timeline is marked lost, and `prepare`, `configure`, `begin_submission`,
    `complete` and `pop_finished` are refused.
  - The orderly path is `device_idle()` (host proof of idleness) →
    `retire_device()` → `pop_finished` until empty → `begin_device(next)`.

## 7. Timelines

**Cross-signal aliasing**
1. `configure(A, {img1, img2})` → ok.
2. `configure(B, {img2', img3})`, where img2' is a view of img2 declared with
   img2's image id → `aliased_images`. B stays unconfigured.
3. `configure(B, {img3, img4})`, where img4 shares memory M with an overlapping
   range of img1 → `aliased_images`.
4. `configure(A, {img5, img6})` → ok. img1 and img2 are retained.
   `configure(B, {img1, img7})` → `aliased_images` until img1's record is
   popped and the host destroys img1.

**Submission followed by an invalid serial**
1. `prepare(A)` → token t. `begin_submission(q0, 10)` → ticket. `attach(.., A, t)` → ok.
2. The host submits. `end_submission(ticket, accepted)`: A's slots record q0:10
   and history advances.
3. Next frame: `begin_submission(q0, 10)` or `(q0, 9)` → `invalid_serial`.
   This is refused before any vkQueueSubmit, so nothing was submitted and
   nothing needs undoing. The host fixes its serial or cancels the unattached
   recordings.
4. If the host already submitted under a bad serial, the protocol was violated
   on the host side (`begin_submission` must precede the submit). The safe
   reaction is `device_idle()` after a real idle wait, or `device_lost()`.

**Remove and re-add the same name**
1. `add("bedray.ssr")` → id S. `prepare(S)` → token 41. `cancel(S, 41)`.
   `remove(S)`.
2. `add("bedray.ssr")` → the same id S, new entry. `prepare(S)` → token 42 or
   higher, because the counter is registry-wide.
3. A stale `cancel(S, 41)` or `attach(.., S, 41)` → `unknown_token`. The new
   recording is untouched. The same holds across `begin_device`.

**Reset between prepare and submit**
1. `prepare(A)` → token t, read = slot 0, reset = none.
   `begin_submission` → ticket, then `attach(A, t)`.
2. The camera owner reports that A's data is wrong. `invalidate(A)` →
   `see_detail / recording_attached`.
3. The host has not submitted, so `end_submission(ticket, rejected)` and then
   `invalidate(A)` → ok. The next `prepare(A)` reports `requested`, with no read.

**Signal added after a completed serial**
1. Signal A submits q0:1..50. The host calls `complete(q0, 50)`.
2. `add("bedray.rc.cascade1")`, then `configure` and `prepare`. The new signal
   reads the registry timeline (`submitted[q0] = 50`, `completed[q0] = 50`).
3. `begin_submission(q0, 50)` → `invalid_serial`. `begin_submission(q0, 51)` → ok.
   A and the new signal can attach to the same ticket.

**Device loss with several signals**
1. A, B and C are configured. A ticket on q0:77 is open with A and B attached.
2. `device_lost()`:
   - A and B finish as unknown on q0:77.
   - The images of A, B and C become three orphaned records, and earlier
     retained records are marked orphaned.
   - The timeline is lost.
3. `complete(...)` and `configure(...)` → `device_unavailable`.
   `begin_device(gen + 1)` → `device_unresolved` while records remain.
4. The Vulkan owner pops every orphaned record during its device-loss teardown,
   then calls `begin_device(gen + 1)`. Serials restart, and every signal must be
   configured again.

**Failed budgeted reconfiguration**
1. With a 64 MiB budget, A has 2 × 16 MiB configured and committed history.
2. `configure(A, 2 × 24 MiB)`: resident 32 + wanted 48 > 64 → `over_budget`.
   A still has its 2 × 16 MiB set, its uses and its history. The next `prepare`
   behaves exactly as before.
3. After the host pops earlier records, `retained_bytes` drops. The same call
   still fails, because A's current 32 MiB stays retained after a swap. The method
   must shrink the request or release A first.

## 8. What each method must supply (not owned here)

- **All temporal passes**: previous and current view-projection, camera
  origin in one rebased frame, jitter of both frames, extent, normal (space
  stated) and linear depth (convention stated), material/geometry identity or
  revision, and object motion.
- **Accumulation / à-trous**: the depth convention behind `HistoryHeader` is
  unconfirmed. The Slang source assumes `clip.w`.
- **Radiance Cascades**: the world-probe mapping, the clipmap scroll per frame
  (a scroll invalidates the scrolled band and is not a reset), and the direction
  basis under rotation. A layout change bumps `signal_revision`.
- **ReSTIR DI/PT**: stable light identity, and invalidation of reservoirs that
  reference changed lights. A light-list rebuild bumps `signal_revision`.

This module does not reproject pixels or invent motion vectors, and it does
not treat a wrapped world position as unique.

## 9. Needed from other owners

**ABI / core owner**
- C entry points mirroring the registry: device lineage, add/remove/configure,
  prepare/cancel, begin_submission/attach/end_submission, complete, and
  pop_finished/pop_orphaned. They return `RegistryStatus` + `HistoryStatus`
  with no exceptions across the boundary.
- A host-assigned per-queue serial that exists **before** `vkQueueSubmit`
  (for example a timeline value) and is reported in `begin_submission`.
- A mapping from queue to index, at most `kMaxHistoryQueues`.
- Three distinct notifications: device loss, a proven idle device, and a new
  device generation.

**Render graph / host (separate obligation, not provided by this module)**
- `completed_only` only limits which slot is chosen for writing. Before the
  latest image is read, the graph or host must still provide the memory
  dependency and the layout transition after `(read_producer_queue,
  read_producer_serial)`. It must also keep every image alive until actual
  completion.
- `unknown` outcomes require the global-idle or device-loss treatment above.
- None of this is integrated into the renderer or the C ABI yet.

**Render graph owner**
- An adapter from `write_after` / `read_producer` to real barriers and semaphore
  waits. Until then, only `completed_only`.
- Calling `cancel` for every recording that is not attached, and
  `end_submission` with the real outcome for every ticket.

**Camera / scene owners**
- One camera contract: projection, jitter, origin rebasing, and linear depth.
- A per-signal `scene_generation`, material identity, and object motion.

**Vulkan owner**
- A device-loss teardown order that destroys orphaned history images before
  `vkDestroyDevice`.

## 10. Known limitations

- Compiled into `bedray_core.dll`; the C++ registry is contract-tested by
  `bedray_history_submission` and every exported C function by
  `bedray_history_c`. No GPU run has checked it.
- Single-threaded, with one open submission ticket at a time.
- Linear searches over signals and records. No performance claim.
- `add` and `configure` allocate. `std::bad_alloc` becomes `out_of_memory` with
  no state change. The other operations do not allocate.
- Undeclared memory aliasing is undetectable.
