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

## Mosaic node graphs (`mosaic.json`)

A pack may ship `shaders/bedray/mosaic.json` — a validated node graph instead of hand-written GLSL. When the file is
present it **wins over `rt.comp`**: the host parses the graph, expands it into a synthetic
`bedray/mosaic.generated.comp` and compiles that through the same shaderc path, validation, resource binding and hot
reload as a hand-written stage (editing `mosaic.json` on disk hot-reloads like a source edit, and a save whose graph
generates the same shader as the running one, such as an editor save that only moved nodes, keeps the stage without
recompiling; graph errors keep the
previous stage rendering with the reason in F3).

| Graph node | Kind | Ports (all optional unless noted) | Meaning |
|---|---|---|---|
| `mosaic.uv` | vec2 | — | Pixel UV, half-texel centered |
| `mosaic.texel` | vec2 | — | Pixel texel as floats |
| `mosaic.size` | vec2 | — | Render target size |
| `mosaic.time` | float | — | `frameTimeCounter` seconds (wraps at 3600) |
| `mosaic.frame` | float | — | Frame counter |
| `mosaic.eye` | vec3 | — | Camera position in TLAS space |
| `mosaic.sceneColor` | vec4 | `uv` | Bedray world color sample |
| `mosaic.sceneDepth` | float | `uv` | RT primary-ray distance (1e6 = no hit) |
| `mosaic.trace` | float (`t`) | `origin`, `direction`, `tMax` | Ray query against the scene TLAS; named outputs below |
| `mosaic.shadow` | float | `direction`, `origin` | 1.0 when a ray toward the direction is **not** blocked, else 0.0 |
| `mosaic.sceneColor`…`mosaic.lut` | vec4 | `uv` | `mosaic.lut` = `bedrayLut` (`texture6.png`) |
| `mosaic.buffer` | vec4 | — | `bedrayParams[index]` (SSBO binding 5, `buffer5.bin`) |
| `mosaic.push` | vec4 | — | push slot `index` (0..3), bytes from `rt.push` |
| `mosaic.const` | 1..4 comps | — | Constant from `value: [..]` |
| `mosaic.swizzle` | by `mask` | `a` | Component swizzle (`mask`: `xyzw`/`rgba`, repeats allowed) |
| `mosaic.add` `sub` `mul` `div` | widest | `a`, `b` | Component-wise; float broadcasts, vectors must match |
| `mosaic.min` `max` | kind of `a` | `a`, `b` | Both operands must be the same kind |
| `mosaic.step` | widest | `a`, `b` | GLSL `step` (scalar edge allowed) |
| `mosaic.mix` | kind of `a` | `a`, `b`, `t` | `t` is float |
| `mosaic.clamp` | kind of `a` | `a`, `lo`, `hi` | `lo`/`hi` float |
| `mosaic.gt` `lt` | float | `a`, `b` | 1.0/0.0 comparison, float operands |
| `mosaic.dot` | float | `a`, `b` | Two vectors of the same kind |
| `mosaic.cross` `reflect` | vec3 | `a`, `b` | vec3 only |
| `mosaic.neg` `floor` `fract` `sqrt` `exp` `abs` `saturate` `normalize` `length` | pass-through | `a` | Any numeric kind in, same kind out |
| `mosaic.lum` | float | `a` | Luma (`dot(v, vec3(0.2126, 0.7152, 0.0722))`), vec3 in |
| `mosaic.output` | — | `color` (required) | Single output; float → grey, vec3 → alpha 1 |

`mosaic.trace` named outputs: bare id is the hit distance `t` (-1 = miss), `.pos` (vec3) the hit position,
`.hit` (float) 1/0 hit mask, `.inst`/`.prim` (float) instance-custom/primitive indices. Trace defaults reconstruct the
camera ray (`origin` = eye, `direction` = view ray through the pixel); the shadow node defaults to a fixed sky
direction. The generated shader references only the bindings the graph uses: 0 TLAS, 1 `BedrayFrame` UBO, 2 scene
color, 3 rgba16f output, 4 depth, 5 user SSBO, 6 user LUT; the push block is declared with exactly
`maxPushIndex + 1` vec4 slots, so `rt.push` must hold at least `16 × (maxPushIndex + 1)` bytes.

Ports that are not arithmetic operands (`uv`, `origin`, `direction`, `t`, `lo`, `hi`, `tMax`) take exactly their
declared kind: a float is not promoted into a vector there. `step` with a vector edge needs a vector of the same kind
as `b`. Named trace outputs keep their own kind everywhere (`ray.pos` is a vec3 operand).

