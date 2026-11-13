# NeoForge tester build

Target: Minecraft 26.3, NeoForge, Windows x64, Java 25 and Sodium 0.9.x for the same game version.
Use the single `-all-natives.jar` in the mods folder, with no second Bedray or standalone Bedray Compute JAR.
Vendor DLLs and their licence notices are extracted from the JAR at startup. Existing user-supplied DLLs are preserved.

## Measure and report

1. Enter a world, choose your image settings and let chunks load.
2. Run `/bedray test`, or use **System → Record 30 s gameplay profile** in Bedray settings.
3. Keep playing with the window focused: 5 s warmup, 30 s collection and up to 5 s for GPU results.
4. A tester ZIP is saved to `bedray/reports/`. It contains diagnostics, a scrubbed bounded log tail, raw frames and FPS statistics.
5. Run `/bedray share`, or use **System → Prepare GitHub test report**. Review the prefilled GPU issue form, attach the ZIP
   from the opened folder, add screenshots and submit through your own GitHub account.

The mod does not have a GitHub token and does not post automatically. `/bedray report` remains a local diagnostics/clipboard action.
Average FPS and 1% low count application frames. GPU timestamps are correlated with their originating application frames;
missing results remain missing. Incomplete or interrupted captures are labelled explicitly.
Capture does not change resolution, quality, upscaler or frame generation. Menus and unfocused frames are excluded from the summary.

## Included runtimes and limits

The package supplies the matching Bedray renderer and wrappers, NRD 4.18, DLSS SR/RR/FG from Streamline 2.14.16,
the official signed experimental Neural Rendering model, XeSS 3.0.2, the legacy AMD SDK 1.1.4 Vulkan runtime,
and the SDK 2.4 Vulkan loader/upscaler pair. Built-in FSR 1 and NIS are shaders.
The AMD SDK 2.4 provider query determines which FSR 2/3.1/4 implementations the current GPU can actually use.
DLSS model presets are choices within the installed runtime, not separate obsolete DLLs loaded concurrently.

Experimental **DLSS 5 Neural Rendering** needs `-Dbedray.dlss5=true` at startup, the direct NGX raster SR/DLAA path,
compatible NVIDIA hardware/driver and no Ray Reconstruction or frame generation. Packaging its model does not establish
successful NGX evaluation or image quality. Read [the DLSS 5 guide](experiments/dlss5.md).
NVIDIA NRC (Neural Radiance Cache) is not integrated; it is different from Neural Rendering.
No claim is made for unavailable hardware features, all historical SDK versions, or unimplemented placeholder libraries.

## Integration provenance

This build starts with current main through `09720a1e`, includes shader-host fixes from PR #106 and FSR 4 Vulkan work from
PR #105 through `c01a51c7`. It also adapts the material-family and skyless-dimension lighting work from `af1e732c`.
Legacy emitter normalization and primary-coverage edits in that branch refer to contracts removed by the newer renderer;
they were not restored. Current material, emission, glass and lighting tests cover the retained contracts.
The older Slang-compute migration branch targets deleted owners; current main already contains the replacement AOT Slang passes.
Uncommitted alternative lighting snapshots and an unrelated deleted-worktree snapshot are not treated as player-ready changes.

Build metadata in the JAR and its native provenance manifest identify the exact final revisions and per-DLL SHA-256 values.
Sources and test evidence are supplied with the tester package. Vendor runtime components remain under their accompanying terms;
they are not relicensed under Bedray's LGPL licence. This is a private tester package, not a public release.
