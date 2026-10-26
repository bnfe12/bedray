# Iris/OpenGL GLSL on Bedray Vulkan — source-only development

Workstream: Iris Vulkan compatibility. Its source is also integrated into the Compute development branch;
package compilation does not supply a shaderpack compatibility claim. No shaderpack ZIP is bundled.
This work adds an opt-in **fullscreen post host**, not a replacement for the entire Iris renderer.
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
  up to eight distinct targets among `colortex0..15` (device `maxColorAttachments` permitting); final has one
  presentation output. Logical targets, output locations and physical image banks are separate.
- Target directives: `colortexNFormat` (GL names mapped to Vulkan, unsupported ones refused by name),
  `colortexNClear`/`colortexNClearColor`, history across frames for `Clear = false`, per-program
  `colortexNMipmapEnabled`, `GAUX4FORMAT` and the legacy names. See [the resource graph](IRIS_RESOURCE_GRAPH.md).
- Legacy fullscreen GLSL inputs, colortex0..15 with the gcolor/gdepth/gnormal/composite/gaux1..4 aliases,
  depthtex0/gdepthtex, and the existing scalar timing/size/near/far uniform contract. Unknown uniforms/features
  are rejected by name.
- Engine macros and world uniforms: see [world state](#engine-macros-and-world-uniforms) below.
- The host color image is updated only after the entire graph records successfully. Runtime code never
  substitutes a mock executor.
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

General packs requiring gbuffers, shadow maps, deferred/compute stages, integer targets, custom
images/textures/SSBO, required Iris features, `scale`/`blend`/`flip` properties, other world/lighting/material
uniforms, fragment coordinate layout overrides/front-face semantics or geometry/tessellation are still unsupported.
Pack property semantics are not silently discarded. Screen/slider/profile metadata and enabled expressions
are evaluated in the documented post dialect; other active renderer properties are refused. Frame generation and temporal upscaling are gated until matching
history/guide semantics exist.

## Startup wiring

Until this change nothing called the shader-host bootstrap, so the selected host was always native and the post
host could not activate whatever was configured. `RtxBootstrap` now runs `ShaderHostStartup` in its early hook,
before the device exists: it selects the host (and compiles the chosen pack when the post host is opted in),
detects ray-tracing-pipeline packs, and loads the optional custom RT stage. A failure there is logged and leaves
the native renderer in place. The F3 diag section shows a `shader_host` block: requested and active host, the
refusal reason of the chosen pack, the work the pack still needs, and the post runtime state.

The bootstrap path was exercised outside the game with the real classes: `iris-color-post` probes READY
(1 pass); Dirt RT is refused by the post host with its full list of unsupported features (SSBO, custom images,
gbuffers, `prepare`, compute composites, ray stages). Ray-tracing packs and user ray-query shaders are covered in
[the custom RT contract](CUSTOM_RT_SHADERS.md).

Shader sources are now handed to shaderc from heap memory. The previous overload encoded them on the 64 KiB
thread stack, which large packs exceed (`Out of stack space`; observed with Dirt RT's ray stages).

## Engine macros and world uniforms

Pack sources and `shaders.properties` are preprocessed with Iris's standard macros, stated only where this host can
state them truthfully at the early bootstrap (no device yet): `MC_VERSION` from the game's own `version.json` with
Iris's formatting (26.3 -> 260300), `MC_MIPMAP_LEVEL` from options.txt, `MC_OS_*`, `IS_IRIS`, `MC_NORMAL_MAP`,
`MC_SPECULAR_MAP`, `MC_RENDER_QUALITY`/`MC_SHADOW_QUALITY` 1.0, `MC_HAND_DEPTH` 0.125, `MC_RENDER_STAGE_*`
(Iris's phase ordinals) and `DH_BLOCK_*`. `MC_GL_VERSION`/`MC_GLSL_VERSION` are 450, the dialect this host
translates to, and `MAX_COLOR_BUFFERS` is 16. `IRIS_VERSION`, `MC_GL_VENDOR_*`, `MC_GL_RENDERER_*` and GL extension
macros are not defined; a pack that references them still builds and the status names them. A macro that cannot be
stated (for example an unreadable game version) refuses every pack that references it, by name.

World uniforms live in one std140 block at binding 18, written once per frame with `vkCmdUpdateBuffer` from the live
game on the render thread, the same calls Iris 1.11.7 makes on 26.3 (checked with javap):

| Uniform | Source |
| --- | --- |
| `worldTime`, `worldDay` | default clock, `% 24000` / `/ 24000`; fixed-time dimensions report 0 except End/Nether |
| `moonPhase`, `sunAngle`, `shadowAngle` | camera environment attributes MOON_PHASE, SUN_ANGLE, MOON_ANGLE |
| `sunPosition`, `moonPosition`, `shadowLightPosition`, `upPosition` | gbufferModelView x Y(-90) x Z(`sunPathRotation`) x X(angle) applied to (0, 100, 0, 1); shadow light = sun by day, moon by night |
| `rainStrength`, `wetness` | rain level (clamped); wetness smoothed with `wetnessHalflife`/`drynessHalflife` (tenths of a second) |
| `skyColor` | environment attribute SKY_COLOR |
| `fogColor`, `fogStart`, `fogEnd` | this frame's camera fog data (color, environmental start/end) |
| `isEyeInWater` | fluid in camera: 1 water, 2 lava (not for spectators), 3 powder snow |
| `eyeBrightness`, `eyeBrightnessSmooth` | block/sky light at the eye x 16; smoothed with `eyeBrightnessHalflife` |
| `nightVision`, `blindness`, `darknessFactor` | mob effects as Iris reads them (conduit power counts as night vision) |

`cameraPosition`/`previousCameraPosition` and the matrices stay in the frame block; `frameCounter`, `frameTime`,
`frameTimeCounter`, `viewWidth`, `viewHeight`, `aspectRatio`, `near`, `far` stay push constants. A declaration with a
different type than Iris's (`uniform float worldTime`) is refused by name, as are uniforms outside these tables.
If a value cannot be read in a frame, programs that read it are skipped for that frame and the status names it;
nothing is substituted. Smoothing restarts when the post resources are retired. In the End, Iris can move the shadow
light to the end flash through a pack setting; this host refuses unknown properties, so that setting cannot be on.
The [world example](../examples/iris-world-post/README.md) reads most of the block.

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

Target formats, clear controls, persistence, mipmaps, engine macros and the world block are implemented
(compile/unit-test evidence only). Next gates are deferred/compute passes and
geometry/shadow integration. They require
runtime evidence before any supported-pack list or release.
