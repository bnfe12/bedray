# Bedray Scene — candidate native GPU scene subsystem

Status: **candidate component, L1 evidence only**. ABI v2 builds as a Windows
x64 Release DLL with MSVC 14.44 (`/W4` clean) and its no-GPU contract test
passes (`bedray_scene_contract`, 2026-10-28). It has not been integrated into
`bedray_core`, run on a real GPU, or exercised by Minecraft. Nothing in this
document is a claim of live rendering correctness.

## Files

| File | Purpose |
|---|---|
| `include/bedray_scene.h` | Public C ABI v2 (POD records, opaque `BedrayScene`) |
| `src/scene_internal.h` | Internal C++ state: buffers, AS, sections, entities, tables, frame slots, plans |
| `src/scene.cpp` | Updates and coalescing, frame planning, command recording, commit and retirement |
| `src/acceleration_scene.cpp` | Vulkan function loading, buffer/AS creation and destruction, size queries, command helpers |
| `tests/scene_contract_test.cpp` | Fake-Vulkan contract test (records copies, builds, barriers; enforces the lifetime rules) |
| `CMakeLists.txt` | Windows/MSVC shared library plus CTest registration |

## 1. Public POD contract

The library follows the ABI rules stated in the header:

- every structure is versioned and fixed-layout; no C++ types, STL containers
  or exceptions cross the boundary;
- Vulkan handles are `uint64_t`;
- input pointers are read only during the call and all referenced bytes are
  copied before return, so no Java/FFM pointer may be retained by the caller;
- calls must be externally synchronized on the render thread.

Compile-time layout checks in `scene.cpp`:

| Type | Bytes |
|---|---:|
| `BedraySceneError` | 1028 |
| `BedraySceneAbiInfo` | 48 |
| `BedraySceneDesc` | 88 |
| `BedraySceneSectionInput` | 96 |
| `BedraySceneEntityInput` | 120 |
| `BedraySceneUpdate` | 56 |
| `BedraySceneTableDesc` | 40 |
| `BedraySceneFrameDesc` | 24 |
| `BedraySceneFrameInfo` | 80 |
| `BedraySceneResources` | 112 |
| `BedraySceneSectionRange` | 8 |
| `BedraySceneSectionRecord` | 64 |
| `GeometryVertex` (section vertex stream) | 64 |
| `BedraySceneStatus` | 4 |
| TLAS instance record (`VkAccelerationStructureInstanceKHR`) | 64 |

Field offsets are also compile-time checked (`origin` at 56,
`geometry_count` at 68, `quad_start` at 72, `pass_bits` at 84, `flags` at 88,
`reserved` at 92 in the section input; `origin` at 0, `meta` at 16,
`vertex_address` at 32, `ranges` at 40 in the section record; `first_quad` at 0
and `quad_count_pass` at 4 in the range).

### Create

`bedray_scene_create` accepts host-owned `VkInstance`, `VkPhysicalDevice`,
`VkDevice`, the raw addresses of `vkGetInstanceProcAddr` and
`vkGetDeviceProcAddr`, the host's Vulkan API version, an enabled-capability
mask, `frames_in_flight` (1..8), and capacity limits. It never creates a
device, queue, swapchain or second Vulkan instance.

Validation rules enforced at create:

- `abi_version == BEDRAY_SCENE_ABI_VERSION`, `flags == 0`, `reserved == 0`;
- non-zero instance/physical device/device and proc-address functions;
- API version >= 1.1;
- `ACCELERATION_STRUCTURE` and `DEVICE_ADDRESS` capabilities are required;
- `1 <= frames_in_flight <= 8`;
- `max_sections`, `max_entities`, `max_instances` non-zero,
  `max_sections <= max_instances`, `max_entities <= max_instances`,
  `max_instances <= 0xFFFFFF` (24-bit `instanceCustomIndex`);
- `max_sections <= 0x800000` and `max_entities <= 0x800000`: slot values must
  stay clear of the entity tag bit (`0x800000`) in the shared custom-index
  namespace;
