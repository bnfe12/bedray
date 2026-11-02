# Optional ray-generation and invocation reordering

The compute RayQuery backend remains the default. The optional backend runs
the same integrator, scene decoders, materials, random streams and descriptor
contract in a ray-generation-only pipeline. RayQuery traversal needs no miss,
closest-hit or any-hit programs. There is one immutable raygen SBT record.

`-Dbedray.rt.raygen=true` requests the ordinary raygen backend;
`-Dbedray.rt.ser=true` also requests NV invocation reordering. These are startup
options: device features must be enabled before creating the logical device.
Each optional group declares its own dependencies. The selector tries SER,
ordinary raygen and compute in that order, subject to actual enabled features,
the driver's reordering hint, packaged AOT artifacts and successful pipeline
registration. Device loss propagates instead of being hidden by a fallback.
`[VKB-RAYGEN]` logs the chosen pass/stage, request, hint and enabled mask;
registration failures retain their pass name and Vulkan status in the logs.

The pinned LWJGL 3.3.3 exposes `VK_NV_ray_tracing_invocation_reorder`, but has no
EXT bindings. This implementation emits `ShaderInvocationReorderNV`, not EXT.
The two extensions must never share a capability bit merely because their
feature members have the same name. A device that reports a no-op reordering
hint uses ordinary raygen. No GPU-name heuristic enables the backend.

All three scene entries have `.raygen` and `.ser` AOT variants. The SER wrapper
reorders after a completed query, using hit/miss, entity and alpha-class hints
before subsequent surface/material work. Filtered visibility calls the wrapped
closest-hit operation too; it can incur reorder overhead. This is an experiment
whose benefit must be measured, rather than a claim of automatic acceleration.
The generated manifest obtains its stage from SPIR-V `OpEntryPoint`: Slang's
raygen reflection omits this field. Raygen launch dimensions are pixels; compute
postprocessing retains its existing workgroups.

Public ABI-v2 layouts are unchanged. Additive program flags identify the kind
and `bedray_core_program_feature_mask` identifies the implementation. Without
that export an older DLL receives only the older capability bits and keeps the
compute path. Pipeline creation borrows the shared host cache synchronously.
The private SBT uses the existing native buffer owner, queried alignment/limits,
noncoherent flush and BDA. It stays with its registered program while any command
recording refers to it. No device, queue, submission or wait is created here.
The native path barriers cover both compute and ray stages only on a device
that enabled the ray-pipeline feature. Scene producer barriers cover subsequent
shader stages, including acceleration-structure reads.

The owner's performance target is 1920x1080 at a stable 60 real frames/s on
RTX 5060 Ti 8 GiB, Ryzen 5 5600XT and 16 GiB RAM. The corresponding whole-frame
budget is 16.67 ms. Frame generation does not count toward that target. This
is an acceptance target, not a measured FPS guarantee. Compare identical scene,
camera, settings, warm cache and completed GPU timestamps across compute,
raygen and SER; record median/p95 frame time, memory pressure and images. Keep
SER off by default until that comparison demonstrates a benefit on the device.

This completes neither EXT SER nor PTLAS/CLAS/DGC, and does not replace the
remaining full-stack ledger. CPU/AOT/native contract checks do not establish
GPU validation, image quality, memory savings or FPS.

Primary API references: [SER proposal](https://docs.vulkan.org/features/latest/features/proposals/VK_EXT_ray_tracing_invocation_reorder.html),
[NV extension](https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_ray_tracing_invocation_reorder.html),
[ray dispatch](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdTraceRaysKHR.html).
