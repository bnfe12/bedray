# Handoff: wiring the Slang path tracer into the live frame

Date: 2026-10-26. Status: **partially written, NOT compiled. The `common` module does not build in this state**
(see "Compile blockers"). Nothing below has run on a GPU or in Minecraft.

Goal set by the author: Path Tracing mode renders through the AOT Slang programs; the GLSL path stays as a fallback,
**and must be fully detachable** (a mode where the GLSL path tracer is never created).

## 1. What already works (verified)

- Fabric bundle `build/bedray-distribution/fabric/` (22:24) builds and is installed in the FreesmLauncher profile
  `Bedray Fabric 26.3` (`mods/bedray-fabric.jar`, `bedray/natives/` with `bedray_core.dll` and the 12 upscaler/denoiser
  natives copied from `Vulkan Testbed 26.3 Bedray`). That installed jar predates every change in section 3.
- Two fixes in that jar, both verified by `:common:runShimTests` 36/36:
  - every Java shader path `"/shaders/X"` -> `"/shaders/glsl/X"` (the GLSL files moved in commit `0136472`, the Java
    paths did not; nothing in the renderer could load);
  - `rt_common_water.glsl:94` local `filter` (GLSL reserved word) renamed `tint`; it broke both `rt_rays` and `rt_path`.
- `RtGlassCostTest` has 3 failing text assertions; the GLSL text was unchanged, so they predate this work.
- Build command: `./gradlew.bat fabricBedrayDistribution --no-daemon -Pslangc=C:/Temp/bedray-slang-2026.20/bin/slangc.exe
  -PbedrayCmake="C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe"`.
  `runShimTests` takes ~15 min (shaderc compiles `rt_rays.comp` in ~7 min).

## 2. Facts found (with source)

- **Bloom is already on Slang in the live frame**: `RtAtmosphere.slangRuntime()` creates `SlangRuntime.create(device, 0)`
  and `RtBloomPass` uses `passes/bloom_*.slang`, falling back to `rt_bloom.comp` only on failure. Recording release:
  `RtFrameRenderer` line ~572 `atmosphere.releaseRecording(cmd.address())` right after
  `encoder.allocateAndBeginTransientCommandBuffer()` (the transient handle comes back only after its previous
  submission completed). Follow the same pattern.
- Path tracing today: `RtFrameRenderer.createPass` (line ~1304) builds `RtPathTracerPass` (GLSL `rt_path.comp`).
  Callers: line ~486 (world init), ~563 (mode switch), ~1283 (`rebuildPlainPipeline`). `VulkanDevice` is reachable
  statically via `VulkanCapabilityChecker.getVulkanDevice()`.
- Mode-switch check (line ~560): `pass.mode() != want || pass.usesGbuffer() != wantGb`. A pass that cannot write the
  NRD/DLSS-RR G-buffer would be **recreated every frame** while a denoiser is selected. Needs a different check.
- Frame outputs: `rt_output` RGBA8 UNORM (GLSL writes tone-mapped LDR directly), `rt_depth` R32F (miss = 1e6, read by
  composite and bloom). Both stay in `GENERAL`. Created in `RtFrameRenderer` line ~655.
- `passes/path_trace.slang` writes **linear HDR** (RGBA16F, no exposure, no tone map) and depth with the same 1e6 miss
  convention. A display step (mean -> exposure -> `tonemapColor` -> RGBA8) is required; `tonemap_curve.slang` is the
  same curve as `rt_tonemap.glsl` / `RtTonemap.java`.
- Camera: the Slang shader takes an explicit basis (forward, right, up, tan half fov in `forward.w`, aspect in `right.w`)
  and builds `F + R*ndc.x*t*a - U*ndc.y*t`, ray origin 0. GLSL `cameraRay` (`rt_common_temporal.glsl:50`) uses
  `pc.invViewProj * (ndc, 0.5, 1)` with `ndc.y` negated when `flipY`, minus `cameraBobOrigin`. Exact basis: unproject
  NDC (0,0), (1,0) and the bottom edge on depth 0.5 => C, X, Y; forward = C/|C|, right = (X-C)/|X-C|,
  up = -(Y-C)/|Y-C|, tan = |Y-C|/|C|, aspect = |X-C|/|Y-C|. Only difference: no view-bob origin.
- Sun/sky inputs: `RtFrameParams.key` (`dir` towards light, `color`, `day`, `isMoon`), `RtFrameParams.skyColor`.
  `sun.slang` uses `color.rgb * color.w`; `sky.slang` (GradientSky) is a two-colour gradient, simpler than `rt_sky.glsl`.
- Auto exposure in the GLSL path comes from statistics `rt_path.comp` writes into `statsBuf`; the Slang shader writes
  none, so the Slang path has no auto exposure (fixed 1.0) until a luminance reduction exists.
- **Device features**: `passes/path_trace.slang` manifest requires `CAP_RAY_QUERY | CAP_BUFFER_DEVICE_ADDRESS |
  CAP_SHADER_INT64` (mask 517). The "Hardware Ray Tracing" group (`RtDeviceRequirements.requirement`) requests
  accelerationStructure, rayQuery, bufferDeviceAddress — **not `shaderInt64`** (GLSL uses `buffer_reference_uvec2`).
  The authoritative enabled set is the private field `VulkanDevice.enabledFeatures` (`FeatureSet`, javap on
  `libs/minecraft-26.3-client.jar`); `FeatureSet.features()` -> `VulkanFeature.name()`. `DeviceFeatureRegistry` has no
  "was this group kept" query.