- `max_table_bytes` and `max_geometry_bytes` non-zero. These are the only
  budgets: one geometry's vertex+index bytes and one table's bytes are bounded
  at update time; capacities (`max_sections`, `max_entities`,
  `max_instances`) are fixed at create. There is no global VRAM budget
  accounting.

### Batched update

`bedray_scene_update` takes contiguous POD arrays for section/entity additions
or replacements and ID arrays for removals. The whole call is transactional:
all validation and all CPU copies happen before any scene state is mutated.
Errors leave the previous scene state intact, except an allocation failure
after mutation has begun, which poisons the scene as `OUT_OF_MEMORY`.

Revision rules:

- section: if `geometry_revision` equals the committed revision, no mesh work
  is queued; a stale queued mesh with a different revision is cancelled. A new
  revision queues one mesh copy.
- entity: geometry and topology revisions are tracked separately. Equal
  committed revisions mean no mesh work. `transform` and `instance_mask` are
  always staged; `instance_mask == 0` normalizes to `0xFF`. All 12 transform
  floats must be finite.
- duplicate IDs within one update, unknown removals, and an ID that is both
  updated and removed are rejected.
- section IDs and entity IDs are separate namespaces and must be non-zero.
- capacity is checked against `max_sections`, `max_entities` and
  `max_instances` (`sections + entities`).

### Tables

`bedray_scene_update_table` copies an opaque byte blob for the material or
light table. Revision numbers identify content and coalesce repeated calls.
`size == 0` clears the table. Revision `0` is a valid revision; an internal
sentinel (`~0ull`) distinguishes "never uploaded". `max_table_bytes` bounds
each table. The bytes are not interpreted by the scene.

### Section geometry contract (ABI v2)

A section input is a single mesh whose vertex stream is one quad per four
vertices. Validation enforces:

- `index_type` is `UINT16` or `UINT32`, `index_count == quads * 6`,
  `vertex_count % 4 == 0`, and `quads <= 65535` (the record's range count is
  16 bits);
- `geometry_count` is 1..3 and `quad_start[0] == 0`; each geometry's range is
  non-empty and ranges are contiguous by construction
  (`quad_start[g]` .. `quad_start[g+1]`, last ends at `quads`);
- `pass_bits` holds two bits per geometry, values 0 solid, 1 cutout,
  2 translucent, strictly increasing across geometries; bits beyond
  `geometry_count` are zero;
- `BEDRAY_SCENE_SECTION_FLAG_GEOMETRY_VERTEX` requires `vertex_stride == 64`
  and describes the `GeometryVertex` stream: position float4 at 0,
  uvLight float4 at 16 (xy uv, zw block/sky), tint float4 at 32, meta uint4
  at 48 (x material id, y flags, zw zero).

One BLAS geometry is created per non-empty pass range. Every geometry points
at the same vertex/index buffers and differs only in its build range;
`VK_GEOMETRY_OPAQUE_BIT_KHR` is set only for the solid (pass 0) range.

**Native-scene indices are local to the pass range that contains them.** Each
quad is indexed as the pattern `(0,1,2, 2,3,0)` relative to the range start:
quad `k` inside a range writes `4k + (0,1,2, 2,3,0)`. The build range supplies
the offset instead: `firstVertex = quad_start * 4` and
`primitiveOffset = quad_start * 6 * index_bytes`. A producer that writes
absolute (section-global) indices double-offsets the vertices and corrupts the
range; a producer that restarts at zero for every quad in a multi-quad range
collapses the range onto its first quad.

The shader-facing section table is rebuilt when membership, geometry
addresses or geometry metadata change. Records are written in ascending
`section_id` order, so a record's index (its slot) is exactly the
`instanceCustomIndex` of the section's TLAS instance:

- `origin.xyz` block origin, `origin.w` stable 32-bit identity hash of the
  section id (not the slot; it survives reordering);
- `meta.x` vertex count, `meta.y` geometry count, `meta.z` section flags,
  `meta.w` zero;
- `vertex_address` BDA of the vertex stream;
- `ranges[g].first_quad`, `ranges[g].quad_count_pass` with bits 0..15 count,
  16..17 pass, 18..31 flags.

