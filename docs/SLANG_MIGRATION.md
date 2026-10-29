# Bedray 2.0 — Slang shader architecture

## Current implementation priority

Existing-feature transfers in the current working tree:

* Sodium compact-vertex decoding now uses `passes/sodium_dequantize.slang` AOT
  and a core-owned pipeline instead of Shaderc/Java pipeline creation. It keeps
  the planar arena contract and the compute-to-AS/attribute-read dependencies.
  It requires the enabled BDA and shaderInt64 features of the current RT path.
  Descriptor-free dispatches retain one program use per command buffer until
  host-proven completion/discard. Opt-in native scene mode owns scene allocation
  and BLAS/TLAS builds; the Java scene remains the reference/default. World
  construction rolls back allocated resources on
  failure before publishing the world.
* Spatial ReSTIR's existing two checker phases are recorded through one native
  `bedray_core_render_phases` call. C++ owns the phase-word updates and barriers;
  Java supplies scene resources and the initial parameters. The native coarse
  path also uses an opaque frame-graph node and scheduler; its private histories
  remain owned by PathTracePass, as recorded in MIGRATION_INTEGRATION_DECISIONS.md.

Finish migrating the existing renderer functionality into the new Slang/C++
architecture first, starting with path tracing. Java retains Minecraft capture,
loader integration and host submission/completion evidence. New rendering
methods, vendor extensions and additional stack technologies are deferred until
the existing functionality has migrated. The architecture manifesto remains the
target design; its advanced features are not prerequisites for this transfer.

The path atlas sampler now belongs to the C++ core and is borrowed by the Java
adapter through `bedray_core_atlas_sampler`. The core releases it after all its
recordings and descriptor pools. This is an additive export with unchanged ABI
structure layouts; the updated Java host requires the matching new DLL.

## Scene query migration

Scene query migration: `contracts/scene_tracer.slang` is the single geometric
interface for primary hits, nearest hits, visibility, distance and surface
motion. Path tracing and reuse/visibility consumers now constrain their
backends with `ISceneTracer`. `traversal/traversal.slang` only re-exports that
contract. Materials, cutout/transmission filtering and BSDF evaluation remain
outside the geometry interface. The software tracer name refers to the existing
Bitmask implementation rather than a second decoder with fabricated roughness.

Hybrid queries select the nearest voxel/mesh surface, preserve primary-ray
filtering and forward motion by hit kind. Voxel providers must return Voxel
hits; hardware providers must return mesh hits. The voxel distance is a per-ray
budget, not camera distance or a guarantee of far-world coverage. Empty voxel
intervals are skipped. The shader harness in
`common/src/test/resources/shaders/slang/verify_scene_tracer.slang` checks generic
code generation only; it does not execute a GPU dispatch or prove visual output.

The typed Radiance Cascades atlas has the same kind of harness in
`common/src/test/resources/shaders/slang/verify_rc_typed.slang`. It instantiates
the typed store API against both `RWStructuredBuffer<RCSegment>` and
`StructuredBuffer<RCSegment>`; it is a compile check, not a stride, layout or
runtime test. The legacy float4 atlas is a different record format and is not
imported there. `verify_rc_environment.slang` keeps the opt-in environment
policy reachable: the default is `Disabled`, a zero boundary is rejected, a
short window cannot add sky, and a positive boundary that `tFar` reaches can.
`verify_rc_trace_api.slang` instantiates every public typed trace entry with
mock backends, including the provisional history and ownership proposal.
`verify_bedray_roots.slang` compiles the curated `bedray.slang` and the opt-in
`bedray_legacy.slang` together so the typed and legacy resolve names cannot
silently converge.

The shader module map, dependency rules, host contract and extension checklist
are in
[`common/src/main/resources/shaders/slang/ARCHITECTURE.md`](../common/src/main/resources/shaders/slang/ARCHITECTURE.md).
The typed RC contract is in
[`radiance_cascades/RC_TRACE_API.md`](../common/src/main/resources/shaders/slang/radiance_cascades/RC_TRACE_API.md).

Architecture target: [ARCHITECTURE_MANIFESTO.md](ARCHITECTURE_MANIFESTO.md).
Audit findings and their original source-only evidence are recorded in
[AUDIT_DEFECTS_AND_ISSUES.md](AUDIT_DEFECTS_AND_ISSUES.md); each finding still
needs confirmation against the current working tree before being marked fixed.

The native core now builds as C++23. Incoming modules from `81a06a2` remain
isolated drafts, not active path-tracing implementations. The default path
keeps the Java reference. The opt-in native path uses the embedded
scene owner, graph/scheduler and explicit completion/discard retirement. Scoped
build/CPU evidence is in VERIFICATION_LOG.md; GPU/game and complete shader parity
remain separate acceptance work.

This document replaces the first Slang migration draft.  The first draft
described a 1:1 port of the existing GLSL.  Bedray 2.0 is a new shader core:
the old GLSL stays in the tree as a behavioural reference, not as a source to
copy line by line.

**Current whole-migration evidence: L0 — source and build wiring.** Prior
agents reported scoped compilation and unit tests for particular revisions;
those results do not establish that the current complete tree builds. Existing
bloom now requires AOT Slang and fails when unavailable, but other legacy
GLSL consumers still exist and must be migrated. A compile or test is not proof
of rendering in Minecraft; that requires a fresh matching live run and evidence.

## Design rules

1. **Contracts before passes.**  A pass may only depend on a contract module
   (`contracts/`) and on an interface (`ISceneTracer`, `IDirectLightSampler`).
   It must not read a legacy `Section`/`Surface` record directly.
2. **No GLSL include chains, no `#ifdef` variants.**  A backend is a Slang
   type that implements `ISceneTracer`; the host specializes it at AOT time.
   Capability differences are data in `RenderPlan`, not preprocessor branches.
3. **No GPU-name checks in shaders.**  The host turns a capability survey into
   `RenderPlan.capabilityMask`; shader code asks `hasCapability`.
4. **One module = one concern.**  Material rules do not live in traversal;
   reservoir math does not live in the light sampler; cascade addressing does
   not live in the trace pass.
5. **The old GLSL is a reference.**  Do not delete it and do not copy its
   include graph.  When behaviour is unclear, read it; when writing new code,
   use the new contracts.
6. **No binaries in git.**  SDKs, generated SPIR-V, DLLs and JARs stay out of
   the repository.  `*.spv`, `*.exe`, `*.dll` and `*.jar` are already ignored.

## Native core (ABI v2)

The split is deliberate: Java owns everything that must know about
Minecraft, Fabric/NeoForge, Sodium and the game's Vulkan lifecycle and device;
C++ owns the renderer, its resources and their GPU lifetime.

```
Java (common/src/main/java/.../rt/)
  SlangRuntime / SlangProgram / SlangPassLibrary   load AOT artifacts, address bindings by name
  BedrayCoreNative / BedrayCoreLayouts             Java 25 FFM downcalls, ABI layout mirror
  RtBloomPass                                      first integrated consumer

C++ (native/bedray_core/)
  core.cpp                                         descriptor sets, pipelines, dispatch recording
```

`bedray_core.dll` exports a versioned C ABI (`include/bedray_core.h`,
`BEDRAY_CORE_ABI_VERSION = 2`).  The core never creates a `VkInstance`,
`VkDevice`, queue or swapchain, never submits and never waits; it records
compute dispatches into the command buffer supplied by the host.

Lifecycle and ownership:

1. `bedray_core_create` binds the host's instance/device/queue and the two
   `vkGet*ProcAddr` function pointers; the core loads the Vulkan entry points
   it needs itself and does not link `vulkan-1.lib`.
2. `bedray_core_register_program` takes one SPIR-V module plus the reflected
   descriptor list and push-constant size.  The core creates the shader
   module, set layout, pipeline layout and compute pipeline.
3. `bedray_core_render_frame` validates the resources, acquires an exclusive
   descriptor pool/set for that dispatch, updates the descriptors, binds the
   pipeline, pushes constants and records `vkCmdDispatch`. It inserts no
   barriers itself: native graph/host integration must supply synchronization.
