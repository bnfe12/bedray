# Iris/OpenGL GLSL on Bedray Vulkan — source-only development

Branch: `codex/iris-vulkan`. No JAR, shaderpack ZIP, release or published compatibility claim is supplied.
This branch adds an opt-in **fullscreen post host**, not a replacement for the entire Iris renderer.
The native Bedray world renderer remains the source of the color image. The host records Vulkan work
on Bedray's existing device/queue and before hand/HUD rendering; it never creates another window,
device or swapchain. The Iris mod itself does not gain a Vulkan renderer by installing its JAR.

## Implemented source scope

- Pack discovery and shaderc preprocessing feed GLSL translation and a Vulkan executor. Includes are
  read only in live branches, with a depth/read/total-source budget and pack-root path confinement.
  Macro-expanded declarations and active draw-buffer directives are analyzed after preprocessing.
  Source options, read-only saved overrides, inherited profiles and conditional renderer properties
  are resolved before translation; see [the option contract](IRIS_OPTIONS.md).
- Iris's sibling `<pack filename>.txt` supplies saved overrides. A session profile can be selected with
  `-Dbedray.iris.profile=NAME`; explicit saved values win. Profile matching and `program.*.enabled`
  choose the active pass list before compilation; disabled passes create no pipelines and record no draws.
- Vertex/fragment interfaces are checked together for type, array shape, location and interpolation.
  Matrices and fixed-size arrays reserve their full location span; the post dialect caps it at 16.
  Top-level declarations are read as statements, including multiline and same-line declarations.
- Root `composite*.vsh/fsh` and one last `final.vsh/fsh`, at most 32 passes. Composite passes can write
  up to four distinct targets among `colortex0..7`; final has one presentation output. Logical targets,
  output locations and physical image banks are separate. See [the resource graph](IRIS_RESOURCE_GRAPH.md).
- Legacy fullscreen GLSL inputs, colortex0..7, gcolor and depthtex0/gdepthtex aliases, and the existing
  scalar timing/size/near/far uniform contract. Unknown uniforms/features are rejected by name.
- Actual target format is used for ping-pong images and pipelines. The host color image is updated
  only after the entire graph records successfully. Runtime code never substitutes a mock executor.
- Reverse-Z raster depth is converted to conventional 0-near/1-far depth in an R32F compute image.
  A typed world frame identifies actual producer, world/frame stamp, device, camera and optional depth
  encoding. Depth-reading packs are suspended without a matching guide; raster depth is never substituted
  for RT/voxel depth. See [the frame/guide contract](IRIS_FRAME_GUIDES.md).
- Current and previous gbuffer projection/model-view matrices, their current inverses, and camera
  positions use a 416-byte std140 UBO updated once in ordered GPU commands. Continuous CPU camera
  history is invalidated on world/device/producer/extent changes, skipped frames and camera cuts.
  Shaders reading unavailable camera/history fields wait for data; no identity/zero stand-ins are advertised.
- Native internal targets already retain GL's Y orientation: Minecraft flips Y on presentation.
  Post images keep their rows unchanged. A vertex entry wrapper converts GL clip Z `[-w,w]` to
  Vulkan `[0,w]`, including early returns from pack main; ordinary `gl_FragCoord` is available.
  Explicit fragment coordinate layout overrides, fragment-depth writes and front-face/point builtins
  are outside this dialect. Bilinear sampling capability is checked for the color format.
- Stable per-pass descriptors are initialized once for each slot. Replacing graph, extent, format,
  device, depth view or world retires resources only after queued host work is submitted.
- Frame command buffers are finalized on both success and failure. Initialization/recording failures
  latch to a native fallback for the session; cleanup failures require restart. Runtime status is
  separate from successful pack compilation.

General packs requiring gbuffers, shadow maps, deferred/compute stages, more than four simultaneous color outputs,
custom images/textures/SSBO, required Iris features, persistent color
buffers, mipmap/format directives, other world/lighting/material uniforms, fragment coordinate layout overrides/front-face semantics or
geometry/tessellation are still unsupported.
Pack property semantics are not silently discarded. Screen/slider/profile metadata and enabled expressions
are evaluated in the documented post dialect; other active renderer properties are refused. Frame generation and temporal upscaling are gated until matching
history/guide semantics exist.

