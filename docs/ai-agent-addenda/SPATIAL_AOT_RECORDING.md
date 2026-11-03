# FSR1 and NIS AOT recording

The existing FSR1 and NIS requests use three Slang entries: `fsr1_easu`,
`fsr1_rcas`, and `nis_scaler`. There is no runtime shader compiler in the spatial
path. FSR1 records EASU followed by RCAS when sharpening is enabled; NIS records
NVScaler. A failed request selects the existing bilinear fallback until the
request changes.

Historical GLSL kernels and their source builder are test fixtures only. They
are excluded from the production source/resource tree. The only runtime shader
compiler users are the extension probe and the host for user-authored packs;
the global inventory test freezes that boundary.

The checked-in MIT vendor headers are included without edits. The FSR1 fp32 shim
preserves the existing approximation constants and functions. NIS retains its
fp32 vendor body, the existing output bounds guard, and all coefficient values;
an adapter supplies combined-sampler reads and constant-table coefficient loads.
The entries require no optional Bedray capability bits.

`BedraySpatialFrameDesc` is an additive ABI-v2 descriptor of 136 bytes. Native
validates both stages before recording and owns push payload copies, immutable
dispatch descriptors, group counts, and entry/inter-stage/exit memory barriers.
EASU uses an 80-byte push block and 8x8 threads; RCAS uses 32 bytes and 8x8;
NVScaler uses 112 bytes and 128 threads for each 32x24 output tile. The host still
owns the input, optional intermediate, output, command buffer, and submission.
Views must use `GENERAL` layout. Programs and samplers belong to the shared
device runtime lease.

`WorldTargetManager` attaches retirement to the exact producer command buffer.
Failure before `execute` abandons the entire command buffer, including a
successful EASU stage preceding a failed RCAS stage. Exceptions from `execute`
or completion-queue insertion retain an unknown recording until the host submits
its pending encoder work and waits for a fence. Fence failure retains the lease
and images. In-place target resize also drains outstanding producer recordings
before replacing borrowed Vulkan views; Java target identity alone is not a
resource lifetime proof.

The checks cover vendor-source and coefficient equivalence, reflected push and
workgroup contracts, FFM offsets, native dispatch payloads, chain validation,
partial recording failure, and host retirement ordering. These are L1/L2 checks.
They do not establish visual equivalence or GPU performance; those require a
live L3 run and shader-owner review.
