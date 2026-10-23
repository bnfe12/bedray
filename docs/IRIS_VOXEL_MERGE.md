# Iris / voxel source integration

Source baselines checked on 2026-10-23:

- Iris `2b7b7d5a370d4a1031b7bfa1539a10162e8602d3` (typed frames, camera/depth uniforms and owned sampled fallback).
- Voxel `24d721a272295be99ffc0b70aec2ae7ae8340ff7` (atlas albedo and guarded material retirement).

A temporary detached linked worktree successfully compiled the combined sources for common, renderer-api,
Fabric, NeoForge and both compute companions. The development branches remain separate. No tests,
GPU dispatch, game sessions, JAR or shaderpack ZIP were produced by this check. The temporary worktree
was removed after compilation; this is not an in-game compatibility result.

## Shared hooks

The merge has conflicts in three Java files and the branch document. The
[hook patch](patches/voxel-iris-frame-hooks.patch) contains their resolution relative to the voxel
baseline, plus a small `ComputeRenderBridge.appliedSceneMode()` accessor:

- Frame begin: start `WorldFrames`, retire Iris on world/selection changes, apply compute requests.
- World recording: capture compute inputs, use the successful `renderDiagnostic()` result, otherwise
  run the existing RT/raster path. Run Iris once after that result, before the hand/HUD.
- Producer: a successful compute frame uses its frame-boundary applied scene to distinguish live voxel
  from synthetic color. Otherwise `RtFrameRenderer.displayedThisFrame()` distinguishes actual RT output
  from raster. Pending UI requests or hardware capability alone do not identify the produced image.
- Device close: retire Iris and compute before the existing frame-generation/device teardown.
- Settings: preserve both existing groups in the same host configuration screen.

The projection reset fix is shared with the newer voxel source and merges automatically. Keep the new
exact raster projection accessor and the actual RT projection publication as well. The common frame
contract stays in the host; compute algorithms retain their renderer-api boundary.

## Applying the recorded resolution

Use a new integration worktree based on the exact voxel baseline above. After merging the Iris baseline
with `git merge --no-commit --no-ff`, restore the following paths from the **voxel side** of that merge,
then apply the patch and stage the resolved files:

```text
common/src/main/java/io/github/andrexarlay/vulkancompat/compute/ComputeRenderBridge.java
common/src/main/java/io/github/andrexarlay/vulkancompat/mixin/framegen/MixinVulkanDeviceFrameGen.java
common/src/main/java/io/github/andrexarlay/vulkancompat/mixin/rtx/MixinGameRenderer.java
common/src/main/java/io/github/andrexarlay/vulkancompat/sodium/BedrayConfigScreen.java
docs/DEVELOPMENT_BRANCHES.md
```

The patch is a merge aid, not a standalone port of Iris. Later code changes require another review.
Its application was also checked against the exact voxel files with `git apply --check --whitespace=error`.

Compilation tasks:

```text
:renderer-api:compileJava :common:compileJava :compute-common:compileJava
:fabric:compileJava :neoforge:compileJava :compute-fabric:compileJava :compute-neoforge:compileJava
```

Environment: JDK 25.0.5, Gradle 9.5.1, Minecraft 26.3, Sodium 0.9.2, Iris 1.11.7,
Fabric loader 0.19.5, NeoForge 26.3.0.57-beta. Dependencies remain ignored/local.

Only raster currently publishes matching depth. RT and voxel color-only post remains possible;
depth-reading packs wait for guides from the same producer/frame. Camera history here contains CPU
matrices and positions, not object motion or previous color. MRT/resource graphs and broader Iris
shaderpack compatibility remain subsequent work. The independent cache/DXGI branch was reviewed
separately; its LWJGL source patch is described in [the frame contract](IRIS_FRAME_GUIDES.md).
