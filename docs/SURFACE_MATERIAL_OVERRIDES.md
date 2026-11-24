# Surface material overrides — resource pack sidecar

Development feature. An author can retune the path-traced surface of any block or item texture with a small JSON file
beside the texture, without authoring LabPBR `_s`/`_n` maps. The file is the same sidecar the
[transparent materials](TRANSPARENT_MATERIALS.md) use, so one file can carry both optics and surface:

`assets/minecraft/textures/block/polished_blackstone_bricks.bedray.json`

```json
{
  "roughness": 0.25,
  "f0": 0.04,
  "metal": false,
  "emission": 0.0
}
```

## Fields

| Field | Meaning / accepted values |
|---|---|
| `roughness` | Linear roughness 0..1. 0 is a mirror, 1 fully matte |
| `f0` | Dielectric reflectance at normal incidence, 0..1 (plastic 0.04, water/ice 0.02) |
| `metal` | Boolean. Metalness 1: the albedo becomes the specular colour and `f0` is ignored by shading |
| `emission` | Emission strength 0..1 applied to the sprite before any engine-side emission gain |
| `emissionMode` | `none` (overrides to no emission), `whole` (uniform `emission`) or `bright` (per-pixel from albedo brightness, torch-flame style) |

Every field is optional; absent fields keep the automatic value from `AutoPbr`. Unknown fields, wrong types or
out-of-range numbers log a rate-limited `[RT-SURFACE]` warning and keep the automatic values for that sprite.

## Precedence and limits

* A LabPBR `_s` map is the stronger statement: when a pack provides it for a sprite, the sidecar surface fields are
  ignored for that sprite. The global pipeline stays LabPBR-compatible.
* The override changes *numbers*, not transport. `whole` emission, `bright` per-pixel emission, cutout and the
  transparency model follow the same engine rules as auto-PBR; there is no new shading path and no new GPU binding.
* The material belongs to the **texture**, like the optical profile: blocks sharing a sprite share its material.
* Applies to the hardware RT / path-tracing material atlas (the compute provider keeps its existing contract).
* Resource reload re-runs the bake, so sidecar changes are picked up on reload like optical sidecars.

## Minecraft-relevant uses

* Modded or vanilla blocks the auto-PBR table does not know: give a mod's block a plausible roughness/metal without
  shipping `_s` maps.
* Whole-emission authoring for glow variants (a clicked redstone lamp texture, a glowing ore overlay) without a LabPBR
  pack.
* Fixing a too-glossy or too-flat vanilla sprite in a "vanilla+" pack that otherwise has no PBR textures.

Nothing here has been validated with an in-game acceptance run in the current build; verification level is
compile + contract tests (`SurfaceOverridesTest`).