4. `bedray_core_release_recording(command_buffer)` releases the descriptor
   allocations recorded into that command buffer. Up to eight pool/set pairs
   per live program are cached; excess or retired-program allocations are
   destroyed. Cached sets are fully overwritten before their next dispatch.
   The host calls release only after all
   GPU submissions of that command buffer completed (or the recording was
   discarded), and before re-recording the same handle.  The engine reuses a
   transient handle after `VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT = 2`
   submits. Handle reuse alone is not proof of GPU completion; the integration
   must establish completion or discarded recording before release.
5. `bedray_core_destroy_program` retires a program; the core defers the
   actual `vkDestroy*` while a recording still references it.
   `bedray_core_destroy` refuses while any recording remains.

The first Windows x64 build:

```powershell
cmake -S native/bedray_core -B native/bedray_core/build -G "Visual Studio 17 2022" -A x64 `
      -DBEDRAY_VULKAN_HEADERS=C:/Dev/Sky/tools/sdk/Vulkan-Headers/include
cmake --build native/bedray_core/build --config Release
native/bedray_core/build/Release/bedray_abi_probe.exe
```

The probe is the ABI guard: it prints the struct sizes and the version
reported by `bedray_core_abi_info` and fails on drift.  The DLL and the build
directory are gitignored; `BedrayCoreLocator` finds the installed DLL through
`NativeLibraries`, never by a guessed path.

## Module map

```
common/src/main/resources/shaders/slang/
├── bedray.slang                       root: curated typed public surface
├── bedray_legacy.slang                opt-in legacy compatibility root
├── contracts/
│   ├── contracts.slang                aggregate import
│   ├── frame.slang                    FrameConstants, CameraState, SunState
│   ├── material.slang                 MaterialSample, AlphaMode, source flags
│   ├── geometry.slang                 VoxelSection, VoxelGrid, GeometrySection
│   ├── hit.slang                      Ray, SurfaceHit, VisibilityResult, RayPayload
│   ├── history.slang                  HistoryHeader/Sample/Policy, reprojection
│   ├── math.slang                     constants, luminance, safeNormalize, frames
│   └── light.slang                    LightSample, LightReceiver, ILightSampler
├── traversal/
│   ├── traversal.slang                ISceneTracer
│   ├── voxel_traversal.slang          BitmaskVoxelTraversal + VoxelWorld
│   ├── hardware_traversal.slang       HardwareTraversal (VK_KHR_ray_query)
│   ├── legacy_section_traversal.slang LegacySectionTraversal (RtWorld 64-byte table + device-address arena)
│   ├── entity_geometry.slang          the captured entity ring: triangle decode, atlas texels, part flags, motion
│   └── hybrid_traversal.slang         HybridTraversal<V, H>
├── voxel/
│   └── voxel.slang                    single-section Bitmask HDDA
├── light/
│   ├── block_emitters.slang           BlockEmitterRecord (the RtEmitters upload) + legacy-look emitter NEE
│   ├── emitters.slang                 EmitterRecord + UniformEmitterSampler; no host feeds this record yet
│   ├── sun.slang                      SunMoonSampler
│   └── sky.slang                      IEnvironmentSampler + GradientSky + SunMoonSky (dome with the sun/moon discs)
├── surface/
│   ├── surface.slang                  MaterialAtlas, BlockMaterialTable, decodeSurface
│   └── visibility.slang               filtered shadow walk (holes + transmittance)
├── shading/
│   ├── microfacet.slang               canonical GGX/Fresnel math
│   └── bsdf.slang                     diffuse/GGX/transmission lobes
├── pathtrace/
│   └── path_tracer.slang              iterative path loop, NEE, Russian roulette
├── restir/
│   ├── restir_di.slang                DIReservoir storage + temporal/spatial merge
│   ├── di_block_emitters.slang        block-emitter adapter: batch, reprojected history, writeback, occlusion decay
│   ├── spatial_block_emitters.slang   neighbour pooling over those records: checker phases, surface gates, merge
│   └── direct_light.slang             compatibility re-export of light/emitters.slang
├── radiance_cascades/
│   ├── rc_atlas_layout.slang          shared atlas addressing (record-format neutral)
│   ├── interval_transport.slang       RCSegment, validity flags, front-to-back composition
│   ├── segment_record.slang           typed whole-record read/write and ray composition
│   ├── probe_resolve.slang            typed probe radiance and completeness
│   ├── segment_store.slang            facade over segment_record + probe_resolve
│   ├── rc_trace_options.slang         trace options, events, result, entry status, environment policy
│   ├── rc_trace_shade.slang           first-surface emission + NEE
│   ├── rc_trace_walk.slang            segment finalization + interval walk
│   ├── rc_trace_dispatch.slang        store, probe and dispatch entries
│   ├── rc_trace_history.slang         provisional temporal history (opt-in)
│   ├── rc_trace_ownership.slang       distance-band proposal (opt-in)
│   ├── rc_trace.slang                 public facade over the six rc_trace_* modules
│   ├── legacy_float4_store.slang      legacy float4 record + confidence-weighted resolve
│   ├── radiance_cascades_store.slang  legacy compatibility facade
│   └── rc_trace_segments.slang        obsolete experiment, not exported, not compiled by default
├── radiance_cascades.slang            cascade/probe/ray addressing (existing)
├── render_plan.slang                  RenderPlan, modes, capability mask (existing)
├── types.slang, bindings.slang        legacy 1:1 scaffold, kept as reference
├── sampling.slang, brdf.slang         pure math, still used by the new modules
├── tonemap.slang, ray_backend.slang   legacy scaffold, kept as reference
├── tonemap_curve.slang                peak-channel tone curve shared by bloom apply
├── bloom/
│   └── bloom.slang                    bloom + sun-glare behaviour, no stage switch
└── passes/
    ├── bloom_prefilter_down.slang     one file = one compute entry point named `main`
    ├── bloom_down.slang
    ├── bloom_up.slang
    ├── bloom_apply.slang
    ├── composite.slang
    ├── atrous.slang                   filtered-a-trous depth/albedo denoise
    ├── temporal_accumulate.slang      running mean over the raster path
    ├── velocity_reproject.slang       G-buffer reprojection for that mean
    ├── ssr.slang                      screen-space reflections
    ├── particles_composite.slang      particle layer over the composite
    ├── volumetric_march.slang         volumetric radiance cascades
    ├── volumetric_apply.slang
    ├── path_trace.slang               first path-trace entry over LegacySectionTraversal
    ├── path_display.slang             exposure, tone map, accumulation of the path radiance
    └── restir_spatial.slang           neighbour pooling over the reservoirs path_trace wrote
