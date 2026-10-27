# Bedray 2.0 — Slang shader architecture

This document replaces the first Slang migration draft.  The first draft
described a 1:1 port of the existing GLSL.  Bedray 2.0 is a new shader core:
the old GLSL stays in the tree as a behavioural reference, not as a source to
copy line by line.

**Current whole-migration evidence: L0 — source and build wiring.** Prior
agents reported scoped compilation and unit tests for particular revisions;
those results do not establish that the current complete tree builds. Existing
bloom code contains a legacy GLSL fallback that conflicts with the target
architecture and is not the final integration. A compile or test is not proof
of rendering in Minecraft; that requires a fresh matching live run and evidence.

## Design rules

1. **Contracts before passes.**  A pass may only depend on a contract module
   (`contracts/`) and on an interface (`ITraversal`, `IDirectLightSampler`).
   It must not read a legacy `Section`/`Surface` record directly.
2. **No GLSL include chains, no `#ifdef` variants.**  A backend is a Slang
   type that implements `ITraversal`; the host specializes it at AOT time.
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
3. `bedray_core_render_frame` validates the resources, allocates a fresh
   descriptor pool/set for that dispatch, updates the descriptors, binds the
   pipeline, pushes constants and records `vkCmdDispatch`. It inserts no
   barriers itself: native graph/host integration must supply synchronization.
4. `bedray_core_release_recording(command_buffer)` destroys every descriptor
   pool recorded into that command buffer.  The host calls it only after all
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
├── bedray.slang                       root: re-exports the modules
├── contracts/
│   ├── contracts.slang                aggregate import
│   ├── frame.slang                    FrameConstants, CameraState, SunState
│   ├── material.slang                 MaterialSample, AlphaMode, source flags
│   ├── geometry.slang                 VoxelSection, VoxelGrid, GeometrySection
│   ├── hit.slang                      Ray, SurfaceHit, VisibilityResult, RayPayload
│   ├── history.slang                  HistoryHeader/Sample/Policy, reprojection
│   ├── math.slang                     constants, luminance, safeNormalize, frames
│   └── light.slang                    LightSample, ILightSampler
├── traversal/
│   ├── traversal.slang                ITraversal
│   ├── voxel_traversal.slang          BitmaskVoxelTraversal + VoxelWorld
│   ├── hardware_traversal.slang       HardwareTraversal (VK_KHR_ray_query)
│   ├── legacy_section_traversal.slang LegacySectionTraversal (RtWorld 64-byte table + device-address arena)
│   └── hybrid_traversal.slang         HybridTraversal<V, H>
├── voxel/
│   └── voxel.slang                    single-section Bitmask HDDA
├── light/
│   ├── emitters.slang                 EmitterRecord + UniformEmitterSampler
│   ├── sun.slang                      SunMoonSampler
│   └── sky.slang                      IEnvironmentSampler + GradientSky
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
│   └── direct_light.slang             compatibility re-export of light/emitters.slang
├── radiance_cascades/
│   └── radiance_cascades_store.slang  atlas layout, interval storage, temporal blend
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
    └── path_trace.slang               first path-trace entry over LegacySectionTraversal
