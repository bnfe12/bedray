# Slang Migration Guide

## What this branch does

This branch (`refactor/slang-pipeline`) rewrites the Bedray shader core from
raw GLSL 460 to **NVIDIA Slang**. Slang compiles to standard **Vulkan SPIR-V**
and optionally to **Apple Metal (MSL)**, **DirectX HLSL** and **CUDA PTX**.

The Java mod code, C++ native bridges and Gradle build remain unchanged.
Only the files under `common/src/main/resources/shaders/` are affected.

## Why Slang

| Problem with current GLSL | What Slang fixes |
|---|---|
| `#ifdef VKB_GBUFFER`, `#ifdef VKB_ACCUM` spaghetti | Real modules (`import`), interfaces and generics |
| Runtime `shaderc` compilation causes startup freezes | AOT compilation by `slangc` during Gradle build; game ships prebuilt `.spv` |
| No way to separate RTX 20 code from RTX 50 code cleanly | `interface IRayBackend` with compile-time specialization per GPU tier |
| `RtShaderCompiler` string concatenation is fragile | Slang has a real module system with `import` and `__include` |
| Poor register pressure optimization | `slangc` produces better SPIR-V for Turing/Ampere/Ada/Blackwell than `glslang` |

## GPU tier system

The mod detects GPU capabilities at startup via `VkPhysicalDeviceProperties`
and selects one of three tiers. Each tier compiles from the **same Slang source**
using Slang's `interface` + `__specialize` mechanism.

| Tier | GPUs | Tracing | Denoiser | Upscaler |
|---|---|---|---|---|
| **1 — Performance** | RTX 20xx, RTX 3050/3060, RX 6600/6700, Arc A580 | Hybrid Fast RT (1 bounce + ReSTIR DI) | NRD Reblur Diffuse+Specular | DLSS Perf / FSR Perf / XeSS Perf |
| **2 — Balanced** | RTX 3070–3090, RTX 4060, RX 7700/7800, Arc A770/B580 | Path Tracing (2–3 bounces + ReSTIR DI) | NRD Reblur Diffuse+Specular or DLSS-RR | DLSS Quality / FSR Quality / XeSS Quality |
| **3 — Ultra** | RTX 4070–4090, RTX 50xx | Full Path Tracing (4–8 bounces + ReSTIR DI + ReSTIR GI) | DLSS 3.5 Ray Reconstruction | DLSS Quality + Frame Gen (2x/3x/4x) |

### Tier 3 exclusive hardware features

These require Vulkan extensions that only exist on Ada Lovelace / Blackwell:

- **Opacity Micromaps** (`VK_EXT_opacity_micromap`): hardware alpha-test for foliage.
- **Shader Execution Reordering** (`VK_NV_ray_tracing_invocation_reorder`): hardware warp sorting for divergent secondary rays.

On GPUs without these extensions the code path is simply absent (not branched —
Slang eliminates it at compile time).

### UMA detection (Steam Deck, Apple Silicon, ARM)

When `VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT` and `HOST_VISIBLE_BIT` overlap,
the system uses unified memory. The RT geometry budget is halved and zero-copy
buffer sharing is enabled to avoid duplicating chunk data.

## Directory layout

