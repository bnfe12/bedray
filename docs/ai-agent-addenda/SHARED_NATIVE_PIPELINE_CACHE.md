# Shared native pipeline cache

The core previously passed `VK_NULL_HANDLE` to every compute-pipeline creation,
even though `RtVulkanPipelineCache` already persisted a cache for Java builders.
`bedray_core_register_program_cached` now borrows that same device cache for one
synchronous call. The old export and all ABI-v2 structure layouts stay intact.
An older DLL continues through uncached registration and logs its missing export.

`SlangRuntime` screens the SPIR-V and required enabled capabilities first, then
borrows the cache under the same lock as Java pipeline creation and persistence.
The core does not retain, serialize or destroy the borrowed cache. Vulkan may not
access it after `vkCreateComputePipelines` returns, so no GPU wait or recording
pin is introduced for this borrow.

`PipelineCacheOwner` serializes use/close, refuses stale captured borrows, and
keeps a failed close retryable. `PipelineCacheDevices` binds that owner to the
actual Java device identity and address; close-before-first-use also creates a
tombstone. A genuinely new device may reuse the numerical address after old
retirement. Closed tombstones keep only a weak device reference and can be
discarded after collection. Tracking is reserved before native cache allocation;
post-acceptance initialization failure leaves the handle reachable for close.

`PipelineCacheIdentity` validates the 32-byte, little-endian Vulkan header,
vendor, device and UUID. The filename also identifies the driver version.
`PipelineCacheData` wraps the opaque driver bytes with a format version, exact
payload length and SHA-256 digest. Reads are bounded and recheck actual length;
payload corruption is rejected before Vulkan. New `.v1.bin` files deliberately
do not import the old unprotected raw cache. A cold cache remains valid.
Atomic replacement preserves the previous complete file on write failure.
Device loss is propagated as device loss rather than labelled cache corruption.

Diagnostics name stage, device, file, source/rejection, Vulkan return, fallback
and retained owner state. They log lifecycle/errors rather than every frame.

Native no-GPU tests check actual borrowed handle delivery, uncached compatibility,
registration rollback, Vulkan device-loss and allocation/unexpected exception
containment. Java tests cover overlapping borrow/close, stale requests, address
reuse, post-native initialization failure, retry, binary corruption/device
validation and old-DLL host integration. No startup-time or FPS improvement is
claimed without a real device measurement.

Normative contracts:
[compute creation](https://docs.vulkan.org/refpages/latest/refpages/source/vkCreateComputePipelines.html),
[cache input](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCacheCreateInfo.html),
[header format](https://docs.vulkan.org/refpages/latest/refpages/source/VkPipelineCacheHeaderVersionOne.html).
