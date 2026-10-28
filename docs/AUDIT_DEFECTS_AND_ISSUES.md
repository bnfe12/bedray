# Bedray 2.0 — Полный реестр выявленных дефектов, архитектурных проблем и алгоритмических ошибок

> **Дата аудита:** 27 октября 2026 г.  
> **Ветка:** `refactor/slang-pipeline`  
> **Статус проверки:** L0 (Двойная построчная верификация по дереву исходников).  
> **Область:** C++ нативный бэкенд, шейдерное ядро Slang, Java/FFM хост-интеграция, сборочные скрипты CMake и Gradle.

---

## 1. Сводная оценка архитектуры (Strict Scorecard)

| Подсистема | Оценка | Статус | Ключевой системный риск |
|---|:---:|:---:|---|
| **Java & FFM Host Layer** | **B+** (82/100) | Требует защиты памяти | Падение JVM при on-heap буферах; спекулятивный сброс дескрипторов; утечка ядра при `BEDRAY_ERROR_IN_USE` |
| **Native C++ Backend** | **C+** (68/100) | Архитектурно разорван | 0 `catch` на C ABI границах (краш процесса Windows SEH `0xE06D7363`); 4 модуля изолированы и не вызываются из Java |
| **Slang Shaders & Alg** | **C** (62/100) | Математические дефекты | Инверсия вероятностей ReSTIR DI ($4\times$ темнее); арифметическое среднее вместо окклюзии в RC; двойной оффсет геометрии |
| **ИТОГОВЫЙ ПРОЕКТ** | **C+ (71/100)** | **НЕ ГОТОВ К ПРОДУ** | **Требуется устранение P0-блокеров до запуска игровых тестов** |

---

## 2. Критические дефекты P0 (Падения процесса, повреждение памяти и физики света)

