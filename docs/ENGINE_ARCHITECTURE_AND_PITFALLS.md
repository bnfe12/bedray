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

---

## 5. Продвинутые инженерные решения уровня AAA-движка (Advanced AAA Engineering)

### 5.1 World-Space Hash Grid ReSTIR (Voxel Hash Cache)
* **Проблема:** Экранный Spatial Reuse в воксельном мире вызывает утечки света сквозь стены толщиной в 1 блок, углы домов и заборы из-за близости пикселей на экране.
* **Решение:** Квантование 3D-мира в пространственную хэш-таблицу (Spatial Hash Grid с ячейками $0.5\times 0.5\times 0.5$ блока). Резервуары ReSTIR связываются с 3D-точкой попадания, гарантируя обмен семплами только между поверхностями одной комнаты/плоскости.

### 5.2 3D Light Grid (Кластеризация эмиттеров на Compute)
* **Проблема:** Тысячи факелов, лавы и светящегося лишайника в радиусе чанков приводят к гигантской дисперсии при случайной генерации кандидатов ReSTIR DI.
* **Решение:** Двухуровневая сетка света (3D Froxel / Cluster Grid). Во время мешинга чанков формируется компактный список эмиттеров на секцию. Compute-шейдер строит кластерную сетку, откуда ReSTIR DI сэмплирует источники с вероятностью $\propto \frac{I}{r^2}$ только из локального радиуса видимости.

### 5.3 2-уровневый Brickmap с битовыми интринсиками (Bitmask HDDA)
* **Архитектура:**
  * **Top Level:** Секция $16\times 16\times 16$ разбита на $4\times 4\times 4$ макро-кирпича, описанных одной 64-битной маской `uint64_t`. Если бит 0 — весь кирпич $4\times 4\times 4$ пуст (воздух).
  * **Bottom Level:** Для непустых кирпичей выделяется листовая 64-битная маска (1 бит на воксель).
* **Интринсики Slang:** Использование `firstbithigh()`, `countbits()` и `WaveActiveAll()` для мгновенного шага луча через пустые кирпичи без 64 итераций цикла.

### 5.4 Динамическая генерация OMM (Opacity Micromap) через Compute
* **Проблема:** Игроки меняют кастомные текстурпаки на лету; предварительно запеченные маски OMM несовместимы с новыми текстурами.
* **Решение:** Легковесный Compute-шейдер при перезагрузке текстурного атласа сканирует альфа-канал листвы/травы, строит 2-state микрокарты непрозрачности и передает их в драйвер для связки с BLAS. Any-Hit шейдеры листвы на RTX 40/50 не вызываются вовсе.

### 5.5 Froxel Volumetric Fog (Объемный свет)
* **Архитектура:** 3D-текстура усеченного конуса камеры $160\times 90\times 64$ с логарифмическим шагом по $Z$.
* **Освещение:** Compute-проход рассчитывает рассеяние (In-Scattering) и экстинкцию, опрашивая прямое солнце и зонды Radiance Cascades. Камера делает 1 трилинейную выборку на пиксель, давая кинематографичные God Rays сквозь листву и окна с оверхедом $\le 1.2$ мс.

### 5.6 Virtual Memory Aliasing в DAG Render Graph
* **Проблема:** Одновременное хранение текстур ReSTIR, каскадов RC, G-буфера, пирамиды Bloom и NRD требует до 6–8 ГБ VRAM только под рабочие буферы.
* **Решение:** Анализ времени жизни ресурсов (Liveness Analysis) в `bedray_render_graph`. Непересекающиеся по времени проходы (например, временные буферы ReSTIR и буферы апскейла Bloom) связываются с общим блоком `VkDeviceMemory` с разными `VkImage`, экономя 35–50% видеопамяти.

### 5.7 Panama FFM `Linker.Option.critical(true)`
* **Оптимизация:** На микро-вызовах нативного слоя (запись push-констант, обновление таймлайнов семафоров), не блокирующих поток и не вызывающих GC, передавать опцию `critical(true)`. Это устраняет накладные расходы Java на сохранение состояния потока, снижая стоимость вызова до машинной инструкции `CALL`.