Entity instances share the TLAS but not the namespace: their
`instanceCustomIndex` is `0x800000 | entity_slot`. The tag bit is the only
signal a hit needs to tell a mob quad from a section quad.

### Frame preparation

`bedray_scene_prepare_frame`:

- requires a non-zero, strictly increasing `frame_index`;
- requires a non-null host command buffer that is not already recorded by an
  unreleased prepare;
- requires fewer than `frames_in_flight` outstanding prepared frames;
- records commands only into the supplied buffer; it never submits, presents,
  waits idle, or creates a queue;
- returns flags describing the work: TLAS rebuilt/updated, BLAS
  rebuilt/refit, uploads, table uploads, or idle;
- returns counters: instance count, BLAS builds, refits, deferred updates,
  upload bytes, section count.

The camera eye used to place section instances is set separately with
`bedray_scene_set_view`. It is view state, not scene content: it never bumps the
scene version and only invalidates the slots that prepared with a different
view.

`bedray_scene_release_frame` must be called only after the host has proven GPU
completion of that submission (or discarded an unsubmitted recording). It
frees that frame's transient staging and BLAS scratch buffers, releases the
BLAS pins held by the frame, and runs retirement collection. The library
cannot verify completion; this is a trust boundary owned by the host.

`bedray_scene_query_resources` returns the resources of the most recently
prepared frame (TLAS, instance buffer and address, section table, material and
light tables). These handles are owned by the scene, remain valid until the
next prepare or `bedray_scene_destroy`, and must not be destroyed by the host.

`bedray_scene_destroy` returns `BEDRAY_SCENE_ERROR_IN_USE` while any prepared
frame is outstanding. Otherwise it destroys every scene-owned buffer, memory
allocation and acceleration structure. The host must call it before destroying
the Vulkan device.

### Status and failure model

`BedraySceneError` is a status plus a fixed 1024-byte NUL-terminated message.
No exception crosses the ABI. All exported functions catch allocation and
unexpected exceptions and convert them to statuses.

`BEDRAY_SCENE_ERROR_DEVICE_LOST` poisons the scene permanently: subsequent
calls fail until `bedray_scene_destroy`. Other recoverable input errors
(`INVALID_ARGUMENT`, `UNKNOWN_ID`, `STALE`, `LIMIT`, `IN_USE`,
`ABI_MISMATCH`, `UNSUPPORTED`) leave the scene usable.

## 2. Ownership graph and frame retirement

```
Host owns                          Scene owns
--------                           ----------
VkInstance                         sections: id -> committed GeometryResource
VkPhysicalDevice                     GeometryResource: device-local vertex/index
VkDevice                             buffer + BLAS
queue                              entities: id -> committed GeometryResource
VkCommandBuffers                     plus pending transform/mask/mesh
VkFences / submissions             tables[2]: GPU buffer + pending bytes
                                   section table: GPU buffer (64-byte records)
                                   frame slots[frames_in_flight]:
                                     TLAS, TLAS scratch, host-visible instance
                                     buffer, transient staging/BLAS scratch,
                                     blas_in_use pins, fingerprint/version
                                   retired_buffers / retired_accelerations
```

Per frame slot:

- one persistent TLAS and TLAS scratch buffer, reused across frames;
- one persistent host-visible instance buffer sized
  `align_up(max_instances * 64, 256)`, written through a mapped pointer;
- a transient list containing the staging buffer and the BLAS scratch buffer
  for that prepared frame, destroyed in `release_frame`;
- `blas_in_use` lists every BLAS referenced by that frame's TLAS; it pins
  those BLAS against in-place refits until the frame is released.

Commit/replacement policy:

- when a section/entity geometry or a table/section-table buffer is replaced,
  the old resource is appended to a retired list with
  `after_frame = last prepared frame index` (before the new frame is
  committed);
- a retired resource is destroyed only when no outstanding frame has
  `frame_index <= after_frame`; this is checked on every prepare and release;
- refit jobs keep the old BLAS and replace only the vertex/index buffer; the
  old buffer is retired with the same rule;
- slot-owned TLAS/instance/scratch objects are replaced only while the slot is
  free, so no prepared frame can reference them; they are destroyed
  immediately at commit;
