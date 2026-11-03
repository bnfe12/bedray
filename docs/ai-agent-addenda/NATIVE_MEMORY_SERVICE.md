# Shared native memory service — 2026-11-03

The core owns one externally synchronized allocator for its host VkDevice.
The Java runtime configures it before registering programs, attaches its empty
native image/buffer owners and embedded native scene, and shares the same
service with native path/RC/bloom/velocity/volumetric/SBT owners. Standalone
v2-compatible owners retain their dedicated path. An old core is explicitly
reported as lacking shared memory; it is not counted as budgeted.

The service suballocates matching memory types, BDA flags, mapped state and
resource classes. Optimal images and buffers use separate blocks. Mapped
slices isolate noncoherent atoms and flush/invalidate rebased ranges.
Requirements2 queries honor required/preferred dedicated allocations; large
resources use dedicated memory. Near the limit, a smaller block can satisfy
a request. Empty blocks are freed; live holes are reused. CPU metadata is
reserved before driver allocation; frees do not allocate.

Native reserved bytes have one hard cap, including retained generations.
Optional VK_EXT_memory_budget negotiation adds process-wide heap pressure
checks, including the host allocator's estimated usage. It is advisory and
cannot reserve memory against external activity. No foreign VMA pointer is
borrowed. Without the extension, heap estimates say unavailable. Local VRAM
capacity excludes non-device-local heaps.

Default native cap: one third of device-local capacity, bounded to 128..3072
MiB (256 MiB when unknown), with 32 MiB blocks. Overrides:
`bedray.rt.nativeMemoryMiB` and `bedray.rt.memoryBlockMiB`. Per-owner limits
still apply. These defaults are policy, not measured best settings for every
scene/device. The host's images/vendor allocations are not moved into this
pool; they remain visible to the process-wide budget estimate.

Retirement/use tokens keep their existing exact completion/discard authority.
Neither a frame number nor an allocator destructor frees unknown uses. Device
loss poisons the shared service; explicit quiesced teardown still releases
allocations. Scene scratch buffers include padding and align actual device
addresses; usable capacity excludes that padding. Public ABI-v2 layouts,
device/queue/submission ownership and barriers are unchanged.

Additive exports are in `bedray_core_memory.h`, version 1, with a 72-byte stats
record. `[VKB-MEMORY]` reports configuration, counters, query failures and
allocation failures (operation, request, resource status, VkResult and budget).
F3 uses cached snapshots; polling occurs at the existing 500 ms perf boundary.

Checks at `84d8a76`: 1131 Java tests, 1122 passed, 9 skipped, no failures/errors;
35 AOT entries built unchanged. Windows CI 41548217102 has baseline/candidate
green: 32 core + 1 scene + 1 lifetime + 1 NRD settings tests. Mock-driver
coverage includes pooling/free holes, dedicated/BDA separation, mapped
atom isolation, external pressure, exact retention, rollback and device loss.
The first native iteration exposed white-box linkage coupling; the ABI bridge
was moved out of core.cpp into core_memory.cpp and CI passed afterwards.
The final padding-capacity follow-up (`4641e0a`) also passed Windows CI
41549142471: candidate 125631999481 and baseline 125631999385. PR #63 merged
as `caf9f73`; its final source still has no live GPU/FPS measurement.

No GPU measurement, VRAM reduction percentage or FPS improvement is claimed.
Transient aliasing, host allocation migration and AS compaction are separate work.
