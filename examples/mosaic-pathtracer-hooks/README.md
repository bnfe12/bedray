# Path tracer hooks: examples

Slang bodies for the Mosaic Studio stages of Bedray's path tracer (see docs/MOSAIC_RENDER_HOOKS.md). Install one with
`mosaic hook <stage> --slang <file>` (it becomes the stage's graph in Studio and is checked inside its module), then
`mosaic pack apply` for the game; `mosaic hook <stage> --reset` removes it.

| File | Stage | What it does |
|---|---|---|
| wet_tops.material.slang | user.material | wetter, polished block tops (rain look) |
| golden_sun.light.slang | user.light | warmer sun, bluer moon in all bounced light |
| valley_fog.medium.slang | user.medium | ground fog inside the light transport |
| firefly_clamp.output.slang | user.pathOutput | soft compression of very bright paths |
