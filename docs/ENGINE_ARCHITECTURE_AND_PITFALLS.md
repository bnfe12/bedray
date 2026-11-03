# Bedray 2.0 Engine Architecture & Production Pitfalls

This document defines the definitive technical specifications for the Bedray 2.0 rendering engine.
All future implementation work, AI agents, and contributors must strictly adhere to these architectural standards.

---

## 1. The Four Core Lighting Algorithms ("The Big Four")

The lighting engine is built strictly upon four foundational mathematical algorithms. Any light transport channel (Direct Light, Indirect GI, Reflections, Transmission/Caustics) can be assigned to any of these four:

1. **ReSTIR DI** (*Reservoir-based Spatiotemporal Importance Resampling for Direct Illumination* - Bitterli et al. 2021):
   - Uses reservoirs to sample thousands of light emitters (sun, moon, torches, lanterns, lava, glowing blocks) with **1 shadow ray per pixel**.
   - Spatial checkerboard reuse and temporal accumulation with defensive clamping (`RESTIR_M_CAP = 20.0`).
2. **ReSTIR PT / GI** (*Path-Space ReSTIR & Reservoir GI* - Lin et al. 2022/2023):
   - Re-samples multi-bounce indirect paths across space and time via shift mappings.
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

## 3. Трехуровневая масштабируемая архитектура (Tiered Scalable Architecture)

Движок строится по принципу единого конвейера-трансформера: благодаря статическому полиморфизму Slang единый шейдерный код работает в трех режимах без дублирования:

| Графический компонент | 1. Программный Voxel-режим (Без RT-ядер) | 2. Hybrid Ray Tracing (Классический RT) | 3. Full Path Tracing (Ультра) |
| :--- | :--- | :--- | :--- |
| **Тени от солнца и факелов** | **DDA Raymarching** по битовым маскам HDDA | **Аппаратные RT-тени** (блоки + полигоны мобов) | **ReSTIR DI** (тысячи эмиттеров, 1 луч) |
| **Отражения (Вода, зеркала, лед)** | **SSR** (экранные) + fallback в Bitmask HDDA вне экрана | **1 аппаратный луч отражения** через `rayQuery` | Полная трассировка путей (Glossy/Rough PT) |
| **Глобальное освещение (GI)** | **Radiance Cascades** (работает чисто на Compute!) | **Radiance Cascades** + 1 диффузный отскок луча | **RCPG + ReSTIR GI** (бесконечные честные отскоки) |
| **Мобы и сущности** | Растеризуются в G-буфер обычным способом | Растеризация + аппаратный BLAS для теней/отражений | Полный Path Tracing через Hardware BVH |
| **Целевое железо** | GTX 1060 / 1660, Intel Arc, встройки, Snapdragon X, Apple M1/M2 | RTX 3050 / 3060, Radeon RX 6600 / 7600, Apple M3/M4 | RTX 4070 / 5060 Ti и выше |
| **Ожидаемый FPS** | **120 – 200+ FPS** | **90 – 140 FPS** | **60 – 120 FPS** |

### 3.1 Как устроен режим «Красиво БЕЗ RT-ядер» (Voxel-only Compute)
1. **Radiance Cascades на Compute-шейдерах:** Radiance Cascades вообще не требует аппаратных RT-ядер. Он шагает по Bitmask HDDA обычными compute-потоками, обеспечивая глубокое, мягкое освещение в пещерах и комнатах на любой видеокарте.
2. **Аналитический Voxel Ambient Occlusion за 0 миллисекунд:** Поскольку воксели лежат в 64-битных масках (uint64_t), затенение в углах блоков (AO) вычисляется побитовыми операциями:
   ```hlsl
   uint aoOcclusion = countbits(blockMask & neighborPattern);
   float ao = 1.0 - float(aoOcclusion) * AO_WEIGHT;
   ```
   Это дает идеальное затенение стыков блоков прямо в первичном луче без зерна и шума.
