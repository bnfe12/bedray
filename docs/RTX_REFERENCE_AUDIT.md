# Lighting and performance reference audit — 2026-11-03

## Reproduction contract

Owner report: DLSS Ray Reconstruction; roughly 30 FPS on a sterile test map,
15 FPS during play at QHD output with DLSS Balanced selected, and 12 FPS near
glass. Whole-scene flicker, foliage instability and overexposure with many
emissive blocks are separate symptoms. This is not a native-1080p benchmark.
The acceptance target remains native 1920×1080 at 60 real FPS on RTX 5060 Ti
8 GB / Ryzen 5 5600XT / 16 GB RAM. A CPU test cannot establish that result.

## Official material inspected

Downloaded outside the Bedray repository, without installing or executing it:

| Reference | Exact revision / package | Evidence inspected |
| --- | --- | --- |
| [NVIDIA Minecraft RTX Remix](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft) | `7597c2a1119a00b5d7c313987d799e46cc3257b9` | `packaging/rtx.conf`, block-outline pass, Java integration |
| [NVIDIA Remix runtime](https://github.com/NVIDIAGameWorks/dxvk-remix) | `bd5f3c8001bdfbafcef44ec58b4cb7015d80d21d` | NEE/cache options, shader architecture |
| [BetterRTX Default](https://bedrock.graphics/presets/default/) | 1.4.4, materials for `v26.40.26` | Official preset page and three material binaries; source URLs, sizes and SHA-256 recorded in `work/rtx-reference-betterrtx/reference-record.json` outside the repository |

BetterRTX's downloaded materials are compiled binaries, not published complete
shader source. Their internal implementation and performance have not been
established by this audit. No reference binaries or copied renderer are shipped
in Bedray. No local RTX visual comparison has been run on this Intel-only host.

## Findings and decisions

* **Exposure:** Bedray used 1.1 as both its fixed exposure and automatic lower
  bound. A measured bright room could never be darkened. Separate those
  concepts, permit 1/64 auto exposure, and retain bounded, time-based adaptation.
  This addresses one proven clipping cause; it does not repair light sampling.
* **Selection:** Remix treats the selected-block outline as world UI with
  depth testing and view offset. Bedray's former black triangle ribbons went
  through the scene BLAS and denoiser. Replay the game's prepared shape-outline
  draw after RT/RR instead. Minecraft remains owner of its buffers, pipeline,
  line width and shape. The current replay precedes the ordinary upscale stage;
  output-resolution overlay composition remains part of the combined-RR work.
* **Temporal input:** RR needs the same camera validation as SR. Skip an invalid
  camera before RR recording, reset on recovery, and promote camera history
  only after successful evaluation/composition. A failed velocity pass must not
  manufacture zero motion for a moving scene. Log skip and recovery boundaries.
* **Reflection data:** Bedray passed no specular motion or hit-distance guide.
  The [official RR integration guide](https://github.com/NVIDIA-RTX/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md)
  describes the primary reflection-to-hit distance/matrices path. Camera depth
  is not a substitute. The existing pack now writes an R32F guide from actual
  specular continuation geometry, without another ray or dispatch. Zero means
  no observed reflection, 65504 an escaped ray. A primary ray starting inside a
  medium does not arm this opaque-surface guide. Later medium/RC changes do not
  discard an already measured edge. At multiple SPP the minimum nonzero edge is used. This
  stochastic guide does not add an unsampled deterministic reflection ray.
* **One temporal reconstruction:** the same guide says RR replaces SR. The new
  output route evaluates RR directly to window extent when FG is OFF, with
  separate input/presentation resources and full-size raster UI/depth. Bootstrap,
  FG-enabled and other-backend routes remain separate. See
  [implementation and acceptance limits](ai-agent-addenda/RR_OUTPUT_RECONSTRUCTION.md).
* **Transparent traversal:** glass/filter/cutout chains restart closest-hit
  queries. Opaque and non-opaque geometry should stay separated; investigate
  bounded candidate gathering rather than repeated full traversal. Preserve
  ordering, exclusions and overflow semantics. Shader compilation proves no
  speedup; measure glass and foliage scenes separately.
* **Many lights:** Remix uses NEE cache importance sampling/MIS and stable
  identity handling. A camera-global list plus a few proposals can boil when
  lights become numerous or reorder. Receiver-local proposals and exact PDF /
  history identity deserve priority over another denoiser or arbitrary clamps.
* **Do not enable every optional feature:** Minecraft Remix explicitly disables
  opacity micromaps for its high-volume terrain, disables atmospheric volume
  lighting because of fireflies, and enables sparse rendering at quality 0.3.
  Its inactive-pixel reuse is an explicit stability/cost tradeoff. These settings
  are evidence of workload-specific tuning, not portable constants or a native
  full-workload FPS comparison. Bedray must not silently reduce resolution.
* **Do not copy radiometric constants:** Remix has its own scene scale and light
  units. Exposure, bloom threshold and emitter gains must be calibrated against
  Bedray's actual linear HDR signal. Different tone mapping alone cannot fix an
  incorrect estimator or disocclusion.

## Verification boundaries

CPU regression tests cover camera recovery, history promotion and exposure
response. AOT reflection/compilation covers shader contracts. Native mock-driver
CI covers recording/lifetime contracts. Remaining visual acceptance needs actual
RTX runs: camera motion, edited chunks, many-light interiors, glass, water,
foliage, selection occlusion and a native 1080p capture with all slow frames kept.
Keep requested and actually active reconstruction settings in the capture.

## Capture the reported workload

Settings → System & Diagnostics → **Record 30 s gameplay profile**. Apply or
revert pending settings first. This returns to the world, warms for 5 seconds
and records 30 seconds with the current resolution, quality, reconstruction and
frame-generation settings. It never forces native 1080p or changes those values.
The destination is `bedray/profiles/capture-<uuid>` inside that game instance;
completion/failure is logged and shown in a toast. A 90-second timeout prevents
an indefinite wait if the world/RT disappears. Raw recording remains bounded.

Analyze with `python tools/automation/run_bench.py <capture-directory> --profile`.
This reports actual application-frame intervals, tails and CPU/GPU times and
rejects missing GPU samples or a changed mode/resolution during the capture.
Generated frames are not counted as application FPS. This command makes no
native-1080p acceptance decision. The existing command without `--profile`
retains the stricter native contract and its 10-second warmup requirement.


## Source follow-up — 11 November 2026

The historical 431c5e3 Fabric interior capture at work/runs/431c5e3-fabric-clean/autotest/rt_modes/06_b_path_b3_f400.png was opened for inspection. It contains pervasive fine-grained noise in indirect lighting and strongly saturated colored walls. That image predates the NRD direct-light correction and the changes below. A source change needs a new matching capture before its visual effect can be judged.

Three contained shading corrections are implemented in the current source.

- GGX evaluates its denominator as a sum of nonnegative terms, with precise arithmetic to retain the narrow smooth-surface peak in FP32.
- Block LabPBR atlas roughness is converted to the perceptual representation consumed by the BSDF and reconstruction guides. Material source flags scope the conversion. Water identification retains its encoded-atlas predicate. [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)
- Mapped normals stay in the viewed geometric hemisphere. Actual non-transmission continuations below that face are rejected after the RC integral replacement. Front-surface direct lighting checks the geometric face, while foliage backlighting retains its dedicated path.

These shader corrections add no rays or textures. Shared SR object motion adds a separate 16-byte record per render pixel, and the two optional temporal masks add two bytes per render pixel plus one compute dispatch. These costs need GPU measurements with the matching artifact.

The comparison targets include the BetterRTX preset's water, night lighting and reflections, Minecraft Remix material integration, and Radiance's native Vulkan renderer. Current evidence establishes their published scope. The relative quality and performance of Bedray remain open until the same scenes, hardware, output resolution, reconstruction quality and frame timing are compared. [BetterRTX preset](https://bedrock.graphics/presets/betterrtx/) · [Minecraft Remix materials](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/blob/main/docs/RESOURCE_PACKS.md) · [Radiance](https://github.com/Minecraft-Radiance/Radiance)
