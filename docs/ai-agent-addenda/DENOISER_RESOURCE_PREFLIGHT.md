# Denoiser and output resource preflight

The frame renderer replaces output images, NRD/RR guides/vendor handles and
growing entity-motion storage before allocating a new command buffer. If an
existing generation changes, the host first submits queued encoder work and
proves completion with its fence. Device idle alone does not submit queued work.

`DenoiserRecordingPins` retains the complete guide generation before first-use
transitions or primary writes. Preparation-only failures therefore count even
when the pack pass is never reached. Resize/destroy refuse pending recordings;
completion/discard releases a pin only after every child owner accepts its proof.
Whole-host drain remains an explicit caller obligation for bulk retirement.

The output image triplet is allocated transactionally. Allocation failure keeps
the old triplet and attempts all rollback actions, preserving cleanup errors on
the original exception. Denoiser ensures are allocation-only preflight calls;
initial transitions remain inside the captured recording.

Failure diagnostics identify preflight substage, denoiser profile, old/requested
extent, output view, guide generation and exact command buffer. Pending-generation
errors list command buffers still requiring proof. Original causes and suppressed
cleanup failures reach the existing game logger; unchanged successful frames gain
no recurring messages.

Focused tests cover ordering, preparation-only pins, independent recording
completion, rejected replacement and diagnostic context. Full integration results
are recorded in `VERIFICATION_LOG.md`. No GPU/visual validation is implied.
