# Settings audit (release/settings-ux)

Why: the settings screen had about 75 controls on three pages, many of them numbers that only a developer understands, and
a right-hand "preview" that was a drawn simulation. This audit decides, for every option that existed on
`release/final` (Sodium pages Upscaling / Ray Tracing / Advanced and Experimental, `RtSettings`, `RtAtmosphereSettings.options()`,
`FrameGenOptions`, the `VulkanRtxConfig` keys), what the user sees.

Decisions:

* **ESSENTIAL** - first page "Bedray", always visible. At most 8 concepts.
* **ADVANCED** - second page "Bedray Advanced", in collapsible groups (collapsed by default).
* **EXPERT** - only with `-Dvkrtx.ui.expert=true` or `ui_expert = true` in `config/bedray.toml`. Numbers and debug tools.
* **HIDDEN-MODE** - belongs to the hidden Ray Tracing (Fast) mode (`release/final-ui`); visible only when that mode is unlocked.
* **FORCED-ON** - no sensible "off": removed from the UI, kept on (`SettingsPolicy.enforce`, run after the config is loaded, a preset is
  loaded or settings are reset; the Expert UI shows the option again and then the stored value is respected).
* **REMOVED** - not shown anywhere (stub or replaced).

FPS column: qualitative (none / low / medium / high). "Needed by the user" is the reason behind the decision.

## Page 1 "Bedray" (ESSENTIAL)

### Compute interoperability update, 2026-10-22

Bedray and Compute remain separate mods. `renderer` is visible on the main Sodium page and the custom
Bedray screen, persists in the host configuration and requests the companion through the renderer API.
The renderer selection, hardware RT mode and read-only `renderer_status` form one renderer concept.
Missing companion state is explicit. No new preview image or gameplay-support claim is introduced.
Expert `compute_scene` and `compute_debug_view` expose diagnostics. Hardware-only controls follow the
pending renderer choice, and voxel mode uses Bilinear with FG off until temporal guides exist.
This update is source-reviewed/compiled separately; the older test evidence does not validate it.

| Option (id) | What it does | Picture / FPS | Needed by the user? | Decision |
|---|---|---|---|---|
| `renderer`, `renderer_status` World Renderer | Hardware/raster or companion voxel diagnostic; status names missing add-on and recorded/failed state | diagnostic scope / cost unmeasured | Yes: the renderer must be selectable without RT hardware | ESSENTIAL, part of the renderer concept |
| `rt_mode` Ray Tracing | Off / Path Tracing (Ray Tracing Fast, AO, Direct only when unlocked) | the whole look / very high | Yes, the main switch | ESSENTIAL |
| `path_bounces` Light Quality | light bounces per path (1-8, default 3) | brighter interiors, colour bleeding / about -10% per bounce | Yes, one slider for "light quality" | ESSENTIAL (was Expert "PT Bounces") |
| `path_auto_exposure` Auto Exposure | exposure follows the scene brightness | brightness / none | Yes: brightness complaints; known to pulse, so it must be switchable. Manual EV is **not available** (needs a renderer change, see "Not solved") | ESSENTIAL |
| `water_style` Water Style | Vanilla / Realistic | water / low | Yes | ESSENTIAL (was a 0..1 slider, now a choice) |
| `atm_godrays` Light Shafts | beams of sun light, Off / On | atmosphere / low-medium | Yes (owner: important) | ESSENTIAL (was Advanced-tier choice Off/Post/Inline; Inline only if stored) |
| `upscaler_backend` Upscaler | Built-in / FSR / DLSS / XeSS | FPS +25-60% / medium | Yes | ESSENTIAL (FSR 1 and NIS only in Expert, or when stored) |
| `dlss_mode`, `xess_mode`, `fsr_mode`, `quality_preset` Upscaler Quality | render size of the selected upscaler; `quality_preset` is "3D Scale" of the built-in one | FPS / sharpness | Yes. One row is shown, the one of the selected backend (rows of the others are hidden) | ESSENTIAL |
| `frame_generation_mode` Frame Generation | Off / 2x / 3x / 4x | smoother motion / presented FPS x2-x4 | Yes (RTX only) | ESSENTIAL |
| presets button, "Reset this page" | user presets, page reset | - | Yes | tools |

## Page 2 "Bedray Advanced" (ADVANCED, collapsible groups)

