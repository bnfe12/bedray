# History C ABI — source candidate

`include/bedray/history_c.h` and `src/history_c.cpp` expose the registry through
an opaque handle, fixed-width structures and status values. The core CMake
target compiles the wrapper into the DLL and links the history library; neither
the frame path nor the Java host calls it yet. This source and build wiring have
not been compiled or tested. ABI version 2 is provisional until verified.
The static libraries request position-independent code, but the core CMake
configuration still explicitly targets Windows; this is not Linux/macOS support.

All calls are externally synchronized on the renderer thread. Input pointers
and descriptor ranges must remain valid during the call. Signal names are
explicit-length strings, nonempty, no embedded NUL, at most 256 bytes. The
optional sensitivity mask accepts only target, scene, camera and discontinuity
bits; mandatory reset reasons always apply. `BEDRAY_HISTORY_DEFAULT_SENSITIVITY`
enables all four for ordinary screen-space signals. Zero deliberately disables
all four optional resets and requires a signal-specific justification.

Only `BEDRAY_HISTORY_COMPLETED_ONLY` is accepted through this C boundary for
now. The other values are reserved for future integration: actual same-queue
barriers or cross-queue semaphore dependencies have not been wired to the
submission protocol. Even completed-only does not supply a read dependency or
layout transition. Unknown submission outcomes are treated as unknown.

`bedray_history_abi_info` reports sizes, alignments and every field offset for
the data structures passed over the boundary. Call it with the size of the
allocated output structure; insufficient size is rejected before writing. The
foreign-language host must check the ABI version before interpreting it, and
compare every layout it uses. Array-field offsets identify the first element;
the element stride is the corresponding reported struct size, or eight bytes
for queue serials. ABI information is not proof that a live renderer is wired.
Returned status is a registry status unless it is one of the wrapper errors
starting at 0x100; detail is meaningful when the status is SEE_DETAIL.

The host must follow the registry's prepare / reserve / attach / submit / end
protocol in `HISTORY_CONTRACT.md`, including reset decisions and actual queue
completion. `pop_finished` and `pop_orphaned` transfer records to the host, not
ownership of Vulkan destruction to this module. Host memory and image lifetimes,
semaphore and barrier setup remain external. A lost device requires the agreed
Vulkan teardown ordering; orphaned is not a claim that GPU use has completed.

`bedray_history_destroy` refuses an active device, configured images, or retained
records. For a healthy device, prove idle, retire it and collect finished
records before destroying the registry. For device loss, collect orphaned
records under the Vulkan owner's loss procedure first. The registry must not be
destroyed merely because a command buffer was recorded or submitted.