```
common/src/main/resources/shaders/
├── glsl/                    ← old GLSL shaders (kept during migration, removed after)
├── slang/                   ← new Slang source
│   ├── bedray.slang         ← root module: re-exports everything
│   ├── types.slang          ← Section, Surface, HitInfo, PushConstants, StatsBuf
│   ├── bindings.slang       ← TLAS, atlases, G-buffer, accumulation image
│   ├── sampling.slang       ← pcgHash, rand01, cosineHemisphere, basisAround, R2 sun disk
│   ├── brdf.slang           ← GGX VNDF, Fresnel, Lambert, energy-conserving split
│   ├── sky.slang            ← procedural sky, sun disk, atmosphere
│   ├── light.slang          ← block light color, lightmap access, emitter radius
│   ├── emitters.slang       ← ReSTIR DI: reservoir, temporal merge, spatial reuse, NEE
│   ├── surface.slang        ← hit decoding, LabPBR material, alpha test, normals
│   ├── water.slang          ← Snell refraction, medium absorption, water surface
│   ├── transparency.slang   ← batched interface gathering, optical profiles
│   ├── shade.slang          ← AO, sky light, baked light fill, distant block light
│   ├── temporal.slang       ← temporal accumulation, motion vectors, history validation
│   ├── denoise_pack.slang   ← NRD G-buffer packing (diffuse + specular channels)
│   ├── tonemap.slang        ← tone mapping, auto exposure
│   ├── noise.slang          ← blue noise, interleaved gradient noise
│   ├── caustics.slang       ← analytic water caustic patterns
│   ├── ray_backend.slang    ← IRayBackend interface
│   ├── backend_query.slang  ← Tier 1/2: VK_KHR_ray_query implementation
│   ├── backend_ser.slang    ← Tier 3: ray_tracing_pipeline + SER implementation
│   ├── rt_path.slang        ← path tracing entry point (uses IRayBackend)
│   ├── rt_rays.slang         ← ray effects entry point (shadows, AO, reflections, GI)
│   ├── rt_restir_spatial.slang ← ReSTIR DI spatial reuse pass
│   ├── rt_atrous.slang       ← A-Trous wavelet denoiser (Pure Vulkan fallback)
│   ├── rt_bloom.slang        ← bloom filter
│   ├── rt_composite.slang    ← final compositing
│   ├── rt_volumetric.slang   ← volumetric fog and light shafts
│   ├── rt_pack_nrd.slang     ← NRD buffer packing dispatch
│   ├── upscale/
│   │   ├── fsr1.slang        ← FSR 1 spatial upscaler
│   │   └── nis.slang         ← NVIDIA Image Scaling
│   └── voxel/
│       └── voxel.slang       ← software voxel fallback (no RT cores needed)
└── spatial/                 ← third-party upscaler shaders (FSR 1, NIS — unchanged)
```

## How to build

### Prerequisites

1. **Slang SDK** — download `slang-<version>-windows-x86_64.zip` from
   https://github.com/shader-slang/slang/releases and extract it.
   Set the environment variable `SLANG_DIR` to the extracted directory.
   Linux: use the Linux release archive the same way.
2. **Java 25**, **Gradle** — same as the main project.
3. **Minecraft 26.3**, **Sodium 0.9.x** — same as the main project.

### Build commands

```bash
# Compile only shaders (produces .spv files in build/shaders/)
./gradlew compileSlang

# Full mod build (compiles shaders, then builds the Java mod)
./gradlew build
```

The Gradle task `compileSlang` invokes `slangc` for each entry-point `.slang`
file. Output SPIR-V bytecode is placed under
`common/build/resources/main/shaders/` and packaged into the mod JAR.

No runtime shader compilation happens. The mod loads `.spv` files directly
via `vkCreateComputePipelines`.

### Cross-compilation targets

```bash
# SPIR-V (Vulkan — primary target)
slangc -target spirv -o rt_path.spv rt_path.slang

# Metal (macOS/iOS — future target)
slangc -target metal -o rt_path.metal rt_path.slang

# HLSL (DirectX — reference only)
slangc -target hlsl -o rt_path.hlsl rt_path.slang
```

## How to add a new shader module

1. Create `shaders/slang/my_module.slang`.
2. Add `import my_module;` in the file that uses it.
3. If it is a new compute entry point, add a `[shader("compute")]` attribute
   on the `main` function and register it in `build.gradle` under `slangEntryPoints`.
4. Run `./gradlew compileSlang` to verify.

## How to add a GPU-tier-specific feature

Use the `IRayBackend` interface:

