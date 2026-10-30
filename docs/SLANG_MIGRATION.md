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

* Frame generation (DLSS-G and FSR 3) prepares its inputs through the AOT pass
  `passes/fg_prepare.slang` recorded by `bedray_core_render_fg_prepare`: the core
  owns the flipped blit of the engine main target into the hudless image, the
  depth/motion flip dispatch, its descriptors and the barriers, and the host only
  hands over the images it already owns (the `BedrayFgPrepareFrameDesc` mirror is
  `BedrayCoreLayouts.FG_PREPARE_DESC`). `FrameGenGpu` borrows the per-device
  `SlangRuntime` from `SlangRuntimePool`, validates the manifest (4 bindings,
  uint2 push, 8x8x1) before the first dispatch, and hands each recording back once
  the encoder's fence proves retirement. `fg_prepare.comp` is deleted; without the
  matching DLL the producer is logged as unavailable and framegen does not run -
  there is no Shaderc fallback. No owner-verified generated frame yet.

* The typed path trace now writes the legacy denoiser guide set for DLSS-RR:
  the entry pass fills `outViewZ` through `outSpecAlbedo` (bindings 26–32) at
  the primary vertex when the host sets `PATH_TRACE_OPTION_GBUFFER`, so an
  explicit `SLANG` path with `DLSS_RR` is no longer refused. NRD's `IN_MV`
  channel is filled too: binding 33 (`outMotionBuffer`) receives the primary
  hit's world motion as the legacy 16-byte `Vec4` at
  `PATH_TRACE_OPTION_NRD_MOTION` (bit 1), from the same `entity_motion_px`
  buffer the renderer already hands both denoisers by device address. `NRD` is
  therefore accepted with an explicit `SLANG` path as well; `AUTO` keeps the
  GLSL pass until a live frame consumes the guides.

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

Bloom also uses coarse native configure/render/destroy calls from
`native/bedray_core/include/bedray_bloom.h`. C++ owns its twelve-dispatch chain,
mip generations, barriers and recording pins. RtBloomPass registers borrowed
runtime programs, validates their existing manifests and supplies the unchanged
128-byte push data. Discard invalidates the affected generation rather than
assuming its transitions executed. The host owns output/depth and completion
proof; it must use a DLL with the new bloom exports. This moves orchestration,
not shader math, and has no GPU/game acceptance yet.

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
silently converge. `verify_water.slang` compiles `surface/water.slang` on its own
and reads every public entry the surface decoder and the path tracer use: both
slope overloads, the wave normal on the top, underside and vertical faces, the
biome tint, the fluid classification predicate, and the medium (extinction per
tint, segment transmittance and in-scatter, the enter/leave boundary rules).

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

**Current whole-migration evidence: L1 — the tree builds and its unit suites
pass.** `:common:compileJava`, `:common:compileTestJava`, `:common:test` and
`runShimTests` are green on `refactor/slang-pipeline` after the legacy renderer
was deleted, and `native/` CTest passes on its own build. None of that is
proof of rendering in Minecraft: with the GLSL path gone, the Slang path is the
only renderer, so the frame that used to be carried by the fallback has to be
re-captured live before a jar ships. A compile or a test is not a frame.

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
5. **The old GLSL is gone.**  The rule used to be "keep it as a reference".
   The owner overrode it: while a second renderer existed, every gap was
   silently filled by the GLSL path and the Slang path never had to close it.
   `common/src/main/resources/shaders/glsl/rt_*.comp|glsl` and the Java passes
   that compiled them at runtime are deleted; the reference is now the Slang
   contract plus `git log`.  Do not copy the old include graph, and do not
   reintroduce a runtime GLSL compile in the renderer (see
   `ShadercSurfaceTest`).  No first-party GLSL ships any more: the Iris depth
   converter reads the AOT `iris_depth` pass through `SlangPass`, and
   `shaders/glsl/` is empty.  What survives Shaderc is not the renderer: the
   `shaders/spatial/` upscaler kernels and the Iris pack host (they compile
   third-party or user-supplied GLSL), plus the one-shot opacity-micromap probe
   in `RtExtensionProbe`.
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
| Bloom + sun glare | `bloom_prefilter_down`, `bloom_down`, `bloom_up`, `bloom_apply` | `RtBloomPass` -> coarse native `BloomPass` | C++ owns mip images, descriptor/push packing, dispatch/barriers and exact retirement; programs/samplers remain runtime-owned. L1/L2, no live validation. |
| Composite | `passes/composite.slang` | `RtCompositePipeline` | AOT-compiled, but the host path is disabled (`LEGACY_RASTER_ENTITY_COMPOSITE = false`); it is not proof of the active renderer. |
| Path tracing | `path_trace`, `path_trace_scene`, `restir_spatial`, `path_display` | Controller -> ReferencePathTraceBackend or opt-in NativePathTraceBackend | Native scene/AS and immutable host capture are integrated. Native mode owns private images/uploads, DI/history/parity, opaque graph scheduling and exact completion/discard. Seven DLSS-RR guides and the NRD motion buffer are forwarded by additive calls when the matching native features/exports are available. Unsupported states are reported explicitly; there is no GLSL fallback. L1/L2, no live validation. |
| Volumetric shafts | `volumetric_march`, `volumetric_march_scene`, `volumetric_apply` | `RtSlangVolumetricPass` -> coarse native `VolumetricPass` | C++ owns history/uploads, two dispatches and barriers. Native/legacy scene tables remain distinct; visibility is world-only and nonzero bob origin is explicitly unsupported. L1/L2, no live validation. |
| Particle/weather composite | `particles_composite` | `RtParticleComposite` -> stateless native recorder | C++ packs descriptors/push and records barriers; host retains game-layer views through exact proof. L1/L2, no live validation. |

