# Visual inspection record

11 November 2026. Historical capture inspection plus L0 source review.

Opened work/runs/431c5e3-fabric-clean/autotest/rt_modes/06_b_path_b3_f400.png from the migration checkout. The interior image has dense fine-grained noise across the floor and walls. Its colored bounce lighting is visible, while the grain obscures material detail. The file belongs to an earlier artifact than the current source.

The current changes correct the GGX numerical peak, LabPBR roughness representation, mapped-normal geometry handling and temporal input paths. Their effect on this scene has yet to be captured. BetterRTX, Minecraft Remix and Radiance remain comparison targets for material detail, water, shadows, indirect light, temporal stability and real frame time. [Rendering reference audit](RTX_REFERENCE_AUDIT.md)

The user requested implementation without tests during this work. No new game, screenshot, benchmark or compiled artifact establishes the current visual result.


The follow-up implements adaptive local normal filtering, unresolved-normal roughness compensation and resolution/FOV-aware water footprints. Independent source review found no remaining static blocker. The new image and performance result remains uncaptured.

The next source pass makes ideal-mirror reflection consistent across sampling, direct-light evaluation and the mixture PDF. At the existing roughness <= 1e-3 threshold, continuation now uses the exact mirror direction and finite-angle NEE no longer evaluates a narrow GGX specular peak. The intended effect is to remove this source of unstable mirror highlights and overlapping celestial contribution; a matching capture is still required to establish the visible result. Smooth dielectric diffuse lighting remains present. No build, test, shader compilation, game run or deployment was performed for this pass.
