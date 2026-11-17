# Custom ray-tracing shaders on Bedray — host contract

Development feature, opt-in, source-only. Two paths share one host, device, queue and world target owner:

1. **Custom RT stage**: a small ray-query compute shader you write against the fixed contract below. It runs
   after hardware RT produced the world image and before hand/HUD and the optional Iris post stage.
2. **Ray-tracing-pipeline packs (Dirt RT)**: Vulkanite-style packs with `rayN.rgen` and `rayN_0.rmiss/.rchit/.rahit`.
   Bedray compiles them and builds their pipeline, but cannot trace them yet (see the last section).

Nothing here was run in a game session. "Compiled" below means shaderc produced SPIR-V through the same Java code the
mod runs at startup; it does not mean the image is correct.

## Custom RT stage

Activation (session only):

```text
-Dbedray.customrt=<file or folder name in shaderpacks/>
```

The pack must contain `shaders/bedray/rt.comp`. `#include` works inside the pack's `shaders/` folder with Iris
semantics (pack-root confined). The entry is Vulkan GLSL 460, compiled for Vulkan 1.2 / SPIR-V 1.4, with
`BEDRAY_CUSTOM_RT` defined to `1`. Shader edits hot-reload in **folder** packs (see below); zip packs need a restart.

The stage runs only in frames where hardware RT (`rt_mode` rays or path) replaced the world image, because only then
does a scene TLAS exist. Temporal upscaling and frame generation must be off: their history would not contain the
stage's output. Every refusal is shown in F3 (`[CUSTOM-RT]`, diag section) and in the log (`[VKB-CUSTOMRT]`).

### Bindings (set 0 only)

Fixed contract bindings:

| Binding | Declaration | Content |
|---|---|---|
| 0 | `uniform accelerationStructureEXT` | Bedray scene TLAS of this frame. Instances are **camera-relative** (origin = camera position). Section instances use cull mask `0x01`, the entity instance `0x02`. Triangle culling is disabled. |
| 1 | `layout(std140) uniform BedrayFrame` | Camera and frame data, layout below |
| 2 | `uniform sampler2D` | Copy of the world color as RT produced it (nearest, clamp) |
| 3 | `layout(rgba16f) uniform writeonly image2D` | Output. Blitted back over the whole world color after the dispatch; write every pixel |
| 4 | `uniform sampler2D` | RT primary-ray distance in blocks (R32F, `1e6` = no hit) when `depthInfo.z == 1`; otherwise the slot holds the color copy and must not be read |

## Extended contract: user resources (Mosaic-friendly)

Bindings **5..31 of set 0** may declare single descriptors the pack itself provides:

| Shader declaration | Pack file |
|---|---|
| `set = 0, binding = N` uniform or storage buffer block | `shaders/bedray/buffer<N>.bin` (raw bytes; uniform cap 65536 B, single buffer cap 8 MiB, 16 MiB total) |
| `set = 0, binding = N` `sampler2D` or `texture2D` | `shaders/bedray/texture<N>.png` (RGBA8, nearest/clamp, one mip; 2048x2048 cap) |

A push-constant block is also allowed:

| Shader declaration | Pack file |
|---|---|
| `layout(push_constant) uniform Push { … };` | `shaders/bedray/rt.push` (raw bytes, zero-filled to the device's `maxPushConstantsSize` and pushed every dispatch; the block must fit the device limit, at least 128 B everywhere) |

Rules and limits:

* Every extra binding **needs its file**; a missing file, a kind without a producer (storage images, standalone
  samplers, texel buffers, input attachments), an array binding or a different set is refused at startup with the
  reason in F3 and the log.
* **Folder packs live-update**: when `shaders/bedray/buffer<N>.bin` or `rt.push` changes on disk, the new bytes are
  re-read and reach the **next dispatch** — no game restart. A file that **grew beyond its creation size** needs a
  restart (the GPU buffer is not reallocated). Zip packs load their bytes once at startup.
* Textures are static for the session. A shader that **writes** a storage buffer keeps its writes between edits:
  new bytes are uploaded only after the file changed, not every frame.
* The fixed bindings (0..4), the output requirement and the device-feature refusal (ray query only) are unchanged.

Any other set, binding, descriptor array or an extra device feature is still refused at startup with the reason.

## Live parameter editing (Mosaic SDK path)

The extended contract exists so a node editor can drive the shader without touching the SPIR-V: the editor writes
parameter bytes into `rt.push` (per-frame small parameters: strength, bias, control flags) and slabs of state into
`buffer<N>.bin` (content that survives between edits, e.g. a light list). Each changed file is picked up on the next
hardware-RT frame.

**Shader source hot reload (folder packs):** the host polls the pack's shader sources (`*.comp`, `*.glsl`, ray stages)
every 0.5 s. When `rt.comp` or any of its includes changed, the same compile + contract check as the startup runs —
on success the stage's pipeline and user resources are rebuilt between frames (a hash-free `reload #N` lands in F3
and the log); on failure the **previous stage keeps rendering** and the reason is shown, so a typo never kills the
session. This works after a failed startup too: fix the file and it compiles in-game. Device features beyond ray
query (for example `Int64`, 8/16-bit storage, descriptor indexing) are refused at every reload: the stage only
relies on the hardware RT device group. Hot reload rebuilds user resources from their files, so buffers may grow to
the caps without a restart.