3. **Честные воксельные тени (DDA Shadows):** Вместо артефактных Shadow Maps пускается луч от поверхности к солнцу через Bitmask HDDA. Это дает идеальные, попиксельно резкие тени от каждого блока на чистом Compute.

### 3.2 Селективный Ray Tracing против тяжелого Path Tracing (Hybrid RT)
Вместо хаотичного блуждания лучей во все стороны, в режиме Hybrid RT лучи пускаются точечно:
1. **Только тени:** 1 направленный луч к источнику света. Мобы отбрасывают честные тени на стены с учетом скелетных анимаций.
2. **Только отражения:** 1 зеркальный луч от воды или полированного камня. Если поверхность матовая (земля, камень) — луч не пускается вообще.
3. **RTAO (Трассированное затенение):** 1 короткий луч в полусфере для физически точной окклюзии под ногами мобов и в траве.
*Итог:* 80% лоска полноценного Path Tracing при втрое меньших затратах ресурсов.

### 3.3 Архитектурный трюк в Slang: Статический полиморфизм без потери FPS
В Slang используется параметризация интерфейсов (Zero-overhead specialization):
```hlsl
interface ISceneTracer {
    bool traceShadowRay(Ray ray, float maxDist);
    HitInfo traceReflectionRay(Ray ray);
};

// 1. Программный бэкенд (Bitmask HDDA)
struct SoftwareVoxelTracer : ISceneTracer {
    bool traceShadowRay(Ray ray, float maxDist) {
        return marchBitmaskHDDA(ray, maxDist); // Только Compute DDA
    }
};

// 2. Аппаратный гибридный бэкенд
struct HardwareHybridTracer : ISceneTracer {
    RayQuery<RAY_FLAG_CULL_OPAQUE> query;
    bool traceShadowRay(Ray ray, float maxDist) {
        return marchBitmaskHDDA(ray, maxDist) || traceHardwareRayQuery(query, ray, maxDist);
    }
};

// Единая логика освещения компилируется AOT в монолитный SPIR-V за 0 runtime-оверхеда:
[shader("compute")]
void mainLighting<Tracer : ISceneTracer>(Tracer tracer, ...) {
    if (!tracer.traceShadowRay(sunRay, sunDist)) {
        // Освещаем точку
    }
}
```

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


---

## 6. Микро-оптимизации, графика, краевые случаи и DX/UX

### 6.1 Z-Order Curve (Код Мортона) внутри 64-битного кирпича
* **Проблема:** Линейная нумерация битов $index = x + 4(y + 4z)$ при наклонных лучах приводит к скачкам по битам с промахами в L1-кэше GPU.
* **Решение:** Перенумерация битов маски $4\times 4\times 4$ по 3D Z-кривой (Morton Order). Соседние в пространстве микровоксели лежат в смежных битах `uint64_t`, что дает $+5–8\%$ чистого FPS в DDA за счет когерентности L1-кэша.

### 6.2 Октаэдральная упаковка нормалей (Octahedral Normal Encoding)
* **Реализация:** Модуль [`contracts/octahedral.slang`](contracts/octahedral.slang).
* **Эффект:** Упаковка 3D-нормалей из `float3` (12 байт) в `snorm16x2` (4 байта) за 4 такта ALU. Экономия 66% пропускной способности шины памяти VRAM на чтении/записи нормалей G-буфера с нулевой визуальной погрешностью.

### 6.3 Половинная точность (FP16 / `float16_t`)
* **Оптимизация:** Перевод каскадов Radiance Cascades, фроксельного тумана, пирамиды Bloom и весов резервуаров ReSTIR на `half` через расширение `VK_KHR_shader_float16_int8`.
* **Эффект:** Использование Dual-Issue ALU FP16 на видеокартах NVIDIA Ada/Blackwell и AMD RDNA 3/4 удваивает темп инструкций и вдвое снижает трафик VRAM.

