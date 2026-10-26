# Transparent materials and cost controls

Source development on 2026-10-26. These changes have not been built, compiled to SPIR-V, tested or exercised on a GPU.
There is no measured FPS improvement. The controls and optical palette described here serve the hardware RT/Path Tracing
renderer; the separate Compute provider retains its existing material/transport contract.

## Material files

A resource pack may put an optical description beside a block texture, for example
`assets/minecraft/textures/block/blue_stained_glass.bedray.json`:

```json
{
  "kind": "glass",
  "ior": 1.52,
  "thickness": 0.0625,
  "measurementDistance": 1.0,
  "transmittance": [0.97, 0.99, 1.0],
  "diffuseLayer": 0.0,
  "cullSharedFaces": false
}
```

| Field | Meaning / accepted values |
|---|---|
| `kind` | `glass`, `water`, `ice`, `thin`; inferred for known vanilla texture names if absent |
| `ior` | Refractive index, finite 1..3. Controls Fresnel and the internal optical path of the authored glass slab; realistic water also uses it for Snell refraction |
| `thickness` | Authored slab thickness in blocks, 0.0001..16; it is not measured from mesh entry/exit |
| `measurementDistance` | Distance in blocks at which the reference RGB transmittance is specified, 0.0001..1024 |
| `transmittance` | Three finite linear RGB channels, each 0..1; 1 transmits that channel, 0 is strongly absorbing |
| `diffuseLayer` | Diffuse coating share, 0..1, weighted by texture alpha; the remaining energy transmits. Water requires 0 |
| `cullSharedFaces` | Permit the conservative extra full-block glass boundary filter; default true for glass |

Unknown fields, invalid types or out-of-range numeric values log a warning and use automatic defaults. Known glass textures
default to IOR 1.52, thickness/measurement distance 1 block and reference transmittance 0.97 per channel. Clear vanilla glass
also has a diffuse coating for its alpha-defined frame; alpha-zero centres are dielectric interfaces. Known vanilla water and
ice textures default to IOR 1.333 and 1.31 respectively. Other sprites retain legacy optics unless a sidecar provides them.

The atlas uses a bounded palette of **31 distinct immutable profiles plus the legacy profile**. Equal property sets share
one record. Four nonlegacy slots are reserved for the automatic glass/clear-glass/water/ice defaults; custom profiles use
the remaining capacity. Five high metadata bits of the existing material atlas select the record; the original three source flags remain
unchanged. The GPU table is 1536 bytes and adds no sampled image or new descriptor binding. New slots are published before
their material rects and are never overwritten while referenced. Palette capacity is shared across resource reloads until
atlas recreation; overflow logs a warning and leaves that sprite on legacy optics. Restarting recreates the palette.

The material belongs to a **texture**, so blocks and panes sharing that texture share its authored thickness. A pack needs a
separate sprite to give those shapes different profiles. Resource reload picks up sidecar changes progressively. Invalid
sidecars and palette overflow do not claim successful custom optics. Warnings are rate limited.

## Transport scope

Glass remains an **authored slab approximation**: Fresnel uses its IOR, absorption uses the refracted internal slab angle,
thickness and reference transmittance, and a diffuse coating uses bounded ambient/block illumination. The camera ray continues
straight outside this slab model; there is no general closed-volume glass tracer, prism bending, nested dielectric stack or
glass caustic solver. Reference absorption is applied on the entry face; the exit retains Fresnel/coating loss. Shadow rays use
the same authored parameters with a straight visibility approximation.

Realistic water keeps its explicit Snell boundary/medium path. Custom IOR is used on camera/path boundaries and the approximate
refracted sun beam. Reference RGB absorption is added to the water's existing extinction; vanilla water uses a tinted sheet.
Analytic caustics and straight secondary filter segments retain their existing approximations. This is not full physical transport.

Opacity micromaps leave optical sprites unresolved, including alpha-zero glass centres. Otherwise fixed-function alpha
culling could remove a dielectric interface before the shader can reflect or filter it.

## Settings

Available in the Bedray screen and `config/bedray.toml`:

| Key | Default | Effect |
|---|---|---|
| `rays_optical_materials` | true | Enable the authored optical palette and clear dielectric centres; off restores legacy shader optics for comparison |
| `rays_direct_translucent_shadows` | true | Tint direct sun/local-light visibility through transparent surfaces; also applies to primary volumetric visibility |
| `rays_indirect_translucent_shadows` | true | Tint visibility while shading reflection/GI hits and deeper PT bounces |
| `rays_cull_shared_glass` | true | Extra conservative removal of matching internal full-block glass faces after Sodium decoding |
| `rays_transparency_stats` | false | Enable delayed GPU counters in F3; atomics add overhead |

Turning off a transparent-shadow filter passes light without that filter. Opaque occlusion, foliage filtering, camera
transparency and surface reflections remain controlled by their own paths. The direct/secondary state is scoped across
reflection/GI shading calls so a secondary call does not change the next primary shadow ray's policy.
Changing either shadow policy or the optical master resets accumulation, ReSTIR reservoirs and native NRD/DLSS-RR history.
The optical-material master switch also changes the frame's tracing flags. It disables palette reads and restores legacy
shader optics without editing sidecars. Material baking and conservative CPU culling have separate lifetimes/settings;
tagged optical sprites continue to stay unresolved by opacity micromaps while the switch is off, so re-enabling optics
cannot expose a stale fixed-alpha classification. This switch restores shader optics, not the complete old micromap workload.

## Shared glass boundaries

Sodium already performs ordinary neighbor-face culling. The additional filter only considers matching unit faces on integral
block planes, with the same optical profile, atlas bounds, uniform color/light and render pass. It removes an opposite-facing
coincident pair only when matching outward companion faces one block to either side also exist. Ambiguous multiple faces,
fractional/slanted models, missing companions, different materials and a lone double-sided sheet are retained. A material
can opt out with `cullSharedFaces: false`.

Filtering is section-local and affects newly decoded geometry. Reload the world to rebuild all geometry after changing the
setting. Existing Sodium culling or missing conservative proof can leave the additional removed-face count at zero. The filter
compacts both decoded arrays and raw GPU-dequantization words, preserving pass ranges and vertex/triangle indexing.

## Counters

`[RT-TRANSPARENCY]` reports completed, delayed frame-slot readback: instrumented queries, recognized transparent candidates,
applied layers, bounded storage/budget events and direct/secondary shadow queries. Counts cover the instrumented built-in
interface/shadow paths, not all BVH node visits or all GPU rays. They are 32-bit counters read as unsigned values. A query can
encounter no transparent surface; one pixel can issue many queries at multiple samples/bounces. No millisecond cost or FPS
claim can be derived from these counts alone; existing GPU pass timers are separate.

`glass_faces_examined` and `glass_faces_removed` are lifetime CPU capture totals, not per-frame GPU counters. When diagnostics
are off there are no per-hit counter atomics. A frame written before diagnostics were enabled reports `WAITING` rather than
presenting its zero counters as a measured result.
