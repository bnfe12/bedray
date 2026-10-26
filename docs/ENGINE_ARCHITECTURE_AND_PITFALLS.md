# Bedray 2.0 Engine Architecture & Production Pitfalls

This document defines the definitive technical specifications for the Bedray 2.0 rendering engine.
All future implementation work, AI agents, and contributors must strictly adhere to these architectural standards.

---

## 1. The Four Core Lighting Algorithms ("The Big Four")

The lighting engine is built strictly upon four foundational mathematical algorithms. Any light transport channel (Direct Light, Indirect GI, Reflections, Transmission/Caustics) can be assigned to any of these four:

1. **ReSTIR DI** (*Reservoir-based Spatiotemporal Importance Resampling for Direct Illumination* - Bitterli et al. 2021):
   - Uses reservoirs to sample thousands of light emitters (sun, moon, torches, lanterns, lava, glowing blocks) with **1 shadow ray per pixel**.
   - Spatial checkerboard reuse and temporal accumulation with defensive clamping (`RESTIR_M_CAP = 20.0`).
2. **ReSTIR PT** (*Path-Space ReSTIR* - Lin et al. 2022/2023):
   - Re-samples multi-bounce indirect paths across space and time via shift mappings.
   - Cinematic photorealism, eliminates Monte Carlo variance at 1-2 SPP for complex indirect light and specular caustics.
   - Dedicated to High-End hardware (Tier 3: RTX 4070+, RTX 50xx).
3. **Path Tracing (PT)** (*Unidirectional Monte Carlo Ray Tracing*):
   - Ground truth physically based shading (GGX VNDF visible normal distribution, Fresnel-Schlick).
   - Standard reference engine for reflections, glass refraction, and baseline light transport.
4. **Radiance Cascades (RC)** (*Hierarchical Multi-Scale Spatial-Angular Cascade Grid* - Sannikov 2024):
   - Solves the rendering equation hierarchically: high spatial resolution at short range, high angular resolution at long range.
   - **Zero noise, zero temporal lag/smearing, low VRAM footprint**.
   - The primary GI engine for RTX 20/30, AMD Radeon, Intel Arc, Laptops, and APUs.

---

## 2. Voxel Geometry Engine: Bitmask HDDA

The software/fallback voxel renderer replaces the legacy per-block 2-level DDA (`voxel.comp`) with **Bitmask Hierarchical DDA (Bitmask HDDA)**.

### Why Bitmask HDDA:
- **Instant O(1) Block Updates:** A block placed or destroyed flips **1 bit** in a `uint64_t` mask. Zero mesh regeneration, zero distance field recomputation.
- **Micro-Footprint:** A $16 \times 16 \times 16$ chunk section is partitioned into sixty-four $4 \times 4 \times 4$ sub-cells. Each sub-cell is a single `uint64_t` occupancy bitmask. Total section storage: **512 bytes**.
- **1-Instruction Ray-Voxel Intersections:** When a ray enters a $4 \times 4 \times 4$ sub-cell, ray-box intersections against all 64 voxels are resolved using bitwise scan intrinsics (`findLSB` / `countTrailingZeros`).
- **No Disconnects:** Sharp 90-degree voxel boundaries are preserved perfectly without the edge-rounding or dilation artifacts of SDFs.

---

## 3. Presets & Mode Matrix

The user interface exposes 3 World Modes, each with 2 Profiles (Fast / Quality), plus a Custom override:

| Mode | Profile | Direct Light | Indirect GI | Reflections | Voxel / RT Traversal | Denoising |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Voxel + Vanilla** *(No RT cores, iGPU, GTX)* | **Fast** | Vanilla / DDA | **Radiance Cascades** | Screen-Space | **Bitmask HDDA** | Off |
| | **Quality** | **ReSTIR DI** (Voxel) | **Radiance Cascades** (Hi-Res) | **Path Tracing** (Voxel) | **Bitmask HDDA** | A-Trous / SVGF |
| **2. Voxel + RT Hybrid** *(RTX 20/30, AMD, Arc)* | **Fast** | **ReSTIR DI** (1 ray) | **Radiance Cascades** | **Path Tracing** (1 ray) | Hybrid (Voxel GI + Hardware Rays) | NRD Diffuse |
| | **Quality** | **ReSTIR DI** (Full) | **Radiance Cascades** | **ReSTIR PT** | Hybrid | NRD Diff+Spec |
| **3. Full Hardware RT/PT** *(RTX 4070+, RTX 50xx)* | **Fast** | **ReSTIR DI** | **Radiance Cascades** (HW) | **Path Tracing** (GGX) | Hardware TLAS/BLAS | NRD / DLSS-RR |
| | **Quality** | **ReSTIR DI** + SSS | **ReSTIR PT** (SER + OMM) | **ReSTIR PT** | Hardware TLAS/BLAS | **DLSS-RR + FG** |

**Custom Mode (Expert):** Every channel (Direct, Indirect, Reflections, Transmission) can independently select any of the 4 algorithms.

---

## 4. The 8 Critical Production Pitfalls & Solutions

1. **Dynamic Chunk Updates (BVH Thrashing):**
   - Solution: Asynchronous BLAS building in a dedicated compute queue (`VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR`) with double-buffered chunk staging.
2. **Motion Vectors & DLSS Ghosting:**
   - Solution: Accurate per-vertex motion vectors for entities/mobs and a Reactive/Transparency Mask for water, fire, and portal particles.
3. **Light Leaking & Shadow Acne on Thin Blocks:**
   - Solution: Adaptive Normal-Oriented Ray Bias based on surface roughness and hit distance. Watertight vertex welding to eliminate floating-point precision gaps between block faces.
4. **HUD / UI Degradation with Frame Generation:**
   - Solution: Strict split compositing pipeline. The 2D Minecraft GUI (crosshair, hearts, hotbar, chat) is rasterized in native monitor resolution **after** DLSS upscaling and frame generation.
5. **Temporal Light Lag (Instant Light Switched Off):**
   - Solution: Luminance disparity triggers. When a light emitter (redstone lamp, torch) is broken or switched off, temporal reservoirs in its radius are flushed immediately.
6. **First-Person Hand Clipping:**
   - Solution: First-person hand is placed in a separate TLAS instance with `MASK_FIRST_PERSON` and custom depth unbiasing to prevent wall clipping.
7. **Transparent Ray Explosion ($2^N$):**
   - Solution: Russian roulette Fresnel transparency. Rays do not branch into both reflection and refraction; a single path is chosen stochastically according to the Fresnel coefficient.
8. **Particle Hell (Rain, Snow, Smoke):**
   - Solution: Large emitters are staged into a compact billboard buffer; weather (rain/snow) is rendered via a volumetric screen-space weather pass, bypassing heavy TLAS triangle insertion.
