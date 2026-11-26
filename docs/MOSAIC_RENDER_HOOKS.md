# Bedray's path tracer through Mosaic: stages, hooks and the agent loop

Bedray's path tracer stays Slang code; Mosaic opens it at fixed points (hooks). Each hook is an ordinary function of
Bedray's Slang pack whose body is the identity in Bedray's own build. Mosaic Studio shows it as a renderer stage of your
own ("Your ... (path tracer)"): its body is a node graph (or a Slang block inside one), checked by slangc inside its
module and rebuilt into the game's passes with **Apply to Bedray** (`mosaic pack apply`).

## Path tracer stages

| Stage | Hook (`pathtrace/path_tracer/...`) | Called | In / out |
|---|---|---|---|
| Your material | `materialStudio` | every surface a path reaches (camera hit and every bounce), before shading | inout albedo, roughness, metallic, emission; in world position, shading normal, material id. Clamped to physical ranges after. |
| Your light | `lightStudio` | every direct-light (NEE) sample of a vertex: sun, moon, block emitters | inout radiance; in direction to the light, world position, light kind (0 block, 1 sun, 2 moon). Kept non-negative. |
| Your sky light | `skyLightStudio` | every path that escapes to the sky | inout radiance; in direction, bounce (0 = the camera sees the sky). |
| Your emission | `emissionStudio` | emission a path sees on a glowing surface (before NEE takes over emitter light) | inout emission; in world position, bounce (0 = camera). |
| Your bounce | `bounceStudio` | after every BSDF sample, before the throughput update | inout weight (kept >= 0; artistic: may gain or lose energy); in lobe (1 diffuse, 2 specular, 4 transmission), new direction, shading normal, depth. |
| Your roulette | `rouletteStudio` | Russian roulette at every depth past the minimum | inout survival (clamped 0.001..1; unbiased for any value); in depth, throughput. |
| Your path output | `pathOutputStudio` | once per path, before the firefly clamp and the denoiser | inout radiance (kept >= 0); in depth, surface hits. |
| Your medium | `mediumStudio` | every segment a path flies, in air and in water (also the open tail of an escaping ray) | inout inScatter (added at the segment's start throughput), inout transmittance (multiplied, clamped 0..1); in camera-relative start, direction, length in blocks, inWater. |

Earlier stages: `light/frame_sky/skyStudio` (the sky an eye or a glossy reflection sees), `post_cinema/hdrStudio`
(scene-linear colour before the tone curve) and `post_cinema/postStudio` (display colour after it).

Every hook is free when it is the identity (inlined away). The material and medium hooks change the light transport
itself, so bounced light, reflections and GI follow them; the image is not post-processed.

Ready examples: `examples/mosaic-pathtracer-hooks` (wet block tops, golden sun, valley fog, soft firefly clamp), one
`mosaic hook <stage> --slang <file>` each.

## The loop for agents (and people in a terminal)

Agent access is off until Settings > Agent allows it (or a settings file given with `-Dmosaic.settings`).

```
mosaic hook user.material --slang wet_tops.slang      # body from Slang text -> graph with one Slang block -> slangc check in its module
mosaic hook user.material --reset                     # back to Bedray's own
mosaic pack check pathtrace/path_tracer/materialStudio
mosaic pack apply                                     # rebuild every pass that uses the changed modules for the game
mosaic render graph.json out.png --time 12100 --pitch 5 --yaw 180 --pos 30,71.5,80 --frames 16 [--size 1280x720] [--world preview-world.bin] [--profile]
                                                      # --profile: GPU ms of the preview G-buffer and of every pass (Vulkan timestamps)
```

`mosaic render` runs a Mosaic graph headlessly over the preview world (the game's capture from
`bedray-studio/preview-world.bin`, a file given with `--world`, or the built-in landscape) with Studio's GPU preview: the
same passes, voxel rays instead of hardware rays, the last of `--frames` frames written as a PNG with the time per
frame. It needs no ray tracing hardware. Bedray's own path tracer still needs a GPU with hardware ray tracing and the
game; verify hook changes there with the in-game autotests (`-Dvkbridge.autotest=...`).

A hook body written as Slang text is stored as a graph (`<studio>/functions/<id>.mosaic.json`): parameters -> one
Slang block (`slang.code`, first line `//@ in T a; inout T b; ret T`) -> out ends; Studio opens it as nodes and the block
can be split into nodes there. Slang, not GLSL: `lerp`, `frac`, `float3`, `mul`.