- `destroy` is refused while `outstanding != 0`.

There is no global `vkDeviceWaitIdle` and no fixed "defer N frames" constant;
retirement is driven by actual prepared-frame release.

## 3. Acceleration-structure policy

The policy is capability-driven (`AS_UPDATE`), never GPU-name-driven.

### Sections (static terrain)

- A section BLAS is built or rebuilt only when its `geometry_revision`
  differs from the committed revision.
- Sections are never refit; a revision change replaces the BLAS and the
  vertex/index buffer.
- Sections may be added/removed at any time; removal retires the committed
  resources under the frame rule.

### Entities (dynamic)

- Transform-only or mask-only changes never touch the entity BLAS.
- A geometry-revision change is refit in place only when all of the following
  hold: the entity already has a committed BLAS, `AS_UPDATE` is enabled, the
  mesh shape is identical (vertex count, index count, vertex stride, index
  type), and no outstanding frame references the BLAS.
- Otherwise (first build, topology revision change, shape change, missing
  `AS_UPDATE`) the BLAS is rebuilt.
- If a refit is required but the BLAS is pinned by an outstanding frame, the
  update is deferred (reported in `deferred_updates`) and retried on a later
  prepare; the pending mesh remains queued.
- A refit rewrites the BLAS storage in place, so every TLAS that references it
  is invalidated via the scene version; the next prepare rebuilds or updates
  the affected slot TLAS before tracing.

### TLAS

- Rebuild/update is required when the slot has no valid TLAS, the slot was
  built from an older scene version, or the instance count changed.
- In-place update is allowed when the slot has a valid TLAS, `AS_UPDATE` is
  enabled, the instance count is unchanged, and the instance fingerprint is
  unchanged. The fingerprint covers BLAS handle and device address, custom
  index, and mask; transforms are deliberately excluded so a pure transform
  change can use TLAS update.
- Mask changes (and custom-index changes caused by membership ordering) alter
  the fingerprint and therefore conservatively rebuild the TLAS.
- Geometry rebuild/refit and membership changes bump the scene version, which
  forces TLAS work on every slot that prepared with older state.
- An empty scene destroys the slot TLAS and reports `TLAS_REBUILT`.
- No TLAS compaction, serialization, or device-generated instances.

### Instance records

Each record is the 64-byte `VkAccelerationStructureInstanceKHR` layout:

- row-major 3x4 transform (sections are placed camera-relative, see below);
- section `instanceCustomIndex` is the section table slot; entity
  `instanceCustomIndex` is `0x800000 | entity_slot`; the value is masked to
  24 bits when written;
- `mask` defaults to `0xFF` and is limited to the low 8 bits;
- SBT record offset 0, `TRIANGLE_FACING_CULL_DISABLE` set;
- BLAS device address from the acceleration structure.

Section placement is camera-relative: `bedray_scene_set_view` stores the camera
eye in the same world space as section origins, and every prepared frame writes
a section instance as `translate(origin - view)` (subtraction in `double`,
rounded once to `float`). The view defaults to unset, which keeps the
historical identity placement. A view change rewrites the slot's instance
buffer and forces the TLAS work path; because the instance fingerprint excludes
transforms, it is an in-place TLAS update whenever `AS_UPDATE` is enabled and
the instance count and fingerprint are unchanged, with no BLAS work. This is
needed because the path tracer traces camera-relative rays from the eye at the
origin while section vertices stay section-local; entities already carry a
camera-relative transform from the host.

Slot compaction is intentional: adding or removing a section can renumber
later sections, which changes their custom indices and therefore the instance
fingerprint (forcing a TLAS rebuild). The shader must resolve a section through
the section table by slot, never treat the custom index as a stable section id;
`origin.w` is the stable identity.

## 4. Required Vulkan functions and barriers

### Functions loaded by the scene

