# Single RR reconstruction with frame generation

For initialized Path Tracing RR with DLSS or native resolution without an
upscaler, FG no longer selects RR at trace resolution followed by a second SR
evaluation. RR writes output-sized color; world raster depth, particles, selection
and post remain output-sized. Guides stay trace-sized. The initial RR bootstrap,
other temporal backends and the explicit `bedray.rt.disableRrOutput` diagnostic
retain their existing routes. This is implementation/test evidence, not RTX
visual or FPS acceptance.

## Current producer and lifetime

`FrameGenGuides` identifies the engine `WorldFrame.Stamp`, host-device identity,
RR guide generation, reverse-Z R32 depth, RG16F previous-minus-current pixel
motion, trace/output extents, exact RR SDK camera and physical primary jitter.
The source is published only after the RT recording was handed to the host and
actually displayed this frame. A current direct-RR route without a successful RR
source skips FG; it cannot substitute old raster depth or motion. Provider frame
counters are not interchangeable with engine stamps.

`FrameGenGuideReads` pins the borrowed RR generation before FG's first command.
Source and output retirements are registered independently before acquisition;
partial unsubmitted recordings are discarded, while uncertain execute/enqueue
outcomes retain both owners until exact completion or a host fence. Old callbacks
cannot release a new read at the same command address. RR replacement/destruction
requires no pending FG source read, and the RT host drain first drains both FG
producer bridges. The vendor's asynchronous interpolation lease on copied FG
outputs remains separate. No native ABI change, device-idle or new submission
owner is introduced.

## Camera and coordinates

The existing `fg_prepare` flips color/depth/motion rows once and negates motion Y.
Its source images are used directly. For `Y = diag(1,-1,1,1)`, prepared SL camera
projection is `Y * Prr`; reprojection becomes `Y * clipToPreviousRR * Y` and SL
jitter becomes `(sdkJitterX,-sdkJitterY)`. Effective eye/view and raw camera
anchors are unchanged. Reconstruction uses SL viewport 0; FG tags, constants,
options, state queries and clearing consistently use viewport 1, with separate
accepted camera histories and the same frame token. Streamline 2.14.x supports
[independent FG viewports](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/docs/ProgrammingGuideDLSS_G.md#53-multiple-viewports).

Physical primary jitter comes from the actual Slang frame for depth encoding,
RR velocity and the FG source, including fallback jitter sequences. RR applies
DLSS sign overrides only when creating SDK evaluation/camera data; velocity uses
the actual current and previous successful physical samples. FFX v1.1.4 samples
`pixel + .5 - jitter`, so prepared FFX jitter is `(-physicalX,+physicalY)` after
the row flip, independently of DLSS sign overrides. See the pinned
[AMD interpolation shader](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v1.1.4/sdk/include/FidelityFX/gpu/frameinterpolation/ffx_frameinterpolation_common.h#L428-L433).
RR camera cuts reset NVIDIA FG history and reinitialize the AMD FG context.
Fixed lower-resolution guides do not enable DLSS-G dynamic-resolution mode;
there is no active DRS producer.

## Verification and remaining acceptance

CPU tests cover engine-stamp/device/output mismatches, asymmetric SDK/physical
jitter, Y-conjugated reprojection, independent viewport histories, retained reads,
stale generations, command-address reuse and partial/uncertain producer failures.
Integration guards cover both vendors' source selection, first-reader pins,
replacement drains and exact primary-jitter wiring. The common suite passed
1,202 tests with 9 skipped; shim checks passed 34/34. All 42 AOT entries and both
loader host/companion pairs built successfully.

Required RTX acceptance still includes FG off/on, DLSS Balanced/Quality and native
RR, camera cuts, world changes, resize, moving entities, glass/water/foliage,
selection and post effects, plus validation-layer output and vendor counters.
Native 1920x1080/60 real FPS remains a separate acceptance target; generated
frames and DLSS upscaling do not count toward it. RR-specific size policy/SR
residency, DI correlation and acceleration-structure performance remain open.
