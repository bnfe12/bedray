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

Verification level: compile/contract only. Shaderc compiles `rt.comp` through the startup path and the pack
passes `CustomRtContractTest` (reflection, contract validation and `CustomRtResources` build); nothing here
has been run on a GPU or in a game session.
