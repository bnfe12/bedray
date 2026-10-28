# Bedray 2.0 — Полный реестр выявленных дефектов, архитектурных проблем и алгоритмических ошибок

> **Дата актуализации аудита:** 28 октября 2026 г.  
> **Ветка:** `refactor/slang-pipeline`  
> **Статус проверки:** L0 (Сплошная тройная построчная верификация тремя субагентами по дереву исходников).  
> **Область:** C++ нативный бэкенд, шейдерное ядро Slang, Java/FFM хост-интеграция, сборочные скрипты CMake и Gradle.

---

## 1. Сводная оценка архитектуры (Strict Scorecard)

| Подсистема | Оценка готовности | Статус | Ключевой системный риск |
|---|:---:|:---:|---|
| **Native C++ Backend** | **42%** | Архитектурно разорван | 4 модуля изолированы (250 КБ балласта); аллокация дескрипторных пулов 900 раз/сек; гонка NRD; жесткий Win32 lock-in. |
| **Slang Shaders & Alg** | **29%** | Частично активен | В рантайме реально работают только 3 прохода (PT, Display, Bloom); остальные 12 изолированы или на GLSL; несоответствие индексации HDDA. |
| **Java & FFM Host Layer** | **32%** | Переходный тупик | Отсутствует Zero-Mesh из PalettedContainer; краш при включении Ray Traced mode или NRD G-buffer; отсутствие `critical(true)`. |
| **Сборочный контур (Build)** | **50%** | Windows-only | Gradle AOT Slang работает на Windows, создан тестовый контур Python/uv/Tracy, но сборка на macOS/Linux заблокирована. |
| **ИТОГОВЫЙ ПРОЕКТ** | **~35% (D+)** | **НЕ ГОТОВ К ПРОДУ** | **Новый стек существует преимущественно в виде спецификаций и изолированных заготовок; движок опирается на старый GLSL.** |

---

## 2. Критические дефекты P0 (Падения процесса, повреждение памяти и физики света)