## 3. Uncommitted changes made for the wiring (review before keeping)

| File | Change | State |
|---|---|---|
| `common/src/main/resources/shaders/slang/passes/path_display.slang` | new: running mean (`accumFrames`, 0 = reset), exposure, `tonemapColor`, writes RGBA8 `outImage`; bindings `radiance` RGBA16F, `accum` RGBA32F, `outImage` RGBA8; 16-byte push | **compiled** with slangc (8156 B SPIR-V, 3 bindings, capabilities 0) |
| `common/src/main/java/.../rt/world/RtPathTraceSlangPass.java` | constructor no longer takes Scene/Atlases; `record(cmd, scene, atlases, depthView, frame, dispatch)`; own depth image removed, depth goes to the caller's `rt_depth` | not compiled |
| `common/src/main/java/.../mixin/rt/MixinVulkanDeviceFeaturesAccessor.java` | new `@Accessor("enabledFeatures")` on `VulkanDevice` (`remap = false`) | not compiled |
| `common/src/main/resources/mixins.bedray.json` | `rt.MixinVulkanDeviceFeaturesAccessor` added to `client` | — |
| `common/src/main/java/.../rt/slang/SlangDeviceCapabilities.java` | new: `enabledMask(VulkanDevice)` maps enabled feature names to `CAP_*` bits | not compiled |
| `common/src/main/java/.../rtx/raytracing/RtDeviceRequirements.java` | new optional group `Bedray Slang Path Tracing` (RT extensions + `shaderInt64` via `FeatureRequest.core(VK10, ...)`), `registerSlangPath` | not compiled |
| `common/src/main/java/.../RtxBootstrap.java` | `SLANG_PATH_PROVIDER_ID` + late provider registration after OMM | not compiled |
| `common/src/main/java/.../rt/world/RtSlangPathPass.java` | new `RtPass`: trace + display, accumulation reset (camera, scene, settings, size), camera basis (section 2), sun/sky mapping, Halton jitter, recording tracking, `builtFor`, `releaseRecording` | not compiled |

## 4. Compile blockers (fix first)

1. `RtPass` lacks the two methods `RtSlangPathPass` overrides. Add:
   - `default boolean builtFor(boolean gbuffer) { return usesGbuffer() == gbuffer; }`
   - `default void releaseRecording(long commandBuffer) {}`
2. `RtPathTraceSlangPassTest` only uses `Frame`/`Dispatch`; re-check it after the constructor change.
3. Unverified imports/APIs in `RtSlangPathPass`: `RtFailure.check`, `RtAdaptiveQuality.bounces`, `RtConfig.effectiveMode`,
   `SlangPassLibrary.available`, `Matrix4f.equals(Matrix4f, float)` — all used elsewhere in the tree, but compile to confirm.
4. `SlangDeviceCapabilities` uses `((Object) device) instanceof MixinVulkanDeviceFeaturesAccessor` because the mixin adds
   the interface at runtime.

## 5. Remaining wiring (not started)

1. `RtFrameRenderer`:
   - mode switch condition: `!pass.builtFor(wantGb)` instead of `pass.usesGbuffer() != wantGb`;
   - after `atmosphere.releaseRecording(cmd.address())` add `pass.releaseRecording(cmd.address())`;
   - a shared or dedicated `SlangRuntime` created with `SlangDeviceCapabilities.enabledMask(vulkanDevice)` (never a
     support query), closed in renderer shutdown after the pass is destroyed;
   - `createPass` for `PATH_TRACED`: selector (below).
2. Selector, e.g. `-Dbedray.pathTracer=auto|slang|legacy` (or a config key):
   - `auto`: Slang when `RtSlangPathPass.isSupported(runtime)` and no G-buffer denoiser is wanted; otherwise GLSL, with
     the reason logged once;
   - `slang`: Slang only, GLSL `RtPathTracerPass` never constructed (the detachable mode the author asked for); if
     unsupported, path tracing reports a clear failure instead of silently using GLSL; denoiser disabled with a log line;
   - `legacy`: current behaviour.
   Construction failure of the Slang pass in `auto` must fall back without leaking the native recording.
3. Log lines to check in `latest.log`: `[VKB-RT] path tracing on AOT Slang passes path_trace + path_display`,
   `[VKB-SLANG] registered path_trace`, `rt_path_trace WxH RGBA16F radiance, depth into rt_depth`, and the
   `Enabling optional FeatureSet [Bedray Slang Path Tracing]` engine line.

## 6. Known gaps of the Slang path (state them in the report)

No NRD/DLSS-RR G-buffer, no auto exposure, two-colour sky, no view-bob origin, moon colour 0, block-material table empty
(`materialCount = 0`), no motion-vector output for the upscalers' reprojection. Image quality will differ from the GLSL
path tracer; this is a first live path, not parity.
