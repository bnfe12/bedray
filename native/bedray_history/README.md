# Bedray temporal history ownership

Isolated C++ policy that decides, per temporal signal, whether last frame's data
may be read and which image may be written. It never allocates, frees,
transitions or synchronizes Vulkan objects, never submits, and never replaces
the shader-side reprojection/disocclusion checks. Pixel validation stays with
each method's Slang module.

**Status: L1, compiled and contract-tested.** The registry builds into
`bedray_core.dll`; `bedray_history_submission` exercises the C++ registry and
`bedray_history_c` drives all 22 exported C functions (2026-10-28). It is not
wired into the frame path, the exported C ABI has no Java caller yet, and no
GPU or game behaviour is claimed.

The full lifecycle, state transitions and cross-component contract are in
[HISTORY_CONTRACT.md](HISTORY_CONTRACT.md).

## Components

| Header | Responsibility |
|---|---|
| `history_signal.hpp` | `SignalId`: stable 64-bit id from a fixed name (`signal_id("bedray.restir_di")`). No method list lives here. |
| `history_state.hpp` | `HistoryKey`, `HistoryReset`, `HistoryResetPolicy`, `HistoryState`: is the committed history eligible for the next frame, and if not, why. |
| `history_timeline.hpp` | `QueueTimeline`: the one authoritative per-device queue state (reserved / completed serials, lost, retired). |
| `history_slots.hpp` | `HistorySlots`: 2–4 image ring for one signal; read/write selection and use recording (prepare / attach / cancel / finish). |
| `history_registry.hpp` | `HistoryRegistry`: signals, timeline, two-phase submissions, cross-signal aliasing, budget incl. retained images, retirement records, device lineage. |

`HistoryPair` was replaced by `HistorySlots`: a pair cannot express several
frames in flight or a write that must wait for unfinished GPU uses.

## Rules in one place

- Recording is not submission; submission is not GPU completion. Everything
  refusable is refused before `vkQueueSubmit` (`begin_submission`, `attach`);
  `end_submission` records the real outcome and cannot fail.
- Queue serials are registry-wide and strictly increasing per queue; signals
  share one serial through one submission ticket.
- A reset yields `read_slot = -1`; the committed slot is never written.
- An unknown submission outcome blocks reuse of the touched images until the
  host proves the device idle or loses it.
- Declared cross-signal and in-flight aliasing is rejected; undeclared aliasing
  cannot be detected.
- Images are destroyed only after the registry hands their record back
  (`pop_finished`, or `pop_orphaned` for a lost device).
- `configure` is transactional; the budget covers configured plus retained images.
- Until the render graph has a barrier adapter, use `HistoryOrdering::completed_only`.
