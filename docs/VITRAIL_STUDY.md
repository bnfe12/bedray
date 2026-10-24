# Vitrail source study for Bedray's Iris/Vulkan support

Historical review date: **October 21, 2026**. Source repository: `avpbynf/vitrail-shaders`,
commit [`7a3cdcb`](https://github.com/avpbynf/vitrail-shaders/tree/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e).
This study covers the source and results published by its author at that time. Vitrail was not run
for this study, and its code was not copied into Bedray. Repository and release observations below
are historical findings, not a fresh check of the external project's current state.

## What Vitrail implements

A typical shaderpack contains programs that render geometry, write normals and materials, create
shadows and read preceding passes. Those programs expect particular texture names, matrices,
clearing rules and buffer swaps. Translating `texture2D` to `texture` cannot establish that contract.

Vitrail implements the shaderpack contract over Minecraft's Vulkan backend. It reads and translates
pack sources before rendering; Minecraft then compiles GLSL to SPIR-V and creates graphics pipelines.
Frames execute prepared programs. Changing options or dimensions requires another source-selection
and translation step because the active conditional branches can change.

The reviewed implementation separates these responsibilities:

| Component | Responsibility | Reason |
| --- | --- | --- |
| Pack and option loading | Select files, includes, conditions and fallback programs | Different settings can change a program's dependencies |
| `GlslTranslator` | Rewrite tokens and legacy GLSL constructs | Text substitution alone cannot distinguish enough language constructs |
| `ProgramTranslator` | Reconcile the stages of one program | Separately compiled stages can disagree on varyings or uniform blocks |
| Target and sampler plan | Define formats, clearing, reads and writes for buffer banks | Reading the wrong bank can produce a plausible but incorrect image |
| Frame and geometry integration | Run pack programs at the required world stages | Final color alone cannot supply world normals, materials or shadows |
| Vulkan resource layer | Manage compute, storage images and buffers | Minecraft's graphics Java API does not expose every Vulkan operation |

Vitrail supplies its own compatible execution system. Installing it does not give official Iris a
Vulkan backend. The documented coexistence chooses the implementation by backend: Iris for OpenGL,
Vitrail for Vulkan.

Sources: [translation design](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/docs/translation.md),
[frame design](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/docs/frame.md),
[program interface](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/common/src/main/java/dev/vitrail/glsl/ProgramTranslator.java),
[installation and Iris coexistence](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/INSTALL.md).

## Interpreting the development timeline

At the review date, public repository metadata reported creation on August 1, 2026; the author
described daily development since July. The latest release examined was `v0.13.0-beta`, dated
October 13. Repository creation does not identify the start of development, and frequent tags do
not measure verified compatibility. These observations do not establish a complete port in a few days.

Factors visible in the reviewed material:

1. Minecraft already supplies the Vulkan device, resource management and shader compiler. The author
   did not have to build the entire graphics backend from scratch.
2. Iris supplies a concrete behavioral reference. `NOTICE` lists reused tables, grammars and rules;
   the project declared LGPL-3.0 licensing. These references reduce guesswork, while integration with
   another backend still requires implementation.
3. The README describes AI assistance with implementation and debugging, together with the author's
   stated human review and image comparisons against Iris. Assistance alone does not establish quality.
4. Translation and compilation are kept outside the ordinary frame loop, with caches and background
   preparation of program families. This explains part of the design, without proving an FPS advantage
   on a particular computer.
5. The author publishes a specific set of packs and image observations. Real pack behavior supplies
   feedback that compilation cannot provide.

An inference from those materials is that the existing platform, Iris reference, development tools
and repeated behavior checks all contributed to progress. This is not a measurement of human work hours.

Historical sources: [repository metadata](https://api.github.com/repos/avpbynf/vitrail-shaders),
[README](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/README.md),
[examined release](https://github.com/avpbynf/vitrail-shaders/releases/tag/v0.13.0-beta),
[NOTICE](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/NOTICE),
[compatibility limits](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/docs/compatibility.md).

## Lessons applied to Bedray

First, inspect a program after selecting its active conditions. Its required uniforms, includes and
draw buffers are unknown beforehand. In the Bedray source examined for this study, shaderc performs
preprocessing and its include callback reads only requested files. Paths remain inside the shader
root, with limits on depth, read count, total source and output size. Temporary pragmas preserve
target directives through preprocessing so the chosen branch's directive survives. This initial
implementation used source defaults; saved Iris options were added in the later stage linked below.

Second, reconcile vertex and fragment stages together. Bedray added checks for type, array size,
interpolation and locations. A `mat3` consumes three locations; the next varying must not overlap
its columns. Bedray's direct Vulkan host supplies explicit locations, so the study did not justify
copying Vitrail's workarounds for renumbering through Minecraft reflection.

Third, establish the backend's actual coordinates. Vitrail's documentation says Minecraft's internal
targets retain GL orientation. Local Minecraft 26.3 bytecode inspection also found a positive viewport
in `VulkanRenderPass` and a destination Y reversal in `VulkanGpuSurface.blitFromTexture` at presentation.
The Bedray source therefore added no extra color/depth flip. Its vertex entry converts clip Z from
`[-w,w]` to `[0,w]`, including after an early return from the original main. Reverse-Z depth conversion
is separate. Those source decisions still require an in-game image for visual acceptance.

Coordinate and API reference:
[graphics API study](https://github.com/avpbynf/vitrail-shaders/blob/7a3cdcbe1dfd7e04fb2b3021ee8352d341e7647e/docs/internals/game-graphics-api.md).

## Development sequence for Bedray and voxel rendering

A post host helps inspect the contract between a pack and its executor. It does not supply the full
Iris world renderer. General pack support requires resource semantics, world data and integration
with the relevant passes, beyond adding source substitutions or fullscreen passes.

The sequence proposed at the review date was:

1. Saved options/profiles and an accurate table of engine/capability macros. At that stage, GLSL
   conditions were processed, while conditional `shaders.properties` and user overrides were pending.
2. A typed Bedray frame contract: matrices, current/previous camera, depth, motion, actual image
   producer and exact coordinate conventions. Raster, RT and voxel producers must state which data
   they actually supply.
3. A resource graph with named colortex, formats, clearing/persistence, mipmaps and bank selection.
   Define the CPU plan before its Vulkan executor.
4. Geometry, shadow and deferred integration in world-rendering phases. More fullscreen passes
   cannot replace these inputs and stages.
5. Compute, images and SSBOs using the same Bedray device and queues.

For a broader frontend, evaluate licensed Iris/Vitrail components with their required notices, or a
complete token-based parser. Separate pack processing from Sodium, mixins and GPU dependencies before
porting it. Embedding another complete engine with its own frame owner would create competing hooks
and resource ownership.

The historical implementation slice changed only the Iris Vulkan development work; it did not
duplicate the shared voxel hooks. Java compiled for both loaders, and the original example compiled
to SPIR-V through the actual Java frontend. That slice ran no tests, GPU dispatch or game sessions,
and produced no product JAR or release. General compatibility comparable to Vitrail was not implemented.

Later-stage note: saved options, inherited profiles and conditional post-pack properties are described
in the [options contract](IRIS_OPTIONS.md). The frontend now receives selected values before include
processing. Further frame and resource work has its own [frame contract](IRIS_FRAME_GUIDES.md) and
[resource graph](IRIS_RESOURCE_GRAPH.md). These separate implementation records do not change the
historical Vitrail study or establish live compatibility.