### 6.4 Мягкие воксельные тени без шума (DDA Cone Tracing)
* **Алгоритм:** Во время трассировки луча тени по Bitmask HDDA отслеживается минимальное расстояние до препятствия:
  $$\text{coneFactor} = \min \left( \frac{\text{distToObstacle}}{t} \right)$$
* **Эффект:** Лучи, проходящие вблизи углов блоков, плавно ослабляются, создавая физически корректную контактную полутень (Contact-Hardening Soft Shadows) за 1 луч без денойзера.

### 6.5 Площадные эмиттеры факелов (Area Lights)
* **Алгоритм:** Координата источника света факела не фиксируется в центре блока, а стохастически джиттерится в габаритах пламени ($2\times 2\times 2$ пикселя на верхушке палочки).
* **Эффект:** Устраняются неестественно резкие треугольные тени от углов блоков в комнатах; свет ложится мягкими реалистичными градиентами.

### 6.6 Тонкостенное просвечивание листвы (Thin-Slab SSS)
* **Алгоритм:** При попадании луча солнца в блок листвы луч не гасится мгновенно, а ослабляется по закону Бера-Ламберта:
  $$I = I_0 \cdot e^{-\sigma \cdot d}$$
  и окрашивается в оттенок текстуры листвы.
* **Эффект:** В лесах и джунглях сквозь кроны деревьев пробивается мягкий рассеянный зеленоватый свет, устраняя эффект «черного склепа» под деревьями.

### 6.7 Водная каустика через 2D-дивергенцию волновых нормалей
* **Алгоритм:** Аналитическая проекция каустического узора на дно водоема через расхождение нормалей анимированной водной глади:
  $$\text{Caustic} = \max(0, 1.0 - \nabla \cdot \vec{N}_{\text{water}})$$
* **Эффект:** Танцующие солнечные сетки на дне рек и океанов с нулевым шумом и 0 дополнительных лучей трассировки.

### 6.8 Краш-тест Minecraft: Обработка краевых случаев
1. **Падающий песок, гравий и поршни:** В момент падения песок превращается в `FallingBlockEntity`. Движок атомарно гасит бит в Bitmask HDDA и переносит геометрию в Hardware BVH сущностей (при приземлении — обратная операция), предотвращая мигание и раздвоение блоков.
2. **Динамический свет в руках (Handheld Light):** Факел в руках игрока не перестраивает чанк; параметры света передаются отдельным вектором через BDA/Push Constants и напрямую подмешиваются в пул кандидатов ReSTIR DI.
3. **Границы загрузки чанков (Border Seams):** При выходе луча за пределы прогруженных чанков трассировка плавно переходит в затухание атмосферного тумана (Fog Extinction), исключая белые дыры на границах мира.

### 6.9 Удобства разработки и игрока (DX & UX)
1. **Live Hot-Reloading Slang (F8):** Фоновый поток C++ моста отслеживает файлы `.slang`; по нажатию F8 компилятор `slangc` в памяти пересобирает изменившийся модуль и обновляет конвейер на следующем кадре без перезапуска игры.
2. **Инженерный Heatmap-экран (F3 + R):** Режим визуализации: DDA Step Heatmap (синий = 2 шага, красный = 64 шага), ReSTIR Confidence ($M$-weight), визуализатор векторов движения.
3. **Авто-генерация PBR для ванильных текстур:** Автоматическая классификация блоков по палитре (шерсть/земля $\to$ Roughness 0.9, металлы $\to$ Metalness 1.0, вода/лед $\to$ Roughness 0.02, IOR 1.33) придает ванильному Minecraft вид премиального PBR-пака без скачивания 2 ГБ модов.
4. **2-секундный Silent-бенчмарк при первом запуске:** При первом входе в игру движок прогоняет 120 оффскрин-кадров, замеряет GPU-таймстампы и автоматически активирует подходящий профиль (Path Tracing для RTX 40/50, Hybrid RT для RTX 3060, Voxel Fast для встроек). Игрок сразу получает стабильный FPS без копания в настройках.
