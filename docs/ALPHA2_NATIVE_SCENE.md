# Alpha 2: native RT scene and built-in Slang passes

Built-in Rays and Path Traced use one C++ scene and one native recorder. AOT Slang
programs can use compute, ray generation or SER with the same scene layout.
The old scene and recorder JVM switches have no effect. Radiance Cascades,
ReSTIR PT and SER remain independent features. Cutout surfaces use Slang alpha
sampling. The removed Java opacity micromap experiment is no longer registered. A missing or incompatible native
library disables built-in hardware RT for the session and logs the cause. A
frame without ready terrain is left to the raster path without advancing trace
history.

## Ownership and mod boundary

| Java entry point or capture | C++ ownership and consumer |
| --- | --- |
| Fabric/NeoForge entry points, mixins, `renderer-api` and host hooks | Game and companion contracts remain on the JVM. Custom RT reads the C++ TLAS with an independent generation lease. Iris post and the independent NoRT/Compute provider retain their own input contracts. |
| Sodium hooks, `SectionGeometryRegistry`, `NativeSectionCapture` | Java indexes and packs CPU captures and supplies identity, position, revision and size. C++ selects admissions, handles rebuilds and evictions, and publishes the committed section revisions. The emitter scanner reads only the CPU captures that match those revisions. |
| `EntityCaptureManager`, `RtEntityGeometryManager`, `EntityTextureAtlas` | Java sees entity IDs, mesh and sprite data. Native owns the entity BLAS and the corresponding positions, indices, previous positions, attributes, rects and texels. The current geometry and shader streams share a generation. |
| `RtMaterialAtlas`, `RtEmitters`, reload epoch | Java bakes Minecraft sprites and packs light inputs and optical profiles. C++ owns both material image planes, immutable optical, light and sky buffers and upload staging. The host albedo atlas and lightmap are Minecraft-owned borrowed views. |
| `RtWorld`, `NativeSceneBridge`, `BedraySceneNative` | The Java adapters pass the borrowed device, command buffer, scene inputs and retirement proof. C++ owns the AS, scene tables, scratch, scene resources and resource generations. |
| `RtFrameRenderer`, `RtSlangPathPass`, `NativePathTraceBackend` | Java translates settings and frame hooks and integrates output/denoiser/presentation with Minecraft. C++ records the built-in trace, accumulation, ReSTIR, statistics, display and volumetric passes. |
| Removed | Java BLAS/TLAS builders, entity GPU ring, Java RT scene allocator, age-based scene fences, opacity micromap cache for the old scene, Java trace recorder and the old AOT traversal variants. |

The Java completion ticket and the C++ scene frame serial name the recording.
A command buffer address alone is insufficient once it can be reused. Submission
is not completion. Proven discard releases the abandoned recording and triggers
a fresh C++ scene before the next trace. It invalidates the abandoned camera
and entity motion history. An uncertain submission retains its owners. At host
device close, confirmed Vulkan device loss may retire recordings without
publishing their output; a timeout does not grant this proof.

## Build and check

Use `fabricBedrayDistribution` and `neoforgeBedrayDistribution` with Slang,
Vulkan headers and a Windows C++ toolchain as documented in [BUILDING](BUILDING.md).
Each distribution supplies one loader JAR and the matching `bedray_core.dll`.
The standalone `bedray_scene` target is for development and tests; its exports
are already in the core DLL. The scene ABI is 3. Keep vendor SDKs separate.

```powershell
python tools/automation/audit_native_scene.py build/bedray-distribution
.\gradlew.bat :core:test :common:test :common:runShimTests
```

Use the same `bedray_core.dll` for the FFM JUnit suite through
`-PbedrayCoreLibrary=<path>`. Run the core CTest suite for the C ABI, scene
retirement, passes and renderer resource contracts. AOT and fake-device tests
cannot establish live image quality.

## Acceptance ledger

| Evidence | Scope |
| --- | --- |
| A0, `06b3d08c5b3f78a998830e9df08a1fd393d3941d` | Historical default Java route. Fabric `rt_look` ran on a loaded world. The explicit native run failed at an FFM call and was not a parity result. |
| Stage 1, `9e15088`, `286e58b`, `5491dad` | Both loaders ran the opt-in C++ scene/recorder with Sodium. NeoForge also logged active NRD and RR. Both loaders exercised custom RT generation leases and discard/rebuild. These are pre-removal packages. |
| Stage 2 development artifact | Fabric `rt_modes` displayed Rays and PT with the obsolete properties set to false and recovered from intentional discard. This preceded the final material and light ownership changes. |
| Exact Stage 2 distributions | Record hashes, manifest identity, CTest/JUnit/shim and each real run in the local acceptance record. Do not use a development artifact as evidence for a later SHA. |

Open release gates: both exact packages with Sodium on a loaded world; same-size
resource reload, resize, chunk streaming, dimension change and supported shader
integrations; mismatched/missing DLL with live raster; repeatable image and frame
time comparison at 1080p native PT and QHD RR; final independent review.
See [Native 1080p 60 audit](NATIVE_1080P_60_AUDIT.md) for the FPS target and
[the benchmark harness](../tools/automation/README.md) for raw capture rules.
