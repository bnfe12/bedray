# Fullscreen Iris resource graph

The experimental post host runs a pack's fullscreen programs over the completed world image with Iris's
colortex model: per-target formats, clear and clear color, history across frames, per-program mipmaps and up to
eight draw buffers per pass. It does not provide a world G-buffer or general Iris compatibility.
Original source examples: [MRT routing](../examples/iris-mrt-post/README.md) and
[formats, history and mipmaps](../examples/iris-history-post/README.md).

## Targets and output locations

Logical targets are `colortex0` through `colortex15`, sampled with `sampler2D`. The OptiFine names `gcolor`,
`gdepth`, `gnormal`, `composite` and `gaux1..4` are accepted as samplers and in directives (colortex0..7).
Higher indices are refused by name. A pass may write up to eight distinct targets in one draw; the executor also
checks the device's `maxColorAttachments` (Vulkan guarantees only four) and refuses with the device's number.
`DRAWBUFFERS` uses a digit list, for example `/* DRAWBUFFERS:03 */`. `RENDERTARGETS` uses comma-separated
indices, for example `/* RENDERTARGETS:3,2 */`. The default remains target 0.

Fragment output **locations** select positions in that ordered target list:

| Directive | Fragment location | Logical destination |
| --- | --- | --- |
| `RENDERTARGETS:3,2` | 0 | colortex3 |
| `RENDERTARGETS:3,2` | 1 | colortex2 |

Constant `gl_FragData[n]` accesses and supported explicit `out vec4` declarations follow that mapping.
A dynamic output index, duplicate target/location, out-of-range location or malformed directive is
refused. Multiple active target directives are refused rather than relying on an undocumented precedence rule.

## Target directives

Directives are read the way Iris reads them: lines whose trimmed text starts with `const`, in the program's
fragment source after preprocessing, including lines inside block comments. Only the live branch of `#if` counts.

| Directive | Meaning here |
| --- | --- |
| `const int colortexNFormat = <GL name>;` | Image format of target N (default `RGBA`, 8-bit) |
| `const bool colortexNClear = false;` | Target keeps its value from the previous frame |
| `const vec4 colortexNClearColor = vec4(...);` | Value of a cleared target at frame start (default (1,1,1,1) for colortex1, (0,0,0,0) otherwise) |
| `const bool colortexNMipmapEnabled = true;` | Mipmaps are generated before **this** program for every target it samples and enables |
| `/* GAUX4FORMAT:RGBA32F */` | colortex7 format; `RGBA32F`, `RGB32F` and `RGB16` only, as in Iris |

A sampled `gdepth` without an explicit colortex1 format selects RGBA32F (the OptiFine rule Iris keeps).
Conflicting explicit values in two programs are refused instead of choosing one.

GL formats map to Vulkan as follows. R/RG/RGBA in 8- and 16-bit UNORM/SNORM, 16/32-bit float,
`R11F_G11F_B10F`, `RGB10_A2`, `RGB565`, `RGB5_A1` and `RGBA4` map directly. GL formats without alpha
(`RGB8`, `RGB16`, `RGB16F`, `RGB32F`, the RGB SNORM formats) are stored as RGBA with alpha writes masked and
alpha cleared to 1, so sampling returns alpha 1 as GL does. Integer formats are refused (the translator has no
integer samplers or outputs), as are `RGB9_E5`, `RGBA2` and `R3_G3_B2`. Every used format must support color
attachment, linear sampling and copies on the device; mipmapped targets also need blits. The refusal names
the target and the GL format.

## Frame model

Each target owns two physical images in its format. Every frame starts with each target's defined value in bank 0:

- colortex0: the completed world image, copied (or blitted when its format differs from the host color format);
- a target with clear enabled: its clear color;
- a persistent target (`Clear = false`): its value from the end of the previous frame.

Reading a target before any pass of the frame wrote it therefore reads that value, as in Iris for a pack without
gbuffers programs. colortex0's clear setting has no visible effect because the world image covers every pixel.
A write copies the target's current bank into its other bank and draws there with load: pixels the pass does not
cover keep their value, and samplers of the same pass still see the previous value. A persistent target that ends
a frame in bank 1 is copied back to bank 0. Fresh images (first frame, after any retirement) are cleared to their
clear color in every bank, so history starts defined.

History is owned by the executor. Graph, extent, host format, depth view, device or world changes retire the
executor and its images, which ends all history; frames in which the post stage does not run leave it unchanged.

## Final image and ownership

An optional `final` pass has one output at location 0 and writes a separate presentation image in the host format.
It reads the current logical targets without changing them. With no active final pass, the current logical
colortex0 is the presented result. The host color is updated only after the graph records.

All draws remain offscreen. The executor owns its intermediate images, samplers and descriptors; it borrows host
color/depth on the existing device and graphics queue. No second renderer/device/queue/config owner is introduced.

Descriptor bindings keep color0 at 0, depthtex0 at 1 and the frame UBO at 2; colortex1..15 occupy 3..17.
Unused sampled bindings refer to valid owned sampled images. A program that enables mipmaps for a target samples
its full chain with trilinear filtering; other programs sample level 0. The image budget is 768 MiB.

## Remaining scope

Unsupported and refused by name: integer targets, custom images/textures, SSBOs, `scale.*`, `blend.*` and
`flip.*` properties, gbuffers, shadow and dimension variants. Depth-reading packs still require
[matching frame guides](IRIS_FRAME_GUIDES.md).

## Evidence

Compilation and unit tests only; no GPU dispatch or game session. The history example compiled through the real
scanner, preprocessor, translator and shaderc: composite 1212/1988, composite1 1212/1492 and final 1212/836 bytes
(vertex/fragment). `IrisRenderTargetsTest` covers the directive parser, live-branch comment preservation, format
mapping and refusals, legacy names, conflicts, the frame plan (history copy-back, frame clears, mipmap sets) and
the eight-output limit; `IrisExamplePacksTest` builds every shipped example.
