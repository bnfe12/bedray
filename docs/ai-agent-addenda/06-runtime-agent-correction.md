# Correction for the renderer integration agent

Pause edits to `RtBloomPass`, `RtAtmosphere`, `RtFrameRenderer` and the shared
ABI while ownership is being coordinated. Preserve all uncommitted work; do not
revert, stash, clean, stage, commit or push. Do not run tests, builds, slangc,
Minecraft or deployment until the user explicitly starts verification.

The user directed Bedray alpha 0.2.0 as a new C++ renderer core plus Slang
algorithms. Java remains only where needed to integrate Minecraft/Fabric/
NeoForge, capture host data, own game objects and communicate via the C ABI.
Do not add a Java-owned renderer, a new legacy GLSL fallback, or device-wide
waits as the new design. The existing Java/GLSL implementation is a behavioural
reference, and uncommitted files are shared work, not disposable output.

Observed candidate edits introduce a Java Slang bloom path inside
`RtBloomPass`, explicitly fall back to `rt_bloom.comp`, and use
`vkDeviceWaitIdle`. Separate the reusable Slang algorithm work from that
integration; report every changed file, intended ownership, resource lifetime,
and dependencies for a new C++ render pass. Do not merge or extend the Java
renderer integration until the owner resolves the overlap.

Coordinate against ABI v2: `bedray_core_render_frame` owns one immutable
descriptor set per dispatch; `bedray_core_release_recording` is called only
when the host proves completion or discards an unsubmitted recording, and
`bedray_core_destroy` returns `BEDRAY_ERROR_IN_USE` while recordings remain.
`VulkanCommandEncoder.queueForDestroy` is a candidate completion bridge for the
same encoder submission; it is not proof for another queue/submission and it
must not be treated as a generic frame-number delay. See
`native/bedray_core/RECORDING_LIFETIME.md`.

Produce an integration plan for moving bloom pipelines, descriptors, mip
images and barriers to a C++ pass. Keep the host API coarse and report missing
scene/render graph contracts rather than inventing a second Vulkan device,
queue, swapchain, or submission path. A source review is not a build or runtime
validation.