### `BedrayFrame` (std140, 320 bytes)

| Offset | Member | Meaning |
|---:|---|---|
| 0 | `mat4 projection` | Base projection in GL convention (clip z in `[-w, w]`), no view bobbing |
| 64 | `mat4 projectionInverse` | |
| 128 | `mat4 modelView` | View bobbing × view rotation; camera-relative, no world translation |
| 192 | `mat4 modelViewInverse` | Ray origin = `modelViewInverse * vec4(0,0,0,1)` in TLAS space |
| 256 | `vec4 cameraPosition` | xyz = world camera position as float (precision drops far from the origin); w = 0 |
| 272 | `vec4 viewSize` | width, height, 1/width, 1/height |
| 288 | `vec4 timing` | frame time counter (s, wraps at 3600), frame time (s), frame counter, 0 |
| 304 | `vec4 depthInfo` | near, far, depth available (1/0), 0 |

The UBO is written with `vkCmdUpdateBuffer` inside the frame's command buffer, so it is ordered with the dispatch.

### Orientation and dispatch

The world target keeps GL orientation: texel row 0 is the bottom of the screen. Normalized device coordinates are
`(texel + 0.5) / size * 2 - 1` without a flip. The dispatch size is `ceil(width / local_size_x)` ×
`ceil(height / local_size_y)` × 1; `local_size_z` must be 1.

### Example

[`examples/custom-rt-sky-shadow`](../examples/custom-rt-sky-shadow/README.md) rebuilds the primary ray, finds the first
opaque hit (from binding 4 when present, otherwise with a ray query) and darkens pixels whose ray toward a fixed sky
direction is blocked. Its SPIR-V compiled to 4356 bytes through the startup path and passes the contract check
(`CustomRtContractTest`). It has not been run on a GPU.

[`examples/custom-rt-mosaic`](../examples/custom-rt-mosaic/README.md) demonstrates the extended contract: push
constants from `rt.push`, a user parameter buffer (`buffer5.bin`) and a static LUT texture (`texture6.png`), all
edit-live on a folder pack. It passes the contract check (`CustomRtContractTest`); it has not been run on a GPU.

## Ray-tracing-pipeline packs (Dirt RT)

Activation: select the pack in Iris as usual (`config/iris.properties`) or with `-Dvkrtx.shaderhost.pack=<name>`, and
opt in with:

```text
-Dbedray.rtpipeline=true
```

What happens, in order:

1. Early startup reads the pack and compiles its 12 ray stages (`ray0..2.rgen`, `ray0..2_0.rmiss/.rchit/.rahit`)
   with the pack's saved Iris options applied to its `.glsl` includes.
2. Bedray reads the descriptor layout and the SPIR-V capabilities back from the compiled code and registers one
   all-or-nothing device group with `VK_KHR_ray_tracing_pipeline`, its dependencies and exactly the features the code
   uses. If the GPU lacks any of them, the group is dropped and F3 says so.
3. On the first frame the ray-tracing pipeline is created from the reflected layout and the shader binding table is
   filled from the driver's group handles.

For the Dirt RT 0.4.0 Modrinth release the 12 stages compiled to 792 056 bytes of
SPIR-V; the reflected layout is set 0 bindings 0, 1, 3, 4, 5, set 1 binding 0 (unbounded) and set 3 bindings 0–4,
and the requested features are 16-bit and 8-bit integers and storage, `rayTracingPipeline`, runtime descriptor arrays
with non-uniform indexing, scalar block layout and variable-count, partially bound descriptor arrays. Steps 2–3 have
not run on a device.

**The trace is not dispatched.** Each binding needs a producer and these have none in Bedray:

| Binding | Pack expects | Why it is missing |
|---|---|---|
| set 1 binding 0 (`geometryBuffers[]`) | Vulkanite quad buffers, 160-byte quads, indexed by instance custom index + geometry index | Bedray's TLAS uses its own section slots and keeps no geometry in this vertex layout |
| set 0 bindings 3–5 | terrain atlas plus LabPBR normal and specular atlases as Vulkanite binds them | not exported by the host |
| set 3 bindings 0–4 | Iris `bufferObject.N` SSBOs | their contents come from the pack's compute/composite programs (`composite*.csh`, custom images), which the Iris post host cannot run |

Recording a trace with unbound descriptors is undefined behaviour on the GPU, so the host stops at the pipeline and
names the missing producers in F3 (`[RT-PIPELINE]`). Even a successful trace would only fill the pack's buffers; its image
comes from the later compute and composite programs. Running Dirt RT therefore needs, in this order: Vulkanite-layout
geometry buffers bound to a TLAS whose custom indices address them, the PBR atlases, and Iris compute programs with
SSBOs and custom images.
