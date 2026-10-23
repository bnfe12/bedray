# Fullscreen Iris resource graph

This stage adds bounded current-frame multiple render targets to the experimental composite/final
host. It does not provide a world G-buffer, persistent shaderpack history or general Iris compatibility.
The [original MRT source example](../examples/iris-mrt-post/README.md) shows the supported routing.

## Targets and output locations

Logical targets are `colortex0` through `colortex7`, sampled with `sampler2D`. A composite pass may write
up to four distinct targets in one draw, within Vulkan's guaranteed minimum color-attachment count.
`DRAWBUFFERS` uses a digit list, for example `/* DRAWBUFFERS:03 */`. `RENDERTARGETS` uses comma-separated
indices, for example `/* RENDERTARGETS:3,2 */`. The default remains target 0.

Fragment output **locations** select positions in that ordered target list:

| Directive | Fragment location | Logical destination |
| --- | --- | --- |
| `RENDERTARGETS:3,2` | 0 | colortex3 |
| `RENDERTARGETS:3,2` | 1 | colortex2 |

Constant `gl_FragData[n]` accesses and supported explicit `out vec4` declarations follow that mapping.
Every mapped location must have a corresponding output; sparse logical indices do not create holes
in the fragment output locations.
A dynamic output index, duplicate target/location, out-of-range location or malformed directive is
refused. Legacy/core output declarations may not overlap. Multiple active target directives are
refused rather than relying on an undocumented precedence rule.

Shaderc preprocessing keeps active target directives, including malformed ones, for diagnosis.
Directives in discarded conditional branches have no effect. Include confinement and the existing
source/read/output budgets still apply.

## Initialization and feedback

Each graph invocation begins with the completed world color copied into logical colortex0. Auxiliary
targets begin undefined and must be written by an **earlier active pass** before they can be sampled.
A write by the same pass is not an earlier value. Declaring an auxiliary sampler also establishes that
input requirement, even if later SPIR-V optimization could remove the reference. The host never fills
unavailable world normals, materials or other guides with placeholder data.

Every logical output uses a separate physical image from its sampled current value. For example, a pass
may read colortex0 and write colortex0; the read sees the preceding pass's value and the write becomes
available to the next pass. A pass does not sample its current attachment while rendering.

On the first auxiliary write, the destination is cleared to zero before drawing so clipped/discarded
pixels have defined contents. A later write preserves the previous logical value before drawing.
The clear defines only unwritten output pixels; it does not authorize reads before a producer.
Only graph-referenced or written logical targets are allocated, each at host extent and color format.
The current executor allocates two physical images per logical target, plus one when final is active.
It checks device extent, attachment and sampled-descriptor limits and caps color-image storage at
512 MiB before allocation. Supported host formats are RGBA8/BGRA8 UNORM/sRGB, packed 10-bit UNORM,
B10G11R11 UFLOAT, RGBA16 float/UNORM and RGBA32 float. Other formats are refused explicitly.
The deterministic bank schedule restarts every frame; no auxiliary value survives as pack history.

## Final image and ownership

An optional `final` pass has one output at location 0 and writes a separate presentation scratch image.
It reads the current logical targets without changing their logical identity. With no active final
pass, the current logical colortex0 is the presented result, including when the last composite writes
only an auxiliary target. Auxiliary target ordering never chooses the presented image accidentally.

All draws remain offscreen. The host color is updated only after the graph records its result.
The executor owns its intermediate images and sampled descriptors; it borrows host color/depth on the
existing device and graphics queue. A graph, device, extent or format change must retire queued uses
before replacement. No second renderer/device/queue/config owner is introduced, so the existing
[voxel integration hooks](IRIS_VOXEL_MERGE.md) continue to identify the actual world-color producer.

Descriptor bindings keep color0 at 0, depthtex0 at 1 and the frame UBO at 2; colors1..7 occupy 3..9.
Unused sampled bindings refer to valid owned sampled images. Depth-reading packs still require
[matching frame guides](IRIS_FRAME_GUIDES.md); adding color targets does not make raster depth valid
for a voxel or RT image.

## Remaining scope and evidence

All targets use the host color format and full extent in this stage. Target format, clear, mipmap,
scale, blending and explicit flip/persistence controls remain unsupported, as do integer outputs,
custom images/textures, compute programs, gbuffers, shadow/deferred stages and dimension variants.
The host does not infer those features from otherwise accepted `colortexN` names.
Legacy target directives (`gcolor/gdepth/gnormal/composite/gaux1..4` format/clear constants and
`GAUX4FORMAT` comments) are also refused; preprocessing preserves active comments for diagnosis.

This work is source only. Compilation evidence is recorded separately; it cannot establish visual
correctness, Vulkan synchronization or general shaderpack compatibility. No tests, GPU dispatch,
game session, product JAR or shaderpack ZIP accompanies this stage.

Java compilation passed for common, Fabric and NeoForge with JDK 25.0.5 / Gradle 9.5.1. The original
MRT example compiled through the actual Java frontend to SPIR-V: composite 1212/680 bytes,
composite1 1212/676 bytes and final 1212/804 bytes (vertex/fragment). Existing color NORMAL/BRIGHT,
depth DISTANCE/MOTION and the depth compute source compiled as well. Source review covered bank
routing, descriptor lifetime and unsupported legacy directives; these checks do not verify GPU behavior.
