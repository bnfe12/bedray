# Slang post shaders from the Mosaic SDK

Branch `mosaic-slang-source`. Source only: unit tests and compilation, no GPU run, no game session.

The fullscreen post host (`IrisPostExecutor`) takes two SPIR-V buffers per pass. A shaderpack gets them by scanning a folder,
translating GLSL 120 and compiling. This change adds a second source that reads them from a Slang shader the
[Mosaic SDK](https://github.com/bnfe12/mosaic-sdk) node editor compiled with `slangc`, with no pack and no GLSL.

## Turn it on

```text
-Dvkrtx.shader_host=iris -Dbedray.mosaic.post=true
```

Optional: `-Dbedray.mosaic.manifest=<mosaic_manifest.json or its folder>`. The default is
`<gameDir>/mosaic/shaders/mosaic_manifest.json`, where the Mosaic mod writes it. In the library of the editor, "Use" marks a
post-process shader as active; the host loads that one. `-Dbedray.iris.post=true` is not needed, `bedray.mosaic.post` opens the same
render gate (`IrisShaderHost.postHostEnabled()`).

Replacing the shader itself (another active shader, a recompiled SPIR-V) needs a restart. Parameter values do not.

## The contract

All in descriptor set 0. Mosaic writes it into the manifest's `spirv` block; `MosaicPostSource` checks it and refuses anything else.

| Item | Contract |
|---|---|
| Stages | `<name>.post.vert.spv`, `<name>.post.frag.spv`, entry point `main` |
| Vertex input | none; the vertex shader builds a fullscreen triangle from the vertex index |
| `colortex0` | combined image sampler, binding 0 |
| `depthtex0` | combined image sampler, binding 1, present only when the shader reads depth |
| Output | one colour attachment, location 0, the final pass |
| Parameters | std140 uniform buffer, binding **19** (`IrisUniforms.BINDING_MOSAIC`), at most 256 bytes |

Parameter buffer: one `float` per parameter at byte offset `4 * index`, in the order of `paramBuffer.fields`. The executor rewrites the
whole block every frame (`vkCmdUpdateBuffer`, same pattern as the frame and world blocks) from `MosaicPostSource.parameterBytes()`,
which takes the numbers from `parameters[].value` in the manifest and re-reads the file only when its modification time moved
(at most twice a second). A preference change in Mosaic therefore reaches the shader without recompiling anything.

## What changed

- `IrisUniforms`: `BINDING_MOSAIC = 19`, `MOSAIC_BYTES = 256`.
- `IrisPostExecutor`: a third uniform buffer (created, bound, destroyed like the other two), one more binding in the set-0 layout,
  one more descriptor write, **the descriptor pool now counts three uniform buffers per set** (it was two), and the per-frame update.
- `shaderhost/mosaic/MosaicPostSource`: reads the manifest, builds one final `IrisPassGraph.Pass` straight from the SPIR-V
  (synthetic translation metadata: draw buffer 0, the sampler bindings that are really declared, no push/frame/world use),
  runs `IrisPostExecutor.validateGraph`, serves the parameter bytes.
- `IrisShaderHost`: probes that source first when `bedray.mosaic.post` is set; `postHostEnabled()` replaces the three
  `bedray.iris.post` checks in `IrisRenderBridge`.
- Tests: `MosaicPostSourceTest` runs against real SPIR-V produced by Mosaic (slangc 2026.20); `IrisPostExecutorTest`'s layout test now
  expects the extra binding.

## What is not proven

Nothing here ran on a GPU. The synthetic `Pass` passes `validateGraph` and `IrisResourcePlan.build`, the SPIR-V is what slangc
emitted, and the layout matches the executor's by construction and by test, but pipeline creation against the real device, the
descriptor update and the frame output have not been exercised. The other Mosaic domains (sky, light, shadow, surface, reflection)
generate modules checked against Bedray's contracts but have no loader yet.