```

`passes/` is the AOT entry-point root: `compileSlang` compiles every
`**/*.slang` below it, so a new stage is a new file, not another `#define` in
an existing shader.  The four bloom files share `bloom/bloom.slang` and
`tonemap_curve.slang`; imports are not transitive, so an entry file imports
every module whose symbols it uses.

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
| `material.slang` | decoded albedo/roughness/f0/emission/alpha mode/optical fields | atlas sampling, traversal decisions |
| `geometry.slang` | `VoxelSection` occupancy masks, `VoxelGrid`, `GeometrySection` ranges | section directory buffers, TLAS handles |
| `hit.slang` | `Ray`, `SurfaceHit`, `VisibilityResult`, `PathVertex`, `RayPayload` | backend-specific hit structures |
| `history.slang` | previous view-projection, reprojection, validation, blend weight | per-feature reservoir records |
| `light.slang` | `LightSample`, `LightEvaluation`, `ILightSampler` | reservoir math, traversal handles |

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

All three implement `ITraversal`: `traceClosest`, `traceVisibility`,
`traceDistance`.  Material alpha tests are **not** in traversal; the material
stage applies `materialBlocksVisibility` / `materialAcceptsAlpha` so voxel and
hardware paths agree on foliage and glass.

## Integrated passes

| Pass | Slang entry files | Host | State |
|---|---|---|---|
| Bloom + sun glare | `passes/bloom_prefilter_down`, `bloom_down`, `bloom_up`, `bloom_apply` | `RtBloomPass` (existing WIP) | Java-side candidate has a GLSL fallback; both ownership and fallback need migration to the target C++/Slang architecture. The C++ ownership, mip-lifetime, barrier and missing-ABI plan is in [BLOOM_CPP_INTEGRATION_PLAN.md](BLOOM_CPP_INTEGRATION_PLAN.md); no current complete-tree or live validation. |
| Composite | `passes/composite.slang` | `RtCompositePipeline` | AOT-compiled, but the host path is disabled (`LEGACY_RASTER_ENTITY_COMPOSITE = false`); it is not proof of the active renderer. |
| Path tracing | `passes/path_trace.slang`, `passes/path_display.slang` | `RtSlangPathPass` → `RtPathTraceSlangPass` | Selected by Path Tracing mode in `RtFrameRenderer`; `AUTO` keeps the legacy GLSL `rt_path.comp` when the AOT passes, device capabilities or a G-buffer denoiser block it. Reads the existing TLAS and the 64-byte `RtWorld` section table through `LegacySectionTraversal`; requires `CAP_RAY_QUERY \| CAP_BUFFER_DEVICE_ADDRESS \| CAP_SHADER_INT64`. No G-buffer, auto exposure, `rt_sky.glsl` or view-bob offset yet; no live-frame validation is recorded here. |

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
| `materialAtlas` | 9 | LabPBR `_s` sampler (legacy binding 9) |
| `normalAtlas` | 10 | LabPBR `_n` sampler (legacy binding 10) |
| `outDepth` | 12 | R32F storage image; first-hit distance, `1e6` on a miss |
| `frame` | 16 | 608-byte `FrameConstants` uniform buffer, one upload per frame |
| `blockMaterials` | 17 | dummy `BlockMaterial` table until voxel materials exist: allocate at least one 64-byte element and push `materialCount = 0` |

The push block is 40 bytes.  It carries sampling and path limits only: the pass
writes scene-referred radiance, and `FrameConstants.exposure` is applied once by
the display/tonemap pass, so accumulation and denoise stay in linear HDR and
exposure cannot be applied twice.  The pass requires `CAP_RAY_QUERY |
CAP_BUFFER_DEVICE_ADDRESS | CAP_SHADER_INT64` (517).  Path tracing uses its own
`SlangRuntime`, created from `SlangDeviceCapabilities.enabledMask`; the mask-0
runtime in `RtAtmosphere` belongs to bloom only. The final C++ renderer must
negotiate the features actually enabled on the game's device and select only
supported methods. If this path is unsupported, the Java `AUTO` selector logs
one fallback line and uses the legacy GLSL `rt_path.comp`; that fallback is a
detachable compatibility path, not the target architecture.

Depth keeps the legacy sentinel: bloom treats `depth >= 1e5` as sky
(`BLOOM_SKY_DISTANCE`), and the path-trace entry writes `1e6` whenever the
primary ray accepted no surface — including a ray that only crossed cutout
holes, which is why the result carries `PATH_TRACE_FLAG_PRIMARY_HIT` instead of
letting the host infer a hit from a distance.  Cutout holes advance the primary
origin, and the accepted first hit reports `primaryAdvance + hit.t`, so the
distance stays measured from the camera rather than from the last hole.  A
primary ray whose data is non-finite, whose interval is empty (`tMin < 0` or
`tMax <= tMin`), or whose cutout advances exhaust `tMax` sets
`PATH_TRACE_FLAG_INVALID_SAMPLE` and never adds environment radiance: an
invalid or truncated path is not a proven miss.  `firstHitDistance` is a
distance along the unit primary direction, not linear view depth; a consumer
that needs view depth must derive it from the camera contract.

The wired adapter is `RtSlangPathPass`, a public `RtPass` that
`RtFrameRenderer` constructs for Path Tracing mode; it owns a package-private
`RtPathTraceSlangPass` plus the `path_display` program. The adapter owns the
RGBA32F running-mean image; the trace pass creates the RGBA16F radiance and R32F
depth images lazily from the frame size, keeps a ring of three separate 608-byte
uniform buffers (the engine keeps at most two frames in flight; separate buffers
avoid `minUniformBufferOffsetAlignment` issues with a 608-byte stride), zeroes
one 64-byte `BlockMaterial` element, validates the generated manifest against
the nine bindings above, and records the trace dispatch, the display dispatch
and a full memory barrier. The renderer keeps
ownership of the TLAS, section table and atlas views. Every command buffer
passed to `dispatch` must reach `releaseRecording` after its submission
completed or the recording was discarded, and `close()` must run before
`SlangRuntime.close()`. This adapter is the current Java host for Path Tracing
mode, not the final ownership model: the C++ renderer must take over resources,
recording and lifetime, and the adapter must not grow into a parallel renderer.

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
  and supply the matching 40-byte push block. The bridge does not do this mapping
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
| ReSTIR DI | `restir/restir_di.slang` | reservoir storage, initial sample, temporal/spatial merge, occlusion feedback, raw and material-filtered visibility resolve; no pass yet |
| Radiance Cascades | `radiance_cascades.slang`, `radiance_cascades/segment_store.slang` | typed segment storage is a source candidate; typed trace exists in an isolated agent workspace, but cross-cascade resolve and host integration are unfinished. The old float4 resolve is incompatible with the typed trace. |
| ReSTIR PT | isolated agent workspace | source candidate exists outside the shared checkout; review and integration remain open. |
| Path tracing | `pathtrace/path_tracer.slang`, `passes/path_trace.slang` | iterative loop over `ITraversal`, NEE through `ILightSampler`, filtered shadows, Russian roulette, first-hit distance; `RtSlangPathPass` wraps `RtPathTraceSlangPass` and `path_display` in Path Tracing mode, with `AUTO` falling back to GLSL when the Slang path is blocked |

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

The user has deferred new builds and tests until the full migration is
complete. The commands below describe later verification; they are not an
instruction to run checks during the current migration phase.

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
path-trace manifest additionally pins its nine bindings, the 64-byte
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
2. **ReSTIR PT** — path-space reservoirs on top of `PathVertex`; keep it
   separate from the DI reservoir so the two reuse policies do not merge.
3. **Radiance Cascades trace/update** — review the isolated typed trace, align
   its segment status and history contracts with the shared store, and define
   cross-cascade reconstruction and host integration before connecting a pass.
4. **More entry passes** — denoise/composite follow the same one-file-per-entry
   pattern and the same ABI; no second pipeline path.
5. **Live game check** — only after the above and an explicit request.  A
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