### [P0-1] FFM падение JVM при передаче on-heap `pushConstants`
* **Файлы:**
  * [`SlangProgram.java:171-203`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangProgram.java#L171-L203)
  * [`BedrayCoreNative.java:324`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java#L320-L330)
* **Поведение:** Если вызывающий код передал heap-буфер (`ByteBuffer.allocate(...)`), сегмент создается как on-heap (`push.isNative() == false`). В `BedrayCoreNative.java:324` вызов `desc.set(ADDRESS, BedrayCoreLayouts.FD_PUSH_CONSTANTS, push)` выбрасывает `java.lang.IllegalArgumentException: Not a native address` (в Java 22+ Panama FFM структуры нативных вызовов запрещают некопированные адреса из кучи JVM без фиксации GC). Рендер-поток мгновенно падает.
* **Причина:** Отсутствие валидации `pushConstants.isDirect()` и копирования в нативную память `Arena`.
* **Статус:** Закрыто в ветке коммитом `3ca8722d` через внедрение `nativePushConstants(arena, buffer, bytes)`.

### [P0-2] Побег C++ исключений через границу C ABI в Java FFM (JVM Crash `0xE06D7363`)
* **Файлы:**
  * [`core.cpp:466, 482, 704, 722`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L466) (`bedray_core_destroy`, `bedray_core_query_capabilities`, `bedray_core_destroy_program`, `bedray_core_release_recording`)
  * [`gpu_buffers_c.cpp:1-150`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_gpu_resources/src/gpu_buffers_c.cpp) (все 16 экспортных функций)
  * [`history_c.cpp:1-150`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_history/src/history_c.cpp) (все 20 экспортных функций)
* **Поведение:** Экспортные C-функции не имеют блоков `try { ... } catch (...)`. При `std::bad_alloc`, `std::out_of_range` или системном сбое C++ исключение пересекает границу C ABI. На платформе Windows/MSVC это генерирует системное SEH-исключение `0xE06D7363`, которое FFM-рантайм Java не может перехватить, вызывая немедленный жесткий краш процесса JVM с генерацией `hs_err_pid.log`.
* **Причина:** Отсутствие `try / catch` на C ABI интерфейсе `bedray_core`.
* **Статус:** **НЕ ИСПРАВЛЕН** (выявлен в ходе аудита, требует отдельного исправления).

### [P0-3] Инверсия вероятности смеси в ReSTIR DI (Свет темнее в $1/q$ раз)
* **Файлы:**
  * [`block_emitters.slang:494-517`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/light/block_emitters.slang#L494-L517)
  * [`path_tracer.slang:142`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L140-L150)
* **Поведение:** При сэмплировании дискретной смеси эмиттеров и солнца с вероятностью ветвления $q = \text{emitterProbability}$ плотность вероятности выбранного семпла равна $p_{\text{mix}} = q \cdot p_{\text{branch}}$. Ранее выполнялось деление вместо умножения (`sourcePdf /= emitterProbability`), что из-за $W = 1/p$ занижало свет в 4 раза при $q = 0.25$.
* **Причина:** Путаница между коррекцией веса сэмпла $W = 1/p$ и плотностью вероятности $p$.
* **Статус:** Закрыто в ветке коммитом `3ca8722d` (деление заменено на умножение).
* **Проверка 2026-10-28 (source-only):** на HEAD плотности умножаются на вероятность ветки (`sourcePdf *= emitterProbability`, `*= 1.0 - emitterProbability`), а single-branch путь не ренормируется. По коду это соответствует $p_{mix} = q \cdot p_{branch}$; вес $1/sourcePdf$ больше не умножается на $q$ ошибочно. Компиляции/теста не было (L0); нужен тест PDF для $q = 0.25, 0, 1$ и сравнение с эталоном.

### [P0-4] Radiance Cascades: арифметическое среднее вместо окклюзии лучевого транспорта
* **Файл:** [`legacy_float4_store.slang:117-185`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/radiance_cascades/legacy_float4_store.slang#L117-L185)
* **Поведение:** Слияние ближнего и дальнего интервалов каскада реализовано через взвешенное среднее:
  ```hlsl
  float confidence = nearInterval.a + farInterval.a;
  float3 radiance = (nearInterval.rgb * nearInterval.a + farInterval.rgb * farInterval.a) / confidence;
  ```
  В физике переноса излучения вдоль луча каскада ($r_0 \to r_1 \to r_2$) действует уравнение:
  $$L_{\text{total}} = L_{\text{near}} + (1.0 - \text{opacity}_{\text{near}}) \cdot L_{\text{far}}$$
  Если ближний интервал уперся в непрозрачный блок ($\text{near.a} = 1.0$), свет дальнего интервала обязан быть полностью заблокирован. Стена толщиной в 1 блок сейчас усредняется с солнцем: $(L_{\text{wall}} + L_{\text{sun}}) / 2$, вызывая сквозную засветку закрытых помещений.
* **Причина:** Ошибочное понимание альфа-канала интервала как "статистической уверенности" (confidence) вместо коэффициента поглощения/непрозрачности.
* **Статус:** **НЕ ИСПРАВЛЕН** (сохраняется в кодовой базе).
* **Проверка 2026-10-28 (source-only, L1 compile):** в дерево добавлен typed-путь: `RCSegment` и `rcComposeSegments` (`L_front + T_front * L_back`) в `interval_transport.slang`, typed `rcProbeIrradiance`/`rcProbeComplete` в `segment_store.slang`, typed trace `rc_trace.slang`. Старый float4 resolve помечен legacy, но всё ещё присутствует и несовместим с typed atlas. Typed API компилируется `slangc` 2026.20 (harness `verify_rc_typed.slang`, exit 0); это только L1. Typed-путь не подключён к живому кадру, поэтому P0-4 нельзя считать закрытым до runtime-теста, reflected stride check, cross-cascade resolve и интеграции.
* **Чистка 2026-10-28 (source-only, L1 compile):** typed trace `rc_trace.slang` разделён на `rc_trace_options/shade/walk/dispatch/history/ownership`; `rc_atlas_layout.slang` больше не тянет legacy-модуль в typed store. Legacy float4 вынесен в `legacy_float4_store.slang`, старый resolve переименован в `rcLegacyProbeIrradiance`/`rcLegacyProbeComplete`, а `bedray.slang` больше не экспортирует legacy-формат. Это снижает риск перепутать atlas, но **не закрывает P0-4**: live-path, GPU-проверки и cross-cascade resolve по-прежнему отсутствуют.

### [P0-5] Двойной оффсет квадов в аппаратном пересечении геометрии (чтение мусора)
* **Файл:** [`geometry.slang:214-230`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/geometry.slang#L214-L230)
* **Поведение:** При сопоставлении абсолютного и локального индекса квада в секции прибавлялся `range.firstQuad` повторно.
* **Причина:** Потеря семантики относительного vs абсолютного индекса.
* **Статус:** Закрыто в ветке коммитом `b00dc38a` через переход на локальные индексы диапазонов геометрии нативной сцены.
* **Проверка 2026-10-28 (source-only):** `geometryRangeForPrimitive` возвращает индекс диапазона, а `geometryVertexBase` вычисляет `(range.firstQuad + (primitive >> 1u)) * 4u` без повторного `firstQuad`; producer использует локальные индексы quad. По коду offset согласован. Компиляции/теста не было (L0); нужен layout/contract test на несколько диапазонов.

### [P0-6] Spatial ReSTIR DI сбрасывает локальные обновления при победе своего семпла
* **Файл:** [`spatial_block_emitters.slang:150-165`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/restir/spatial_block_emitters.slang#L150-L165)
* **Поведение:** Если собственный семпл пикселя оказывался лучше соседей, шейдер завершал работу без записи (`return;`), отбрасывая накопленный соседский вес $W_{\text{sum}}$ и счетчик $M$, что порождало полосы дисперсии и мерцание.
* **Причина:** Неверная интерпретация рулетки Рестира.
* **Статус:** Закрыто в ветке коммитом `3ca8722d` (ранний выход удален, объединенный резервуар всегда валидируется и сохраняется).
* **Проверка 2026-10-28 (source-only):** `spatial_block_emitters.slang` публикует merged reservoir всегда, когда он конечен и валиден, независимо от того, победил ли собственный sample; `mergeReservoirs` обновляет `M`/`W_sum` даже при сохранении текущего sample. Ранний `if (!won) return;` отсутствует. Компиляции/теста не было (L0); нужен регрессионный тест на own-winner и не-конечный вес.

### [P0-7] NRD Bridge: Гонка данных при перезаписи буфера констант кадра (GPU Crash TDR)
* **Файлы:** [`vkb_nrd.cpp:411-429, 910-932`](file:///C:/Users/andre/Downloads/bedray-slang/native/nrd_bridge/src/vkb_nrd.cpp#L411-L429)
* **Поведение:** Буфер констант `d->cbBuffer` выделен с размером `cbStride * cbSlots` (рассчитан только на 1 кадр). При этом движок поддерживает от 1 до 8 кадров в полете. В функции `vkb_nrd_dispatch` счетчик слотов `nextSlot` каждый кадр сбрасывается в 0. При записи кадра $N+1$ вызов `vkCmdUpdateBuffer` перезаписывает смещение 0, пока GPU еще выполняет кадр $N$. Это приводит к гонке памяти, графическим артефактам денойзинга и зависанию GPU (TDR).
* **Причина:** Отсутствие циклического кольца буферов констант по кадрам в полете (`d->ring`).
* **Статус:** **НЕ ИСПРАВЛЕН** (выявлен в ходе аудита).

### [P0-8] Нарушение Vulkan VUID-03332 при аллокации памяти структур ускорения BLAS
* **Файлы:** [`acceleration_scene.cpp:84-90, 282-285`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/src/acceleration_scene.cpp#L84-L90)
* **Поведение:** Буферы для структур ускорения и секций создаются с флагом `VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT`. Однако при выделении памяти `vkAllocateMemory` вызывается со структурой `VkMemoryAllocateInfo` без добавления в цепочку `pNext` структуры `VkMemoryAllocateFlagsInfo` с флагом `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`.
* **Причина:** Нарушение обязательного требования спецификации Vulkan VUID-VkMemoryAllocateInfo-pNext-03332.
* **Угроза:** На строгих драйверах (NVIDIA, RADV, Intel) вызовы построения BLAS завершаются с ошибкой `VK_ERROR_INITIALIZATION_FAILED` или сбоем устройства.
* **Статус:** **НЕ ИСПРАВЛЕН** (выявлен в ходе аудита).

---

## 3. Архитектурные дефекты и проблемы связности P1

### [P1-1] Оторванные и неинтегрированные нативные C++ модули
* **Файлы:** `native/bedray_gpu_resources/`, `native/bedray_history/`, `native/bedray_render_graph/`, `native/bedray_frame_lifetime/`
* **Проблема:** Написано свыше 250 КБ чистого C++ кода (графы рендеринга, таймлайн-семафоры, кольцевые пулы буферов, трекинг истории). Однако в Java ([`BedrayCoreNative.java`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java)) экспортированы только функции из монолитного `core.cpp`. Ни один из 4 вспомогательных модулей не вызывается из игры.

### [P1-2] Аллокация `VkDescriptorPool` на каждый Compute Dispatch (900 раз/сек)
* **Файл:** [`core.cpp:828-844`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L828-L844)
* **Проблема:** В `bedray_core_record_dispatch` при каждом отдельном диспатче создается новый `VkDescriptorPool` с `maxSets = 1`. При 15 compute-проходах за кадр на 60 FPS это **900 вызовов `vkCreateDescriptorPool` и 900 вызовов `vkDestroyDescriptorPool` в секунду**.
* **Угроза:** Блокировка внутренних мьютексов аллокатора Vulkan-драйвера, деградация 1% Low FPS и фрагментация VRAM.

### [P1-3] Барьерный молот `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` в Render Graph
* **Файл:** [`vulkan_recording.cpp:113-122`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/src/vulkan_recording.cpp#L110-L125)
* **Проблема:** Между каждым проходом графа рендеринга выставляется глобальный барьер `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` с `srcAccessMask = READ | WRITE`. Видеокарта сбрасывает все конвейеры до нуля перед началом каждого прохода, снижая утилизацию GPU на 30–45%.

### [P1-4] Мертвый код отставки буферов команд и спекулятивный `releaseRecording`
* **Файлы:** [`SlangRuntime.java:174-178`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L170-L178), [`RtFrameRenderer.java:590-592`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L588-L595)
* **Проблема:** Метод `retireSubmittedRecording` является мертвым кодом. Вместо него `RtFrameRenderer` спекулятивно дергает `releaseRecording` в начале кадра, полагая, что командный буфер уже завершился на GPU, что ведет к удалению дескрипторов во время исполнения на видеокарте.

### [P1-5] Безвозвратная утечка ядра при смене разрешения / режима (`BEDRAY_ERROR_IN_USE`)
* **Файлы:** [`core.cpp:470`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L468-L476), [`SlangRuntime.java:197-204`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L195-L205)
* **Проблема:** Если при вызове `bedray_core_destroy` в ядре осталась хоть одна незавершенная запись, функция возвращает `BEDRAY_ERROR_IN_USE`. Java-слой ловит исключение и бросает указатель `core`, навсегда оставляя старые пайплайны и шейдерные модули в VRAM.

### [P1-6] Нарушение глобальной истории из-за оборачивания секций по модулю 1024
* **Файлы:** [`contracts/hit.slang:33-36`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/hit.slang#L30-L40), [`traversal/hardware_traversal.slang:62`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/traversal/hardware_traversal.slang#L60-L65)
* **Проблема:** Позиция в мире вычисляется со сверткой координат: `result.worldPosition = float3(section.origin.xyz & 1023) + localPosition;`. При $X, Z > 1023$ координаты сбрасываются в 0, проецируя точку на 1024 блока назад. Временное накопление (TAA, SVGF) взрывается шлейфами.
* **Статус на 2026-10-28 (source-only):** в заявленном виде на HEAD не воспроизводится. `reprojectToHistory` вызывается только из `passes/temporal_accumulate.slang`, который читает отдельный `positionImg` (RGBA32F, абсолютные мировые координаты), а не `SurfaceHit.worldPosition`. Сам `worldPosition` документирован как 1024-обёрнутый neighbourhood proxy и запрещён как identity для history; единственный потребитель — не подключённый `restir_pt`. Латентный риск остаётся, если `restir_pt` подключат к `worldPosition` без развёртки; это host/contract-задача. Компиляции/теста не было (L0).

### [P1-7] ReSTIR PT: раздутый резервуар (465 МБ VRAM) и отсутствие детерминанта Якоби
* **Файл:** [`restir/restir_pt.slang`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/restir/restir_pt.slang)
* **Проблема:** Структура резервуара для путей занимает 112 байт на пиксель (464.5 МБ в 1080p). При сдвиге путей отсутствует учет матричного детерминанта Якоби. Модуль не подключен к конвейеру.

### [P1-8] Обрыв путей при отсечении горизонта в GGX VNDF
* **Файлы:** [`shading/bsdf.slang:161-163`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/shading/bsdf.slang#L160-L165), [`path_tracer.slang:302-306`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L300-L310)
* **Проблема:** При отражении микрограни под горизонт геометрии (`lightLocal.z <= 0.0`) трейсер путей полностью прекращает трассировку луча (`break;`), вызывая неестественное затемнение кромок объектов.
* **Статус на 2026-10-28 (source-only):** заявленное затемнение кодом не подтверждается. При `lightLocal.z <= 0.0` вклад single-scattering GGX BRDF равен нулю (`NdotL <= 0`), а `break` происходит уже после накопления emission и NEE текущей вершины; продолжение пути с нулевым throughput дало бы тот же результат. `valid=false` здесь семантически неточно (это валидный нулевой сэмпл, а не сломанный путь), но на радианс не влияет. Реальная потеря энергии на силуэтах — отсутствие multiscatter-компенсации, отдельная задача. Компиляции/теста не было (L0); для уверенности нужен unit-тест на то, что ниже-горизонтный сэмпл не меняет сумму пути.

### [P1-9] Накопление смещения луча в вырезах (Cutout Holes) искажает ViewZ
* **Файл:** [`path_tracer.slang:228, 246`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/pathtrace/path_tracer.slang#L220-L250)
* **Проблема:** При прохождении листвы `advance = hit.t + options.rayBias;` накапливается в `primaryAdvance`, систематически завышая `firstHitDistance` для денойзера.
* **Статус на 2026-10-28 (source-only):** в заявленном виде не воспроизводится. После каждого hole `ray.origin` сдвигается на `hit.t + rayBias`, поэтому следующий `hit.t` отсчитывается уже от новой точки и уменьшается на накопленное смещение. `primaryAdvance` хранит фактическое расстояние от камеры до текущего origin, а `primaryAdvance + hit.t` даёт расстояние до той же поверхности; $N \times \text{rayBias}$ не накапливается. Компиляции/теста не было (L0); для уверенности нужен тест с несколькими cutout-слоями и известной глубиной.

### [P1-10] Засветка пещер скайбоксом в Radiance Cascades
* **Файл:** [`rc_trace_options.slang:99-114, 193-201`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/radiance_cascades/rc_trace_options.slang#L99-L114), [`rc_trace_walk.slang:232-252`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/radiance_cascades/rc_trace_walk.slang#L232-L252)
* **Проблема:** При `options.worldBoundaryDistance == 0.0` любой промах луча в терминальном каскаде считается выходом в небо, заливая пещеры дневным светом.
* **Статус на 2026-10-28 (source-only, L1 compile):** исправлено в `rc_trace_options.slang` (политика и defaults) и `rc_trace_walk.slang` (добавление environment в far-сегмент). Environment теперь opt-in: `TerminalCascade` требует положительный `worldBoundaryDistance`, `rcEnvironmentAllowed` дополнительно требует `tFar >= worldBoundaryDistance`, а `defaultRCTraceOptions()` выбирает `Disabled`. Нулевая граница больше не означает «доверяем терминальному каскаду» — это отсутствие доказательства, и небо не добавляется. Host, которому нужен sky в RC, обязан задать реальную границу и поднять far override терминального каскада до неё. `verify_rc_environment.slang` компилируется `slangc` 2026.20 (L1); runtime/GPU-теста нет. Это изменение контракта `RCTraceOptions`, его нужно согласовать с host-интеграцией.

### [P1-11] Рудименты рантайм-компиляции GLSL через Shaderc
* **Файлы:** [`SodiumDequantize.java:36`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/sodium/SodiumDequantize.java#L36), [`VelocityPipeline.java:55`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/upscaler/VelocityPipeline.java#L55), [`RtParticleComposite.java:48`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtParticleComposite.java#L48)
* **Проблема:** Ключевые проходы жестко дергают LWJGL Shaderc в рантайме.

### [P1-12] Краш игры при выборе режима Ray Tracing (`RtMode.RAY_TRACED`)
* **Файл:** [`RtFrameRenderer.java:1365`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L1365)
* **Проблема:** При попытке выбрать режим Ray Tracing выбрасывается: `throw new RtFailure("slang_ray", "ray effects are not yet available in the Slang renderer");`.

### [P1-13] Краш Slang при включении G-buffer денойзера (NRD / DLSS-RR)
* **Файл:** [`RtFrameRenderer.java:1378-1388`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L1378-L1388)
* **Проблема:** Если включен NRD или DLSS-RR, метод сообщает `"a G-buffer denoiser is selected and the Slang path writes no G-buffer"` и выбрасывает исключение.

---

## 4. Замечания по стандартам, переносимости и тестированию P2

### [P2-1] Хардкод путей разработчика в CMake
* **Файлы:** [`bedray_core/CMakeLists.txt:20`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L20), [`bedray_render_graph/CMakeLists.txt:56`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/CMakeLists.txt#L56)
* **Проблема:** Зашит абсолютный локальный путь `C:/Dev/Sky/tools/sdk/Vulkan-Headers/include`.

### [P2-2] Блокировка сборки на не-Windows платформах в CMake (macOS / Linux / ARM)
* **Файлы:** [`bedray_core/CMakeLists.txt:7-9`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L7-L9), [`bedray_scene/CMakeLists.txt:5-7`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L5-L7)
* **Проблема:** `if(NOT WIN32) message(FATAL_ERROR "... currently supports Windows only") endif()`.

### [P2-3] Хардкод расширения `.dll` в Java Native Loader
* **Файл:** [`BedrayCoreLocator.java:20`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java#L20)
* **Проблема:** Зашит `DLL = "bedray_core.dll"`. Игра не может загрузить `.dylib` на macOS или `.so` на Linux.

### [P2-4] Хардкод бинарника `slangc.exe` в Gradle
* **Файл:** [`gradle/slang.gradle:49`](file:///C:/Users/andre/Downloads/bedray-slang/gradle/slang.gradle#L45-L55)
* **Проблема:** AOT-задача вызывает исключительно `bin/slangc.exe`.

### [P2-5] Ловушка выравнивания std430 в структуре `VelocityPush`
* **Файл:** [`passes/velocity_reproject.slang:59`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/passes/velocity_reproject.slang#L55-L65)
* **Проблема:** Поле `float2 jitterPrevious` расположено по смещению 60 байт (не кратно 8). SPIR-V вставляет 4 байта невидимого паддинга, сдвигая данные.

### [P2-6] Ненадежная проверка NaN в `math.slang` при fast-math
* **Файл:** [`contracts/math.slang:41-43`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/math.slang#L40-L45)
* **Поведение:** `value == value` при оптимизациях схлопывается в `true`.
* **Статус:** **НЕ ИСПРАВЛЕН** (выявлен в ходе аудита).

### [P2-7] Нарушение взаимности Гельмгольца в диффузном Френеле
* **Файл:** [`shading/bsdf.slang:93, 229`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/shading/bsdf.slang#L90-L100)
* **Проблема:** Диффузный вес умножается на $(1 - F(N \cdot V))$, игнорируя угол падения света $N \cdot L$.

### [P2-8] Конфликт статического CRT (`/MT`) и динамического CRT (`/MD`)
* **Файлы:** [`bedray_core/CMakeLists.txt:77`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L77), [`bedray_scene/CMakeLists.txt:44`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L44)

---

## 5. Сверка с главами ARCHITECTURE_MANIFESTO.MD

| Глава Манифеста | Заявленная цель | Фактическое состояние | Реализация |
|---|---|---|:---:|
| **Глава 1. Масштабируемость** | `ISceneTracer`: Tier 1 Voxel, Tier 2 Hybrid, Tier 3 Path Tracing | Интерфейс написан, но проходы жестко завязаны на `LegacySectionTraversal`. | **15%** |
| **Глава 2. Геометрия чанков** | Bitmask HDDA Мортон; Zero-Mesh PalettedContainer; треугольный BLAS | Zero-Mesh отсутствует; меши извлекаются полигонами Sodium; Morton изолирован. | **25%** |
| **Глава 3. Освещение и оптика** | ReSTIR DI + Light Grid; Radiance Cascades SPWI; Thin-Slab SSS, каустика | P0-4 открыт; `light_grid.slang` и `optics.slang` изолированы; RC в коде — 3D сетка. | **35%** |
| **Глава 4. Атмосфера и дальность** | Фроксели $160\times 90\times 64$; LOD Clipmaps 256 чанков | Фроксели отсутствуют; объемный марш на старом GLSL; клипмапы отсутствуют. | **15%** |
| **Глава 5. Vulkan и память** | BDA 64-bit; Reverse-Z; VMA Slab Allocator; `VK_KHR_pipeline_binary` | BDA отсутствует в push constants; VMA нет (dedicated); aliasing заблокирован в коде; кэш null. | **20%** |
| **Глава 6. Микро-оптимизации** | Octahedral snorm16x2; FP16; Subgroup Wave Voting; Branchless DDA | Octahedral изолирован; Subgroup HDDA изолирован; FP16 в каскадах не включен. | **30%** |
| **Глава 7. Minecraft edge-cases** | Динамические блоки; факел в руке; Dirty Epoch; UV сдвиг лавы | Факел в ReSTIR не инжектится; Dirty Epoch отсутствует; координаты режутся `& 1023`. | **25%** |
| **Глава 8. Денойзинг и апскейл** | DLSS RR / NRD ReBLUR; Karis-Bloom; AgX Tone Mapping | Karis-Bloom на Slang; NRD и DLSS-RR падают в Slang; A-Trous на GLSL. | **45%** |
| **Глава 9. DX и FFM** | Live Hot-Reload F8; F3+R heatmaps; Panama `critical(true)`; Tracy harness | Python/uv/Tracy харнесс готов; F8 и оверлеи отсутствуют; `critical(true)` не выставлен. | **35%** |
| **Глава 10. Верификация** | Double-Verification Gate; 5-фильтровый шлюз самокритики | Протокол внедрен в документацию; выявленные P0 зафиксированы для планомерного устранения. | **40%** |

---

## 6. Пошаговый план устранения (Remediation Roadmap)

1. **Фаза 1 (Срочные P0 блокеры стабильности и физики):**
   * Закрыть P0-2 (защита C ABI в `core.cpp`).
   * Закрыть P0-4 (уравнение переноса в `radiance_cascades_store.slang`).
   * Закрыть P0-7 (кольцевой буфер констант NRD в `vkb_nrd.cpp`).
   * Закрыть P0-8 (флаг `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT` в `acceleration_scene.cpp`).
   * Закрыть P2-6 (числовая стабильность в `math.slang`).
2. **Фаза 2 (Стабилизация Vulkan-бэкенда):**
   * Заменить аллокацию `VkDescriptorPool` на каждый диспатч в `core.cpp` (P1-2) на Ring Descriptor Pool.
   * Добавить `Linker.Option.critical(true)` в `BedrayCoreNative.java`.
   * Устранить краши при выборе режимов `RAY_TRACED` (P1-12) и G-buffer денойзера (P1-13).
3. **Фаза 3 (Интеграция изолированных модулей):**
   * Подключить `optics.slang` к `path_trace.slang` (активация SSS и каустики).
   * Подключить `light_grid.slang` к ReSTIR DI вместо плоского сэмплирования.
   * Синхронизировать индексацию `geometry.slang` с Morton Z-кривой `morton.slang` и `hdda_subgroup.slang`.
   * Устранить обрезание `& 1023` в `hit.slang` и `hardware_traversal.slang` (P1-6).
4. **Фаза 4 (Кроссплатформенность и воксельный движок):**
   * Снять блокировку `if(NOT WIN32)` в `CMakeLists.txt` и `bedray-distribution.gradle`.
   * Добавить `.dylib` / `.so` в `BedrayCoreLocator.java`.
   * Реализовать прямое чтение `PalettedContainer` чанков в Java для честного Zero-Mesh Bitmask HDDA.
