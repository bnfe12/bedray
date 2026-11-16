# Settings reference

## Transparent materials and cost controls (source development)

`rays_direct_translucent_shadows` and `rays_indirect_translucent_shadows` independently control colored transparent
visibility in direct and secondary shading (both default on). `rays_cull_shared_glass` enables the conservative extra
section-local full-block face filter (default on; reload the world after a change). `rays_transparency_stats` enables
delayed GPU counters in F3 (default off; atomics add overhead). These apply to hardware RT/Path Tracing.
Per-texture IOR, thickness and absorption parameters belong in resource-pack sidecars. See
[TRANSPARENT_MATERIALS](TRANSPARENT_MATERIALS.md). No validation run or FPS measurement was made for these source changes.
`rays_optical_materials` (default on) is the master switch for the authored shader optics, with a legacy mode for comparison.

Select **Bedray Settings** in the upper-left corner of the title screen or the in-game pause menu. It is also available under **Options → Video Settings → Bedray Settings** in Sodium. The menu groups settings for image and FPS, lighting, the world, effects, presets and system tools. Categories appear in a sidebar on wider screens and in two compact rows at larger GUI scales. **Apply** saves staged settings in `config/bedray.toml`.

Development alpha.7 includes **World Renderer** and **Renderer Status** on the main Bedray page. Install
the separate matching Bedray Compute JAR to choose **Voxel (Experimental)**; a missing add-on is shown
explicitly. Voxel traces accepted textured block models and cutouts with world sky/block light and shadows
inside a 48-block window. Shaded composition preserves distant, unsupported and translucent raster surfaces.
It selects Bilinear and switches off FG; physical GI/reflections and matching temporal guides remain pending.
The hardware RT mode preference is retained for switching back. See [COMPUTE](COMPUTE.md).

### Find, review and save settings

- Search across tabs by name or setting ID. Some dependent controls, such as **Bloom Shape**, appear in search while their effect is off. Hover over the value to see which effect enables the control.
- **Review (n)** beside the search box shows all pending settings, including staged values hidden by the current renderer or effect. Select a visible value to jump to its tab. An imported preset can include additional values without an individual control.
- **Apply** saves the draft, and **Revert** reloads the applied settings. Closing with pending changes asks whether to discard them or keep editing.
- **System → Menu Transparency** controls the settings menu and previews the change while you drag. In a world, it also changes the dimming over the scene. The preference is stored in `config/bedray.toml`.
- Select a setting and open **Details** for its description, available screenshots and its individual reset. To stage a broader reset, use **System → Reset editable settings**.
- Use **Presets** to save named settings, copy the current draft or import a preset from the clipboard. Imported values are staged until you select **Apply**. Named preset files live in `bedray/presets/<name>.toml`.

**FPS cost** is qualitative (none / low / medium / high). The alpha has **no published benchmark table**; measure on your own
system with the F3 screen (Bedray block).

## Core settings

| Setting | What it does | FPS |
|---|---|---|
| Ray Tracing | Off, or Path Tracing (recommended). Needs `VK_KHR_ray_query`. The experimental Ray Tracing (Fast), AO and Direct modes appear only when unlocked (`experimental_rays = true`). | Path Tracing: very high |
| Light Quality (bounces) | How many times light bounces, 1-8 (default 3). | about -10% per bounce |
| Auto Exposure | Brightness follows the scene. Known issue: it can pulse when the scene changes, see [KNOWN_ISSUES.md](KNOWN_ISSUES.md). | none |
| Water Style | Vanilla (classic) or Realistic. | low |
| Light Shafts | Beams of sun / moon light. | low-medium |
| Upscaler | Built-in, FSR 1, NIS, FSR, DLSS, XeSS. A backend whose files are missing is marked "library missing" and has no effect. See [UPSCALERS.md](UPSCALERS.md). | raises FPS |
| Upscaler Quality (DLSS / XeSS / FSR / FSR 1) or 3D Scale (Built-in) | Render size before the upscale. Custom shows a 25-100% slider stored for each backend (NIS: 50-100%). | quality vs FPS |
| Frame Generation | Off, 2x, 3x, 4x (NVIDIA, needs Streamline). See [FRAME_GENERATION.md](FRAME_GENERATION.md). | presented FPS up |

## Detailed settings

**Path Tracing Details** - Rays per Pixel (the Fast / Quality profile is not offered in the alpha), Ray Distance, Denoiser (Off / NRD /
DLSS Ray Reconstruction; a missing library falls back to accumulation and the log says so), Water Caustics, Sharp Block Edges
(only with Ray Reconstruction).

**Light & Atmosphere** - Light Shaft Strength, Bloom (+ strength), Sun Glare, Fog (+ density), Distance Haze.

**Image & Upscaling Details** - Surface Relief (auto normals), Pixel-Sharp Textures, Custom 3D Scale, FSR Sharpening.

**Frame Generation Details** - Reflex, presented FPS cap, status line.

**Diagnostics & Reset** - F3 Bedray Info, Copy diagnostics report, Reset ALL.

### Expert (only with `-Dvkrtx.ui.expert=true` or `ui_expert = true` in `bedray.toml`)

Adds a group **Expert: Numbers** (light budget, sun size, auto-normal strength, manual RT distance, LOD bias
offset, effect tuning such as bloom threshold, shape and light shaft range, fog in-scattering, FSR version), the **Ray Tracing (Fast,
experimental)** group, the RT debug buffer view, and shows FSR 1 / NIS in the Upscaler list. Without the flag these options keep their
stored values; three of them are **forced on** (see below).

### Forced on (no switch)

`path_accumulate` (frame accumulation), `rt_distance_auto` (ray tracing covers the game render distance), `auto_texture_lod_bias`
(sharper textures with upscalers). They are set to `true` when the config is loaded, a preset is loaded or settings are reset, unless
the Expert UI is on.

## Developer flags (unchanged)

`-Dvkrtx.*` JVM arguments and the autotest scenarios (`-Dvkbridge.autotest=...`) keep working; the settings screen does not read them
except `-Dvkrtx.ui.expert=true`. Autotests set the runtime fields directly, so hidden or forced options can still be driven. The
screenshots of the right-hand panel come from `-Dvkbridge.autotest=settings_preview` (see [TESTING.md](TESTING.md)).

## Hotkeys (Controls, Key Binds, "Bedray")

| Key (default) | Action |
|---|---|
| O | Toggle ray tracing on / off |
| I | Cycle the tracing mode |
| U | Cycle the denoiser |
| K | Toggle the debug overlay |

## `config/bedray.toml`

The file is written by the game. Edit it only while the game is closed. Settings the page no longer shows (all `rays_*`, `rt_*`
numbers, `atm_*` tuning) are still read from and written to the file.

Under **Effects**, **Bloom Shape** sets round, horizontal or vertical glow.
**Sun Halo Size** adjusts the halo from 50% to 200% independently of the sun disc.
Both controls are saved with the rest of the visual settings and can be included in presets.