| Group | Option (id) | What it does | FPS | Decision |
|---|---|---|---|---|
| Path Tracing Details | `pt_preset` Path Tracing Profile | Fast / Quality / Custom set rays, bounces, distance together | high | HIDDEN: `release/final-ui` removed the quality presets from the public UI (alpha defaults 2 rays, 4 bounces, 384 blocks, caustics on, auto exposure off, DLSS-RR); the option is built but not added to the page |
| | `path_spp` Rays per Pixel | paths per pixel | high | ADVANCED (was Expert) |
| | `path_distance` Ray Distance | longest bounce ray | medium | ADVANCED (was Expert) |
| | `path_denoiser` Denoiser | Off / NRD / DLSS Ray Reconstruction | medium | ADVANCED. Not an essential: the fallback to accumulation when a library is missing already happens automatically (logged), so the default NRD needs no choice |
| | `path_caustics` Water Caustics | focused sunlight under water | medium | ADVANCED (off by default) |
| | `dlss_rr_geometric_normals` Sharp Block Edges | normals fed to Ray Reconstruction | none | ADVANCED, row only with Ray Reconstruction selected (was in an "Advanced and Experimental" page) |
| Light & Atmosphere | `atm_godrays_strength` | beam brightness | none | ADVANCED (row hidden while Light Shafts are off) |
| | `atm_bloom`, `atm_bloom_strength` | glow around bright lights | low | ADVANCED (new in the UI; the toggle was only in `RtAtmosphereSettings`) |
| | `atm_sunglare` | halo around the sun | none | ADVANCED |
| | `rt_fog` Fog, `rt_fog_density` | atmospheric fog | low | ADVANCED (was "Volumetric / Distance Fog" in the Experimental page) |
| | `atm_fog_aerial` Distance Haze | far terrain fades | low | ADVANCED |
| Image & Upscaling Details | `auto_normals` Surface Relief | bumps from block textures | low | ADVANCED |
| | `point_sampling_sharpness` Pixel-Sharp Textures | hard pixels up close | none | ADVANCED (needs F3+T) |
| | `custom_scale` Custom 3D Scale | 25-100% for the built-in upscaler | medium | ADVANCED, row only for Built-in + Custom |
| | `fsr_sharpness` FSR Sharpening | RCAS | very low | ADVANCED, row only for FSR |
| Frame Generation Details | `frame_generation_reflex` Reflex, `frame_generation_limit` FPS cap, `frame_generation_status` | latency, cap, honest status line | none | ADVANCED |
| Diagnostics & Reset | `debug_overlay` F3 Bedray Info | Bedray block on F3 | none | ADVANCED |
| | Copy diagnostics report | report for bug reports | none | ADVANCED (from `release/final-ui`) |
| | `reset_all_defaults` Reset ALL | every value, also hidden ones | - | ADVANCED |

Every group ends with "Reset this group"; the right-hand panel has "Reset to default" for the selected option; page 1 has
"Reset this page".

## Hidden or removed

| Option (id) | Decision | Reason |
|---|---|---|
| `rt_preset` RT Quality and `rays_shadows`, `rays_soft_shadows`, `rays_reflections`, `rays_ao`, `rays_gi`, `rays_emissive`, `rays_accumulate` | HIDDEN-MODE | only used by Ray Tracing (Fast), hidden in this release; shown in group "Ray Tracing (Fast, experimental)" when unlocked or Expert |
| `rays_shadow_quality`, `rays_reflection_quality`, `rays_reflection_distance`, `rays_ao_quality`, `rays_ao_radius`, `rays_emissive_quality`, `rays_emissive_distance`, `rays_gi_quality`, `rays_gi_distance` | EXPERT | numbers of the hidden mode |
| `rt_distance_auto` | FORCED-ON (true) | "RT Distance: As in Game" has no reason to be off; the manual distance remains in Expert |
| `rt_distance_chunks` RT Distance (manual) | EXPERT | only meaningful with `rt_distance_auto` off |
| `path_accumulate` PT Accumulation | FORCED-ON (true) | averaging frames is the safety net when the denoiser is off or missing |
| `auto_texture_lod_bias` Auto Texture LOD Bias | FORCED-ON (true) | negative mip bias for upscalers is always right; sharpness complaints (section 18) make "off" harmful |
| `texture_lod_bias_offset` | EXPERT | fine tuning |
| `rt_vram_budget_mb` RT Memory Budget, `rt_light_budget`, `sun_size_tenths`, `auto_normal_strength` | EXPERT | numbers with no visible meaning for players |
| `fsr_version` | EXPERT | Auto is right; the list depends on the AMD DLL |
| `atm_godrays_quality`, `atm_godrays_range`, `atm_bloom_threshold`, `atm_bloom_scatter`, `atm_sunglare_strength`, `atm_sunglare_star`, `atm_fog_vanilla`, `rt_fog_in_scattering` | EXPERT | tuning of effects that have an on/off or strength control already |
| `rt_debug_view` RT Debug Buffer | EXPERT | diagnostics |
| `dlss5_status` DLSS 5 Neural Rendering | REMOVED | not implemented; a status line that cannot be switched on is noise (the code and toml keys stay) |
| `rt_reset_basic`, `rt_reset_advanced`, `rt_reset_expert`, `rt_reset_all` | REMOVED | replaced by option / group / page reset |
| `quality_preset` PT "Custom" naming, `rt_show_advanced`/`rt_show_expert` | internal | UI state only |
| `fsr1_*`, `nis_*` modes | not offered | FSR 1 and NIS appear in the Upscaler list only in Expert (or while stored) |
| `vibrant_visuals`, `dlss_nr_*`, `sun_tilt_deg`, `fog_distance_scale` | not in the UI (unchanged) | no UI before this branch either |

## Counts

Before (`release/final`): 3 pages, about 75 option rows (53 on the Ray Tracing page when both sections were expanded).
After: page 1 shows 8 concepts (9-10 rows because Frame Generation and the Upscaler quality are separate rows, plus two button rows);
page 2 has 5 collapsible groups with about 25 options in total; the Expert numbers appear only with the flag.

## Not solved (needs other work)

* Manual exposure (EV): not offered, there is no manual EV in the renderer (changing it is a shader / render change, not part of this branch).
* "Particles" setting: there is no particle option in Bedray at all.
* `pt_preset` Quality (section 21): the profile values are not changed here.
* Auto exposure instability (section 19): documented in `docs/KNOWN_ISSUES.md`, not fixed here.
