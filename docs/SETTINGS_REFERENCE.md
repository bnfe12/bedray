# Settings reference

Open **Options, Video Settings** and look for the **Bedray** section: two pages, **Bedray** (the essentials) and
**Bedray Advanced**. Everything is saved in `config/bedray.toml`. The decision for every option, with the reasons, is in
[SETTINGS_AUDIT.md](SETTINGS_AUDIT.md).

Development alpha.4 adds **World Renderer** and **Renderer Status** to the main Bedray page. Install
the separate matching Bedray Compute JAR to choose **Voxel (Experimental)**; a missing add-on is shown
explicitly. The current voxel mode displays bounded gray opaque cubes with diagnostic sunlight/shadows.
It selects Bilinear and switches off FG; full textures, materials and temporal guides are still pending.
The hardware RT mode preference is retained for switching back. See [COMPUTE](COMPUTE.md).

How the screen works:

* Move the mouse over a setting, or click it: the **right-hand panel** shows real screenshots taken in the game for that setting
  (same camera, same scene), what changes, what it costs, and what it needs. **Slider** mode splits the picture at the mouse (click
  to lock), **Flip** switches between the two frames with a click, **Compare with** picks the other value (Off, Default, Low, High...).
  The small zoomed pictures show the area where the effect is. A setting that cannot be shown in a still (frame generation, latency)
  says so instead of showing a picture. If a screenshot has not been captured yet the panel shows a "PLACEHOLDER" box with the file name.
* The panel never covers the Done / Apply / Undo buttons. On a narrow window or a high GUI scale it is hidden and Sodium's normal
  tooltip is used; if the content is taller than the space, scroll over the panel with the mouse wheel.
* **Reset to default** in the panel resets the selected setting. Every group on the Advanced page ends with **Reset this group**;
  the first page ends with **Reset this page**; **Reset ALL Bedray settings** (Diagnostics group) resets everything.
  Group / page / option resets use Sodium's change mechanism: press **Apply** to keep them, **Undo** to cancel.
* **My presets...** (first page): save the current settings under a name, load or delete them, copy them as text and import text from
  the clipboard. Files: `bedray/presets/<name>.toml` in the game folder. They contain setting values only (no paths); loading a
  preset applies it at once and saves `bedray.toml`.
* Rows that do not apply (for example the DLSS quality row while FSR is selected) are hidden, not greyed.

**FPS cost** is qualitative (none / low / medium / high). The alpha has **no published benchmark table**; measure on your own
system with the F3 screen (Bedray block).

## Page "Bedray"

| Setting | What it does | FPS |
|---|---|---|
| Ray Tracing | Off, or Path Tracing (recommended). Needs `VK_KHR_ray_query`. The experimental Ray Tracing (Fast), AO and Direct modes appear only when unlocked (`experimental_rays = true`). | Path Tracing: very high |
| Light Quality (bounces) | How many times light bounces, 1-8 (default 4). | about -10% per bounce |
| Auto Exposure | Brightness follows the scene. Known issue: it can pulse when the scene changes, see [KNOWN_ISSUES.md](KNOWN_ISSUES.md). | none |
| Water Style | Vanilla (classic) or Realistic. | low |
| Light Shafts | Beams of sun / moon light. | low-medium |
| Upscaler | Built-in, FSR, DLSS, XeSS. A backend whose files are missing is marked "library missing" and has no effect. See [UPSCALERS.md](UPSCALERS.md). | raises FPS |
| Upscaler Quality (DLSS / XeSS / FSR) or 3D Scale (Built-in) | Render size before the upscale. | quality vs FPS |
| Frame Generation | Off, 2x, 3x, 4x (NVIDIA, needs Streamline). See [FRAME_GENERATION.md](FRAME_GENERATION.md). | presented FPS up |

## Page "Bedray Advanced" (groups are collapsed; click a header)

**Path Tracing Details** - Rays per Pixel (the Fast / Quality profile is not offered in the alpha), Ray Distance, Denoiser (Off / NRD /
DLSS Ray Reconstruction; a missing library falls back to accumulation and the log says so), Water Caustics, Sharp Block Edges
(only with Ray Reconstruction).

**Light & Atmosphere** - Light Shaft Strength, Bloom (+ strength), Sun Glare, Fog (+ density), Distance Haze.

**Image & Upscaling Details** - Surface Relief (auto normals), Pixel-Sharp Textures, Custom 3D Scale, FSR Sharpening.

**Frame Generation Details** - Reflex, presented FPS cap, status line.

**Diagnostics & Reset** - F3 Bedray Info, Copy diagnostics report, Reset ALL.

### Expert (only with `-Dvkrtx.ui.expert=true` or `ui_expert = true` in `bedray.toml`)

Adds a group **Expert: Numbers** (RT memory budget, light budget, sun size, auto-normal strength, manual RT distance, LOD bias
offset, effect tuning such as bloom threshold and light shaft range, fog in-scattering, FSR version), the **Ray Tracing (Fast,
experimental)** group, the RT debug buffer view, and shows FSR 1 / NIS in the Upscaler list. Without the flag these options keep their
stored values; four of them are **forced on** (see below).

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
| Y | Toggle voxel sharpness (geometric normals) |
| K | Toggle the debug overlay |

## `config/bedray.toml`

The file is written by the game. Edit it only while the game is closed. Settings the page no longer shows (all `rays_*`, `rt_*`
numbers, `atm_*` tuning) are still read from and written to the file.