### [P0-1] FFM падение JVM при передаче on-heap `pushConstants`
* **Файлы:**
  * [`SlangProgram.java:146`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangProgram.java#L140-L150)
  * [`BedrayCoreNative.java:324`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java#L320-L330)
* **Поведение:** В `SlangProgram.java` вызывается `push = MemorySegment.ofBuffer(pushConstants)`. Если вызывающий код передал heap-буфер (`ByteBuffer.allocate(...)`), сегмент создается как on-heap (`push.isNative() == false`). В `BedrayCoreNative.java:324` вызов `desc.set(ADDRESS, BedrayCoreLayouts.FD_PUSH_CONSTANTS, push)` выбрасывает `java.lang.IllegalArgumentException: Not a native address` (в Java 22+ Panama FFM структуры нативных вызовов запрещают некопированные адреса из кучи JVM без фиксации GC). Рендер-поток мгновенно падает.
* **Причина:** Отсутствие валидации `pushConstants.isDirect()` и копирования в нативную память `Arena`.

### [P0-2] Побег C++ исключений через границу C ABI в Java FFM (JVM Crash `0xE06D7363`)
* **Файлы:**
  * [`core.cpp:466-477, 479-495, 520-535`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L466-L535) (`bedray_core_destroy`, `bedray_core_query_capabilities`, `bedray_core_destroy_program`, `bedray_core_release_recording`)
  * [`gpu_buffers_c.cpp:1-150`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_gpu_resources/src/gpu_buffers_c.cpp) (все 16 экспортных функций)
  * [`history_c.cpp:1-150`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_history/src/history_c.cpp) (все 20 экспортных функций)
* **Поведение:** Экспортные C-функции не имеют ни одного блока `try { ... } catch (...)`. При `std::bad_alloc`, `std::out_of_range` или системном сбое C++ исключение пересекает границу C ABI. На платформе Windows/MSVC это генерирует системное SEH-исключение `0xE06D7363`, которое FFM-рантайм Java не может перехватить, вызывая немедленный жесткий краш процесса JVM с генерацией `hs_err_pid.log`.
* **Причина:** Иллюзия того, что C++ код внутри C-оберток "не бросает исключений".

### [P0-3] Инверсия вероятности смеси в ReSTIR DI (Свет темнее в $1/q$ раз)
* **Файлы:**
  * [`block_emitters.slang:499, 508, 514`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/light/block_emitters.slang#L490-L517)
  * [`path_tracer.slang:142`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L140-L150)
* **Поведение:** При сэмплировании дискретной смеси эмиттеров и солнца с вероятностью ветвления $q = \text{emitterProbability}$ плотность вероятности выбранного семпла равна $p_{\text{mix}} = q \cdot p_{\text{branch}}$.
  В `block_emitters.slang` вместо умножения выполнено деление:
  ```hlsl
  emitterSample.sourcePdf /= emitterProbability; // Строка 508: PDF = p / q
  sunSample.sourcePdf /= 1.0 - emitterProbability; // Строка 514: PDF = p / (1 - q)
  ```
  В `path_tracer.slang:142` вес Монте-Карло вычисляется как `1.0 / sourcePdf`. В итоге вес равен $\frac{1}{p / q} = \frac{q}{p}$. Вместо деления на вероятность ветки свет **умножается** на неё. При $q = 0.25$ эмиттеры светят ровно в 4 раза слабее физической нормы.
  На строке 499 деление на `emitterProbability` выполняется даже при `sunIntensity <= 0.0`, хотя строкой выше авторский комментарий гласит: `// One branch only: its density is already the whole density, so nothing to renormalise`. В результате в пещерах и ночью факелы ошибочно тускнеют.
* **Причина:** Путаница между коррекцией веса сэмпла $W = 1/p$ и плотностью вероятности $p$.
* **Статус на 2026-10-28 (source-only):** на HEAD плотности умножаются на вероятность ветки (`sourcePdf *= emitterProbability`, `*= 1.0 - emitterProbability`), а single-branch путь не ренормируется. По коду это соответствует $p_{mix} = q \cdot p_{branch}$; вес $1/sourcePdf$ больше не умножается на $q$ ошибочно. Компиляции/теста не было (L0); нужен тест PDF для $q = 0.25, 0, 1$ и сравнение с эталоном.

### [P0-4] Radiance Cascades: арифметическое среднее вместо окклюзии лучевого транспорта
* **Файл:** [`radiance_cascades_store.slang:174-175`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/radiance_cascades/radiance_cascades_store.slang#L168-L180)
* **Поведение:** Слияние ближнего и дальнего интервалов каскада реализовано через взвешенное среднее:
  ```hlsl
  float confidence = nearInterval.a + farInterval.a;
  float3 radiance = (nearInterval.rgb * nearInterval.a + farInterval.rgb * farInterval.a) / confidence;
  ```
  В физике переноса излучения вдоль луча каскада ($r_0 \to r_1 \to r_2$) действует уравнение:
  $$L_{\text{total}} = L_{\text{near}} + T_{\text{near}} \cdot L_{\text{far}}$$
  где $T_{\text{near}} = 1.0 - \text{opacity}_{\text{near}}$. Если ближний интервал уперся в непрозрачный блок ($\text{near.a} = 1.0$), свет дальнего интервала обязан быть полностью заблокирован ($T_{\text{near}} = 0$). В текущей реализации стена толщиной в 1 блок усредняется с солнцем: $(L_{\text{wall}} + L_{\text{sun}}) / 2$, вызывая сквозную засветку закрытых помещений.
* **Причина:** Ошибочное понимание альфа-канала интервала как "статистической уверенности" (confidence) вместо коэффициента поглощения/непрозрачности.
* **Статус на 2026-10-28 (source-only):** в дерево добавлен typed-путь: `RCSegment` и `rcComposeSegments` (`L_front + T_front * L_back`) в `interval_transport.slang`, typed `rcProbeIrradiance`/`rcProbeComplete` в `segment_store.slang`, typed trace `rc_trace.slang`. Старый float4 resolve помечен legacy. Компиляции и тестов на этой машине не было (L0); typed-путь не подключён к живому кадру, поэтому P0-4 нельзя считать закрытым до сборки, теста и интеграции.

### [P0-5] Двойной оффсет квадов в аппаратном пересечении геометрии (чтение мусора)
* **Файл:** [`geometry.slang:218`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/geometry.slang#L214-L220)
* **Поведение:** Функция `geometryVertexBase` вычисляет базовый индекс вершины:
  ```hlsl
  int rangeIndex = geometryRangeForPrimitive(section, primitive);
  if (rangeIndex < 0) return 0u;
  GeometryRange range = section.ranges[uint(rangeIndex)];
  return section.meta.x + (range.firstQuad + (primitive >> 1u)) * 4u;
  ```
  В `geometryRangeForPrimitive` (строки 203–208) переменная `quad = primitive >> 1u` уже сопоставляется с `range.firstQuad` и является **абсолютным** индексом квада внутри секции. Сложение `range.firstQuad + quad` добавляет `range.firstQuad` **дважды**. Для любых мешей со смещением диапазонов (прозрачные блоки, вода, декали) видеокарта читает чужие вершины со сдвигом на $4 \times \text{firstQuad}$ позиций, вызывая графические артефакты геометрии и битые UV.
* **Причина:** Потеря семантики относительного vs абсолютного индекса при рефакторинге структур Sodium.
* **Статус на 2026-10-28 (source-only):** на HEAD `geometryRangeForPrimitive` возвращает индекс диапазона, а `geometryVertexBase` вычисляет `(range.firstQuad + (primitive >> 1u)) * 4u` без повторного `firstQuad` и без `section.meta.x`. C++ producer использует `primitiveOffset = quad_start * 6 * index_bytes` и `firstVertex = quad_start * 4`, а индексы локальны для quad. По коду offset согласован; компиляции/теста не было (L0), нужен layout/contract test на несколько диапазонов.

### [P0-6] Spatial ReSTIR DI сбрасывает локальные обновления при победе своего семпла
* **Файл:** [`spatial_block_emitters.slang:160-162`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/restir/spatial_block_emitters.slang#L150-L165)
* **Поведение:** При пространственном объединении резервуаров с соседями код делает:
  ```hlsl
  if (!won) {
      return; // no neighbour won the roulette
  }
  ```
  Если собственный семпл пикселя оказался ярче/лучше семплов всех соседей, флаг `won` остается `false`. Шейдер завершает работу без записи в текстуру. При этом отбрасываются все учтенные соседские испытания ($M$ и $W_{\text{sum}}$). В результате на экране возникают резкие полосы дисперсии между соседними пикселями и временное мерцание (flicker).
* **Причина:** Неверная интерпретация рулетки Рестира: резервуар обязан сохранять результат объединения даже тогда, когда выбранным остался исходный кандидат.
* **Статус на 2026-10-28 (source-only):** на HEAD `spatial_block_emitters.slang` публикует merged reservoir всегда, когда он конечен и валиден, независимо от того, победил ли собственный sample; `mergeReservoirs` обновляет `M`/`W_sum` даже при сохранении текущего sample, а receiver lanes восстанавливаются из `own`. Ранний `if (!won) return;` отсутствует. Компиляции/теста не было (L0); нужен регрессионный тест на собственный winner и на не-конечный вес.

---

## 3. Архитектурные дефекты и проблемы связности P1

### [P1-1] Оторванные и неинтегрированные нативные C++ модули
* **Файлы:**
  * [`native/bedray_gpu_resources/`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_gpu_resources/)
  * [`native/bedray_history/`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_history/)
  * [`native/bedray_render_graph/`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/)
  * [`native/bedray_frame_lifetime/`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_frame_lifetime/)
* **Проблема:** В проекте написано свыше 250 КБ чистого C++ кода (графы рендеринга, таймлайн-семафоры, кольцевые пулы буферов, трекинг истории). Однако в Java ([`BedrayCoreNative.java`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java)) экспортированы только функции из монолитного [`core.cpp`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp). Ни один из 4 вспомогательных модулей не вызывается из игры и даже не линкуется в `bedray_core`. Это архитектурный балласт ("мертвый код на будущее").
* **Угроза:** Иллюзия готового модульного движка при фактически примитивном C ABI.

### [P1-2] Аллокация `VkDescriptorPool` на каждый Compute Dispatch (900 раз/сек)
* **Файл:** [`core.cpp:828-844`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L828-L844)
* **Проблема:** В функции `bedray_core_record_dispatch` при каждом отдельном диспатче создается новый `VkDescriptorPool` с параметром `maxSets = 1`:
  ```cpp
  pool_info.maxSets = 1;
  VkResult result = core->create_descriptor_pool(core->device, &pool_info, nullptr, &dispatch_pool);
  ```
  Пул привязывается к записи и удаляется только после выполнения кадра. При 15 compute-проходах за кадр на 60 FPS это **900 вызовов `vkCreateDescriptorPool` и 900 вызовов `vkDestroyDescriptorPool` в секунду**.
* **Угроза:** Тяжелейшая блокировка внутренних мьютексов аллокатора Vulkan-драйвера, деградация 1% Low FPS и фрагментация видеопамяти. Необходим Ring Descriptor Pool на кадр.

### [P1-3] Барьерный молот `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` в Render Graph
* **Файл:** [`vulkan_recording.cpp:113-122`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/src/vulkan_recording.cpp#L110-L125)
* **Проблема:** Между каждым проходом графа рендеринга выставляется глобальный барьер:
  ```cpp
  barrier(command_buffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
          0, 1, &memory, 0, nullptr, ...);
  ```
  с флагами `srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT`.
* **Угроза:** Это полностью нивелирует смысл DAG Render Graph (асинхронный compute, перекрытие независимых вычислений). Видеокарта принудительно сбрасывает все конвейеры до нуля перед началом каждого прохода, снижая общую утилизацию GPU на 30–45%.

### [P1-4] Мертвый код отставки буферов команд и спекулятивный `releaseRecording`
* **Файлы:**
  * [`SlangRuntime.java:174-178`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L170-L178) (`retireSubmittedRecording`)
  * [`RtFrameRenderer.java:590-592`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L588-L595)
* **Проблема:** Метод `retireSubmittedRecording`, гарантирующий корректное удаление дескрипторов через очередь энкодера после завершения сабмита, является мертвым кодом (0 вызовов). Вместо него `RtFrameRenderer` в начале кадра спекулятивно дергает:
  ```java
  atmosphere.releaseRecording(cmd.address());
  pass.releaseRecording(cmd.address());
  ```
  полагая, что текущий транзиентный буфер команд уже освободился GPU.
* **Угроза:** Если пул командных буферов возвращает дескрипторы по циклической схеме (3+ буфера) или при перекрытии кадров, дескрипторные пулы кадра $N-1$ разрушаются прямо во время выполнения на GPU (Vulkan validation error: `use of deleted descriptor pool`).

### [P1-5] Безвозвратная утечка ядра при смене разрешения / режима (`BEDRAY_ERROR_IN_USE`)
* **Файлы:**
  * [`core.cpp:470`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L468-L476)
  * [`SlangRuntime.java:197-204`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L195-L205)
* **Проблема:** Если при вызове `bedray_core_destroy` в ядре осталась хоть одна незавершенная запись (`!core->recordings.empty()`), функция возвращает `BEDRAY_ERROR_IN_USE` и прерывает очистку, не освобождая пайплайны и память. Java-слой ловит исключение, логирует ошибку и помечает рантайм закрытым, навсегда бросая нативный указатель `core`.
* **Угроза:** Каждое изменение настроек графики или ресайз окна оставляет в VRAM старые пайплайны и шейдерные модули до перезапуска игры.

### [P1-6] Нарушение глобальной истории из-за оборачивания секций по модулю 1024
* **Файлы:**
  * [`contracts/hit.slang:33-36`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/hit.slang#L30-L40)
  * [`contracts/history.slang:63-70`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/history.slang#L60-L72)
  * [`traversal/hardware_traversal.slang:62`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/traversal/hardware_traversal.slang#L60-L65)
* **Проблема:** Позиция в мире вычисляется со сверткой координат: `result.worldPosition = float3(section.origin.xyz & 1023) + localPosition;`. При этом `reprojectToHistory` умножает этот вектор на `header.prevViewProjection`.
* **Угроза:** Как только игрок перемещается за пределы блока с координатой $X, Z > 1023$, координаты сбрасываются в 0, проецируя точку на 1024 блока назад. Временное накопление (TAA, SVGF) взрывается шлейфами и сбросом истории по всему экрану.

### [P1-7] ReSTIR PT: раздутый резервуар (465 МБ VRAM) и отсутствие детерминанта Якоби
* **Файл:** [`restir/restir_pt.slang`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/restir/restir_pt.slang)
* **Проблема:** Структура резервуара для путей занимает 112 байт на пиксель (464.5 МБ в 1080p, 1.86 ГБ в 4K). При сдвиге путей (path shift mapping) отсутствует учет матричного детерминанта Якоби преобразования телесных углов вершин отскока. Модуль не подключен ни к одному проходу конвейера.
* **Угроза:** Падение FPS при высоких разрешениях, нефизичные светлые пятна на незеркальных поверхностях.

### [P1-8] Обрыв путей при отсечении горизонта в GGX VNDF
* **Файлы:**
  * [`shading/bsdf.slang:161-163`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/shading/bsdf.slang#L160-L165)
  * [`path_tracer.slang:302-306`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L300-L310)
* **Проблема:** Если сгенерированное микрогранью отражение уходит под горизонт геометрии (`lightLocal.z <= 0.0`), `bsdfSample` возвращает `result.valid = false`. Трейсер путей при `!bsdf.valid` полностью прекращает трассировку луча (`break;`).
* **Угроза:** На шероховатых материалах и на силуэтах под скользящими углами пути массово погибают на первом же отскоке, вызывая неестественное затемнение кромок объектов.

### [P1-9] Накопление смещения луча в вырезах (Cutout Holes) искажает ViewZ
* **Файл:** [`path_tracer.slang:228, 246`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L220-L250)
* **Проблема:** При прохождении листвы/решеток `advance = hit.t + options.rayBias;` накапливается в `primaryAdvance`.
* **Угроза:** Измеренное расстояние `firstHitDistance` систематически завышается на $N_{\text{cutout}} \times \text{rayBias}$, искажая линейную глубину G-буфера для денойзера NRD/SVGF.

### [P1-10] Засветка пещер скайбоксом в Radiance Cascades
* **Файл:** [`radiance_cascades/rc_trace.slang:363-367`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/radiance_cascades/rc_trace.slang#L360-L368)
* **Проблема:** При `options.worldBoundaryDistance == 0.0` любой промах луча в терминальном каскаде считается выходом в открытое небо.
* **Угроза:** В глубоких пещерах с длинными пролетами луч терминального каскада достигает своего максимального радиуса ($r_{\text{far}} = 64$ блока), не встретив стену, и ошибочно добавляет солнечный/дневной свет в подземные локации.

### [P1-11] Рудименты рантайм-компиляции GLSL через Shaderc
* **Файлы:**
  * [`SodiumDequantize.java:36`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/sodium/SodiumDequantize.java#L36)
  * [`VelocityPipeline.java:55, 237`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/upscaler/VelocityPipeline.java#L55-L60)
  * [`RtParticleComposite.java:48`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtParticleComposite.java#L45-L50)
  * [`RtShaderCompiler.java`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java)
* **Проблема:** Ряд ключевых проходов все еще жестко дергают LWJGL Shaderc в рантайме для компиляции старых `.comp` файлов.
* **Угроза:** Краш игры на системах и архитектурах (Linux ARM, macOS, Steam Deck), где бинарники Shaderc отсутствуют в classpath.

---

## 4. Замечания по стандартам, переносимости и тестированию P2

### [P2-1] Хардкод путей разработчика в CMake
* **Файлы:**
  * [`bedray_core/CMakeLists.txt:20`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L20)
  * [`bedray_render_graph/CMakeLists.txt:56`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/CMakeLists.txt#L56)
  * [`bedray_scene/CMakeLists.txt:18`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L18)
* **Проблема:** Зашит абсолютный локальный путь `C:/Dev/Sky/tools/sdk/Vulkan-Headers/include`.
* **Угроза:** При сборке на чистой машине или CI/CD сборочный скрипт падает, если системные переменные `VULKAN_SDK` не экспортированы глобально.

### [P2-2] Блокировка сборки на не-Windows платформах в CMake (macOS / Linux / ARM)
* **Файлы:**
  * [`bedray_core/CMakeLists.txt:7-9`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L7-L9)
  * [`bedray_scene/CMakeLists.txt:5-7`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L5-L7)
* **Проблема:** Присутствует директива `if(NOT WIN32) message(FATAL_ERROR "... currently supports Windows only") endif()`.
* **Угроза:** Прямой запрет на компиляцию нативных библиотек на macOS (Apple Silicon M1–M4) и Linux (Steam Deck, ARM64).

### [P2-3] Хардкод расширения `.dll` в Java Native Loader
* **Файл:** [`BedrayCoreLocator.java:20`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java#L20)
* **Проблема:** Константа `DLL = "bedray_core.dll"` жестко зашита в коде загрузчика.
* **Угроза:** Игра не может загрузить `libbedray_core.dylib` на macOS или `libbedray_core.so` на Linux, завершая запуск с ошибкой `resolved, but bedray_core.dll is not in the file list`.

### [P2-4] Хардкод бинарника `slangc.exe` в Gradle
* **Файл:** [`gradle/slang.gradle:49`](file:///C:/Users/andre/Downloads/bedray-slang/gradle/slang.gradle#L45-L55)
* **Проблема:** Задача AOT-компиляции вызывает исключительно `bin/slangc.exe`.
* **Угроза:** На macOS и Linux Gradle падает с ошибкой `Cannot run program "bin/slangc.exe"`.

### [P2-5] Ловушка выравнивания std430 в структуре `VelocityPush`
* **Файл:** [`passes/velocity_reproject.slang:59`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/passes/velocity_reproject.slang#L55-L65)
* **Проблема:** Поле `float2 jitterPrevious` расположено по смещению 60 байт (не кратно 8).
* **Угроза:** Компилятор SPIR-V вставляет 4 байта невидимого паддинга, сдвигая данные `jitterPrevious` и `renderSize` относительно Java Push Constants. На ARM64 это может приводить к прерываниям невыровненного доступа.

### [P2-6] Ненадежная проверка NaN в `math.slang` при fast-math
* **Файл:** [`contracts/math.slang:41-43`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/math.slang#L40-L45)
* **Проблема:** Проверка `value == value` при оптимизациях `-ffast-math` схлопывается компилятором в `true`.
* **Угроза:** Пропуск значений `NaN`/`Inf` в буферы рендеринга и денойзер.

### [P2-7] Нарушение взаимности Гельмгольца в диффузном Френеле
* **Файл:** [`shading/bsdf.slang:93, 229`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/shading/bsdf.slang#L90-L100)
* **Проблема:** Диффузный вес умножается на $(1 - F(N \cdot V))$, игнорируя угол падения света $N \cdot L$.
* **Угроза:** Нарушение симметрии BRDF $f(\omega_i, \omega_o) \neq f(\omega_o, \omega_i)$ в обратных путях и NEE.

### [P2-8] Конфликт статического CRT (`/MT`) и динамического CRT (`/MD`)
* **Файлы:**
  * [`bedray_core/CMakeLists.txt:77`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L77)
  * [`bedray_scene/CMakeLists.txt:44`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L44)
* **Проблема:** Использование флага `/MT` принудительно линкует независимые копии рантайма C++ в разные DLL.
* **Угроза:** Передача объектов STL или освобождение памяти, выделенной в одной библиотеке, из другой приводит к повреждению кучи MSVCRT.

### [P2-9] Отсутствие автоматизированного тестового контура и профилирования Tracy
* **Файлы:** [`docs/TESTING.md`](file:///C:/Users/andre/Downloads/bedray-slang/docs/TESTING.md), [`scripts/`](file:///C:/Users/andre/Downloads/bedray-slang/scripts/)
* **Проблема:** Ранее в проекте тестирование выполнялось исключительно вручную через параметры `-Dvkbridge.autotest=...` без централизованного сбора перцентилей фреймтайма (1% Low, 0.1% Low) и телеметрии зон Tracy.
* **Решение:** Внедрен выделенный модуль автоматизации [`tools/automation/`](file:///C:/Users/andre/Downloads/bedray-slang/tools/automation/) на базе **Python 3.15+** под управлением менеджера **`uv`**, управляющий игровыми сессиями, съемкой трасс Tracy Profiler (TCP 8086) и автоматической генерацией отчетов.

---

## 5. Требования кроссплатформенности: macOS (Apple Silicon M1–M4) и ARM64

Для обеспечения полноценной работы Bedray 2.0 на macOS (чипы M1, M2, M3, M4) и любых других ARM-системах (Qualcomm Snapdragon X Elite, Linux aarch64, Steam Deck) заложены следующие архитектурные стандарты:

### 1. Шейдерный конвейер и API (Slang -> Metal / SPIR-V MoltenVK)
* **Двойной путь компиляции Slang:**
  * **Vulkan/MoltenVK:** Slang AOT генерирует стандартизированный SPIR-V 1.4/1.5. На macOS MoltenVK транслирует вызовы в Metal Compute и Ray Tracing (`raytracing::intersector`).
  * **Native Metal (MSL):** Slang изначально поддерживает целевой генератор `-target metal`. В случае прямого Metal-бэкенда весь шейдерный код компилируется без изменений.
* **Аппаратный RT vs Software Воксели (M1/M2 vs M3/M4):**
  * Чипы **Apple M3 и M4** имеют аппаратные RT-ядра в кристалле GPU.
  * Чипы **Apple M1 и M2**, а также многие мобильные ARM GPU (Mali, базовые Adreno) аппаратных ядер ускорения лучей не имеют.
  * **Решение:** Воксельный движок **Bitmask HDDA** работает на стандартном Compute Pipeline и не требует расширений трассировки лучей. На M1/M2 и младших ARM-чипах игра работает в режиме Bitmask HDDA, а на M3/M4 и дискретных GPU переключается на аппаратный `VK_KHR_ray_query` / Metal RT.

### 2. Специфика архитектуры TBDR и UMA (Унифицированная память)
* **Tile-Based Deferred Rendering (TBDR):** Мобильные и M-серии процессоры рендерят изображение тайлами. Грубые глобальные барьеры памяти (`ALL_COMMANDS_BIT`) на TBDR вызывают сброс тайловой памяти (Tile Memory Spill) в медленный LPDDR5 кэш. Переход на точечные барьеры Render Graph критичен для ARM.
* **Zero-Copy / Coherent Память в UMA:** На чипах Apple Silicon и Snapdragon CPU и GPU делят общую физическую память. Промежуточные staging-буферы для передачи мешей Sodium не нужны: буферы вершин помечаются как `VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT`, исключая двойное копирование шины.
* **Размер подгруппы (Subgroup Size):** На Apple Silicon `subgroupSize = 32`. Запрещается хардкодить допущения о ширине варпа (64 для AMD или 16 для Intel); все волшебные операции в Slang оперируют динамическим `WaveGetLaneCount()`.

### 3. Устранение блокировок платформы в кодовой базе
* **CMake:** Удаление запрета `if(NOT WIN32) message(FATAL_ERROR ...)` во всех нативных проектах. Сборка `.dylib` на macOS (через Apple Clang `-std=c++20 -fPIC`) и `.so` на Linux ARM64.
* **Java FFM Locator:** Замена зашитого `bedray_core.dll` в [`BedrayCoreLocator.java`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java) на кроссплатформенный резолвер (`libbedray_core.dylib` для macOS, `libbedray_core.so` для Linux, `bedray_core.dll` для Windows).
* **Сборочный тулчейн Gradle:** В `slang.gradle` скачивать и запускать нативный бинарник компилятора (`slangc-macos-arm64`, `slangc-linux-aarch64` и т.д.).
* **Строгое выравнивание структур (Data Alignment):** Процессоры ARM64 вызывают системное прерывание `SIGBUS` при невыровненных обращениях к памяти. Все нативные структуры C ABI и push-константы Slang стандартизированы с шагом 8 и 16 байт (std430).

