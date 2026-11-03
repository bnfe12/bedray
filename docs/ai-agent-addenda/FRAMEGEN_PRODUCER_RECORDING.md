# Frame-generation producer recording

The shared DLSS-G / FSR 3 input preparation uses the AOT `fg_prepare`
Slang entry and `bedray_core_render_fg_prepare`. The pass preserves the
existing HUD-less nearest blit, depth row flip and motion-vector Y sign.
Java retains the vendor contexts and output images; C++ records the producer
commands and owns immutable dispatch descriptors. No native submission or
wait is introduced.

`FrameGenProducerRecordings` captures the exact `FrameGenGpu` owner before
initial image transitions or vendor prepare commands. A successful host
completion callback releases that recording. A body/end failure permanently
discards the never-executed recording and restores first-use initialization.
An execute/enqueue exception retains the owner until a host submission and
fence prove that queued work has ended. Old callbacks identify their ticket
and cannot release a newer recording at a reused command-buffer address.

`FrameGenHostCommands` contains the host encoder/fence adapter. Both FG
providers use it. World-target replacement drains all existing producer
owners, including failed or disabled providers, before replacing borrowed
color, depth or motion-vector inputs. Failed completion waits retain the
images. Output replacement and teardown drain producers before the existing
vendor-consumer retirement boundary. FSR 3 disables generation, waits for
presents and destroys the old context before output replacement; SDK errors
retain the context and its images. Streamline disables generation and clears
resource tags before the existing device-idle output replacement/close.

A graphics-producer proof does not prove asynchronous vendor consumption.
The three output slots and the vendor present/tag lifecycle remain host
owned. This change does not claim a new per-slot vendor-consumption proof or
GPU/visual validation.

Vendor failure quarantine also turns interpolation off. Streamline tag ownership
starts before the first tag assignment, so partial registration and failed clears
remain retryable. An unacknowledged options call keeps native mode state unknown;
teardown must obtain an acknowledged OFF before freeing outputs. FSR 3 checks the
menu/no-world disable result as well as teardown results. Cleanup attempts every
required action and preserves the initiating error plus suppressed cleanup errors.

The canonical 104-byte layout and export binding live in `BedrayCoreLayouts` and
`BedrayCoreNative`. `BedrayFgPrepareNative` validates and builds that descriptor;
it does not create a second native binding or an independent layout.

Failure logs identify provider, frame/slot, extents, mode state and cleanup outcome.
Producer failures add the exact command buffer, commands/end/execute/enqueue stage
and unsubmitted/unknown outcome. Shared recording-retirement errors identify the
command buffer, proof and failing owner index; failed owners remain pinned.

Evidence: focused CPU tests cover completion, body/end discard, execute and
enqueue uncertainty, failed drain retry, stale callbacks and reentrant
drain rejection. Native recording and AOT compilation are validated by the
associated CI and verification-log entry.