Instance-level (through the host's `vkGetInstanceProcAddr`):

- `vkGetPhysicalDeviceMemoryProperties`
- `vkGetPhysicalDeviceProperties2`
  (with `VkPhysicalDeviceAccelerationStructurePropertiesKHR` for scratch
  alignment)

Device-level (through the host's `vkGetDeviceProcAddr`):

- `vkCreateBuffer`, `vkDestroyBuffer`, `vkGetBufferMemoryRequirements`
- `vkAllocateMemory`, `vkFreeMemory`, `vkBindBufferMemory`
- `vkMapMemory`, `vkUnmapMemory`, `vkFlushMappedMemoryRanges`
- `vkGetBufferDeviceAddressKHR` (or core `vkGetBufferDeviceAddress`)
- `vkGetAccelerationStructureBuildSizesKHR`
- `vkCreateAccelerationStructureKHR`, `vkDestroyAccelerationStructureKHR`
- `vkGetAccelerationStructureDeviceAddressKHR`
- `vkCmdBuildAccelerationStructuresKHR`
- `vkCmdCopyBuffer`
- `vkCmdPipelineBarrier`

There is deliberately no submit/present/queue-wait/device-wait entry point in
the scene's function table.

Memory selection: geometry, tables, section table, AS storage and scratch are
device-local; staging and the instance buffer are host-visible and mapped at
creation. Non-coherent mapped memory is flushed with `VK_WHOLE_SIZE` (whole
allocation) to satisfy the `nonCoherentAtomSize` rules. Scratch addresses are
aligned to `minAccelerationStructureScratchOffsetAlignment`, with a minimum of
256 bytes even when the driver reports zero scratch size.

### Recorded barrier sequence

1. **Host writes -> transfer reads**: staging buffer barrier,
   `HOST`/`HOST_WRITE` -> `TRANSFER`/`TRANSFER_READ`, then `vkCmdCopyBuffer`
   calls for geometry, tables and the section table.
2. **Copies / host instance writes -> acceleration-structure build**:
   - each new or refit geometry buffer: `TRANSFER`/`TRANSFER_WRITE` ->
     `ACCELERATION_STRUCTURE_BUILD`/`ACCELERATION_STRUCTURE_READ`;
   - instance buffer: `HOST`/`HOST_WRITE` ->
     `ACCELERATION_STRUCTURE_BUILD`/`ACCELERATION_STRUCTURE_READ`.
   The source stage is the union of `TRANSFER` and `HOST` as applicable.
3. **BLAS work**: `vkCmdBuildAccelerationStructuresKHR` for every job.
   A refit uses `ALLOW_UPDATE`, source = destination = the committed BLAS and
   the update scratch size. A rebuild targets the new BLAS.
4. **BLAS -> TLAS**: if the TLAS references a BLAS written this frame, or the
   TLAS itself is updated in place, a memory barrier
   `ACCELERATION_STRUCTURE_BUILD`/`ACCELERATION_STRUCTURE_WRITE` ->
   `ACCELERATION_STRUCTURE_BUILD`/`ACCELERATION_STRUCTURE_READ` is recorded
   before the TLAS build. A BLAS update invalidates and requires this
   ordering for any TLAS that references it.
5. **TLAS build/update**: `vkCmdBuildAccelerationStructuresKHR` on the TLAS.
   Update mode requires the same instance count; the fingerprint check ensures
   the referenced BLAS set is unchanged.
6. **AS work / uploads -> compute tracing**: a final barrier with:
   - source stages: `ACCELERATION_STRUCTURE_BUILD` when AS work happened,
     `TRANSFER` when copies happened, `HOST` when the instance buffer was
     rewritten;
   - source access: `ACCELERATION_STRUCTURE_WRITE` for AS work;
   - destination stage: `COMPUTE_SHADER`;
   - a memory barrier with destination access
     `SHADER_READ | ACCELERATION_STRUCTURE_READ` (the TLAS is read through an
     acceleration-structure access, which `SHADER_READ` alone does not cover);
   - buffer barriers to `SHADER_READ` for every uploaded geometry buffer, new
     table, new section table and the instance buffer.

The host must append its tracing/dispatch commands after the scene's recorded
commands, submit them, and call `release_frame` only after that submission is
complete. The scene never synchronizes Minecraft/Sodium resources.

## 5. Java reference mapping (read-only references)

| Legacy reference | Legacy behaviour | Native concept |
|---|---|---|
| `rt/world/RtWorld.java` (`SectionGpu`, `gpu` map, `section_table`, TLAS built every frame, `DEFER_FRAMES`) | One BLAS per section, TLAS rebuilt each frame, fixed defer window | `sections` map + committed `GeometryResource`; persistent per-slot TLAS with update/rebuild policy; retirement keyed to released frame indices |
| `rt/world/RtInstanceWriter.java` | Writes the 64-byte instance layout directly | Same layout produced by `plan_tlas`; transform floats 0..47, custom index/mask word at 48, SBT/flags at 52, BLAS address at 56 |
| `rt/sodium/DecodedSection.java` | Decoded Sodium quads with positions/uvs/colors/light/normals/raw words | Not consumed directly: Java must triangulate and pass raw vertex/index bytes through `BedraySceneSectionInput`/`BedraySceneEntityInput`. The scene does not decode Sodium or own the atlas |
| `rt/sodium/SectionGeometryRegistry.java` | Decoded-section cache with revisions | `geometry_revision` on section inputs; equal revision means no rebuild |
| `rt/world/RtEntityBlas.java` | Entity BLAS rebuilt every frame with `PREFER_FAST_BUILD` | Per-entity BLAS with capability-driven refit/rebuild and deferred refit; the per-frame fast-build policy is intentionally not carried over |
| `rt/world/RtEntityGeometryManager.java` | CPU ring buffers, atlas swaps, retire queue | Scene-owned per-frame staging, per-slot instance buffers, frame-index retirement; the scene does not manage entity capture or atlases |
| `rt/core/BedrayCoreNative.java` | FFM segments, status/error convention, recording release | Same ABI style: POD inputs, `uint64_t` handles, status plus fixed message, prepare/release recording pair. The scene is not yet exported by `bedray_core` |
| `RtWorld` section table (48 bytes/slot) | Shader lookup by `instanceCustomIndex` | `BedraySceneSectionRecord` (64-byte ABI v2 layout), sections sorted by `section_id`, custom index equals the table slot, buffer address/count exposed via `BedraySceneResources` |
| `RtWorld` light hash / vegetation bits / OMM micromaps | Shader-side flags and micromap objects | Not modelled in this candidate; open integration gap |

## 6. Open ABI decisions for the integrating agent

1. **Packaging**: ship as a separate `bedray_scene.dll` (current candidate) or
   fold the implementation into `bedray_core` behind a versioned extension of
   `bedray_core.h`. The addendum forbids editing the core ABI header now.
2. **Section record**: ABI v2 fixes the 64-byte record and the 8-byte range
   (count 16 bits, pass 2 bits, flags 14 bits). The shader-side field semantics
   must still be confirmed against the Slang `SceneTraversal`; any change is an
   ABI v3 addition coordinated with the Java and Slang agents.
3. **Custom index stability**: section slots are compacted by `section_id`
   order, so membership changes renumber later slots. The shader resolves
   sections through the table by slot; `origin.w` is the stable identity. The
   entity tag bit `0x800000` is a fixed convention of the entity ring path and
   part of this contract.
4. **Table schema**: table bytes are opaque. Alignment, std430 layout and
   element counts must be defined together with the shader; the scene only
   guarantees byte-identical upload and BDA exposure.
5. **Textures, atlases, OMM/micromaps, and light flags** used by the legacy
   renderer are outside this component and need explicit ownership decisions.
6. **Failure-to-status mapping**: decide how `STALE`, `UNKNOWN_ID`,
   `ABI_MISMATCH`, `LIMIT` and `IN_USE` surface as `BedrayStatus` values in
   the host (several are recoverable input errors rather than GPU errors).
7. **Device loss**: a poisoned scene must be destroyed and recreated; decide
   whether the host tears down the whole renderer or only the scene.
8. **Capacity growth**: `max_*` are fixed at create. Growing beyond them
   requires recreate; a future ABI may allow capacity extension.
9. **Section-table allocation**: the table is reallocated at its exact current
   size when membership or geometry addresses change; there is no
   preallocation to `max_sections`. This is simple but causes churn on large
   terrain edits; an arena/free-list policy may replace it later.
10. **Instance buffer**: allocated for `max_instances` records per slot and
    host-visible; a device-local + upload alternative is possible if profiling
    shows the host write is significant.
11. **No record flags yet**: frame and update `flags` must be zero. Future
    flags (e.g. explicit TLAS rebuild, async compute stage) need ABI additions.
12. **Trust boundary**: `release_frame` assumes the host proved completion.
    A future ABI may accept a timeline semaphore/fence value to make this
    enforceable inside the library.

## 7. Build and test evidence

Toolchain and exact commands used:

```
cmake -S . -B build -DBEDRAY_VULKAN_HEADERS=C:/Dev/Sky/tools/sdk/Vulkan-Headers/include
cmake --build build --config Release
ctest --test-dir build -C Release --output-on-failure
```

The MSVC build uses `/W4 /permissive- /EHsc /utf-8` and completes without
warnings. The shared library is `build/Release/bedray_scene.dll`.

Latest executed result (ABI v2, 2026-10-28): **1/1 tests passed**
(`bedray_scene_contract`), exit code 0, no compiler warnings. The contract
test uses a fake Vulkan proc table (no GPU, no driver).

Staged artifact: `bedray_scene.dll`, 221 184 bytes, SHA-256
`5cabb51c2bbb354d0d61c5377f90a9edf74871227aee9ac37239cdd87f2e12ee`
(x64, static CRT, imports only `KERNEL32.dll`).

Contract coverage (every item below executes in `bedray_scene_contract`):

- static ABI layout checks, including every documented field offset;
- create validation (capabilities, frames, capacities, reserved fields,
  23-bit slot space for sections/entities);
- section-contract rejection: `quad_start[0] != 0`, empty range, unordered or
  out-of-range passes, `pass_bits` beyond `geometry_count`, bad
  `geometry_count`, unknown flags, `GEOMETRY_VERTEX` with the wrong stride,
  `vertex_count % 4`, `index_count != quads*6`, non-indexed sections,
  quad count above `0xFFFF` (boundary `0xFFFF` accepted);
- one BLAS geometry per non-empty pass range with per-range
  `primitiveCount`/`primitiveOffset`/`firstVertex`, shared vertex/index
  addresses, `OPAQUE` only on pass 0, and matching size-query max counts;
- section record meta/origin/ranges and stable `origin.w` identity across
  rebuilds;
- section table records ordered by `section_id`, TLAS custom index equal to
  the table slot, entity tag `0x800000`, slot compaction after removal;
- `GeometryVertex` section stream with 64-byte stride;
- UNKNOWN_ID on removing an unknown section;
- first frame records copies, BLAS builds, TLAS build and the full barrier
  chain;
- idle frame reuses slot state and reports `IDLE`;
- update input copying, revision coalescing and stale pending cancellation;
- transform-only update performs TLAS update with no BLAS work;
- mask-only update rebuilds the TLAS with no BLAS work;
- refit is deferred while a frame referencing the BLAS is outstanding, then
  performed and linked correctly to the existing BLAS;
- without `AS_UPDATE`, a geometry change rebuilds and retires the old
  resources only after the referencing frame is released;
- table coalescing, clearing, and revision `0` uploads;
- stale frame indices, outstanding-frame limits, duplicate command buffers and
  destroy-while-outstanding;
- transactional updates (a bad batch leaves the scene unchanged);
- capacity limits;
- entity removal clears the TLAS and retires resources immediately when no
  frame is outstanding;
- device loss poisons the scene.

### Limitations / required live validation

- This is L1 source/build evidence only. The fake Vulkan layer does not model
  driver behaviour, real memory types, synchronization validation, or GPU
  hazards.
- Not yet validated with Vulkan validation layers, on a real GPU, or inside
  Minecraft.
- No performance or memory measurements (BLAS/TLAS build cost, scratch sizing,
  table churn) exist yet.
- Required next steps for the integrating agent: record the scene commands
  with validation layers enabled; compare the rendered scene against the
  legacy path; verify correct section/material/light table consumption from
  Slang; measure BLAS/TLAS cost and VRAM against the Java renderer; then
  integrate into `bedray_core` and the in-game Path Tracing mode.
