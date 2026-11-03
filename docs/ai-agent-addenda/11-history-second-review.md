# Temporal history: second source review

The registry-wide serials, explicit submission outcome, retained-image records
and device-wide loss transition address the first review's principal design
gaps. This is a source review only: no compilation, tests, GPU or game run.

## Reset after prepare can still submit an obsolete read

`HistoryRegistry::invalidate` immediately invalidates the state even when a
recording is pending. A recording prepared earlier may already contain a read
of the old image with `reset == none`. `HistorySlots::attach` only checks the
reset captured by `prepare`, so it accepts that recording even if the signal
was invalidated before attachment. `finish` detects the changed state only
after submission; preventing the resulting commit does not undo the GPU read
or its output. An invalidation after attachment but before `vkQueueSubmit` has
the same issue. Define and enforce a protocol that prevents submission of a
recording invalidated since preparation: for example, fail invalidation while
the signal has a pending recording and require the owner to cancel or reject
the open ticket before invalidation. Address the interval after attachment too;
do not silently discard usage accounting for an already accepted submission.
Document which caller action is required if the reset event occurs in this
window, and distinguish invalidation for future frames from invalidation that
must affect a not-yet-submitted recording.

## Tokens may repeat after signal removal

`HistorySlots::next_token_` is local to an entry. Removing a signal and
registering the same name again constructs a new entry with token 1. An old
`(SignalId, token)` can therefore match a later pending recording of the same
name, and an old `cancel` or `attach` can operate on the new recording. Use a
registry-wide non-reused token or an incarnation/generation included in the
handle. Preserve fail-closed exhaustion and cover remove/re-add as well as
device changes in the contract.

## Host integration remains a separate obligation

`completed_only` limits write-slot selection. It does not by itself provide
the memory dependency or layout transition required to read the latest image;
the render graph/host must establish those and retain resources through actual
completion. `unknown` submission results require the documented global-idle
or device-loss treatment. Nothing here is integrated into the renderer or C ABI.

Respect the user's standing instruction to defer builds and tests until the
full migration. Do not modify or reset other agents' work.