The host addresses bindings by Slang parameter name (`srcTex`, `dstImg`,
`finalImg`, `depthTex`), and `RtBloomPass` validates the generated manifest
against the resources it can supply before the first dispatch.  A shader edit
that moves a binding or changes the 128-byte block is rejected with an explicit
contract error before native recording.

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
| `outViewZ` | 26 | R32F storage image, negative view-space Z of the primary surface (the NRD `IN_MV`/`IN_NORMAL_ROUGHNESS` family's depth channel); `1e6` on a sky pixel |
| `outNormalRough` | 27 | RGBA16F, view-facing shading normal in xyz and linear roughness in w, matching `rt_pack_nrd.comp` |
| `outSpecular` | 28 | RGBA16F, zeros: the path tracer carries specular inside the radiance it already accumulated |
| `outDiffuse` | 29 | RGBA16F, residual radiance (`outImage - outDirect`), `.a` = primary hit distance |
| `outAlbedo` | 30 | RGBA8, diffuse albedo floored at 0.02 for demodulation |
| `outDirect` | 31 | RGBA16F, the primary vertex's own next-event estimate |
| `outSpecAlbedo` | 32 | RGBA8, F0 guide with the legacy rule `metal > 0.5 \|\| roughness < 0.6 ? (metal ? albedo : F0) : 0` |
| `outMotionBuffer` | 33 | `RWStructuredBuffer<float4>`, one 16-byte element per render pixel: the primary hit's world motion (prev minus cur) and the `rt_path.comp:616` trust flag in `w`; the NRD `IN_MV` channel `rt_pack_nrd.comp` reads at `StatsBuf.motionAddr` |

The seven guide images exist for DLSS-RR only. When the host has no complete
set, it binds pass-owned 1×1 dummies and leaves `PATH_TRACE_OPTION_GBUFFER`
(bit 0 of `PathTraceOptions.flags`) clear, so the shader never writes them; the
recording throws if the bit and the bound set disagree. The NRD layouts are
accepted with an explicit `SLANG` path: the renderer binds the same
`entity_motion_px` buffer both denoisers already receive by address as
`outMotionBuffer`, sets `PATH_TRACE_OPTION_NRD_MOTION` (bit 1), and the pass
writes one legacy `Vec4` per pixel there. An RGBA16F image cannot carry that
channel (8 B/px against the pack's 16-byte stride), which is why it is a storage
buffer rather than a guide image. The recording refuses a bit without a buffer,
a buffer without the bit, a range shorter than the frame, and motion without the
guide images NRD also consumes. A C++ host binds one-texel dummies and leaves
both option bits clear.

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
CAP_BUFFER_DEVICE_ADDRESS | CAP_SHADER_INT64` (517).  Path tracing runs on the
device's one pooled `SlangRuntime`, built from
`SlangDeviceCapabilities.enabledMask` (`SlangRuntimePool`); `RtAtmosphere`
borrows that same core for bloom and light shafts instead of keeping a mask-0
one. The final C++ renderer must
negotiate the features actually enabled on the game's device and select only
supported methods. There is no selector and no second renderer any more: the
`-Dbedray.pathTracer=auto|slang|legacy` switch and the GLSL `rt_path.comp` it
could fall back to are deleted, so a Slang path the device cannot run fails the
frame, `RtGeometryGate.markFailed` records `stage=… result=…`, ray tracing stays
off for the session and the game keeps drawing the raster image.  A trace request
that names no mode — `enableHardwareRayTracing` on while `rt_mode` is `off`, or
the legacy toml key read without `rt_mode` — resolves to Path Tracing
(`VulkanRtxConfig.effectiveRtMode`).  Ray Tracing mode itself is in the same
position: its GLSL effects pass is deleted and the Slang
`ssr.slang`, `composite.slang`, `atrous.slang`, `temporal_accumulate.slang` and
`velocity_reproject.slang` passes are not wired into the frame loop yet, so
`createPass` throws `RtFailure("slang_ray", …)` — which turns tracing off for the
session — until they are.  The Slang volumetric passes are connected:
`RtAtmosphere` records the march and apply dispatches when the post god-ray mode
is selected with fog on, and a march that refuses to register sets
`shaftsUnavailable` (one warning, no shafts) instead of failing the frame.

`volumetric_march` binding 2 reads `LegacySection` through `LegacySectionTraversal`,
while the native scene publishes a different `GeometrySection` record although both
are 64 bytes.  That is answered by its own reviewed entry:
`volumetric_march_scene.slang` binds the native table as `StructuredBuffer<GeometrySection>`
and traces the world-only instance mask, and `RtSlangVolumetricPass` hands both
march programs to the core, which selects one from
`BEDRAY_VOLUMETRIC_FRAME_NATIVE_SCENE`.  Record size is never by itself a reason to
rebind one table to the other's descriptor.

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

The procedural **End Portal starfield**, the **Sobel auto-normal** and glowing
parts as **next-event-emission candidates** shipped in `e1bcd82`; the entity
decode now matches `fetchEntitySurface` on every behaviour the legacy function
had. Switch:
`-Dvkbridge.rt.slangEntities` (default true) falls back to a terrain-only trace.
Evidence: `compileSlang` produces 18 bindings / 112-byte push for `path_trace`
(523968 B SPIR-V) and 9 bindings for `path_display` (the motion image the tracer
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
the reservoir ring, the trace pass's captured entity atlas (bindings 23/24, or
the trace pass's one-element stand-ins when a frame captured nothing) and —
deliberately — the *trace pass's own* `FrameConstants` upload, so the extent the
pool iterates and the extent the ring was addressed with come from one buffer.
The push carries no width/height field for the same reason; it does carry
`entityAtlasSize` at byte 48, because a pooled record can belong to an entity
emitter and re-resolving it has to decode that atlas.  The block stays exactly
64 B: the layout is std430, where a `float3`/`uint3` would be 16-byte aligned and
move the lane to byte 60, so the padding is scalar.  The stage orders its own
two dispatches (a compute-to-compute barrier between the
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

Status update (2026-10-29). Entities are traced by the typed path through the
`0x02` mesh mask with their own decode, motion vectors and NEE candidates
(`e1bcd82`), so `legacyHitToSurface` no longer decides their fate. Water is no
longer a flat pane either: `surface/water.slang` ports the legacy wave field,
the biome tint and the fluid classification, and `decodeSurface` assigns
`MATERIAL_SOURCE_FLUID`, the optical alpha mode, IOR 1.333 and a **clear**
Fresnel interface. The volumetric medium landed in the same module and the
typed path: `waterSigmaT` (clear absorption + biome dye + grey scattering),
`waterInScatter` with the multiple-scatter floor, a `WaterMedium` carried by
`tracePath`, per-segment absorption and in-scatter, entry/exit on a refracted
transmission through a horizontal fluid face, and a medium-aware NEE
(`traceShadowVisibilityMedium`) so an underwater receiver is shadowed through
its own column. A caller without medium state still gets the legacy tinted
sheet (`waterBiomeTint * 0.85`). Evidence: `slangc` 2026.20 compiles all 18
roots (path_trace 546,524 B / path_trace_scene 558,340 B, 18 bindings, 112 B
push), `verify_water.slang` compiles with the medium entries, and
`RtSlangWaterMediumTest` (6 guards) plus `RtWaterMediumTest` (7 numeric tests)
pass; the full suite is green. What remains of the water item is the camera
medium (a camera *inside* water starts in air, because the medium is only
entered through a surface transmission), Snell refraction and caustics of the
NEE beam under water (the shadow ray is straight), side-aware IOR on the exit
face (the BSDF assumes air-to-material), and `RtSettings.waterStyle` is still
not wired into the typed path. No owner-verified frame of any of this exists
yet. Block-emitter NEE landed in an earlier batch with its own recording
buffers (`RtSlangBlockEmitterTest`); the optical medium/eta item, the
`CameraState.view`/`.projection` identity is still
open, and the denoiser G-buffer is complete for both layouts: the entry pass
writes the seven DLSS-RR guide channels and NRD's per-pixel `IN_MV` vec4
(binding 33, the same `entity_motion_px` buffer the renderer gives the pack by
device address), so an explicit `SLANG` path with `NRD` is accepted; `AUTO`
keeps the GLSL pass until a live frame validates the guides.

Status update (2026-10-29, camera medium). The two water gaps that needed no
layout change are closed. The host now says whether the eye is in a fluid:
`RtFrameRenderer` copies `RtFogState.medium == 1` — the same vanilla camera
state that already picks the water fog — into `RtFrameParams.cameraInWater`. The
Slang pass writes it straight into the contract bit
`FRAME_FLAG_CAMERA_IN_WATER = 1u << 5u` (`RtSlangFrameData` →
`Frame.FLAG_CAMERA_IN_WATER`), additive after bits 0-4 so the 608-byte
`FrameConstants` layout is untouched. The native backend builds its own
`FrameConstants`, so the same verdict also travels as the host bit
`BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER (1u << 8)` — the core's
`make_frame_constants` maps it onto bit 5 of the frame word beside the history
bit, and `BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER (1u << 4)` advertises that
mapping: a host that reaches an older core, which rejects an unknown flag with
`INVALID_ARGUMENT`, drops the bit instead of losing the frame, and the trace
starts in air as it always did. `path_trace_entry.slang` reads the word into the
new `PathTraceOptions.primaryInWater` (a struct field, not a `PathTracePush.flags`
bit: that word mirrors host ABI bits one for one), and `tracePath` seeds the body
before the first segment with `primaryWaterMedium()` — a geometric walk straight
up from the eye that skips invalid and cutout faces and takes the first up-facing
`MATERIAL_SOURCE_FLUID` top, so it names the same biome tint and surface height a
diving transmission would. If the walk finds nothing the host still named the
medium, so the fallback puts the surface at the eye: it can only understate
depth, never invent column. The optical lobe takes `incidentIor` now, and
`eta = incidentIor >= dense ? incidentIor : incidentIor / dense` makes the ratio
exceed 1 when a ray leaves the dense side, so `refract` can return a zero vector
and the surface mirrors under water (critical angle 48.7 deg at 1.333). Evidence:
`RtSlangWaterMediumTest` (9 guards, mutation-proved by reverting each of the five
wiring points), `bedray_path_trace_pass_test` (the frame word carries bit 5 beside
the history bit, and an unlisted flag is still rejected), `:common:compileSlang`
with `slangc` 2026.20, the full `:common:test` suite and CTest. What remains of
the water item is the Snell-refracted NEE beam and its caustics (the shadow ray
is still straight) and `RtSettings.waterStyle`. No owner-verified frame of any of
this exists yet.

Status update (2026-10-30, the refracted beam). An underwater receiver is now lit
along the path the light took instead of the straight sight line. `waterBeamToLight`
bends the sample's air direction through the plane the medium record names
(`waterBeamTravel` refracts against the flat sheet and, when a wave crest has turned
its face to the beam, against that face; the ratio is `1/WATER_IOR`, so entering the
dense side has no critical angle) and returns the column length, the point where the
beam crosses, and the étendue weight `cos(air)/cos(water)` — the radiance gain and
the cone squeeze cancel the indices, so a floor under a flat sheet receives exactly
the flux the sheet let through. `traceShadowBeam` walks it in two legs: the column
with the medium-aware filter along the refracted direction, stopping a bias short of
the sheet, and the sky with the plain filter starting a bias above the crossing
(lifted along the light, not along the receiver's normal). Neither leg traces the
face itself; the crossing is charged once, explicitly, with `waterBeamTransmittance`
at the air cosine, and `surface.slang` now decodes a fluid's `f0` from the same
`WATER_INTERFACE_F0` so the two cannot drift. `waterBeamFocus` adds the caustic: the
2x2 Jacobian of the map from entry point to beam footprint at the receiver's depth,
read by finite difference over three wave normals (no extra ray), clamped to
`[0.1, 3.0]`, with a fold of the map taking the floor. The beam is offered only to
`LightKind.Sun`/`Moon` and only while `medium.inside`; a block emitter under water
keeps the straight column, because it never crossed anything. The direct term shades
`-beam.direction` — the same direction its cosine test and its BSDF use, so a beam
bent below the receiver's horizon is simply not lit — and carries
`beam.solidAngle * focus` in the estimator. Evidence: `:common:compileSlang` with
`slangc` 2026.20 (path_trace 1,038,264 B / path_trace_scene 1,065,900 B), the
extended `verify_water.slang` harness compiles on its own (56,708 B),
`RtSlangWaterBeamTest` (9 guards, double-precision ports of Snell, étendue and the
finite-difference stencil) and the extended `RtSlangRayOffsetTest` pass, and the full
`:common:test --rerun-tasks` suite reports 896 tests, 0 failures, 0 errors, 3 skipped.
All 16 mutation probes are caught (each revert names its guard) and the four shader
files come back byte-identical. The cost is measured, not assumed: the two extra walk
instantiations in `traceShadowBeam` are +426 KB / +440 KB of SPIR-V on the two path
roots (+70 %); reordering the legs onto a single shared callee was tried and is not
smaller. What remains of the water item is bending what the direct beam does not bend: the
sky-dome and emission paths seen from under water are absorbed by the medium but not
refracted at the sheet. `RtSettings.waterStyle` is no longer on that list - it reaches
the typed path as its own frame flag (`FLAG_WATER_REALISTIC`, carried by
`RtSlangFrameData` into `Frame.FLAG_WATER_REALISTIC` and consumed by
`surface/water.slang`), so the switch moves the waves and leaves the rest of the optics
alone; the secondary BSDF lobe already refracts through the interface
(`bsdfSample(..., medium.inside ? WATER_IOR : 1.0, ...)`). No owner-verified frame of
any of this exists yet.

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

`native/bedray_render_graph/` compiles a pass graph, tracks image layouts and
emits broad `ALL_COMMANDS` barriers before each pass. The native core links it by
default (`BEDRAY_ENABLE_RENDER_GRAPH`) and now drives it for the main renderer
workload: `configure_path_trace` constructs a `FrameGraphOwner`
(`src/frame_graph_owner.cpp`), which registers the whole native path-trace group
as one opaque `path_trace.frame` node, compiles the plan from the capability mask
the host reports, and binds the scheduler. `bedray_core_render_path_trace` records
that node through `record_frame`; `bedray_core_release_recording` proves
completion through `release_proven`; configuring again, destroying the pass or
destroying the core is refused while a lease remains. The owner test
(`bedray_frame_graph_owner`) covers the capability refusal, the opaque-group
shape, double release and a failed release that retains its slot, and it is part
of the 25-test CTest suite. Bloom, volumetric, particles and the frame-generation
producer still record directly; the graph is not yet the whole-frame recorder.

Adopting the graph for those passes, and for the rest of a frame, still needs the
following before it can be claimed. The adapter records into the host command
buffer through the supplied `vkCmdPipelineBarrier`, never creates a device and
never submits, so the Minecraft host keeps ownership of the queue and
presentation. Each pass must then:

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
  itself.

Two contracts must keep one owner as more passes move over: the graph's 64-bit
`required_capabilities` (logical-device feature bits) and the 10-bit Slang
capability mask (517) share the mapping in `FrameGraphOwner::plan_capabilities`
today, and a second copy elsewhere would make `compile` fail with
`missing_capability` or `is_current` report a stale plan; and
`RtMemory.createImage` creates EXCLUSIVE images without a queue family index, so
single-graphics-family ownership must be proven or an ownership transfer added.
History stays on `completed_only` until submission outcomes are connected; the
graph node does not read or write history.

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
| Radiance Cascades | `radiance_cascades.slang`, `radiance_cascades/field_mapping.slang`, `field_gather.slang`, `field_transport.slang`, `passes/rc_field_build_scene.slang`, `passes/rc_path_trace_scene.slang` | v2 finite-window field integrated into the native path-trace group: `rc_field_build_scene` builds five haloed cubemap levels (terminal trace, then reconstruct 3→0 with compose-before-average bridges) and `rc_path_trace_scene` gathers reflected direct light for a primary diffuse continuation; the core records five dependent dispatches, a 272-byte metadata UBO and the 32-byte-per-cell tails (bindings 34/35) under a 192 MiB default budget. Switch `-Dvkbridge.rt.radianceCascades=true`, which requires `nativePathTrace` + `nativeScene` and both AOT entries; the axis is `-Dvkbridge.rt.rcBaseAxis` (8/16/32). No temporal field reuse. The v1 typed atlas (`rc_probe_trace.slang`) is host-agnostic source, not the field the renderer dispatches, and its legacy float4 resolve must not be wired to the typed atlas. Evidence is the native contract plus `RtSlangRcFieldTest`; no owner-verified frame yet. |
| ReSTIR PT | `experimental/restir_pt.slang` (quarantined) | Source candidate in the tree and imported by no root (a test enforces it). v3 derives the change of measure for the fixed-first-vertex shift: reuse is restricted to a certified diffuse first vertex, the stored density transports as `q_new = q_old * G_old / G_new`, and the merge multiplies the target ratio by the Jacobian `G_new / G_old` (sky/emitter, delta and glossy first vertices are rejected). v4 makes the reservoir's `M` the number of attempts: `ptAddPath` admits every valid first edge (v3 gated it on `ptPathReusable`, which conditioned the estimate on a recorded hit and scaled it by `1/P(recorded)`), while the certified-class gate stays in the merge/temporal/spatial steps that transport a density. Derivation and CPU validation: `docs/RESTIR_GI_DERIVATION.md`; `RestirPtChangeOfMeasureTest` and `RestirPtReservoirInvariantTest`; the module compiles through the pinned `slangc`. Still missing: a pass, a manifest-checked host and GPU evidence. |
| Path tracing | `path_trace`, `path_trace_scene`, `restir_spatial`, `path_display` | Controller -> ReferencePathTraceBackend or opt-in NativePathTraceBackend | Native scene/AS and immutable host capture are integrated. Native mode owns private images/uploads, DI/history/parity, opaque graph scheduling and exact completion/discard. Seven DLSS-RR guides and the NRD motion buffer are forwarded by additive calls when the matching native features/exports are available. Unsupported states are reported explicitly; there is no GLSL fallback. L1/L2, no live validation. |
| Volumetric shafts | `volumetric_march`, `volumetric_march_scene`, `volumetric_apply` | `RtSlangVolumetricPass` -> coarse native `VolumetricPass` | C++ owns history/uploads, two dispatches and barriers. Native/legacy scene tables remain distinct; visibility is world-only and nonzero bob origin is explicitly unsupported. L1/L2, no live validation. |
| Particle/weather composite | `particles_composite` | `RtParticleComposite` -> stateless native recorder | C++ packs descriptors/push and records barriers; host retains game-layer views through exact proof. L1/L2, no live validation. |

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
path-trace manifest additionally pins its twenty-five bindings, the 64-byte
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

### What the deleted GLSL guards covered

Deleting the GLSL invalidated every test whose oracle was GLSL text. Such a
test compares the port against a file that no longer exists, which proves
nothing about the port, so each one was either re-aimed at the Slang source it
is supposed to protect or removed.  The first group is closed:

* the self-avatar exclusion now reads `traversal/scene_traversal.slang`
  (`selfAvatarPrimitive`, `tracePrimary`) and `passes/path_trace.slang`;
* the vegetation hit bits now read `traversal/legacy_section_traversal.slang`;
* the ReSTIR DI and spatial pool numbers are pinned literally against
  `restir/*.slang` instead of by comparison with `rt_restir_spatial.comp`;
* the live velocity reprojection body is pinned inside
  `history/velocity_live.slang`;
* the NRD motion channel reads `pathtrace/path_tracer.slang` and the
  binding record in `RtFrameBindings`;
* the block-emitter falloff, chromatic categories and shadow bias read
  `light/block_emitters.slang`, and the entity and G-buffer hermetic masks read
  `surface/surface.slang` and `pathtrace/path_trace_entry.slang`;
* the whole water model is guarded by `RtSlangWaterTest`: the wave table and
  its deep-water dispersion, the volumetric absorption and dye split, the
  medium entry rules, the refracted beam and its column bound, the étendue
  ratio and the caustic Jacobian, all read out of `surface/water.slang` and
  cross-checked against the host's own water rule in `AutoPbr` and
  `OpticalMaterials`. Six mutation probes (retuned wave mix, replaced
  dispersion law, dropped fold guard, frozen étendue, absorption reading the
  style switch, column taken from the sight line) were each caught, and the
  shader was restored byte-identically.
* the canopy is a filter again, as the legacy path had it:
  `RtSlangFoliageTransmissionTest` pins the shadow-only leaf layer
  (`0.60 * mix(1, hue, 0.45)`, ~36 % through one leaf block), the
  cutout-and-not-emissive gate, the two-sided key-light acceptance, the
  back-lit term outside the front-face gate (`0.42`,
  `smoothstep(0, 0.08, ...)`, `float3(1.05, 1.25, 0.75)`), and the sun-side
  shadow origin (one block for a front-face leaf cube, one centimetre for a
  card, the bent beam under water), read out of `surface/surface.slang`,
  `surface/visibility.slang`, `light/sun.slang` and
  `pathtrace/path_tracer.slang`. Fifteen mutation probes (two-sided cosine,
  beam and occluded setter, layer constant, tag copy, flag bit, both emissive
  gates, sun-side normal, cube step, underwater origin, estimator and
  no-shadow branch, front-face gate, silhouette ramp) were each caught, and
  the shaders were restored byte-identically.
* pixel shadows are the cell policy the deleted
  `rt_common_pixel_shadow.glsl` ran, carried in the existing flag word:
  `PATH_TRACE_OPTION_PIXEL_SHADOWS` (bit 7) plus the cell size in bits 8..15
  in 1/16-block units (the legacy `shadowPixel/16`), so `PathTracePush` stays
  112 B. `pathPixelShadowOrigin` snaps the receiver - never the caster -
  horizontally to its cell centre, projects the lateral onto the surface
  plane and keeps the snap only when the one validation ray is unblocked; the
  estimator and the world-locked disc rotation hash the same
  `pathTraceShadowCell`, only the primary, non-entity, non-leaf, above-water
  receiver pays, and a leaf freezes to its block at frame 0 as the legacy
  `sunSampleFrame` did. The sampler draws from `LightReceiver.sunSeed` when
  `stableSun` is set and replays the same draws into the path stream otherwise,
  so an ordinary receiver keeps its exact per-path sequence.
  `RtSlangPixelShadowTest` (10) mirrors the cell arithmetic, the projection,
  the blocked-cell fallback, the primary-only rule, the foliage freeze, the
  stream sync and the host clamp, read out of `pathtrace/path_tracer.slang`,
  `pathtrace/path_trace_entry.slang`, `light/sun.slang` and
  `contracts/light.slang`. Eighteen mutation probes (flag bit, size shift,
  entry decode, primary gate, underwater gate, blocked snap, projection sign,
  cell scaling, foliage frame/cell, y-shear, stable seed, stream sync, rotation
  cell, receiver default, host clamp, host flag OR) were each caught, and every
  mutated file was restored by sha256.
* the distant block-light handover the deleted `rt_common_shade.glsl` ran is
  back as a mix, not a max. `light/block_emitters.slang` owns the field:
  `blockLightFarMix` (a smoothstep over `listRange ± EMITTER_RADIUS`, the inner
  edge floored at 8 blocks so a small collection range cannot put baked light
  on the player), `BLOCK_LIGHT_FAR_FULL` (0.999), `BLOCK_LIGHT_FIELD_GAIN`
  (0.05) and `vanillaBlockIrradiance` (the pre-lightmap warm tint
  `(1.0, 0.82, 0.60)`, block light only - the field stands in for the emitter
  estimator, not for the sky). The tracer fades the emitter share of the
  composite draw by `1 - emitterFar` (fresh and reused alike), adds
  `pathBakedBlockLight * emitterFar` - the reflecting lobe's albedo, the
  `1 - Fsc` the interface did not take and the LabPBR AO, with no cosine and
  no 1/pi, exactly the tuned legacy term - and past `BLOCK_LIGHT_FAR_FULL`
  `LightReceiver.bakedBlockLight` makes `SunAndBlockEmitterSampler` skip the
  emitter branch entirely; the key light keeps full density, and the
  end-of-path `reuseClear` keeps a skipped reservoir from persisting. The
  distance is `length(hit.position)`, camera-relative the way the list was
  sorted. The range reaches the tracer as `PathTraceOptions.emitterListRange`
  from `emitterSampler.maxDistance` (offset 76, so `PathTracePush` stays
  112 B), and the option is flag bit 16, which the host sets only while
  `SLANG_BLOCK_EMITTERS`/`RtSettings.emissive()` keeps emitters on - a frame
  with the switch off gets no field either. `RtSlangDistantBlockLightTest` (9)
  mirrors the band, the gain and tint, the mix structure, the primary-diffuse
  gate, the exhausted-list skip and the host wiring, read out of
  `light/block_emitters.slang`, `pathtrace/path_tracer.slang`,
  `pathtrace/path_trace_entry.slang`, `contracts/light.slang` and the two host
  classes. Twenty-two mutation probes (band floor, gain, tint, clamp, sampler
  gate, fade on both branches, distance source, bounce/metal/light gates, reuse
  skip, verdict, albedo count, entry range, host flag, host switch, path
  range, contract default, bit collision, clear) were each caught, and every
  mutated file was restored by sha256.
* the glass cost the deleted `RtGlassCostTest` measured is now a policy of the
  typed walk. `surface/visibility.slang` spends one closest-hit query per
  layer, so the transmittance floor (`SURFACE_VISIBILITY_MIN_TRANSMITTANCE`,
  1e-3) ends an absorbed ray before the next pane, and every filtered layer and
  every hole advances past its own candidate through `skipSurfaceCandidate`
  (`ray.tMin = hitDistance + bias`; the origin stays, so `hit.t` is still
  measured from the original ray). The medium walk applies
  `waterSegmentTransmittance` to the column first and the interface's
  `1 - Fsc` Fresnel share second, because a fluid face tints once and the
  colour is the distance absorption. `RtSlangGlassCostTest` (5) reads the two
  constants out of the shader as the model's own inputs, proves five 25 %
  panes trip the floor before the 16-layer budget while a clean window fills
  it, and pins the four floor-to-skip adjacencies, the six advances, the
  hole's advance-without-filter and the fluid share against
  `surface/visibility.slang` and `surface/surface.slang`.
* the NaN guards the deleted `RtShaderNaNGuardTest` pinned are structural in
  the typed modules. `safeNormalize` gates the squared length on
  `MATH_EPSILON` (1e-6), both GGX half vectors are
  `safeNormalize(V + L, N)`, the block emitter rejects a zero density and a
  zero cosine before its divisions and keeps the `max(cosReceiver, 1e-4)` and
  `0.001` shadow-length floors, the sun rejects a zero source PDF after the
  divide, the tangent frame needs its det gate *and* guarded numerators (a
  collinear edge zeroes one while the UVs stay distinct), and the primary
  normal is replaced below 1e-4 at both entry points; no shading or NEE module
  carries a bare `normalize(`, and the entry's three are the explicit camera
  basis plus the two gated lines. The camera ray builder has no homogeneous
  divide at all (explicit basis, no matrix multiply, no division) - stronger
  than the deleted `safeW` guard - and both velocity modules sanitise a
  non-finite reprojection to zero motion plus a full disocclusion, which is why
  the live-parity module keeps the bare `w` divide its GLSL source had.
  `RtSlangNaNGuardTest` (9) mirrors the helpers numerically and the call sites
  structurally, read out of `contracts/math.slang`, `shading/bsdf.slang`,
  `shading/microfacet.slang`, `light/block_emitters.slang`, `light/sun.slang`,
  `pathtrace/path_trace_entry.slang`, the two velocity modules and the
  velocity pass. Thirty-one mutation probes (floor branch, floor order, floor
  value, layer budget, hole skip/break/filter, skip origin, medium filter,
  fluid and non-fluid share, gate value and comparison, both half vectors,
  emitter direction/cosine/floor/bias, sun axis and PDF order, tangent det and
  numerator, both entry gates, a new bare normalize, the camera divide and the
  camera matrix, both velocity sanitises, the pass clip-w gate) were each
  caught, and every shader was restored by sha256.

The second group was closed in the hot-loop/layout batch, with one deliberate
reinterpretation:

* the rules of the deleted `RtPathHotLoopTest` and `RtOptimizationTest` are now
  `RtSlangHotLoopTest` (7): one decode per accepted hit, the primary record
  filled at the first accepted surface, the sky evaluated only on a miss, an
  ambient-only bounce stopping at geometry, one key-light draw per vertex, the
  reservoir reset by `emitters.fingerprint()` rather than a section version,
  and no shading normal built by a transmittance walk. The last rule had been
  lost in the port: `decodeSurface` carries the legacy `wantNormal` switch
  again, and both walks of `surface/visibility.slang` plus the camera's
  `primaryWaterMedium` decode with `false`, so a shadow walk pays for no atlas
  normal, AO texel, entity auto-normal or wave fit. Seven mutation probes
  (entity gate, atlas gate, the shaded overload, one visibility walk, the sky
  branch, the fingerprint, plus the build pin) were each caught, and every file
  was restored by sha256.
* the reach the deleted `RtBlockLightReachTest` measured is
  `RtSlangBlockLightReachTest` (7): the `d^-1.5` → `d^-1.25` seam at two
  blocks, the 24-block squared window, the rsqrt form (no `pow`), the candidate
  distance/self gate read before the atlas fetch, the hoisted `lightRadius`,
  and the sqrt-ladder index PMF. Mutation-proved by moving `EMITTER_RADIUS`.
* `RtStatsLayoutTest` has no field-by-field successor, because the struct it
  mirrored has no shader on the other end. The typed stats ABI is the four
  counter words (16 B) that `passes/frame_stats.slang` writes through
  `RWStructuredBuffer<uint>`; no pass imports the legacy `FrameStats` or
  `PushConstants` declarations (only `backend_query.slang` and
  `bedray_legacy.slang` import the module, and neither names them), so the
  slot's tail is host-only scratch, not a layout a shader can misread.
  `RtSlangLayoutAbiTest` (8) pins the blocks that do have a consumer -
  `FrameConstants` 608 B with every section offset, `PathTracePush` 112 B with
  the 16 members and the emitter/di/entity offsets, `DIReservoir` 64 B,
  `BlockEmitterRecord` 96 B, `FrameStatsPush` 8 B, the four counter words vs
  the host's 16 B, and the legacy 128-byte push against `RtFrameParams` - and
  proves the reachability boundary that keeps the tail dead. `gradle/slang.gradle`
  keeps the build-time pin; a same-size swap of `diState`/`entityState` fails
  that pin, and with it skipped fails the unit test, then was restored by
  sha256.

The third group tested the deleted classes themselves (runtime compiler
options, the SPIR-V disk cache, the GLSL include graph, the `rtx/raytracing`
experiment engine) and is gone with no loss. The sun and moon disc did not
lose coverage: `RtSlangSkyDiscTest` already guards `light/sky.slang`.

## Next steps

1. **Path-trace native integration** — the C++ group owns resource allocation,
   frame data, the reflected descriptor mapping, dispatch, completion/discard and
   retirement; Java supplies Minecraft-owned state and the command buffer. The native
   scene, the seven denoiser guides, NRD motion, the frame-stats stage and the RC field
   are forwarded by additive calls. Remaining: an owner-verified live frame, and
   agreeing on view depth versus ray distance before a downstream consumer treats
   `outDepth` as linear view depth. Unsupported device capabilities are reported, not
   routed to GLSL.
2. **Sample rejection** — the tracer keeps the two bad endings apart
   (`PATH_TRACE_FLAG_INVALID_SAMPLE` for a non-finite fault,
   `PATH_TRACE_FLAG_TRUNCATED` for a path that only stopped short) and
   `pathTraceSampleRejected` publishes that verdict through the alpha lane of the
   radiance image, which the display pass turns into a weight of 0 or 1. The host now
   reads the total back: `RtStatsMetering` mirrors the counter pass's 1-in-16 tile walk
   (the test enumerates both masks, edge tiles included) and the renderer prints
   `reject_pct = metered - samples` on the F3 `rt_pass` line - n/a when the slot holds
   no measurement or when the count exceeds the mask, which would mean host and shader
   disagree. Still open: the split by reason (invalid versus truncated) and the
   visibility-overflow count; those belong to the C++ pass, which owns the buffers, and
   need a new binding/ABI.
3. **ReSTIR PT** — path-space reservoirs on top of `PathVertex`; keep it
   separate from the DI reservoir so the two reuse policies do not merge. The source
   candidate is quarantined in `experimental/restir_pt.slang`. Its change of measure is
   derived and CPU-validated (diffuse first vertex, `G_new/G_old` Jacobian; see
   `docs/RESTIR_GI_DERIVATION.md`), and v4 makes its attempt count match the estimator
   (every valid first edge is an attempt; only certified paths may merge). Reuse cannot
   ship until the module has a pass, a manifest-checked host and a GPU smoke run.
4. **Entity shading parity** — closed in `e1bcd82`: the ring is traced, moving
   parts reproject, an End Portal is drawn as the procedural starfield, mob skin
   gets its **Sobel auto-normal**, and glowing parts are **next-event
   candidates**. No owner-verified frame of these behaviours is recorded yet.
4a. **Water** — the surface model and the volumetric medium are ported: wave
   field, biome tint, classification, Fresnel interface, depth absorption,
   in-scattering with the multiple-scatter floor, and a medium-aware shadow
   walk. Closed: a camera that starts *inside* water seeds its own body (the
   host publishes `FRAME_FLAG_CAMERA_IN_WATER` from `RtFogState.medium`, and
   `primaryWaterMedium` walks up from the eye for the surface it sits under),
   the optical lobe refracts from the medium the ray travels in (a path leaving the
   body bends away from the normal and can totally internally reflect), and
   `RtSettings.waterStyle` reaches the typed path as its own frame flag so the toggle
   moves the waves only. Still open: bending what the direct beam does not bend — the
   sky-dome and emission paths seen from under water are absorbed but not refracted at
   the sheet. Closed in the same batch as the medium: the underwater NEE is now a
   Snell-refracted two-leg beam with a wave-focus caustic (`waterBeamToLight`,
   `traceShadowBeam`, `waterBeamFocus`).
4b. **Denoiser G-buffer** — the entry pass writes the seven DLSS-RR guide
   channels (`outViewZ`, `outNormalRough`, `outDiffuse`, `outAlbedo`,
   `outDirect`, `outSpecular`, `outSpecAlbedo`, bindings 26–32) at the primary
   vertex, plus NRD's per-pixel `IN_MV` vec4 into binding 33
   (`outMotionBuffer`, the renderer's existing `entity_motion_px`). The
   renderer no longer refuses the denoiser under a trace mode: the pure
   `DenoiserResolution` table decides from the setting and the libraries alone,
   and the pass builds its guide variant whenever a denoiser is active. The C++ backend forwards
   the seven guides through the additive
   `bedray_core_render_path_trace_guides` call (`GUIDE_OUTPUTS`), so
   `nativePathTrace` + `DLSS_RR` no longer blocks on missing outputs. The additive NRD_MOTION feature and
   `bedray_core_render_path_trace_motion` also forward the borrowed vec4-per-pixel
   buffer at binding 33. Native NRD requires that matching feature/export; the
   old calls reject enabled motion writes without real targets. A rejected
   sample is closed: the entry computes `pathTraceSampleRejected`
   (`INVALID_SAMPLE | RC_UNRESOLVED`) once, and the alpha lane, `outMotion`, the
   seven guides and binding 33 all read that one verdict, so a refused sample
   writes the neutral miss set and zero motion. A truncated path stays trusted:
   it stopped short, but its primary vertex is real. Open: an owner-verified
   frame that consumes the guides through DLSS RR/NRD, and the artifact check
   that goes with it (the table and the guide layout are unit-proven, not run).
5. **Radiance Cascades trace/update** — the v2 finite-window field is integrated into
   the native path-trace group: `rc_field_build_scene` builds five haloed cubemap
   levels (terminal trace, then reconstruct 3→0 with compose-before-average bridges)
   and `rc_path_trace_scene` gathers reflected direct light for a primary diffuse
   continuation. Enable with `-Dvkbridge.rt.radianceCascades=true` (which needs
   `nativePathTrace` + `nativeScene` and both AOT entries) and pick the axis with
   `-Dvkbridge.rt.rcBaseAxis` (8/16/32). Remaining: temporal field reuse and an
   owner-verified frame that proves the field in a real scene. The v1 typed atlas is
   host-agnostic source; do not wire the legacy float4 resolve to the typed atlas.
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
