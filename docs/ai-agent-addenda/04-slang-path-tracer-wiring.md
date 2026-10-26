# Handoff: Slang path tracer wired into the live frame

Date: 2026-10-26. Status: **wired, compiled, tested and packaged. Not yet verified on a GPU or in Minecraft.**

Path Tracing mode now selects an AOT Slang pass (`path_trace` + `path_display`, both RGBA16F/linear) with the GLSL
`rt_path.comp` tracer as fallback. The legacy path is detachable at runtime with `-Dbedray.pathTracer=slang`.

## 1. Selector

`-Dbedray.pathTracer=auto|slang|legacy` (`RtConfig.PathTracer`, parsed once at class load).

| Value | Path Tracing mode | Denoiser |
|---|---|---|
| `auto` (default) | Slang when the runtime, both AOT passes, the required capabilities (rayQuery, bufferDeviceAddress, shaderInt64), the RT world and no debug view are available; otherwise GLSL | G-buffer denoiser is turned off while Slang is active, with a log line naming `-Dbedray.pathTracer=legacy`; if Slang is blocked, normal resolution (NRD / DLSS-RR) continues and the GLSL path keeps the denoiser |
| `slang` | Slang only; a missing native/artifact/capability raises `RtFailure("slang_path", ...)` instead of silently falling back | forced off (`reason=slang_path_has_no_gbuffer`) |
| `legacy` | GLSL only; `RtSlangPathPass` is never constructed and the Slang runtime is never created | unchanged |

Rationale for AUTO: the shipped profile has `path_denoiser = "DLSS_RR"`, so the handover's earlier rule ("Slang only when
no G-buffer denoiser is wanted") would have kept every default run on GLSL. The Slang path writes no G-buffer, so AUTO
disables the denoiser for Path Tracing instead; Ray Tracing mode is unaffected.

## 2. Wiring (implemented)

- `RtPass` gained `default boolean builtFor(boolean gbuffer)` and `default void releaseRecording(long commandBuffer)`.
  The mode-switch check uses `!pass.builtFor(wantGb)`; without it a Slang pass would be rebuilt every frame while a
  denoiser is wanted.
- `RtFrameRenderer`:
  - one dedicated `SlangRuntime` created with `SlangDeviceCapabilities.enabledMask(VulkanDevice)` — the features the
    logical device really enabled, never a support query; bloom keeps its own capability-free runtime;
  - `pass.releaseRecording(cmd.address())` after `atmosphere.releaseRecording(...)`, before re-recording the transient
    command buffer whose previous submission completed;
  - shutdown destroys the pass (which releases its recordings), then closes the dedicated runtime;
  - `slangPathBlocker()` decides AUTO before the denoiser is switched off and is reused by `createSlangPathPass` so the
    logged fallback reason is the real blocker, not its consequence.
- `RtSlangPathPass`: trace + display, RGBA32F running mean, camera basis from `invViewProj` (same C/X/Y construction as
  GLSL `cameraRay`, including the `flipY` convention), Halton sub-pixel jitter, sun/sky from `RtFrameParams`, full
  memory barriers after both dispatches, per-command-buffer native recording release.
- `path_display.slang`: 16-byte push (`accumFrames`, flags, exposure, maxRadiance), three storage-image bindings,
  `tonemap_curve.slang`. `path_display` is now in `shaders/slang/manifest/index.json` (10 passes) and in the built jar.

## 3. Verification performed

- `:common:compileJava` and `:fabricBedrayDistribution` succeed with
  `-Pslangc=C:/Temp/bedray-slang-2026.20/bin/slangc.exe` and the documented CMake path.
- Targeted tests pass: `RtPathTraceSlangPassTest` (7), `SlangPassLibraryTest` (7), `BedrayCoreAbiTest` (3).
- Full `:common:test`: 605 tests, 7 failed, 3 skipped. All 7 failures are pre-existing and unrelated to this work:
  `RtGlassCostTest` (3) asserts unchanged GLSL text, `RtSelfAvatarExclusionTest` (4) still reads the old
  `src/main/resources/shaders/...` paths that moved to `shaders/glsl/...` in `0136472`.
- Built `build/bedray-distribution/fabric/bedray-fabric.jar` (sha256 `84996bb928cbe6688a9514b785e7d97e44833497f2e8a89254421ca4068b4289`).
  It contains `path_trace`/`path_display` manifests and SPIR-V, `RtSlangPathPass`, `RtPathTraceSlangPass`, and the new
  `auto_slang_path_has_no_gbuffer` selector strings.
- Installed into `Bedray Fabric 26.3`:
  `minecraft/mods/bedray-fabric.jar` replaced (backup `minecraft/bedray-backups/bedray-fabric.jar.20261026-2313.bak`).
  `bedray_core.dll` is byte-identical to the installed one (sha256 `cd6a0eab...fc8e1d`), so it was not replaced.

## 4. Not verified

Nothing below has run in Minecraft yet. The next step is launching the profile, selecting Path Tracing (the profile
already has `rt_mode = "path"`), and reading `latest.log` for:

- `Enabling optional FeatureSet [Bedray Slang Path Tracing]` (or its absence: then AUTO keeps the denoiser and GLSL);
- `[VKB-RT] Slang path runtime ready enabled_mask=0x...`;
- `[VKB-SLANG] registered path_trace` / `path_display`;
- `[VKB-RT] path_tracer selector=auto on AOT Slang passes path_trace+path_display (gbuffer=false)`;
- `[VKB-RT] rt_path_trace WxH RGBA16F radiance, depth into rt_depth (Slang, 9 binding(s))`;
- `[VKB-RT] denoiser requested=DLSS_RR active=off reason=auto_slang_path_has_no_gbuffer (...)`.

A `VK_ERROR_DEVICE_LOST`, validation error, or a black/half-black image is a failure of this first live path, not of the
fallback: rerun with `-Dbedray.pathTracer=legacy` to confirm the GLSL tracer still works, then send `latest.log`.

## 5. Known gaps of the Slang path (image quality / parity)

- no NRD / DLSS-RR G-buffer and no motion-vector output (G-buffer denoisers are disabled for this mode);
- no auto exposure or luminance statistics (`statsBuf` is not written; exposure is fixed at 1.0);
- one sample per pixel per frame (host `spp` is not looped), so `spp > 1` settings are effectively ignored while
  accumulation is off; temporal accumulation only helps when the camera is still;
- two-colour gradient sky instead of `rt_sky.glsl`; moon radiance 0 and `key.isMoon` is not mapped;
- no view-bob origin (primary rays start at the camera origin; bob projection is still used for the camera basis);
- block-material table is empty (`materialCount = 0`), so voxel material extensions are not sampled;
- debug views (normals/depth/uv/section) are not implemented; AUTO falls back to GLSL for them.

This is a first live path, not parity. Debug-view, auto-exposure, motion-vector and material-table work belong to the
next batch.
