# Bedray native GPU buffer owner

Source candidate for per-device Vulkan buffer allocation with a provisional
C ABI. The core DLL now builds and exports that ABI, but the graph, Java host
and Minecraft frame path do not yet call it. The optional test uses mock Vulkan
functions, not a driver; a successful build/test does not prove GPU execution or
in-game rendering. Vulkan images and scene upload are not yet implemented here.

The host supplies an already-created device, enabled feature guarantees,
physical memory properties, `nonCoherentAtomSize` and Vulkan device functions.
It owns queue submissions, memory barriers, queue-family ownership and all
graphics lifecycle. The module uses one dedicated allocation per buffer so a
driver requiring dedicated memory is supported; suballocation and VMA are not
implemented. Device-address usage requests allocation flags and returns the
queried address only when the host declares the feature enabled and supplies
the corresponding device function. Per-device heap/budget telemetry and image
allocation need separate owners.

The host assigns an in-process-unique nonzero generation to each owner instance;
buffer/use handles carry this generation and monotonically assigned counters.
External synchronization on the renderer thread is mandatory. Before recording
any command that uses a buffer, call `begin_use`. Keep each use outstanding
until the host proves either that its recording was discarded without ever
being submitted, or that all submissions completed and the recording cannot
be submitted again. If a submission outcome is unknown, never call
`complete_use` without that proof. Multiple uses of one buffer are independent.

`retire` stops new use but does not destroy a buffer; `collect` destroys only
retired buffers without uses. `device_idle` is valid only after proving both GPU
idleness and that the affected command buffers cannot later be submitted or
resubmitted. It never waits for the device itself. `close` refuses while
resources remain. The destructor intentionally does not issue Vulkan calls, so
failure to close safely leaks rather than destroying in-use GPU objects.

After observed device loss call `device_lost`; ordinary allocation, mapping,
collection and use completion then refuse. The host must quiesce all device
callers and invoke `release_after_device_loss` during its device-loss teardown
before destroying the device. This releases even unknown uses; it does not claim
normal GPU completion. A fresh device requires a fresh owner and generation.

Mapped CPU read/write requests are rejected while any use is outstanding.
Non-coherent writes flush, reads invalidate, and ranges are aligned to the
device atom size and clipped to the allocation's end. These operations do not
replace the required Vulkan execution/memory dependency between CPU and GPU.
The host must validate image/buffer aliasing and producer-consumer visibility.

For local source verification, configure with `BEDRAY_VULKAN_HEADERS` and
`BEDRAY_GPU_RESOURCES_TESTS=ON`, build, then run `bedray_gpu_buffers_test`.

## Provisional C ABI

`include/bedray/gpu_buffers_c.h` is C-compatible and does not require Vulkan
headers. All handles and function addresses are explicitly 64-bit, so the
implementation targets a 64-bit process. The caller must first check
`bedray_gpu_abi_version`, then `bedray_gpu_abi_info` sizes, alignments and all
field offsets against its own bindings. The size argument is validated before
the info structure is written. Owner creation also checks ABI version, size,
mandatory function addresses, flags, memory type count and a power-of-two atom
size. It cannot verify that addresses, the reported enabled features, memory
properties or the generation are correct: those are mandatory host proofs.

The C ABI keeps buffer/use IDs paired with the caller-supplied unique owner
generation. All host pointers remain valid only for the duration of the call;
the wrapper never retains host data buffers. A use reservation is explicit;
neither the C ABI nor the core's existing dispatch automatically reserves one.
Do not release a use or destroy its owner until the lifecycle conditions above
are met. `bedray_gpu_owner_destroy` refuses an owner with outstanding resources.
After device loss, explicitly release the lost-device resources as part of the
host's quiesced teardown before destroying the owner.

For export checking only, `BEDRAY_GPU_RESOURCES_SHARED_ABI=ON` builds a separate
standalone `bedray_gpu_resources_abi` DLL and C layout probe. This is not a
game-distribution DLL. The normal targets are separate static libraries for the
buffer implementation and C wrapper. To export the symbols from the eventual
`bedray_core` DLL, the integrator must deliberately compile `gpu_buffers_c.cpp`
into that target with `BEDRAY_GPU_RESOURCES_EXPORTS` and link the implementation
library; merely linking an otherwise unreferenced static wrapper does not prove
the object or its exports will be included. FFM and Vulkan validation still need
to be performed against that exact final DLL. The wrapper neither changes nor
intercepts `bedray_core_render_frame`.
