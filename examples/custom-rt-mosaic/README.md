# Custom RT pack — Mosaic extended contract demo

Demonstrates the extended custom RT contract from [docs/CUSTOM_RT_SHADERS.md](../../docs/CUSTOM_RT_SHADERS.md):
one ray-query compute pass that restyles the path-traced world image using the host's user resources.

| Resource | File | Used as |
|---|---|---|
| Push constants | `shaders/bedray/rt.push` (16 B = one vec4) | Tint rgb + stylization strength, live-reloadable on folder packs |
| User buffer (binding 5) | `shaders/bedray/buffer5.bin` (64 B = four vec4) | Vignette and mix parameters, live-reloadable |
| User texture (binding 6) | `shaders/bedray/texture6.png` (16x16) | Static nearest-sample LUT overlay |

Run it with `-Dbedray.customrt=custom-rt-mosaic` on a folder pack inside `shaderpacks/`, hardware RT mode on,
temporal upscaling and frame generation off. Edit `rt.push` or `buffer5.bin` while the game runs: the next
RT frame picks the bytes up without a restart. Shader source edits (`rt.comp`, includes) also hot-reload in
folder packs: the stage recompiles between frames, and a compile error keeps the previous stage rendering
with the reason in F3. Zip packs load everything once at startup.

## Node-graph mode (`mosaic.json`)

When `shaders/bedray/mosaic.json` is present the host **ignores `rt.comp`** and builds the stage from the graph
instead (see the "Mosaic node graphs" section of [docs/CUSTOM_RT_SHADERS.md](../../docs/CUSTOM_RT_SHADERS.md)): the
showcase graph traces the primary ray (`mosaic.trace`), applies the sky shadow mask (`mosaic.shadow`), tints by the
push strength (`rt.push` slot 0, swizzled to `w`) and adds a sky glow on misses — the same stylization as the
hand-written `rt.comp`, minus the buffer/LUT overlay. Delete or rename `mosaic.json` to fall back to `rt.comp`
(which additionally uses `buffer5.bin` and `texture6.png`). Editing `mosaic.json` hot-reloads like any source file.

Verification level: compile/contract only. The graph parses (`MosaicGraphTest`) and the generated shader compiles to
SPIR-V through the real shaderc path (reflection: bindings 0..3, push block present) in this repo's harness; the pack
passes `CustomRtContractTest`. Nothing here has been run on a GPU or in a game session. Both emitters were verified
against the Slang SDK 2026.20 (`slangc -target spirv -profile cs_6_5 -emit-spirv-directly`): the showcase graph
compiles to 5164 B SPIR-V with zero errors on both paths.