```csharp
// ray_backend.slang
interface IRayBackend {
    bool traceVisibility(float3 origin, float3 dir, float tmax);
    float traceDist(float3 origin, float3 dir, float tmax);
    HitInfo traceClosest(float3 origin, float3 dir, float tmax);
};
```

Implement it twice:

```csharp
// backend_query.slang — Tier 1 and 2 (all GPUs with VK_KHR_ray_query)
struct RayQueryBackend : IRayBackend { ... };

// backend_ser.slang — Tier 3 (RTX 40/50 with VK_NV_ray_tracing_invocation_reorder)
struct SerBackend : IRayBackend { ... };
```

The path tracer code does not know which backend it uses:

```csharp
// rt_path.slang
void tracePath<B : IRayBackend>(B backend, ...) {
    if (backend.traceVisibility(origin, sunDir, sunDist)) { ... }
}
```

`slangc` specializes this into two separate `.spv` binaries.
The Java layer picks the correct binary based on the detected GPU tier.

## Rules for contributors

1. **Do NOT invent features.** Port existing GLSL logic 1:1 first.
   Algorithmic improvements (ReSTIR GI, OMM, SER) are separate follow-up PRs.
2. **Do NOT use GLSL-only syntax** (`layout(set=..., binding=...)`, `#extension`).
   Use Slang `[[vk::binding(N, S)]]` attributes and `import` for modules.
3. **Keep the math identical.** The rendered image must match the old GLSL output
   pixel-for-pixel (within floating-point tolerance). If it does not, the port
   has a bug.
4. **Test both SPIR-V and Metal output.** Run `slangc -target spirv` and
   `slangc -target metal` after every change. Metal must compile even if we
   do not test on macOS yet.
5. **No runtime shader compilation.** All `.spv` files are prebuilt by Gradle.
6. **One module = one concern.** Do not merge unrelated code into one file.

## Migration order

The migration proceeds bottom-up (leaf modules first, entry points last):

| Step | Module(s) | GLSL source | Notes |
|---|---|---|---|
| 1 | `types`, `bindings` | `rt_common_core.glsl` lines 1–200 | Data structures and descriptor bindings |
| 2 | `noise`, `sampling` | `rt_noise.glsl`, `rt_common_sampling.glsl` | Pure math, no scene access |
| 3 | `tonemap` | `rt_tonemap.glsl` | Pure math |
| 4 | `sky` | `rt_sky.glsl` | Procedural sky model |
| 5 | `light` | `rt_common_light.glsl` | Block light, lightmap |
| 6 | `brdf` | part of `rt_common_core.glsl` | GGX, Fresnel, Lambert |
| 7 | `surface` | `rt_common_surface.glsl` | Hit decoding, LabPBR |
| 8 | `shade` | `rt_common_shade.glsl` | AO, sky light, baked fill |
| 9 | `emitters` | `rt_common_emitters.glsl` | ReSTIR DI |
| 10 | `temporal` | `rt_common_temporal.glsl` | Motion, accumulation |
| 11 | `water`, `transparency`, `caustics` | `rt_common_water.glsl`, `rt_caustics.glsl` | Refraction, glass |
| 12 | `denoise_pack` | `rt_pack_nrd.comp` | NRD G-buffer |
| 13 | `ray_backend`, `backend_query` | `rt_common_sampling.glsl` (trace functions) | IRayBackend interface |
| 14 | `rt_path` | `rt_path.comp` | Path tracing entry point |
| 15 | `rt_rays` | `rt_rays.comp` | Ray effects entry point |
| 16 | `rt_restir_spatial` | `rt_restir_spatial.comp` | ReSTIR spatial reuse |
| 17 | Post-processing entries | `rt_bloom`, `rt_atrous`, `rt_composite`, `rt_volumetric` | Independent passes |
| 18 | `backend_ser` | NEW | Tier 3 SER backend (after core migration is done) |

## Licence

Same as the main project: **LGPL-3.0-or-later**.
