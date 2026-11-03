# Temporal context ownership

`VelocityRecordingOwner` owns the shared Slang lease, velocity pipeline and the
exact recordings which also contain DLSS SR, XeSS or FSR SDK commands.
`WorldTargetManager` builds the frame inputs and borrows that pipeline. It no
longer maintains a second velocity completion ledger.

A vendor rejection discards the entire unsubmitted command buffer before it can
be ended or executed. Once execution was attempted, an exception leaves the
recording pending until exact completion or a successful host submit/fence.
Retirement cannot run from inside an open temporal recording. It also checks the
actual device captured when the SDK context was created.

`UpscalerReconfigure` chooses transitions; it supplies no completion proof.
`UpscalerContextRetirement` orders proof before checked SDK destruction, returning
an empty handle only when both succeed. Each runtime remains the sole context
owner. Frame age is no longer used to release vendor objects. SDK release
failure preserves the owning handle for retry, including cleanup from FAILED
and reconfiguration states. NGX stays initialized for its original device.

Diagnostics identify provider, operation stage, frame, context/feature handle,
extent, failed proof or SDK return and retained state. Original exceptions are
preserved; secondary cleanup failures are suppressed on them. Stable frames do
not emit a new ownership log.

No-GPU tests inject failed proof and SDK destruction, verify retry and empty
owner behavior, and guard rejection/submission ordering at the host boundary.
They do not validate vendor execution or visible output in a game.
