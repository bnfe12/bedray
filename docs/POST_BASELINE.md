# Plain compute post-shader GLSL reference baseline (L2 offscreen)

Frozen GLSL-reference hashes for the Stage 2.1 Slang migration. Captured BEFORE each port;
never regenerated to hide a delta. Synthetic inputs, production descriptor ABI, shaderc GLSL.

| shader | size | GLSL SHA-256 (output) | device |
|---|---|---|---|
| fg_prepare | 70x50 | `a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39` | NVIDIA GeForce RTX 5060 Ti |
| iris_depth | 70x50 | `64a10360b69521d2d6d3e7438526ffb9e5ce5a9f4f267ed510809fc534af71cc` | NVIDIA GeForce RTX 5060 Ti |
| rt_particles_composite | 70x50 | `6f5fedf2460b7d0da9aeff237d1a7ba0990d0d42d97b1972d67c7a7c282e3bf8` | NVIDIA GeForce RTX 5060 Ti |
| rt_composite | 70x50 | `720cd21b365ed7ea67e897c2394eceb19408d5eca05e99ea6a1d8e6cebd24a73` | NVIDIA GeForce RTX 5060 Ti |

## Machine-readable frozen hashes

```
fg_prepare glsl_sha256=a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39 size=70x50
iris_depth glsl_sha256=64a10360b69521d2d6d3e7438526ffb9e5ce5a9f4f267ed510809fc534af71cc size=70x50
rt_particles_composite glsl_sha256=6f5fedf2460b7d0da9aeff237d1a7ba0990d0d42d97b1972d67c7a7c282e3bf8 size=70x50
rt_composite glsl_sha256=720cd21b365ed7ea67e897c2394eceb19408d5eca05e99ea6a1d8e6cebd24a73 size=70x50
```