```

`passes/` is the AOT entry-point root: `compileSlang` compiles every
`**/*.slang` below it, so a new stage is a new file, not another `#define` in
an existing shader.  The four bloom files share `bloom/bloom.slang` and
`tonemap_curve.slang`; a plain `import` is not transitive, so an entry file
imports every module whose symbols it uses.  A module may re-export the types in
its public signatures with `__exported import` (the typed RC store does this for
`RCSegment` and `RadianceCascadeLayout`); that is an explicit API decision, not
automatic transitive visibility.

`MaterialAtlas` now holds combined `Sampler2D` fields, not `Texture2D` +
`SamplerState`: ABI v2 binds `COMBINED_IMAGE_SAMPLER` and has no separate
sampler descriptor.  Storage images carry an explicit `vk::image_format`
matching the view the host binds (RGBA8 display targets, RGBA16F bloom mips,
RGBA16F path-trace radiance, R32F depth), which also keeps a pass off the
optional `shaderStorageImageRead/WriteWithoutFormat` features.

`import voxel;` does **not** search subdirectories.  The root and the new
modules use quoted imports (`import "voxel/voxel.slang";`), so a single
`-I .../shaders/slang` include path is enough.

## Data contracts

| Contract | Owns | Must not own |
|---|---|---|
| `frame.slang` | camera, sun/moon, atmosphere, exposure, frame index/flags | descriptor bindings, plan selection |
| `material.slang` | decoded albedo/roughness/f0/emission/emission-mask/alpha mode/optical fields | atlas sampling, traversal decisions |
| `geometry.slang` | `VoxelSection` occupancy masks, `VoxelGrid`, `GeometrySection` ranges | section directory buffers, TLAS handles |
| `hit.slang` | `Ray`, `SurfaceHit`, `VisibilityResult`, `PathVertex`, `RayPayload` | backend-specific hit structures |
| `history.slang` | previous view-projection, reprojection, validation, blend weight | per-feature reservoir records |
| `light.slang` | `LightSample`, `LightEvaluation`, `LightReceiver`, `ILightSampler` | reservoir math, traversal handles |

Voxel contract: a 16³ section is 64 macro cells of 4³ voxels, one `uint64_t`
mask per cell.  A block edit flips one bit; no mesh or SDF rebuild.  The
single-section HDDA lives in `voxel/voxel.slang`; the world-level section DDA
and the hash/dense directory live in `traversal/voxel_traversal.slang`.

## Traversal backends

| Backend | Type | Scene | Use |
|---|---|---|---|
| Software | `BitmaskVoxelTraversal` | `VoxelWorld` (sections + directory) | no RT cores, iGPU, GTX |
| Hardware | `HardwareTraversal` | TLAS + instance mask | RTX 20/30/40/50, RDNA 2+, Arc |
| Hybrid | `HybridTraversal<V, H>` | voxel near + TLAS far | Voxel + RT Hybrid mode |

All three implement `ISceneTracer`: `traceClosest`, `traceVisibility`,
`traceDistance`.  Material alpha tests are **not** in traversal; the material
stage applies `materialBlocksVisibility` / `materialAcceptsAlpha` so voxel and
hardware paths agree on foliage and glass.

## Integrated passes

| Pass | Slang entry files | Host | State |
|---|---|---|---|
| Bloom + sun glare | `passes/bloom_prefilter_down`, `bloom_down`, `bloom_up`, `bloom_apply` | `RtBloomPass` (existing WIP) | Requires AOT Slang; mip and sampler ownership remains in Java and must move to C++. See [BLOOM_CPP_INTEGRATION_PLAN.md](BLOOM_CPP_INTEGRATION_PLAN.md); no live validation. |
| Composite | `passes/composite.slang` | `RtCompositePipeline` | AOT-compiled, but the host path is disabled (`LEGACY_RASTER_ENTITY_COMPOSITE = false`); it is not proof of the active renderer. |
| Path tracing | `passes/path_trace.slang`, `passes/restir_spatial.slang`, `passes/path_display.slang` | `RtSlangPathPass` → `RtPathTraceSlangPass` + `RtSpatialReuseSlangPass` (optional); opt-in native pass in `bedray_core` | Selected by Path Tracing mode in `RtFrameRenderer`; `AUTO` keeps the legacy GLSL `rt_path.comp` when the AOT passes, device capabilities or a G-buffer denoiser block it. Reads the existing TLAS and the 64-byte `RtWorld` section table through `LegacySectionTraversal`; requires `CAP_RAY_QUERY \| CAP_BUFFER_DEVICE_ADDRESS \| CAP_SHADER_INT64`. `-Dvkbridge.rt.nativePathTrace=true` moves the same three dispatches into the C++ pass when the DLL exports the additive ABI; the Java path stays default. No G-buffer, auto exposure, `rt_sky.glsl` or view-bob offset yet; no live-frame validation is recorded here. |

The host addresses bindings by Slang parameter name (`srcTex`, `dstImg`,
`finalImg`, `depthTex`), and `RtBloomPass` validates the generated manifest
against the resources it can supply before the first dispatch.  A shader edit
that moves a binding or changes the 128-byte block therefore falls back to
GLSL with a log line instead of binding the wrong image.

### Path-trace host contract

The generated manifest is the authority; this table records what the Java host
supplies, and `RtPathTraceSlangPass` validates the generated manifest against it
before the first dispatch.

| Name | Binding | Host resource |
|---|---|---|
| `tlas` | 0 | the frame TLAS (`ACCELERATION_STRUCTURE_KHR`) |
| `outImage` | 1 | **new** RGBA16F storage image (`STORAGE_BIT \| SAMPLED_BIT`), linear radiance — not the legacy RGBA8 display target |
| `sections` | 2 | the existing 64-byte-per-slot `RtWorld` section table |
| `albedoAtlas` | 3 | block atlas sampler (legacy binding 3) |
| `outMotion` | 5 | RGBA16F storage image the tracer owns, sized with the radiance: rgb is the primary surface's own displacement from last frame, `a` marks it as a moving entity part; `path_display` binds the same image as `motion` |
| `materialAtlas` | 9 | LabPBR `_s` sampler (legacy binding 9) |
| `normalAtlas` | 10 | LabPBR `_n` sampler (legacy binding 10) |
| `outDepth` | 12 | R32F storage image; first-hit distance, `1e6` on a miss |
| `frame` | 16 | 608-byte `FrameConstants` uniform buffer, one upload per frame |
| `blockMaterials` | 17 | dummy `BlockMaterial` table until voxel materials exist: allocate at least one 64-byte element and push `materialCount = 0` |
| `emitters` | 18 | the `RtEmitters` ring buffer bound at the current segment offset — the same 96-byte records the legacy `rt_path.comp` reads, so one upload serves both paths; a dummy element when the list is empty |
| `reservoirs` | 19 | the ReSTIR DI ring of `RtSlangPathPass`: two halves of one 64-byte `DIReservoir` per pixel (`2 * width * height * 64`), zeroed when the light list or the render size changes; a one-record dummy when the branch is off |
| `entityPositions` | 20 | this frame's ring slot of captured vertex positions, 12 bytes per vertex (a `float3` as three float bit-patterns), bound for `frameVertices` elements |
| `entityIndices` | 21 | this frame's ring slot of triangle indices, three `uint` per primitive; the bound length is the triangle count the decoder reads |
| `entityAttrs` | 22 | the same slot of the `uint4` per vertex: packed colour, light (`block \| slot << 8 \| sky << 16 \| flags << 24`), `u` and `v` as float bits |
| `entityRects` | 23 | the `uint4` per RT entity atlas slot - rect, size and the authored-PBR half - so a held iron sword comes out metallic |
| `entityTexels` | 24 | the square ABGR atlas `EntityTextureAtlas` uploads |
| `entityPrevious` | 25 | the same ring slot one frame earlier - same offset, same length as `entityPositions`, so a triangle the previous frame did not have answers no motion rather than reading past the binding |

The push block is 112 bytes.  It carries sampling and path limits plus the
emitter tail (`emitterCount`, `emitterProbability`, and `emitterCameraRelative`,
whose xyz is the camera minus the origin the list was rebuilt at and whose w is
the candidates per call), the ReSTIR DI tail (`diCameraDelta`, the raw camera
delta of the frame, and `diState`, whose x holds the `PATH_TRACE_DI_*` bits and
whose y is the half of the ring this dispatch writes, chosen and advanced by the
host) and `entityState` (x: atlas
texels per side, 0 for none; yz: the first-person body's primitive range).  The
six entity buffers bind at the ring slot's own offset and length — this frame's
positions and the same vertices one frame earlier, indices, attributes, atlas
rects and atlas texels — so a frame that captured no model binds a zeroed dummy
and cannot walk a stale slot;
`entityState.x == 0` is the shader's own off test for the atlas.  The pass writes
scene-referred radiance, and
`FrameConstants.exposure` is applied once by the display/tonemap pass, so
accumulation and denoise stay in linear HDR and exposure cannot be applied twice.  The pass requires `CAP_RAY_QUERY |
CAP_BUFFER_DEVICE_ADDRESS | CAP_SHADER_INT64` (517).  Path tracing uses its own
`SlangRuntime`, created from `SlangDeviceCapabilities.enabledMask`; the mask-0
runtime in `RtAtmosphere` belongs to bloom only. The final C++ renderer must
negotiate the features actually enabled on the game's device and select only
supported methods. If this path is unsupported, the Java selector reports the
reason; it no longer falls back to legacy GLSL `rt_path.comp`. The legacy ray
effects pass is likewise reported unavailable until its Slang replacement is
connected. The Slang volumetric passes compile but are not yet connected, so
god rays are currently reported unavailable.

#### Native C++ path-trace pass (opt-in)

`native/bedray_core/src/path_trace_pass.cpp` records the same three dispatches
through the core's existing `bedray_core_render_frame` /
`bedray_core_render_phases` path. The core still creates no device, queue,
swapchain or submission; the host passes its command buffer and the pass
records into it.

Ownership:

* **C++ pass:** radiance, motion, accumulation `[2]` and history-depth `[2]`
  images; per-recording `FrameConstants` upload; the 64-byte block-material and
  reservoir dummies; the 4/16-byte entity dummies; the trace, optional spatial
  and display dispatches; image transitions and entry/compute/exit barriers;
  per-recording image/buffer pins. Native DI owns its reservoir allocation,
  parity, extent changes, reset and history validity. External-ring ABI clients
  remain supported, but the current Java native backend requires NATIVE_DI.
* **C++ scene/core:** native mode owns section geometry, section table,
  BLAS/TLAS and the atlas sampler. `path_trace_scene` reads the native table;
  the Java reference scene/table has a separate traversal entry.
* **Java host:** Minecraft/Sodium/entity capture, settings/camera/POD preparation,
  borrowed game atlas views, output/depth and submission/completion evidence.
  Host entity and emitter capture storage is leased to exact recordings;
  neither modulo rings nor elapsed frame counts release it.
* **C++ pass, history:** the pass owns accumulation parity and
  `history_written`. The host signals a reset with `accum_frames == 0` or
  `BEDRAY_PATH_TRACE_FRAME_RESET_HISTORY`; a host resize also zeroes the host's
  sample count so the frame cannot read a just-recreated half.

The additive ABI is in `bedray_core.h` and mirrored by `BedrayCoreLayouts`:
`BedrayPathTraceBuffer` 32 B, `BedrayPathTraceRange` 24 B,
`BedrayPathTraceScene` 312 B (`sections` at 8, `albedo_view` 40,
`block_materials` 72, `emitters` 104, `reservoirs` 136, `entity_positions`
168), `BedrayPathTraceCamera` 416 B, `BedrayPathTraceLighting` 80 B,
`BedrayPathTraceAtmosphere` 80 B, `BedrayPathTracePath` 112 B
(`emitter_camera_relative` 48, `di_camera_delta` 64, `di_state` 80,
`entity_state` 96), `BedrayPathTraceFrameDesc` 1072 B (`scene` 32, `camera`
344, `lighting` 760, `atmosphere` 840, `path` 920, `frame_index` 1032,
`frame_time` 1048) and `BedrayPathTraceDesc` 80 B. None of the ABI v2 struct
layouts changes.

Lifetime: `bedray_core_release_recording` releases the pass's image/buffer pins
together with the core's descriptor sets, so the host calls it only after proven
GPU completion or proven discard. `bedray_core_destroy_path_trace` refuses while
a recording remains; `bedray_core_destroy` destroys the pass and refuses if the
pass still has recordings. A program still referenced by the configured pass is
deferred by `bedray_core_destroy_program`, so the pass cannot be left with a
dangling handle. Unknown submission outcomes keep the recording pinned; there
is no frame-count recycle and no `vkDeviceWaitIdle`.

Selection: `-Dvkbridge.rt.nativePathTrace=true`
(`RtConfig.SLANG_PATH_TRACE_CPP`) uses the native pass only when the loaded DLL
exports the coarse pass and NATIVE_DI support. An explicitly requested native
path reports a mismatched DLL as unsupported. The switch is off by default
until owner live evidence exists. The embedded native scene is connected and
selects `path_trace_scene` through the native-scene frame flag.

Depth keeps the legacy sentinel: bloom treats `depth >= 1e5` as sky
(`BLOOM_SKY_DISTANCE`), and the path-trace entry writes `1e6` whenever the
primary ray accepted no surface — including a ray that only crossed cutout
holes, which is why the result carries `PATH_TRACE_FLAG_PRIMARY_HIT` instead of
letting the host infer a hit from a distance.  Cutout holes advance the primary
origin, and the accepted first hit reports `primaryAdvance + hit.t`, so the
distance stays measured from the camera rather than from the last hole.  A
primary ray whose data is non-finite, whose interval is empty (`tMin < 0` or
`tMax <= tMin`) sets `PATH_TRACE_FLAG_INVALID_SAMPLE`, and so does a hit
parameter, cutout advance or throughput that stops being a number; an interval
the cutout advances merely used up sets `PATH_TRACE_FLAG_TRUNCATED`.  Neither
adds environment radiance: a path that never proved a miss is not a proven miss.
Only the first of those is dropped from the running mean, which is what
`pathTraceSampleRejected` and the alpha lane are for.  `firstHitDistance` is a
distance along the unit primary direction, not linear view depth; a consumer
that needs view depth must derive it from the camera contract.

`RtFrameRenderer` constructs `RtSlangPathPass`, which prepares Minecraft inputs
and delegates through `PathTraceBackend`. `NativePathTraceBackend` holds borrowed
program handles, coarse ABI state and recording identities; it creates no Java
GPU owners, descriptor tables or Vulkan commands. `ReferencePathTraceBackend`
contains the Java orchestration retained as the default until owner live
acceptance. Its allocations also use native buffer/image owners. SlangRuntime
alone owns cached programs; wrappers borrow them.

Both paths use the eighteen-binding trace and nine-binding display contracts,
including separate `previousAccum`/`previousDepth` sources. Native frame recording
uses an opaque graph node and exact scheduler leases. PathTracePass remains the
single authority for its private barriers and history; it does not fabricate a
second HistoryRegistry submission. See MIGRATION_INTEGRATION_DECISIONS.md.

Every recording must reach completion or discard retirement before reuse or
teardown. The host registers a generation-specific RecordingCompletion action
after attaching the command buffer to the shared Minecraft encoder. Shutdown,
resize and mode switches submit/await the host fence before releasing dependent
resources. A timeout preserves strong references and quarantines the renderer;
there is no device-idle lifetime mechanism. Unknown execution uses conservative
discard invalidation after host drain. Device-loss APIs exist; automatic game
device recovery remains unfinished. None of this substitutes for a GPU/game run.

The running mean is reprojected: `path_display.slang` carries each pixel's
history through `FrameConstants.prevViewProjection` plus the anchor delta,
rejects it on a depth discontinuity (`REPROJECTION_TOLERANCE`) and keeps a
per-pixel sample count, so accumulation continues while the camera moves instead
of restarting on every frame. A pixel whose surface itself moved needs a third
term: the tracer writes `outMotion` for the primary hit (`float4`, rgb is the
displacement of that surface from last frame to this one and `a` says whether the
surface is a moving entity part), the display pass reads it per pixel and
reprojects `position + cameraDelta + surfaceMotion`, so a walking mob's pixels
find the history of that mob's own part rather than the wall it walked past.
Terrain writes the zero the reprojection already assumes, and a pixel that hit
nothing writes nothing. The adapter still resets the whole image when the
scene changes, the key light's direction or colour changes, or the sky colour
changes — a moving sun must not be averaged into a static dot. The history is
only trusted after it was written once (`historyWritten`), and the reset path is
`RtSlangPathPass.resetHistory`. A mean that lives for thousands of frames also has
to refuse a sample it cannot trust: `tracePath` separates a non-finite fault
(`PATH_TRACE_FLAG_INVALID_SAMPLE`) from a path that merely stopped short
(`PATH_TRACE_FLAG_TRUNCATED` — no lobe left, an interval used up by cutout
advances, an unnamed hit, the firefly guard), `pathTraceSampleRejected` states the
rule once, `passes/path_trace.slang` publishes the answer in the alpha lane of the
radiance image (its only consumer is the display pass), and the display pass folds
that lane into a weight of 0 or 1, so a rejected sample changes neither the mean nor
its count and does not overwrite `historyDepth` with the far plane a rejected
primary ray leaves behind. Truncated paths keep their radiance: dropping them would
bias every short path in the image. The sun/moon cone uses
`RtSettings.sunRadiusRad()` (the `sun_size_tenths` setting, default 0.0044 rad)
like the GLSL path; the earlier hardcoded 0.0093 rad was about twice that radius,
or roughly 4.5x the solid angle. Evidence level: both fixes compile through
`compileSlang` and are covered by `RtPathTraceSlangPassTest`; neither has an
owner-verified frame recorded yet.

### Path-trace shading corrections

Two estimator defects were fixed in `shading/bsdf.slang` and
`shading/microfacet.slang`.  `bsdfSample` now divides each lobe weight by its
selection probability (`specularProbability` or its complement) in addition to
the conditional density; without that divisor indirect dielectric reflection
and transmission were scaled by the selection probability.  `ggxReflectionPdf`
now returns the visible-normal density `D * G1 / (4 * NdotV)` that
`sampleGGXVNDF` actually draws from, instead of the half-vector density
`D * NdotH / (4 * VdotH)`; the old form disagreed with the sampler whenever
roughness was not near zero.  Evidence level: the changed modules were compiled
through the `path_trace` entry point with `slangc` (`cs_6_5`, the same
arguments as `compileSlang`), but this was a manual SPIR-V compile, not a Gradle
build, and no GPU frame or image comparison exists yet.

`passes/path_trace.slang` now copies the pushed alpha cutoff into
`MaterialSamplingOptions` before tracing.  The primary path already used the
pushed 0.1, while material decoding and the filtered shadow walk used the 0.5
default, so a texel with alpha between them could be visible to the camera but
transparent to its shadow ray.  The same options now reach both; the pass
compiled with the same `slangc` invocation.

Shadow rays also bias their origin along the geometric face normal (flipped to
the receiver side) instead of the normal-mapped shading normal, so a tilted
normal cannot start the ray inside the surface.  NEE still evaluates the BSDF
and cosine with the shading normal.  This change is covered by the same
manual SPIR-V compile.

`tracePath` adds a surface's emission on every accepted hit: the face of a
glowing block is seen as its own radiance, and the light it casts on other
surfaces arrives from emitter NEE. Those are two terms, not a double count,
provided a glowing face is not lit by its own quad — which is why
`ILightSampler` takes a `LightReceiver` and the sampler rejects a candidate
closer than half a block when `receiver.emission > 0.5` (see the next section).

### Entity geometry in the traced scene

`traversal/entity_geometry.slang` is a port of `fetchEntitySurface` from
`rt_common_surface.glsl`, not a re-derivation: the look the owner iterates on
lives in that function, so the constants, the bands and the order of the branch
chain are copied rather than guessed.

What a port cannot translate one-for-one, and how it was settled:

* **Reaching the records.** The legacy path reads the ring through device
  addresses stored in a statistics buffer; the Slang pipeline passes every other
  per-frame list as a descriptor, so the ring is six bound buffers (20-25) -
  the previous-position ring included, because a motion vector is a geometry
  question and not a second convention.
  Each binds at the ring slot's own offset and at this frame's length, which
  makes `entityIndices.GetDimensions()` the authoritative triangle count and
  leaves a frame with no capture reading one zeroed element rather than the last
  thing that was there. Device addresses through the push block were rejected:
  the block is pinned, and a `uint4 entityState` of counts does the same job.
* **Naming the geometry.** One TLAS instance holds the whole ring, so the
  committed `primitiveId` is an index into this frame's triangles and the
  instance's custom-index bit (`0x800000`) is the only thing that tells a mob
  quad from a section quad. `decodeHit` routes on that bit; the section decoder
  returns a miss for an entity id so a routing mistake reads nothing instead of
  indexing a freed table entry.
* **World position.** A captured model comes from vanilla's camera-relative
  `PoseStack`, so the shader's world position is `hit + cameraAnchor`, where the
  anchor is the same vector the legacy `camRel` held and the block-emitter tail
  already carries (`emitterCameraRelative.xyz`). It is a wrapped neighbourhood
  proxy, not an identity, which is why an entity surface reports
  `sectionId = SECTION_INDEX_NONE` and cannot key a history or an emitter on it.
* **The first-person body.** It stays in the scene so water and glass still
  reflect it, but the eye sits inside it. `ISceneTracer.tracePrimary` therefore
  takes the ray's own policy, and the candidate loop skips the body's primitive
  range only for a primary ray; shadow and bounce rays keep it. Traversal stays
  material-free: cutout, tint and pane decisions are the surface layer's, so an
  entity part is filtered by `decodeSurface` and the tracer's cutout-advance
  loop exactly like a block face.
* **The alpha byte.** `EntityTextureAtlas` writes coverage below 224/255 and an
  emission code in five bits at or above it, so the three bands are resolved in
  one place - hole (`alpha < 0.1`, vanilla's entity cutout), thin pane
  (translucent *and* not a black shadow-blob texel), opaque emissive. The
  selection wireframe is a translucent pane with `transmission = 0`, which lets
  every shadow through while still shading its own colour at 0.35, and the
  fullbright floor (`light > 0.995` on both channels raises emission to 0.4) runs
  after the chain, so a hole stays unlit.
* **Where a moving part stood.** The host already uploaded the previous position
  of every captured vertex, relative to *this* frame's camera, so
  `entityMotion` interpolates the same hit's two halves with the same barycentric
  weights and returns `previous - current` - the vector a consumer *adds* to
  today's position to land in yesterday's picture, with camera motion already
  cancelled by that anchoring. The question belongs to `ISceneTracer`
  (`surfaceMotion`, defaulting to zero for a backend that tracks nothing moving),
  the tracer asks it once for the primary surface and writes `outMotion`, and
  both consumers - the accumulating display pass and ReSTIR DI temporal reuse -
  add that vector to their existing `position + cameraDelta`. No new push field,
  no device address, no second reprojection convention. A triangle the previous
  slice does not cover, a miss, and terrain all answer zero, which is exactly what
  a static reprojection already assumes.

Known gaps, all of them visible: no **End Portal starfield**, no **Sobel
auto-normal**, and an emissive part is not yet a **next-event-emission
candidate**. Switch:
`-Dvkbridge.rt.slangEntities` (default true) falls back to a terrain-only trace.
Evidence: `compileSlang` produces 18 bindings / 112-byte push for `path_trace`
(420184 B SPIR-V) and 9 bindings for `path_display` (the motion image the tracer
owns); `RtSlangEntityTest` (12 tests) pins the scene mask, the instance-bit
routing in both directions, the anchor arithmetic and the missing identity, the
primary-only self-avatar skip across all three ray kinds, the alpha bands and the
fullbright order against the legacy function, the ring-slot offsets and the
bound-length authority, the host fallback plus the switch, and the motion path -
the previous ring read through `GetDimensions` with its own bound refusal, the
`previous - current` direction, the entity-only answer from `surfaceMotion`, the
third term in both consumers and the buffers that carry the slice.
`RtPathTraceSlangPassTest` and `SlangPassLibraryTest` pin the push layout and the
manifest. Nine mutations - the mask back to world-only, the anchor dropped from
`worldPosition`, the section decoder accepting an entity instance id, the
self-avatar skip moved into `traceClosest`, the fullbright floor zeroed, the index
range bound at the buffer start instead of the ring slot, the triangle count taken
from a constant rather than `GetDimensions`, the atlas/range cross-check gutted,
the frame switch forced off - each failed exactly the guard that owns it, and every
mutated file came back byte-identical. So did thirteen more, all of them motion:
the subtraction reversed, the previous-ring refusal deleted, the previous count
read from the current vertices, the previous binding filled with this frame's
positions (twice, in the pass and in the host), the slice bound at offset 0,
`+ surfaceMotion` dropped from the accumulator and `+ receiver.motion` from the
DI reprojection, the motion lane made to claim every primary hit, the traversal
default made to answer a non-zero lie, terrain let into the entity decoder, a
miss allowed to keep writing motion, and the carrier field renamed. No
owner-verified frame yet.

What a playtest report of "mobs are still missing" can be told apart from: the frame
logs one line per session, before the dispatch that could fail, either naming the
triangle and vertex counts (divided out of the same 12-byte ranges the shader itself
measures), the atlas size and the first-person range, or saying the frame has no
entity capture at all and naming the switch. A capture that never filled the ring and
a trace that ignores a full ring used to look identical in the log; five further
mutations - the session gate removed, either branch of the line deleted, the counts
read as raw bytes instead of divided by the stride, and the line moved after the
dispatch - each failed `theFrameReportsWhatTheRingActuallyHolds`.

### Block-emitter NEE

`light/block_emitters.slang` gives the Slang path the emitters the legacy path
already sampled; before it, a torch-lit room rendered black because the only
wired `ILightSampler` was `SunMoonSampler`. The module reads the `RtEmitters`
records through binding 18 and implements `ILightSampler` (plus
`SunAndBlockEmitterSampler`, which splits each draw between the key light and
the emitters and divides the returned `sourcePdf` by the branch share — with one
live branch nothing is renormalised).

The *structure* is new Slang (contracts, `MaterialAtlas`, `sampling.slang`), the
*look* is the legacy kernel deliberately kept: `d^-1.5` to two blocks then
`d^-1.25` with the `0.84090` seam constant, the squared `EMITTER_RADIUS` window,
the eight chromatic categories, the `0.25` edge-on floor for two-sided sources,
the redstone alpha-bit test and the open-source shadow end bias. Those values
were tuned by the owner against real screens, so `RtSlangBlockEmitterTest` pins
each of them on both sides — retune one file and the test names the other.

Estimator contract: `radiance` carries the legacy `p^`-folded weight
(`wsum / M` divided by the receiver cosine, because `bsdfEvaluate` applies that
cosine again) multiplied by the solid-angle density, and `sourcePdf` stays the
honest density of the chosen point so ReSTIR can reuse the sample without
re-deriving it. For one candidate per call this reproduces the legacy integrand
exactly; for more it is its RIS average.  `blockEmitterEstimateSample` is the
single place that turns a (position, `p^`, pdf, `Le/texelEmission`, end bias)
tuple into that pair, and both the fresh batch winner and a reused reservoir
record are reduced to the same tuple, so the shading of a pixel cannot depend on
which of the two produced its light.

Known deviations from the legacy path: entity-atlas emitters (`uvc.w != 0`) are
skipped, because the entity texture is not bound to this pass and a candidate
that cannot be evaluated must not take weight; the pixelated-cell shadow origin
is not ported. Switches: `-Dvkbridge.rt.blockEmitters` (default true, the whole feature),
`-Dvkbridge.rt.emitterCandidates` (1..32, default 8 = `NEE_CANDIDATES`) and
`-Dvkbridge.rt.emitterProbability` (0..1, default 0.5).

### ReSTIR DI temporal and spatial reuse

`restir/di_block_emitters.slang` is the block-emitter adapter over the reservoir
math of `restir/restir_di.slang`, and `passes/path_trace.slang` binds it at
binding 19.  One `DIReservoir` per pixel and per frame half holds the light
point, `p^`, `M`, the weight sum, the record index, the age, the receiver
position and the packed receiver normal — 64 bytes, pinned in
`gradle/slang.gradle` and re-checked against the generated manifest before the
first dispatch.

The normal is the one lane with a hand-written encoding, and Slang has two ways
to get it silently wrong, so `reservoirPackNormalXY` / `reservoirUnpackNormalXY`
are pinned by `RtSlangRestirDiTest`.  `asuint` of a `uint` is the *numeric*
int-to-float conversion, not a bitcast: a packer returning it stores a real
number (rounded away above 2^24) that `asuint(float)` on the read side turns into
something else again — `asfloat` is the bitcast.  And the two halves of a snorm
pair are signed, so `0xFFFF` must decode to `-1`; read as unsigned, any surface
facing `-x` or `-y` produced a component above one, the reconstructed `z` went to
zero and the normal gate compared noise.  Neither mistake breaks a compile or
shows up as a black screen: the reservoir stays readable and reuse just stops
filtering.

The merge is the legacy one, in the legacy numbers: `M` capped at 20, a temporal
ratio cap of 8, an occlusion decay of 0.25, the spatial ratio cap of 5 (used by
the spatial stage below), and the receiver gates `normalDot >= 0.8` on a static
camera / `0.6` while moving plus a 5 % + 0.1-block distance and position
tolerance.  A stored sample is *re-resolved* through
`BlockEmitterSampler.evaluate` instead of caching its radiance, which both keeps
one estimator and rejects a record whose quad changed under the reservoir.

Space: a record stores the light point in the camera-anchored space of the frame
that wrote it, so the history read reprojects the receiver with
`receiver.position + receiver.motion + cameraDelta` through
`frame.camera.prevViewProjection` — the same convention `path_display.slang`
already uses for the running mean, with the surface's own displacement read from
the vector the tracer committed for that pixel — and an
accepted record comes back with `- cameraDelta`.  A mob part therefore merges
with the history of the same part rather than with whatever lit the pixel it
walked through, which would have been a bias and not noise.  The view bob needs no term of
its own; it is inside both receivers and differs by centimetres, far below the
tolerance above.

Two calls, not one: `reuseSample` publishes the reservoir with its full weight
and `reuseConfirm(occluded)` re-reads that slot and decays it, because the path
tracer owns the shadow ray and nothing may be carried between the two calls in
shader or in host state.  Every path that does not reach the primary NEE — the
invalid-ray early return and the loop tail — publishes an empty record, so a
light that left the screen cannot be resurrected by the next frame's history.

Spatial reuse (`restir/spatial_block_emitters.slang`, entry
`passes/restir_spatial.slang`) pools those records across neighbours before the
next frame's temporal import reads them: a pixel draws eight neighbours from a
plus/minus-seven-pixel square, re-resolves each one at *its own* surface and
merges it through the same `mergeReservoirs` primitive with the legacy caps.  It
traces no rays — a reservoir is a sample distribution, and visibility stays the
shading pass's job — so a neighbour only has to prove it belongs to the same
surface: normal dot at least 0.85 and a plane tolerance of the same 5 % + 0.1
blocks, measured against the camera distance the record's own receiver lane
carries.  Because both readers and writers live in the half the trace pass just
wrote, no reprojection or `cameraDelta` term is involved at all.  The stage runs
as two dispatches, one per checker colour, and forces every neighbour offset onto
the opposite colour: the cells a dispatch writes are then never cells another
thread of the same dispatch reads, so the update is in-place and race-free without
a second ring, and the second dispatch imports what the first merged.  Two
contracts the reader cannot recompute are stored for it in `stats.w` — the sign of
the receiver normal and `DI_RECEIVER_SELF_EMISSIVE`, without which a wall of
glowing lamps would light itself from its own quad as soon as a neighbour's sample
was re-resolved at it.  A merged record keeps the *writing* pixel's receiver lanes,
and a non-finite winner is dropped rather than stored, because next frame's import
reads that slot.

`RtSpatialReuseSlangPass` owns no memory: it binds the atlases, the emitter list,
the reservoir ring and — deliberately — the *trace pass's own* `FrameConstants`
upload, so the extent the pool iterates and the extent the ring was addressed with
come from one buffer.  The push carries no size field for the same reason.  The
stage orders its own two dispatches (a compute-to-compute barrier between the
phases, since phase two imports what phase one merged) and ends with a trailing
one, so the pooled half is available to the batch that reads it next frame — the
same shape the legacy stage has.  The stage is optional by design: a missing AOT
artifact or a manifest that disagrees with the host closes the program again, logs
`spatial reuse off: …` once and leaves temporal reuse running, exactly as the
legacy path did when its spatial kernel was compiled out.

`RtSlangPathPass` owns its own ring (`rt_path_restir`, `2 * pixels * 64` bytes)
rather than sharing the GLSL one: the two record layouts differ, and a mode
switch must not hand half-written bytes to the other path.  It alternates the
parity only after a dispatch that really wrote records, and drops the whole ring
when the light-list fingerprint (now also carried as `RtFrameParams.
emitterFingerprint`) or the render size changes.  Switch: `-Dvkbridge.rt.restirDi`
(default true; false falls back to the per-frame RIS draw of the same list) is the
session capability and decides allocation, the player's `path_restir_di` row
(`RtSettings.pathRestirDi()`) is the per-frame decision — `diRequested()` versus
`diActive()`, so switching at runtime costs no reallocation and no
`vkDeviceWaitIdle` inside a recorded frame.  Reuse is a choice per technique, not
one button: the ray pass has `rays_restir_temporal` (the ring address handed to
`rt_rays.comp` goes to zero when off, which is that shader's own off test) and
`rays_restir_spatial` (gated at the two checker dispatches, since
`-Dvkrtx.rt.restirSpatial` only decides whether the kernel exists), and the path
tracer has `path_restir_di` above plus `path_restir_spatial` for its pooling stage
(`spatialRequested()` needs the kernel, the parent and the row; `spatialActive()`
additionally needs this frame to have written records over a non-empty emitter
list).  All four are `RtSettings` booleans, so they persist, obey
`-Dvkrtx.rt.<key>`, and are packed into the per-frame change diff of
`RtFrameRenderer`: any of them moving drops the histories it invalidates and shows
up as `restir(a->b)` in the `resetHistory:` log.  Spatial reuse is offered as a
child of temporal in both paths because it merges the reservoirs temporal writes —
an independent row would be a switch that does nothing.  Evidence:
`compileSlang` produces 18 bindings / 112-byte push for `path_trace` — the entity
ring and its previous-position buffer were bound after this batch, and the DI
contract they carry is unchanged — and 6 bindings
/ 64-byte push for `restir_spatial`, `RtRestirSettingsTest` pins each switch to the
consumer that acts on it, and
`RtSlangRestirDiTest` + `RtSlangRestirSpatialTest` + `RtSlangBlockEmitterTest` +
`RtSlangSampleRejectionTest` + `RtSlangVisibilityOverflowTest` +
`RtSlangSkyDiscTest` +
`RtPathTraceSlangPassTest` pin the numbers, the
space and the host contract; no GPU frame or side-by-side screenshot is recorded.

The same audit recorded the remaining systemic gaps in
`docs/PATH_TRACE_CPP_INTEGRATION_PLAN.md` ("Follow-ups from the 2026-10-27
shader audit"): no emitter NEE
buffer, missing optical medium/eta tracking, thin-pane transmission reachable
only through BSDF sampling, `CameraState.view`/`.projection` still identity (the
two products no Slang pass reads, since `viewProjection` and
`prevViewProjection` are filled from the engine pose), entities
excluded by the `0x01` path-trace instance mask while `RtWorld` writes the
entity BLAS with `0x02` (widening the mask is not enough:
`legacyHitToSurface` returns a miss for entity custom indices, so an entity
would hide the terrain behind it), and water decoded as a flat translucent
pane because no code assigns `MATERIAL_SOURCE_FLUID` on the triangle path and
no water module is compiled.  Both later gaps are source findings; neither was
checked in game.

`bsdfSample` and `bsdfPdf` now describe the same thin-pane mixture.  The
sampler used to always transmit the diffuse branch while `bsdfPdf` reported a
front-hemisphere diffuse-reflection density that no sample could produce; an
optical interface similarly reported a diffuse density even though its
transmission is delta.  The pane now splits the branch by `transmission`, the
density carries the branch probability, and `bsdfEvaluate` omits the diffuse
lobe for optical transmission.  The live triangle path forces translucent
panes to `transmission = 1`, so its camera output is unchanged; ReSTIR PT's
reverse density now matches the sampler.  Evidence: the same manual `slangc`
compile through `path_trace` produced 95,068-byte SPIR-V; no Gradle build or
GPU frame was verified.

`RtSlangPathPass.fillFrame` now converts the display-referred
`SkyRenderState.skyColor` to radiance with `RtTonemap.invert` before writing
`FrameConstants`.  `GradientSky` consumes radiance, but the pass wrote the
vanilla display colour and the display pass tone-mapped it again, applying the
gain and shoulder to an already display-referred value; the legacy sky
converts with `tonemapInverse` for the same reason.  The sun/moon key colour is
already radiance and is left unchanged.  This is a source-only Java change
(no Gradle build, GPU frame or image comparison).

### Render-graph recording boundary

`native/bedray_render_graph/` is the planned recording layer: it compiles a
pass graph, tracks image layouts and emits broad `ALL_COMMANDS` barriers before
each pass.  The native core links it by default (`BEDRAY_ENABLE_RENDER_GRAPH`),
but `core.cpp` neither constructs nor executes it. A previous agent reported
scoped compilation and ABI tests; those reports do not cover later graph and
history changes and do not prove a rendered frame. The current sources have
not been independently built as a complete migration.

When the frame moves to the graph, path tracing becomes one
`record_vulkan_graph` pass.  The adapter records into the host command buffer
through the supplied `vkCmdPipelineBarrier`, never creates a device and never
submits, so the Minecraft host keeps ownership of the queue and presentation.
The pass must then:

* call `prepare_vulkan_recording` before recording, with the actual
  `initial_layout`, the full `VkImageSubresourceRange`, and the capabilities
  enabled on the device; `final_images` becomes the tracked layout afterwards;
* move image creation and the UNDEFINED-to-GENERAL transition into the C++
  resource and graph owners. The callback must not change tracked layouts;
  resource replacement and retirement must follow GPU completion instead of
  assuming `vkDeviceWaitIdle` belongs in the frame path;
* expose `VkImage`, layout and range from the output resources, because
  `VulkanImageBinding` takes the image, not the view;
* use GENERAL for the radiance and depth storage images, which the adapter
  accepts for both storage writes and sampled reads;
* use one `BedrayFrameDesc` per compute pass if adopting `record_core_dispatches`;
  the C++ integration must resolve reflected named bindings into that descriptor
  and supply the matching 64-byte push block. The bridge does not do this mapping
  itself and has not yet been connected or validated in a live frame.

Two contracts still need one owner before integration: the graph's 64-bit
`required_capabilities` (logical-device feature bits) and the 10-bit Slang
capability mask (517) must share one mapping, otherwise `compile` fails with
`missing_capability` or `is_current` reports a stale plan; and
`RtMemory.createImage` creates EXCLUSIVE images without a queue family index,
so single-graphics-family ownership must be proven or an ownership transfer
added.  History stays on `completed_only` until submission outcomes are
connected; this pass does not read or write history.

The history registry now has a provisional C ABI and is referenced by the core
build, but neither the frame path nor the host drives its submission protocol.
The graph must not claim history support until prepare/attach, actual submit
outcomes, completion, resource retirement and the read-producer dependency
are wired together. See `native/bedray_history/C_ABI.md` and
`native/bedray_render_graph/VULKAN_INTEGRATION.md`. Both describe source
candidates, not a validated or active runtime.

## Big Four status

| Algorithm | Module | State |
|---|---|---|
| ReSTIR DI | `restir/restir_di.slang` | reservoir storage, initial sample, temporal/spatial merge, occlusion feedback, raw and material-filtered visibility resolve; both stages now run as AOT passes (`path_trace.slang` temporal, `restir_spatial.slang` pooling) |
| Radiance Cascades | `radiance_cascades.slang`, `radiance_cascades/rc_atlas_layout.slang`, `radiance_cascades/rc_trace.slang`, `radiance_cascades/interval_transport.slang`, `radiance_cascades/segment_store.slang` | typed trace facade split into options/shade/walk/dispatch/history/ownership; `segment_store.slang` is a facade over `segment_record.slang` and `probe_resolve.slang`, and the typed probe resolve is `rcResolveProbe`/`rcProbeComplete` returning `RCProbeRadiance`; these are source-only in this checkout. The legacy float4 store is isolated behind `legacy_float4_store.slang`/`radiance_cascades_store.slang` and its resolve is named `rcLegacyProbeIrradiance`/`rcLegacyProbeComplete`; it is incompatible with the typed atlas. Environment is opt-in on a positive world boundary, so a terminal-cascade miss under a roof is not treated as sky. Cross-cascade reconstruction, unbiased temporal reuse and host integration are unfinished. |
| ReSTIR PT | isolated agent workspace | source candidate exists outside the shared checkout; review and integration remain open. |
| Path tracing | `pathtrace/path_tracer.slang`, `passes/path_trace.slang` | iterative loop over `ISceneTracer`, NEE through `ILightSampler` with sun/moon plus block emitters, filtered shadows, Russian roulette, first-hit distance; the traced scene is terrain *and* the captured entity ring (`traversal/entity_geometry.slang`), decoded by the instance's custom-index bit; `RtSlangPathPass` wraps `RtPathTraceSlangPass`, the optional `RtSpatialReuseSlangPass` and `path_display` in Path Tracing mode, with `AUTO` falling back to GLSL when the Slang path is blocked |

Surface decoding lives in `surface/surface.slang`; the filtered shadow walk lives
in `surface/visibility.slang`; the BSDF lobes live in `shading/bsdf.slang`.
Traversal remains material-free: it returns the first geometric candidate, and
the surface layer decides whether that candidate is a cutout hole, a translucent
filter or an opaque wall.

## Render plan

`render_plan.slang` already contains `RenderWorldMode` (VoxelVanilla,
VoxelHybrid, FullHardware), `RenderProfile` (Fast, Quality), the four lighting
algorithms plus Vanilla/ScreenSpace, traversal/denoiser modes and a capability
mask (`HARDWARE_RT`, `SER`, `OMM`, `DLSS_RR`, `FRAME_GENERATION`).  The GPU-tier
table from the first draft is now an input to this plan, not a shader variant:

| Tier | GPUs | Tracing | Denoiser | Upscaler |
|---|---|---|---|---|
| 1 — Performance | RTX 20xx, RTX 3050/3060, RX 6600/6700, Arc A580 | Hybrid Fast RT (1 bounce + ReSTIR DI) | NRD Reblur Diffuse+Specular | DLSS/FSR/XeSS Performance |
| 2 — Balanced | RTX 3070–3090, RTX 4060, RX 7700/7800, Arc A770/B580 | Path Tracing (2–3 bounces + ReSTIR DI) | NRD Reblur or DLSS-RR | DLSS/FSR/XeSS Quality |
| 3 — Ultra | RTX 4070–4090, RTX 50xx | Full PT (4–8 bounces + ReSTIR DI + ReSTIR PT) | DLSS 3.5 Ray Reconstruction | DLSS Quality + Frame Gen |

## Verification

Every batch runs `:common:test` before it is committed, and the owner playtests
the deployed jar in the real instance — a compile or a unit test is never
recorded as a game check. Pass `-Pslangc=<path to slangc.exe>` (or set
`SLANG_DIR`): without it `:common:compileSlang` fails closed in about a second
and the whole suite goes with it.

`slangc` 2026.20 is not committed; install the Slang SDK outside the
repository and point `SLANG_DIR` at it.

```powershell
$env:SLANG_DIR = 'C:\Temp\bedray-slang-2026.20'
.\gradlew.bat :common:compileSlang
.\gradlew.bat :common:test --tests '*BedrayCoreAbiTest' --tests '*SlangPassLibraryTest'

# While another workstream has an incomplete pass in passes/, scope the build
# to the finished ones. The index then lists only what was compiled.
.\gradlew.bat :common:compileSlang "-PslangPasses=bloom_*.slang,composite.slang,path_trace.slang"

cmake -S native/bedray_core -B native/bedray_core/build -G "Visual Studio 17 2022" -A x64 `
      -DBEDRAY_VULKAN_HEADERS=C:/Dev/Sky/tools/sdk/Vulkan-Headers/include
cmake --build native/bedray_core/build --config Release
native/bedray_core/build/Release/bedray_abi_probe.exe
```

`compileSlang` fails closed: without `slangc` it does not silently package an
incomplete renderer.  It writes SPIR-V and a manifest per pass into
`build/generated/slang`, which is a resource root; both are packaged into the
jar and are never committed.

The task is also the build-time guard on the host contract:

* every SPIR-V capability must map to a `BedrayCapability` bit, otherwise the
  build stops instead of producing a module the core cannot validate at
  registration; capability 55/56 (a storage image without an explicit format)
  fails with the names of the offending `RWTexture2D` parameters, and the
  annotation has to match the view the host binds;
* each buffer binding records `elementBytes` from reflection (64 for
  `LegacySection`, 608 for `FrameConstants`, 64 for `BlockMaterial`), so a
  struct growth is visible in the manifest rather than at dispatch time;
* `STRUCT_LAYOUTS` pins the size **and the field offsets** of the structs that
  are shared with Java or with the legacy `RtWorld` table, so a same-size field
  reorder still fails the build.

For the older revisions that the agent actually checked, the reported tests
provide L2 evidence for the exercised ABI sizes and manifests. They do not
establish that the current, subsequently modified tree compiles. Those checks
were intended to verify that the ABI struct sizes match between C++ and Java,
the generated manifests load, and the bloom manifests keep the binding
numbers, 128-byte push block and 8x8x1 group size the host supplies.  The
path-trace manifest additionally pins its sixteen bindings, the 64-byte
`LegacySection` / 64-byte `BlockMaterial` element sizes, the 608-byte
`FrameConstants` block and the exact capability set.  They do not prove a
pipeline runs on a GPU.  A live game check is a separate, explicitly requested
step.

The source-only check still works for modules that have no entry point:

```powershell
slangc -target spirv -I common/src/main/resources/shaders/slang `
       -o $env:TEMP/bedray-root.spv `
       common/src/main/resources/shaders/slang/bedray.slang
```

The root compile ends with `E57004: SPIR-V output contains no exported symbols`
because a module has no entry point; that is expected.

On 2026-10-28 the shader-owner branch `feat/slang-rc-transport-20261028`
manually compiled all 17 `passes/*.slang` with the `compileSlang` arguments
(`slangc` 2026.20, `-target spirv -profile cs_6_5 -entry main
-matrix-layout-column-major`), each exit 0. The same compiler built
`verify_rc_typed.slang` and `verify_rc_environment.slang`. This is L1
code-generation evidence for that revision only: it is not a Gradle build, no
manifest was validated by `compileSlang`, and no GPU dispatch, stride check,
cross-cascade resolve or image comparison was run.

## Next steps

1. **Path-trace native integration** — migrate resource allocation, frame data,
   reflected descriptor mapping, dispatch and retirement into the C++ renderer.
   The ownership split, scene/shader contract gap, lifetimes and open decisions
   are recorded in [PATH_TRACE_CPP_INTEGRATION_PLAN.md](PATH_TRACE_CPP_INTEGRATION_PLAN.md).
   Java supplies Minecraft-owned state and the existing command buffer through a
   narrow boundary. Replace the legacy scene representation with the new scene
   contract; the existing `LegacySectionTraversal` is reference-stage work, not
   proof of the target architecture. Apply exposure once in the display pass.
   Unsupported device capabilities must be reported, not routed to GLSL.
   The cutout-distance correction is in the source and compiles to SPIR-V
   (`passes/path_trace.slang`, `cs_6_5`); it has not been exercised on a GPU or
   in game. Invalid/truncated primary paths are flagged and excluded from the
   sky path. Still agree on view depth versus ray distance before a downstream
   consumer treats `outDepth` as linear view depth.
2. **Sample rejection** — the tracer keeps the two bad endings apart
   (`PATH_TRACE_FLAG_INVALID_SAMPLE` for a non-finite fault,
   `PATH_TRACE_FLAG_TRUNCATED` for a path that only stopped short) and
   `pathTraceSampleRejected` publishes that verdict through the alpha lane of
   the radiance image, which the display pass turns into a weight of 0 or 1.
   The host still has no readback of either flag, so a fault that is not
   non-finite - a traversal that returns a hit the decoder cannot name, an
   exhausted visibility budget (now rejected by `surfaceVisibilityProven`, so
   it darkens instead of leaking light) - is invisible in a log. A counter per
   reason belongs to the C++ pass, which owns the buffers.
3. **ReSTIR PT** — path-space reservoirs on top of `PathVertex`; keep it
   separate from the DI reservoir so the two reuse policies do not merge.
4. **Entity shading parity** — the ring is traced and moving parts reproject;
   three behaviours of
   `fetchEntitySurface` are not: the procedural **End Portal starfield**, the
   **Sobel auto-normal** that gives mob skin its relief, and glowing parts as
   **next-event candidates**, so a lantern held in hand lights nothing but itself.
5. **Radiance Cascades trace/update** — typed trace, store and probe resolve
   are now in the shared checkout as source-only. Environment is opt-in on a
   positive world boundary that the terminal window reaches; a zero boundary
   adds no sky, because a terminal-cascade miss can end inside a cave. Align
   temporal history and prove that boundary, then define cross-cascade
   reconstruction and host integration before connecting a pass. Do not wire
   the legacy float4 resolve to the typed atlas.
6. **More entry passes** — denoise/composite follow the same one-file-per-entry
   pattern and the same ABI; no second pipeline path.
7. **Live game check** — only after the above and an explicit request.  A
   compile or a unit test is not a game check.

## Rules for contributors

1. Do not port old GLSL 1:1.  Use it to understand behaviour; write the new
   module against the contracts.
2. Do not add a shader variant for a GPU name.  Add a capability bit and a
   plan rule.
3. Do not put material rules in traversal or traversal handles in contracts.
4. Do not commit SDKs, generated SPIR-V, DLLs or JARs.
5. Do not call a compile or a test "working in game".  Graphics need a fresh
   live check with a log or screenshot.

## Licence

Same as the main project: **LGPL-3.0-or-later**.
