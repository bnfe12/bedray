# Compute baseline (V0)

Generated 2026-11-12 by `:compute-common:computeBaseline` at commit `78f529b6` (dirty working tree).

Evidence level: **L2 offscreen** on a real Vulkan 1.0 device with zero enabled extensions. This is not in-game (L4) evidence. The probe harness waits for the queue after every frame, so `harness_*` wall times are an upper bound; in-game frames never wait. GPU region times come from timestamp queries harvested four frames late without steady-state waits. In-game capture/submit costs are reported only by the host's `[BEDRAY-COMPUTE] frame_timing` log (at most once per second).

- Device: NVIDIA GeForce RTX 5060 Ti, driverVersion raw `-1690075136`, device Vulkan API 1.4.357
- GPU timestamp queries: available, period 1.000 ns
- Resolution 1280x720, 30 warmup frames discarded, 120 measured frames per scene; fixed camera, fixed lighting, no upscaler/frame generation offscreen. GPU history counters enabled (`-Dbedray.compute.gpuCounters=true`).
- Steady state uploads nothing: all 27 sections are resident after the first frame; residency cost is reported separately.

## Steady-state GPU timings (120 frames)

| scene | gpu_upload_ms | dispatch_ms | blit_ms | gpu_total_ms | p50_ms | p95_ms | p99_ms | samples |
|---|---|---|---|---|---|---|---|---|
| field | 0.002 | 4.956 | 0.130 | 5.088 | 5.054 | 5.315 | 5.487 | 120 |
| darkroom | 0.002 | 5.431 | 0.066 | 5.500 | 5.479 | 5.674 | 5.863 | 120 |
| cave | 0.002 | 3.767 | 0.051 | 3.820 | 3.807 | 3.811 | 4.275 | 120 |

Percentiles are nearest-rank over per-frame GPU totals (upload+dispatch+blit).

## CPU preparation, harness wall time and first-frame residency

| scene | upload_prep_ms | record_ms | harness_p50_ms | harness_p95_ms | harness_p99_ms | first_upload_ms | sections | cubes | models | quads | bytes | emitters | history |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| field | 0.014 | 0.154 | 16.903 | 17.983 | 18.820 | 4.903 | 27 | 2246 | 118 | 189 | 1626032 | 0/0 | steady, window 64/64 frames |
| darkroom | 0.013 | 0.113 | 17.436 | 19.288 | 21.445 | 0.611 | 27 | 3587 | 0 | 189 | 1626032 | 14/14 | steady, window 64/64 frames |
| cave | 0.001 | 0.080 | 15.574 | 16.369 | 17.859 | 0.313 | 27 | 107913 | 0 | 189 | 1626032 | 26/26 | steady, window 64/64 frames |

`upload_prep_ms`/`record_ms` are per-frame CPU means over the measured window; `first_upload_ms` is the one-time CPU cost of preparing all 27 sections. `bytes` counts first-frame upload payload (tiles, tints, palettes, model headers/quads, lighting). `history` is the temporal accumulator state at window end.

## GPU history counters (debug flag, accumulated over the measured window)

| scene | accepted | distance_reject | normal_reject | empty | bounds | invalid_reprojection | nan_radiance | low_weight |
|---|---|---|---|---|---|---|---|---|
| field | 36542760 | 33720 | 54120 | 203040 | 262320 | 0 | 0 | 0 |
| darkroom | 110591400 | 44040 | 814680 | 1800 | 772800 | 0 | 0 | 0 |
| cave | 110592000 | 314760 | 6664320 | 0 | 796800 | 0 | 0 | 0 |

## Provider-owned GPU memory (actual VkMemoryRequirements sizes, bytes)

| scene | grid | palette | models | frame | history | output | counters | total |
|---|---|---|---|---|---|---|---|---|
| field | 884752 | 663552 | 21288960 | 4304 | 31457280 | 3932160 | 128 | 58231136 |
| darkroom | 884752 | 663552 | 21288960 | 4304 | 31457280 | 3932160 | 128 | 58231136 |
| cave | 884752 | 663552 | 21288960 | 4304 | 31457280 | 3932160 | 128 | 58231136 |

## Images

| scene | oracle hits | SHA-256 (final frame) | PNG |
|---|---|---|---|
| field | 343802/921600 | `d6a7faaf3dcbb64c29ef38d2d1027920749bdb99e3ee44ea9ac197a2dcbeb904` | `build\reports\compute\baseline-field.png` |
| darkroom | 921600/921600 | `8fe599fd63fd2776d50c0ffce881b8d1be9f162f4a39b2079c2d0d6884e042c9` | `build\reports\compute\baseline-darkroom.png` |
| cave | 921600/921600 | `2550b9a3c8293dd7a085f52211ab6b750c17675505bdefbe586c6d9bef03d701` | `build\reports\compute\baseline-cave.png` |

## Scenes

- **field**: Open field, biome border at x=24, grass/leaves/water, day key light
- **darkroom**: Enclosed room, glowstone + 4 torches + lava corner, no sky light
- **cave**: Cave at world Y=-48 (deeper than 48 blocks), carved cavern, lava pool

All scenes fill the resident 48x48x48 window (27 sections), use one fixed camera and one lighting frame, and synthesize reverse-Z raster depth from the CPU DDA oracle exactly like the in-game composition path.

## Reproduce

```
./gradlew :compute-common:computeBaseline
```

In-game follow-up (L4, owner-verified): enable the live scene and read `[BEDRAY-COMPUTE] frame_timing` for capture_ms/submit_ms plus the same provider summary; compare against this table before any shader change (baseline/A-B rule).
