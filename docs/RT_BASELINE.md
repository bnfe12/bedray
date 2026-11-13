# RT baseline (Stage 2.0, GLSL reference)

Generated 2026-11-12 by `:common:rtBaseline`. Evidence level: **L2 offscreen** on a real Vulkan 1.2 ray-query device. Not in-game (L4). The harness waits for the queue every frame, so `harness_p50_ms` is an upper bound; `gpu_dispatch_*` come from timestamp queries around the dispatch. This is the GLSL reference captured BEFORE any Slang port; the port must reproduce these hashes (A/B).

- Device: NVIDIA GeForce RTX 5060 Ti, driverVersion raw `-1690075136`, device Vulkan API 1.4.357
- GPU timestamp queries: available, period 1.000 ns
- Resolution 320x240, 30 warmup frames discarded, 120 measured frames per scene; fixed camera, fixed lighting.

## GPU dispatch timings (120 frames)

| scene | gpu_p50_ms | gpu_p95_ms | gpu_p99_ms | harness_p50_ms | hits |
|---|---|---|---|---|---|
| rt_test | 0.005 | 0.005 | 0.005 | 1.768 | 40401/76800 |
| rt_rays | 0.190 | 0.209 | 2.496 | 2.397 | 28518/76800 |
| rt_path | 0.207 | 0.231 | 2.553 | 2.503 | 26963/76800 |

## Images

| scene | SHA-256 (final frame) | PNG |
|---|---|---|
| rt_test | `2da2605c6d5537722bcda95273e9e24a60ce32b1dfd5bdc0e5bbd03eddbc8930` | `build\reports\rt\baseline-rt_test.png` |
| rt_rays | `1e92574e51e677c7f8037ff07e53dfe0a573f4b4b797c8109e89e309991d868e` | `build\reports\rt\baseline-rt_rays.png` |
| rt_path | `cf7c8196d5c2f3e6d812d1e98c47adb362c2bd78e4e34cfb46d23d7f415caf0c` | `build\reports\rt\baseline-rt_path.png` |

## Scenes

- **rt_test**: one hardware triangle (SimpleAccelerationStructureBuilder), minimal ray-query shader.
- **rt_rays**: one synthetic section (floor + back wall, solid pass), MODE_LIT, no optional flags.
- **rt_path**: same scene, accumulation variant (VKB_ACCUM), accumulating over the measured window.

## Reproduce

```
./gradlew :common:rtBaseline
```

A/B gate (Stage 2.1/2.2): the Slang port must reproduce these hashes (or a documented, justified delta), GPU totals within measurement noise. Do not regenerate this baseline to hide a delta.
