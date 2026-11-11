# Visual inspection record

11 November 2026. Historical capture inspection plus L0 source review.

Opened work/runs/431c5e3-fabric-clean/autotest/rt_modes/06_b_path_b3_f400.png from the migration checkout. The interior image has dense fine-grained noise across the floor and walls. Its colored bounce lighting is visible, while the grain obscures material detail. The file belongs to an earlier artifact than the current source.

The current changes correct the GGX numerical peak, LabPBR roughness representation, mapped-normal geometry handling and temporal input paths. Their effect on this scene has yet to be captured. BetterRTX, Minecraft Remix and Radiance remain comparison targets for material detail, water, shadows, indirect light, temporal stability and real frame time. [Rendering reference audit](RTX_REFERENCE_AUDIT.md)

The user requested implementation without tests during this work. No new game, screenshot, benchmark or compiled artifact establishes the current visual result.


The follow-up implements adaptive local normal filtering, unresolved-normal roughness compensation and resolution/FOV-aware water footprints. Independent source review found no remaining static blocker. The new image and performance result remains uncaptured.

The next source pass makes ideal-mirror reflection consistent across sampling, direct-light evaluation and the mixture PDF. At the existing roughness <= 1e-3 threshold, continuation now uses the exact mirror direction and finite-angle NEE no longer evaluates a narrow GGX specular peak. The intended effect is to remove this source of unstable mirror highlights and overlapping celestial contribution; a matching capture is still required to establish the visible result. Smooth dielectric diffuse lighting remains present. No build, test, shader compilation, game run or deployment was performed for this pass.

## Owner-supplied artifacts and stationary-only report

The nine November 11 captures were inspected directly as attached images:

| Capture filename / group | Visible issue |
|---|---|
| `024441` | Fence/glowstone room: angular dark projections with sharp spikes. |
| `024522`, `024646` | Floor/wall shadow silhouettes with abrupt segments; the stills do not identify the caster or establish temporal behavior. |
| `024021`, `024048` | Corridor: different illumination levels in two views. Camera position and capture state differ, so this is not a controlled A/B. |
| `024129`, `024142` | Entrance: large illumination difference around the doorway/floor across two views. |
| `024330` | Bright, dirty-looking band and colored speckle near a shadow boundary and colored emitters. |
| `025230` | Multiplayer cliff/lava scene: strong warm/green lighting; the still alone does not establish a transport defect. |

The historical NeoForge instance log near these captures reports active DLSS RR and 1 SPP. Its authoritative `bedray.toml` currently has `path_auto_exposure = false`, `path_denoiser = "DLSS_RR"` and pixel shadows disabled. These current settings are not a time-stamped proof of every setting at each capture. The installed historical JAR was inspected read-only: SHA-256 `9f7d38af23af6993e99546bde9222629fb0298c2f75b3ad45c8f58e56034b944`, modified November 11 at 01:45:58. It is not an artifact built from this source pass.

Two further attached images extend the report:

- `codex-clipboard-2fb96832-c0fb-4769-a4c6-920c6e030915.png`: metal blocks, white wall, pool and mangroves. The owner explicitly reports periodic edge/light/shadow wobble and brief gray flashes in dark mangrove shadows **only while stationary**; movement removes the symptom. Temporal behavior comes from that report, not from a measured image sequence.
- `codex-clipboard-ef3256b8-3500-4d88-8add-17c5bc1e2df1.png`: NeoForge multiplayer scene with many torches and a portal; sharp yellow/green and purple patches appear across the floor, with strong local light. Exact light/material/reservoir state and artifact provenance are not established by the still.

The source corrections now cover RR de-jitter X sign, NRD producer jitter/history, the foliage receiver's fixed sun stratum, finite-emitter visibility endpoints, primary indoor key-light roulette and DI temporal geometric plane membership. See the [contract audit and explicit limits](RT_SHADER_DATAFLOW_AUDIT.md#stationary-lighting-and-shadow-contracts). A prior ideal-mirror correction is also present. None of these source changes is a before/after image comparison, and the colored floor patches and wall artifacts remain open visual acceptance items.

This pass is **L0 / source reviewed**. No builds, tests, shader compilation, game runs, benchmark or deployment were performed. One existing RR wiring assertion was updated to the corrected X-sign contract; it was not executed. The primary indoor roulette change has a documented additional visibility-ray cost, with no measured FPS result. Required later evidence is an exact-artifact stationary sequence in these scenes, followed by slow motion, disocclusion and moving entities; compare raw lighting and reconstructed output separately and record frame times. RR, NRD and supported loaders/integrations require their own matching evidence.
