# Visual inspection record

11 November 2026. Historical capture inspection plus L0 source review.

Opened work/runs/431c5e3-fabric-clean/autotest/rt_modes/06_b_path_b3_f400.png from the migration checkout. The interior image has dense fine-grained noise across the floor and walls. Its colored bounce lighting is visible, while the grain obscures material detail. The file belongs to an earlier artifact than the current source.

The current changes correct the GGX numerical peak, LabPBR roughness representation, mapped-normal geometry handling and temporal input paths. Their effect on this scene has yet to be captured. BetterRTX, Minecraft Remix and Radiance remain comparison targets for material detail, water, shadows, indirect light, temporal stability and real frame time. [Rendering reference audit](RTX_REFERENCE_AUDIT.md)

The user requested implementation without tests during this work. No new game, screenshot, benchmark or compiled artifact establishes the current visual result.


The follow-up implements adaptive local normal filtering, unresolved-normal roughness compensation and resolution/FOV-aware water footprints. Independent source review found no remaining static blocker. The new image and performance result remains uncaptured.