All kind mismatches, unknown types/ports, cycles, multiple outputs and out-of-range indices are refused at parse with
the node id (`MosaicGraphTest`). The in-game editor for these graphs is described in
[MOSAIC_EDITOR.md](MOSAIC_EDITOR.md). The emitter and the showcase graph compile to SPIR-V through the real shaderc path
(verified in this repo's harness); the example has not been run on a GPU.

The same graph has a Slang mirror emitter (`MosaicCodegen.slang`): `[shader("compute")]`, combined samplers
(`Sampler2D`), `mul()` matrix ops and Slang `RayQuery` — the canonical AOT target of the renderer. Both emitters were
verified end to end on the showcase graph and a stress graph (all six bindings + trace/shadow named outputs):
GLSL through shaderc, Slang through `slangc 2026.20 -target spirv -profile cs_6_5 -emit-spirv-directly` (5164 B and
6444 B SPIR-V, no errors). The runtime path still compiles the GLSL mirror; the Slang output ships as the canonical
source for the AOT build.

### Host-produced bindings of Mosaic graphs

When the stage comes from `shaders/bedray/mosaic.json`, these set-0 bindings are filled by the host and **never read
from pack files** (a hand-written `rt.comp` keeps the pack-file meaning of 16..31):

| Binding | Declaration | Content |
|---|---|---|
| 4 | `uniform sampler2D` | Also on **raster** frames: the game's depth buffer turned into the same ray distance (blocks, `1e6` = sky) by a host pass, so `depthInfo.z == 1` whenever the renderer has a depth buffer |
| 15 | `readonly buffer` | World volume around the camera (`MosaicWorld`) |
| 16 | `layout(std140) uniform BedrayWorldInfo { vec4 weather; vec4 skyColor; vec4 fogColor; vec4 extra; }` | `weather` = rain 0..1, thunder 0..1, biome base temperature at the camera, 0; sky and fog colour of the frame (rgb); `extra` reserved (0) |
| 18 | `readonly buffer BedrayHeight { ivec4 originRel; vec4 camFrac; uint heights[]; }` | Height field for far shadows (`MosaicHeight`): 256 x 256 columns around the camera, signed 16-bit absolute height above the top motion-blocking block (two per uint, x fastest; `-32768` = not sampled yet); `originRel` = field origin x / z - floor(camera), z = floor(camera y), w = 1 when valid |
| 17 | `layout(std140) uniform BedrayPrevFrame { mat4 projection; mat4 modelView; vec4 cameraPosition; }` | The previous frame's `BedrayFrame.projection`, `modelView` and `cameraPosition` (144 B); on the first frame equal to the current one |
| 19 | `uniform sampler2D` | Previous final image of the stage (rgba16f, bilinear, clamp); black on the first frame |
| 20 + i | `uniform sampler2D` | Buffer pass `i` of a multi-pass graph (buffer names sorted), rgba16f at 1/1, 1/2, 1/4 or 1/8 of the frame, bilinear, clamp |

**Multi-pass graphs.** Each `mosaic.pass` (Buffer) node becomes its own compute dispatch writing binding 3 of its
pass into its buffer image; passes run in dependency order before the final image, every pass on the same descriptor
set layout (the union of all passes' bindings; one binding used with two kinds is refused). Read buffer, Blur buffer
and Kawase blur nodes sample a buffer at binding 20 + i. Studio-built SPIR-V covers every pass: `mosaic.spv` for the final image and `mosaic.pass.<buffer>.spv` for each Buffer,
each with a `.json` sidecar holding the hash of the generated Slang; a pass without a matching pair is compiled in the
game (slangc when installed, else GLSL through shaderc).

**Loop and temporal nodes.** `mosaic.repeat` (mask = a function `vec4 f(vec4 acc, float i[, vec2 uv])` of the file,
index = 1..64 runs, optional `init` and `uv` inputs) emits `acc = f(acc, i)` in a loop. `mosaic.taa` (mask = a Buffer
name or none for the scene colour, input `blend`, default 0.9) blends the reprojected previous frame clamped to the
3x3 neighbourhood of its source.

**Graph settings.** An optional `"settings": {key: [numbers]}` object sets the numbers the generated helpers use
(`MosaicSettings`): `sunTilt`, `skyHorizon`, `skyZenith`, `skyNight`, `skyDayRise`, `skyDayOffset`, `sunDiscColor`,
`sunDiscSharpness`, `sunDiscBrightness`, `shadowDirection`, `shadowRayStart`, `shadowRayLength`, `hitOffset`,
`voxelSteps`, `materialPathSteps`, `farShadowSteps`, `farShadowStep`, `farShadowGrowth`, `farShadowBias`,
`materialColors` (45 numbers: 15 rgb colours in material-class order). They are emitted as constants `ms_<key>`;
missing keys use their defaults, unknown keys or wrong counts are errors. Every built-in node function takes its
own tunables (distances, colours, biases, strengths, speeds) as inputs with defaults, so nothing that changes the
picture is fixed inside the generator.

**History buffers (feedback).** `mosaic.bufferPrev` (Buffer last frame; mask = a Buffer name, input `uv`) reads a
Buffer as it was at the end of the previous frame, and `mosaic.accumulate` (mask = the Buffer it feeds, inputs
`color`, `uv`, `frames` default 32) blends this frame's value into it: last frame's value at the reprojected
position with weight `1/n`, `n` counted in alpha up to `frames`, reset off screen, on the sky, on NaN and where the
previous ray distance there does not match this surface seen from the previous camera. Neither is an ordering
dependency, so a Buffer may feed itself. For every Buffer read this way the plan adds a **history pass**
`~prev.<buffer>` (same size, copies the Buffer), and with Accumulate also `~depth` (full size, the ray distance of
binding 4 in every channel). History passes run **after the final image**, so all readers in the frame see the previous
frame. Their bindings follow the user Buffers (names sorted; `~` sorts after letters, so user Buffer bindings keep
their numbers); Studio SPIR-V for them is `mosaic.pass.~prev.<buffer>.spv` / `mosaic.pass.~depth.spv`. Buffer
images are kept between frames and cleared to black when they are (re)created.

**Denoise.** `mosaic.atrous` (mask = a Buffer, inputs `uv`, `step` default 1, `edges` default 1; 0 = plain blur)
is one edge-aware a-trous step: a 5 x 5 B3 kernel with taps `step` buffer pixels apart, weighted down by the tap's
distance from this pixel's surface plane and by its brightness difference. Chain steps 1, 2, 4 through Buffers.

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
edit-live on a folder pack — both as hand-written `rt.comp` and as the equivalent `mosaic.json` node graph (which
takes precedence when present). It passes the contract check (`CustomRtContractTest`); it has not been run on a GPU.

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
