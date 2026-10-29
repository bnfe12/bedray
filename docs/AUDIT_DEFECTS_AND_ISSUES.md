# Bedray 2.0 — Полный реестр выявленных дефектов, архитектурных проблем и алгоритмических ошибок

> **Дата актуализации аудита:** 29 октября 2026 г.<br>
> **Ветка:** `fix/audit-remediation-20261029`<br>
> **Статус проверки:** L0 (построчная верификация по дереву исходников) + L2 (Java-тесты без GPU).<br>
> **Область:** C++ нативный бэкенд, шейдерное ядро Slang, Java/FFM хост-интеграция, сборочные скрипты CMake и Gradle.

---

## 1. Сводная оценка архитектуры (Strict Scorecard)

| Подсистема | Оценка готовности | Статус | Ключевой системный риск |
|---|:---:|:---:|---|
| **Native C++ Backend** | **42%** | Частично связан | `gpu_resources`/`render_graph`/`scene` подключены к core; history-API не забинден, `frame_lifetime` изолирован; аллокация дескрипторных пулов кэшируется (P1-2); жёсткий Win32 lock-in. |
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
* **Статус:** **ИСПРАВЛЕНО (коммит `06ca754f`; проверено по исходникам 2026-10-29).** Все экспорты `core.cpp` обёрнуты в function-try-block с `catch (...)`, который возвращает безопасный статус (или `0`/`void` для legacy-сигнатур). `gpu_buffers_c.cpp` — 18/18, `gpu_images_c.cpp` — 14/14, `history_c.cpp` — 22/22 экспортов имеют `try/catch`. Нативный CI [run 40718040730](https://github.com/bnfe12/bedray/actions/runs/40718040730) собрал и прогнал core 17/17, scene 1/1, frame lifetime 1/1 на этой линии. Проверка исключения через FFM на живой JVM не выполнялась; GPU/игрового прогона нет.

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
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** Буфер констант выделяется как `cbStride * cbSlots * d->frames`; база записи кадра — `d->ring * d->cbSlots`, и кольцо продвигается после загрузки, синхронно с пулом дескрипторов. `dispatchN` по-прежнему ограничен `cbSlots` на кадр. Контракт закреплён `NrdConstantRingSourceTest` (L0/source: NRD и Vulkan headers внешние, нативный мост здесь не собирался).

### [P0-8] Нарушение Vulkan VUID-03332 при аллокации памяти структур ускорения BLAS
* **Файлы:** [`acceleration_scene.cpp:84-90, 282-285`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/src/acceleration_scene.cpp#L84-L90)
* **Поведение:** Буферы для структур ускорения и секций создаются с флагом `VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT`. Однако при выделении памяти `vkAllocateMemory` вызывается со структурой `VkMemoryAllocateInfo` без добавления в цепочку `pNext` структуры `VkMemoryAllocateFlagsInfo` с флагом `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`.
* **Причина:** Нарушение обязательного требования спецификации Vulkan VUID-VkMemoryAllocateInfo-pNext-03332.
* **Угроза:** На строгих драйверах (NVIDIA, RADV, Intel) вызовы построения BLAS завершаются с ошибкой `VK_ERROR_INITIALIZATION_FAILED` или сбоем устройства.
* **Статус:** **ИСПРАВЛЕНО (2026-10-28, коммит `5e89275`).** Для буферов с `VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT` в `VkMemoryAllocateInfo.pNext` теперь добавляется `VkMemoryAllocateFlagsInfo` с `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`; нулевой полученный адрес отклоняется с очисткой до публикации. Проверено нативным CI: [run 40718040730](https://github.com/bnfe12/bedray/actions/runs/40718040730) — core 17/17, scene 1/1, frame lifetime 1/1, ABI probe v2 exit 0. Это L2 без GPU/драйверной валидации VUID; GPU-прогона нет.

---

## 3. Архитектурные дефекты и проблемы связности P1

### [P1-1] Оторванные и неинтегрированные нативные C++ модули
* **Файлы:** `native/bedray_gpu_resources/`, `native/bedray_history/`, `native/bedray_render_graph/`, `native/bedray_frame_lifetime/`
* **Проблема:** Написано свыше 250 КБ чистого C++ кода (графы рендеринга, таймлайн-семафоры, кольцевые пулы буферов, трекинг истории). Однако в Java ([`BedrayCoreNative.java`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java)) экспортированы только функции из монолитного `core.cpp`. Ни один из 4 вспомогательных модулей не вызывается из игры.
* **Статус:** **ЧАСТИЧНО ИСПРАВЛЕНО (2026-10-29, проверено по исходникам).** `bedray_gpu_resources` компилируется в `bedray_core` и реально вызывается из Java: `SlangRuntime.createImageOwner/createBufferOwner` → `BedrayGpuImagesNative`/`BedrayGpuBuffersNative` (FFM-экспорты `bedray_gpu_*`); используется в `RtPathTraceSlangPass` и `ReferencePathTraceBackend`. `bedray_render_graph` линкуется в core (`bedray_graph_frame`, `bedray_graph_history`, `bedray_graph_vulkan`), а `FrameGraphOwner` ведёт запись path-trace через `graph::FrameScheduler`. `bedray_scene` встроена в core как `bedray_scene_objects`. Остаётся разрыв: C-API `bedray_history` компилируется в core (`history_c.cpp`), но Java его не биндит, а `path_trace_pass` ведёт собственную историю изображений; `bedray_frame_lifetime` не подключён ни к core, ни к render graph и собирается только как отдельная библиотека с тестом. Контракт проводки закреплён `NativeModuleWiringTest`. Это L0 по CMake/исходникам; нативная сборка в этом окружении не запускалась.

### [P1-2] Аллокация `VkDescriptorPool` на каждый Compute Dispatch (900 раз/сек)
* **Файл:** [`core.cpp:828-844`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L828-L844)
* **Проблема:** В `bedray_core_record_dispatch` при каждом отдельном диспатче создается новый `VkDescriptorPool` с `maxSets = 1`. При 15 compute-проходах за кадр на 60 FPS это **900 вызовов `vkCreateDescriptorPool` и 900 вызовов `vkDestroyDescriptorPool` в секунду**.
* **Угроза:** Блокировка внутренних мьютексов аллокатора Vulkan-драйвера, деградация 1% Low FPS и фрагментация VRAM.
* **Статус:** **ИСПРАВЛЕНО (коммит `3ca8722d`; проверено по исходникам 2026-10-29).** `BedrayProgram` держит кэш до 8 пар `{pool,set}`; `recycle_descriptors` возвращает их после записи, а следующий dispatch берёт готовую пару (`available_descriptor_count > 0`) вместо `vkCreateDescriptorPool`. Новый pool создаётся только при пустом кэше (например, при пиковой конкурентности больше 8) и освобождается при уничтожении программы. Нативная сборка в этом окружении не запускалась; GPU-профилирования «900/сек» нет.

### [P1-3] Барьерный молот `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` в Render Graph
* **Файл:** [`vulkan_recording.cpp:113-122`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/src/vulkan_recording.cpp#L110-L125)
* **Проблема:** Между каждым проходом графа рендеринга выставляется глобальный барьер `VK_PIPELINE_STAGE_ALL_COMMANDS_BIT` с `srcAccessMask = READ | WRITE`. Видеокарта сбрасывает все конвейеры до нуля перед началом каждого прохода, снижая утилизацию GPU на 30–45%.
* **Статус:** **ЛАТЕНТНО, НА ЖИВОМ ПУТИ НЕ ВОСПРОИЗВОДИТСЯ (2026-10-29, проверено по исходникам).** `FrameGraphOwner.configure` добавляет ровно один проход с `RecordingKind::opaque_group`; `record_vulkan_graph` для opaque_group барьер не эмитит — синхронизацию внутри группы ведёт сам path-trace pass. `core_dispatch` (единственный потребитель широкого барьера для dispatch-графов) из core не вызывается. Консервативный `ALL_COMMANDS` остаётся в адаптере для будущих multi-pass dispatch-графов и закреплён нативным `vulkan_recording_test`; при подключении такого графа его нужно заменить на stage/access из `ResourceUse`. GPU-профилирования не было, поэтому цифра «30–45%» не подтверждена.

### [P1-4] Мертвый код отставки буферов команд и спекулятивный `releaseRecording`
* **Файлы:** [`SlangRuntime.java:174-178`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L170-L178), [`RtFrameRenderer.java:590-592`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L588-L595)
* **Проблема:** Метод `retireSubmittedRecording` является мертвым кодом. Вместо него `RtFrameRenderer` спекулятивно дергает `releaseRecording` в начале кадра, полагая, что командный буфер уже завершился на GPU, что ведет к удалению дескрипторов во время исполнения на видеокарте.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** `RtFrameRenderer` регистрирует освобождение через `RecordingCompletion.submitted(...)` уже после `encoder.execute(cmd)`; release выполняется только в очереди уничтожения после ожидания завершения submission (или по доказанному discard). Спекулятивного `releaseRecording` в начале кадра больше нет. Мёртвый `SlangRuntime.retireSubmittedRecording` удалён. Контракт «submission — не proof» закреплён `RecordingCompletionTest.retirementWaitsForHostCompletionProof`, а доказательство завершения — `RecordingCompletionChecks`. Это L2 без GPU; живой прогон не выполнялся.

### [P1-5] Безвозвратная утечка ядра при смене разрешения / режима (`BEDRAY_ERROR_IN_USE`)
* **Файлы:** [`core.cpp:470`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/src/core.cpp#L468-L476), [`SlangRuntime.java:197-204`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/slang/SlangRuntime.java#L195-L205)
* **Проблема:** Если при вызове `bedray_core_destroy` в ядре осталась хоть одна незавершенная запись, функция возвращает `BEDRAY_ERROR_IN_USE`. Java-слой ловит исключение и бросает указатель `core`, навсегда оставляя старые пайплайны и шейдерные модули в VRAM.
* **Статус:** **СМЯГЧЕНО, ПРИНЯТОЕ ОГРАНИЧЕНИЕ (2026-10-29, проверено по исходникам).** `SlangRuntime.close()` больше не теряет указатель: при `BEDRAY_ERROR_IN_USE` он логирует отказ, оставляет `closed=false` и библиотеку загруженной. `RtFrameRenderer.shutdown` в этом случае сохраняет сильные ссылки и `teardownPending`, чтобы повторить teardown после успешного drain; в ветке с миром исключение `destroy_slang_runtime` ловится и помечает gate, не освобождая ресурсы под in-flight записью. Это осознанная утечка до повторной попытки/выхода из процесса, а не молчаливая потеря указателя; принудительно уничтожать core с незавершёнными записями нельзя. Живого теста с реальным `IN_USE` нет.

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
* **Статус:** **ПРИНЯТОЕ ОГРАНИЧЕНИЕ (2026-10-29).** Режим скрыт из релизного UI (`RtMode.RAY_TRACED.isPublic() == false`, unlock через `experimental_rays`), а исключение ловится в `RtFrameRenderer.render()`: кадр логирует `[VKB-RT] FAILED stage=slang_ray` и выключает RT на сессию через `RtGeometryGate.markFailed`, игра не падает. Полноценного Slang-прохода ray effects в текущей миграции нет; реализовывать его без GPU-валидации нельзя.

### [P1-13] Краш Slang при включении G-buffer денойзера (NRD / DLSS-RR)
* **Файл:** [`RtFrameRenderer.java:1378-1388`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java#L1378-L1388)
* **Проблема:** Если включен NRD или DLSS-RR, метод сообщает `"a G-buffer denoiser is selected and the Slang path writes no G-buffer"` и выбрасывает исключение.
* **Статус:** **ЧАСТИЧНО ЗАКРЫТО (2026-10-29).** Прохода с таким сообщением больше нет: path-entry пишет семь DLSS-RR guide-каналов (bindings 26-32), а `createSlangPathPass` при `AUTO` и денойзере явно отказывается от Slang-пути с новым сообщением `"AUTO does not select the Slang path while a denoiser is enabled"`; исключение ловится и выключает RT, а не игру. `SLANG + DLSS_RR` разрешён, `SLANG + NRD` по-прежнему отклоняется честной причиной — нет world-space motion buffer. Это остаётся feature gap (P1-13b): без него NRD нельзя включить на Slang-пути.

---

## 4. Замечания по стандартам, переносимости и тестированию P2

### [P2-1] Хардкод путей разработчика в CMake
* **Файлы:** [`bedray_core/CMakeLists.txt:20`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L20), [`bedray_render_graph/CMakeLists.txt:56`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_render_graph/CMakeLists.txt#L56)
* **Проблема:** Зашит абсолютный локальный путь `C:/Dev/Sky/tools/sdk/Vulkan-Headers/include`.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** Fallback `C:/Dev/Sky/...` удалён из `bedray_core`, `bedray_scene` и `bedray_render_graph`; остались только `-DBEDRAY_VULKAN_HEADERS=...` и `VULKAN_SDK`. `NativePlatformGateTest` запрещает возврат пути. CMake здесь не установлен, конфигурация не запускалась.

### [P2-2] Блокировка сборки на не-Windows платформах в CMake (macOS / Linux / ARM)
* **Файлы:** [`bedray_core/CMakeLists.txt:7-9`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_core/CMakeLists.txt#L7-L9), [`bedray_scene/CMakeLists.txt:5-7`](file:///C:/Users/andre/Downloads/bedray-slang/native/bedray_scene/CMakeLists.txt#L5-L7)
* **Проблема:** `if(NOT WIN32) message(FATAL_ERROR "... currently supports Windows only") endif()`.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29), только гейт.** Оба проекта по умолчанию по-прежнему отказывают на не-Windows, но сообщение теперь называет явный opt-in: `-DBEDRAY_ALLOW_UNSUPPORTED_PLATFORM=ON`. С ним конфигурация выдаёт `WARNING`, что платформа не тестируется и не поддерживается. `bedray_scene` оставляет пустой `PREFIX` только на Windows, чтобы на Linux/macOS имя совпадало с `System.mapLibraryName`. CMake в этом окружении не установлен, поэтому конфигурация/сборка не запускалась; непроверенная платформа по-прежнему не заявлена как поддерживаемая.

### [P2-3] Хардкод расширения `.dll` в Java Native Loader
* **Файл:** [`BedrayCoreLocator.java:20`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLocator.java#L20)
* **Проблема:** Зашит `DLL = "bedray_core.dll"`. Игра не может загрузить `.dylib` на macOS или `.so` на Linux.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29), имя и allowlist.** `BedrayCoreLocator.FILE` и `BedraySceneLocator.FILE` теперь вычисляются через `System.mapLibraryName(LIBRARY_ID)`; `LibraryDescriptor` принимает `.dll`, `.so` и `.dylib` (пути и `*.so.1` по-прежнему отклоняются). Контракт закреплён `BedrayNativeNameTest` и `NativePlatformGateTest`. Это не включает загрузку на не-Windows: структурная проверка `NativeLibrariesImpl` остаётся PE-only, а NRD/DLSS-обёртки Windows-only по своей природе.

### [P2-4] Хардкод бинарника `slangc.exe` в Gradle
* **Файл:** [`gradle/slang.gradle:49`](file:///C:/Users/andre/Downloads/bedray-slang/gradle/slang.gradle#L45-L55)
* **Проблема:** AOT-задача вызывает исключительно `bin/slangc.exe`.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** Имя бинарника в `slangDir`/`SLANG_DIR` выбирается по `os.name`: `slangc.exe` на Windows, `slangc` на остальных платформах; явные `-Pslangc`/`SLANGC` по-прежнему имеют приоритет. Контракт закреплён `NativePlatformGateTest`. Сам AOT-прогон в этом окружении выполнялся с явным `-Pslangc=...`, поэтому ветка `slangDir` тестом не исполнялась.

### [P2-5] Ловушка выравнивания std430 в структуре `VelocityPush`
* **Файл:** [`passes/velocity_reproject.slang:59`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/passes/velocity_reproject.slang#L55-L65)
* **Проблема:** Поле `float2 jitterPrevious` расположено по смещению 60 байт (не кратно 8). SPIR-V вставляет 4 байта невидимого паддинга, сдвигая данные.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** Оба неявных промежутка названы полями `padJitter` (offset 60) и `pad2` (offset 88); отражение по-прежнему даёт 96 байт, `jitterPrevious` остаётся на offset 64. Контракт закреплён `VelocityPushLayoutTest`.

### [P2-6] Ненадежная проверка NaN в `math.slang` при fast-math
* **Файл:** [`contracts/math.slang:41-43`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/contracts/math.slang#L40-L45)
* **Поведение:** `value == value` при оптимизациях схлопывается в `true`.
* **Статус:** **ИСПРАВЛЕНО (2026-10-29).** `isFinite` классифицирует биты экспоненты: `(asuint(value) & 0x7f800000u) != 0x7f800000u`; целочисленная операция не зависит от fast-math. Контракт закреплён `SlangFiniteContractTest`.

### [P2-7] Нарушение взаимности Гельмгольца в диффузном Френеле
* **Файл:** [`shading/bsdf.slang:93, 229`](file:///C:/Users/andre/Downloads/bedray-slang/common/src/main/resources/shaders/slang/shading/bsdf.slang#L90-L100)
* **Проблема:** Диффузный вес умножается на $(1 - F(N \cdot V))$, игнорируя угол падения света $N \cdot L$.
* **Статус:** **ОСТАВЛЕНО ОСОЗНАННО (2026-10-29).** Формула совпадает с owner-tuned legacy GLSL и не меняется без GPU A/B: замена на зависящий от $L$ множитель — визуальное изменение, которое нельзя подтвердить текущим L1/L2-прогоном. Зафиксировано комментарием в `bsdf.slang` и разделом Image в `KNOWN_ISSUES.md`.

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