## Future development activation

With matching source built into a local development instance, choose an unpackaged shader folder
(or an existing user-owned pack) through `config/iris.properties`, or use:

```text
-Dvkrtx.shader_host=iris
-Dbedray.iris.post=true
-Dvkrtx.shaderhost.pack=iris-color-post
```

The original [color-only source example](../examples/iris-color-post/README.md) demonstrates the narrow
fullscreen contract. It is not an in-game test result. Pack changes currently require restart.

## Compilation and evidence

Compile Java only (JDK 25 and local game/loader compile dependencies from BUILDING.md):

```text
gradlew :common:compileJava :fabric:compileJava :neoforge:compileJava
```

Compile baseline: JDK 25.0.5, Gradle 9.5.1, Minecraft 26.3, Sodium 0.9.2, Iris 1.11.7, Fabric loader 0.19.5 and NeoForge 26.3.0.57-beta. Local dependency JARs are compile inputs only, excluded from Git and not published as product artifacts.

Java compilation, the new depth shader's GLSL-to-SPIR-V compilation, and the original color-only example's GLSL 120 → Vulkan GLSL 450 → SPIR-V compilation succeeded in the cloud environment.
No tests, GPU dispatch or game sessions were run for this change, per the requested source-only workflow.
Compilation does not prove depth reconstruction, orientation, lifecycle, output color or pack compatibility.

The follow-up frontend milestone also compiled the example through the actual Java shaderc include
callbacks, macro preprocessing, paired translation and SPIR-V compilation. It adds no test execution,
GPU work or product JAR. See [the Vitrail source study](VITRAIL_STUDY.md) for the architecture decisions
and the distinction between this post subset and a complete shader engine.

The options milestone compiled the example with source defaults (one final pass) and its BRIGHT profile
(composite plus final). Both configurations compiled through properties/option resolution to SPIR-V.
This is compiler evidence only; it does not establish image correctness or full Iris option parity.

The typed-frame milestone compiled the depth source example in DISTANCE and MOTION configurations,
including all eight frame UBO fields. The updated depth compute shader compiled with its 128-byte
push layout. Hardware RT publishes color provenance only after recording/submission, while raster
depth conversion uses the exact current projection. No GPU correctness result is claimed.

The current-frame resource milestone compiled an original three-pass MRT example through the real
scanner/preprocessor/translator and SPIR-V compiler. It routes output locations into logical targets
3 and 2, recombines them into 0, then records a separate final output. Existing color NORMAL/BRIGHT,
depth DISTANCE/MOTION and the depth compute source also compiled. No tests or GPU/game runs were made.

## Merge into Bedray with the voxel branch

Keep `common/.../shaderhost` as the compatibility/post-processing layer. Voxel traversal remains in
`compute-common`, reached through `renderer-api`; no Iris or private game classes belong in that module.
After the branches are reviewed, merge the frame hooks in this exact order:

1. Host frame/lifecycle and request handling.
2. One world image producer: raster/native RT, or the compute provider.
3. Iris fullscreen post stage over the producer's completed image.
4. Hand/HUD, upscaling/presentation in their existing host phases.

The merged hook begins `WorldFrames` once, then selects an explicit producer from the result of
`ComputeRenderBridge.renderDiagnostic()` or `RtFrameRenderer.displayedThisFrame(target)`. Pass
`VOXEL_COMPUTE` for a successful live compute image, `SYNTHETIC` for its artificial scene, `HARDWARE_RT`
for an actually copied RT image, and `RASTER` otherwise. Requested settings are not provenance.
The typed `renderFrame(WorldFrame)` seam can consume explicitly supplied matching guides. Current
native RT/voxel adapters remain color-only, so depth-reading post packs stay unavailable on them.
The host owns all target formats, command submission and retirement. Resolve the three shared mixin/UI
files once; do not retain duplicate frame hooks or a second executor copied from the old development repo.

Next gates are target formats/clear controls/persistence/mips, world/device-aware engine macros and
additional world uniforms, then geometry/shadow/deferred integration. They require
runtime evidence before any supported-pack list or release.
