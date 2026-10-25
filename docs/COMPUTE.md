# Bedray Compute — experimental development

## Architecture decision: streamed coverage and one settings owner (2026-10-25)

The current distribution includes the Compute provider as a nested mod inside Bedray. Its mod ID,
registration and versioned host API remain separate; installing an additional external companion is
not part of that distribution. Preserve existing hardware RT and Iris functionality while extending
the software renderer.

Requested horizontal coverage follows Minecraft's effective render distance. Read loaded chunks
only, across the world's valid section-height range. Use bounded incremental capture and section
deltas, compact GPU pages and a small section directory. Do not grow the old fixed 27-section model
slabs into a render-distance-sized dense allocation. Unknown, queued, empty and resident sections
are distinct states; incomplete coverage retains raster and reports its actual residency and memory
pressure. CPU capture, retained caches, upload bytes and GPU scene storage all require explicit bounds.
Queue barriers order in-place page reuse; resource destruction retains the host completion rules.

An additive streaming API keeps the legacy bounded snapshot path for existing providers. The host
owns loaded-world capture and requested settings; the provider owns residency and publishes immutable
coverage statistics. No RT extension, buffer device address or assumed frame-completion offset is
introduced by this decision.

The existing Bedray settings screen becomes the primary interface. Sodium links to that screen.
Fresh per-screen options reuse the existing definitions and validators, keeping a private draft until
Apply. Revert and Cancel do not mutate renderer state or a parent Sodium draft. Search, localization,
renderer-relevant controls, and requested-versus-active status belong in this one interface. Avoid
per-frame schema rebuilding, file scanning and unmeasured performance promises.

Compilation and packaging verify implementation contracts. A fresh matching-version game run and
visible populated output are separate acceptance requirements; this decision does not claim those
checks have already passed.

Stage: bounded software voxel rendering on ordinary Vulkan compute. Bedray owns the game data,
device, queue, targets and settings; Bedray Compute is a separate provider nested in the player JAR. Development
candidates are for focused personal checks. Compilation and archive inspection do not establish
presentation, stability, performance or compatibility in Minecraft.

No RT extensions, acceleration structures, ray queries, device addresses, Int64, vendor SDKs or
vendor DLLs are required by the Compute provider. In-game use still requires Minecraft 26.3's Vulkan
backend baseline and the provider's queue/format/descriptor checks. This is not an arbitrary-GPU claim.

## Alpha.13 streaming and settings

The included API 1.6 provider receives incremental section changes across Minecraft's effective render
distance and valid world height. Loaded chunks are read without creating new chunks. Discovery starts
near the camera, reprioritizes vertical movement, and bounds section capture and upload work. Geometry
changes invalidate Voxel independently of the hardware RT capture gate. Air-only sections retain their
actual propagated light; known empty geometry is not treated as missing lighting.

GPU residency uses an absolute-coordinate section directory and reusable compact cell, palette and
model arenas. Model storage follows used quads, rather than reserving 8192 quads for every section.
Uploads publish a directory entry only after its payload is complete. The scene allocation obeys its
memory budget, pending payloads are bounded, and nearer sections can replace farther ones. Eviction
feeds back into capture with a retry cooldown. Requested chunks, resident sections, upload queue and
actual allocated scene bytes are separate diagnostics. Output/history images are additional memory.

The settings screen uses a private pending model, shared validators, search, category navigation,
direct choice menus, and Apply/Revert. Fast, Beautiful and Custom profiles are separate for Voxel and
Path Tracing. Both Voxel presets follow Minecraft distance; Fast lowers the scene budget and disables
the extra diffuse bounce and translucent optics. Beautiful enables those effects. Custom unlocks manual
quality controls. A named or imported personal preset is staged before applying it.

Temporal reuse still requires matching surface distance and normal. Its effective averaging cap is
64 in a steady scene, 32 for camera motion and 16 for scene/lighting changes, instead of the previous
eight during every streaming frame. Lighting changes are compared with an anchor; significant changes
shorten accumulation for eight recorded frames, and large discontinuities reject old history. These
are response/noise heuristics, not strict radiance lifetimes or measured guarantees. More history can
also retain view-dependent reflection/transmission information longer; motion response needs live checks.

Available renderer paths are raster, hardware Path Tracing, experimental hardware RT effects, and
Voxel with raster preservation. Voxel currently suspends the hardware pass. A simultaneous RT+Voxel
mode needs explicit effect ownership, composition and temporal guides. Merely adding a preset name
does not implement it; separate pure/hybrid labels must not select the same underlying path silently.

These are development changes. Final matching-version gameplay evidence is required for coverage,
noise, menu layout, switching, resize/reload, memory pressure and sustained travel.

## Alpha.11 integration and optical limits

The host integrates the newer hardware-renderer fixes for degenerate geometry/camera directions,
nonfinite temporal inputs, missing sun halo, small-emitter power and canopy sampling. Sun textures
retain resource-pack opacity in the restored halo, the body mask and reflections; opacity is applied
once to specular brightness. Underwater camera-medium detection now measures surface elevation from
the current query origin after skipping glass or ice.

The newer Iris post graph adds target formats, clearing, image history and mipmaps. Its borrowed-target
identity is adopted only when preparing an accepted frame, before allocations/recording can fail.
Early rejection keeps the previous owner, and image/view identity changes retire its resources first.
This historical checkpoint shipped Bedray and Compute separately; alpha.13 includes the provider.
The alpha.10 activation fixes below are retained.

Hardware Path Tracing still has distinct optical limits: realistic water/glass primarily receive the
sun through the reflected environment, without a separate rough-interface solar estimate. Water-volume
in-scattering uses an ambient approximation without directional sunlight visibility, so it cannot
produce shadowed underwater sun shafts. Submerged opaque surfaces have a separate refracted sunlight
path. Restoring the halo does not establish that these other effects are implemented or verified.

## Alpha.10 activation fix

A matching alpha.9 game launch selected Voxel, captured its camera and still displayed raster. The
lighting collector required `GameRenderState.readyForLevelRendering`, a field that Minecraft 26.3
declares but never populates, including the inspected NeoForge 26.3.0.76 runtime. Its default false
value prevented every lighting snapshot and therefore every compute dispatch in game.

Alpha.10 uses the actual `shouldRenderLevel` world-pass condition while retaining the existing current
camera/projection and finite lightmap/sky checks. Pending lighting and raster inputs now have separate,
specific status messages and a transition-only `frame_pending` log. `frame_inputs_ready` reports
recovery; `scene_recorded` still means command recording, not visual acceptance. The fix needs a fresh
matching-version game run; earlier offscreen probes did not exercise this host readiness check.

Vanilla's boss-overlay fade can also retain a small negative value after subtraction. The collector
accepts a finite boss factor and clamps the published value to [0,1], so that ordinary fade-out does
not disable Compute. Nonfinite input still keeps raster and reports a pending reason.

## Alpha.8 visual pass

Ordered by visible effect per unit of work. Each step stays bounded and keeps the raster fallback.

1. **No gray cubes.** Gray cubes came from cube palette overflow: the appearance key held the
   per-position biome tint, so biome blends and random rotations exhausted the 128 entries. API minor 5
   moves a resolved tint into an optional per-cell value when all tinted faces of a cell agree, so the
   palette holds one entry per geometry. A cube that still does not fit falls back to the model palette.
   If that palette is also full, the cell becomes explicitly unsupported. A cube without a palette entry
   is still an occluder, but its raster pixel is kept instead of being shaded gray.
2. **Emissive blocks light the scene.** The cells inside the window that emit light are listed (up to
   128, nearest to the window center). Each emitter is a sphere with the surface area of its geometry:
   the unit cube, or the bounding box of a model's quads. One emitter per pixel is chosen by reservoir
   sampling, weighted by its unshadowed irradiance bound, and one shadow ray is traced toward it.
   Emitter radiance is set so that a level-e emitter delivers vanilla's response(e-1) at one block.
   Beyond that it falls off as 1/d². Vanilla block light is still added where no listed emitter can
   explain it. A single source of level e at Manhattan distance d gives at most e-d, so a higher vanilla
   level means a source outside the grid or beyond the list.
3. **Ambient occlusion, sky light and one bounce** come from one cosine-weighted ray per pixel. A ray
   that leaves the grid sees the sky, scaled by vanilla sky light at the surface. A ray that hits
   geometry returns that surface's albedo times its key, sky and block irradiance. The key light is
   sampled over the Sun's real angular radius.
4. **Water and translucent blocks** use Schlick Fresnel with one reflection ray and one transmission
   ray. Water refracts (n = 1.333) and attenuates by Beer-Lambert. Its extinction is chosen so that one
   block transmits exactly vanilla's blend weight (1-a)+a·c of the surface texel. The medium radiance
   is c times the irradiance at the surface. Other translucent quads are thin filters (n = 1.5) with
   no bending. Key-light and bounce rays are filtered by translucent layers, so stained glass casts
   colored shadows. If the camera is inside a model cell (for example under water), translucent
   surfaces keep raster. Unresolved transmission also keeps raster.
5. **Temporal accumulation.** Two RGBA32UI history images store half-precision radiance, a sample
   count, hit distance and normal. Each surface point is reprojected with the previous frame's camera.
   A bilinear history tap is accepted only if its distance and normal match. The history window is 64
   frames while the camera and scene are steady, and 8 frames on any frame with camera motion or a
   scene upload. Environment changes alone do not currently shorten history, and the reduced cap is
   not held for a cooldown after a single update; lighting-response correctness remains a follow-up.
6. **Larger region: not in this pass.** The 27-section window is fixed by the stream contract
   (2 section reads per frame, 14-frame revisit). The model SSBO grows by 786,432 bytes per section,
   so 5×5×5 sections would need about 98 MB of model slabs and a 63-frame revisit. That needs a
   streaming design rather than a larger constant.

## Alpha.7 scope (retained)

- Actual block-atlas albedo and uniform game tint, with nearest LOD-zero sampling and animated atlas epochs.
- Exact six-face opaque cubes retain their fast path. Other accepted cell-local baked models use their
  actual quad positions and UVs: layered grass, partial shapes and cutout surfaces are no longer rejected
  merely for having more than six faces.
- Alpha-tested primary and shadow intersections; later accepted coplanar overlays take precedence.
- Real propagated Minecraft block/sky light, intrinsic scalar block emission and current-frame environment.
- A bounded directional sun/moon shadow ray; fixed explicit display processing without temporal noise/history.
- Raster color/depth input preserves distant, unsupported and nearer unmatched surfaces. Accepted translucent
  model/fluid primary hits preserve the original raster pixel; this does not implement refraction/reflections.
- Normals, distance and occupancy debug views isolate the compute scene. CPU counters expose accepted cubes,
  models, light and omitted cells. Recording counters do not prove presentation.

In the legacy snapshot path, the resident window is **3×3×3 loaded sections (48 blocks per axis)**. Capture polls two sections
per rendered frame, so a stationary window is revisited within 14 rendered frames. Membership follows
camera section coordinates and includes valid world build sections; 27 residents are not always possible.
Only already loaded chunks are read, with creation disabled. Missing cells remain unknown.

## Geometry and composition decision (2026-10-24)

Use cell DDA as the broad phase and actual baked triangles as the narrow phase. Collision boxes and
sprite-name heuristics cannot represent partial shapes, layered textures or alpha. Preserve the exact
cube path, opt into model occupancy through the public provider API, and cap all palettes/uploads.

Keep raster inputs as an explicit hybrid composition source while the bounded compute representation
is incomplete. Replace a shaded pixel only when raster depth agrees with the computed surface;
otherwise retain its raster color. At the window edge, blend toward raster to reduce the bounded
lighting seam. Ordinary Minecraft sky, distant terrain and unsupported foreground objects remain visible.
This makes the checkpoint useful for focused scene checks without claiming a complete path tracer.
Depth agreement cannot identify translucent particles that blend without writing depth. A separate
pre-transparency composition stage or coverage contract is still needed for complete particle preservation.

Random model offsets and geometry crossing the owning unit cell require primitive bucketing across cells.
Until that exists they remain explicit unsupported input, retained by the shaded raster composition.
Models beyond 64 quads, appearances beyond the 128-entry section palette, unknown models and invalid
atlas sources are also unsupported. A guessed solid cube is never substituted for those inputs.

Fluid input uses the vanilla tessellator where its exact result fits the bounded cell representation;
waterlogged geometry requires the combined result to fit. Actual fluid heights, flow UVs and tint are
captured rather than inventing full fluid cubes. Alpha.8 adds bounded experimental transmission and
colored filtering; unresolved paths retain raster. Correct water exit-medium tracking and complete
transparent composition remain open.

## Public data contract

`renderer-api` major 1/minor 6 adds opt-in streamed section deltas, render-quality input and immutable
coverage status. Prior constructors and callbacks remain available. The following describes the retained
minor 5 payload format; the fixed GPU slab layout applies only to the legacy snapshot path.

Providers explicitly opt into
`supportsModelGeometry()`; older providers continue receiving the cube-only representation. Minor 5 adds
`supportsCellTint()`: only opted-in providers receive `SectionTintData`, `CubeFaceMaterial.cellTint` and
`ModelQuad.CELL_TINT`; others keep tints baked into every palette entry. Minor 5 also adds
`ModelQuad.FLUID` (fluid surfaces are medium boundaries, not thin layers).
`compute-common` imports no Minecraft, loader or private Bedray implementation classes.

`SectionSnapshot` has 4096 cells in x+16*(y+16*z) order:

| Value | Meaning |
|---|---|
| 0 | Empty |
| 1 | Proven opaque full cube |
| 2 | Unsupported |
| 3 | Accepted baked model |

Optional immutable payloads:

- `CubeSectionMaterials`: up to 128 six-face materials and one-based per-cell references. A face is
  32 little-endian bytes: six affine UV floats, packed ARGB tint, reserved zero; 192 bytes per material.
- `SectionModelGeometry`: up to 128 appearances, 64 quads per appearance and 8192 quads per section.
  Each model header is 16 bytes: first quad, quad count and two reserved zeros. A quad is 96 bytes:
  four interleaved xyzuv vertices (20 floats), ARGB tint, flags, emission and reserved zero. Triangles
  are 0/1/2 and 2/3/0. Positions are cell-local and all UVs refer to the matching live block atlas.
- `SectionLightData`: 4096 shorts, with block light in bits 0..3, sky light 4..7 and intrinsic emission
  8..11, including air cells. Null means unavailable, not darkness.
- `SectionTintData` (minor 5): 4096 ints, zero or opaque ARGB. It multiplies cube faces whose face word
  bit 0 is set and model quads flagged `CELL_TINT`. A cell-tinted appearance requires a nonzero value.
  A nonzero value requires cube or model occupancy. The host uses it only when every tinted face or quad
  of the cell resolves to one color; otherwise tints stay baked.

Model/cube generations must agree within and across sections. Geometry, tint, UV, light-only and resource
epoch changes participate in content equality and world revisions. Stable unchanged sections retain
object identity. A new session replaces previous world data; snapshot membership/order changes require
new revisions.

GPU cells remain one uint: occupancy bits 0..7, cube/model reference 8..15, block light 16..19,
sky light 20..23, intrinsic emission 24..27, lighting-present bit 28. `0xffffffff` is unknown before
any decode. The grid is 432 KiB plus its 16-byte header, followed in the textured pipeline by 432 KiB
of per-cell tints (zero = none). Cube palettes are 648 KiB; the formerly reserved face word is the flag
word (bit 0 = cell tint). One combined model
SSBO has 55,296 bytes of headers followed by 27 fixed 786,432-byte quad slabs (21,288,960 bytes total).
Only used quad spans are uploaded, in aligned chunks no larger than 65,536 bytes. The frame SSBO keeps the pipeline at four storage buffers: the 96-byte `LightingFrame`, then the previous
forward view-projection, previous camera and eye in current grid coordinates, frame info (emitter count,
history valid, history cap, motion) and 128 emitters of 32 bytes (4,304 bytes). Push constants remain
112 bytes; control.z carries the frame counter for the random sequence. The raster variant adds two
RGBA32UI storage images for history (three storage images in total).

Uploads record `vkCmdUpdateBuffer` and transfer/compute barriers on the host queue. No mapped staging
range is overwritten while prior frames may read it. Camera-only frames do not rebuild scene uploads.
The layout is bounded, not a measured final performance design.

## Camera, raster and resource lifetime

`AtlasFrame` borrows the current block atlas in GENERAL layout, RGBA8 UNORM with sRGB-encoded texels.
The provider owns its sampler/descriptors, never the host atlas. Atlas samples and tints decode once;
target encoding occurs once. Reload blocks capture until models and atlas agree, retires descriptors
before old handles close, and starts a fresh resource epoch. Animation uses the current atlas contents.

`RasterFrame` borrows same-device color/depth views in GENERAL layout for the current render call.
Color supports RGBA/BGRA UNORM or sRGB; depth is D32_FLOAT reverse device Z. The provider writes a
separate owned output and only then blits back, avoiding sampled/write image aliasing during dispatch.

Extended Compute uses the exact current raster projection and effective camera origin, including
walking bob/hurt pose, independently of the old hardware RT bob approximation toggle. Nonfinite or
unavailable camera data skips that frame and retries; old camera snapshots are not presented as current.
Initialization/structural failures remain explicit and latched for the request.

`RenderTarget.destroyBuffers` has a shared pre-close gate: submit queued references, retire Compute
and Iris consumers, then allow vanilla to close target images/views. Same-size recreation is included.
Cleanup failure stops ordinary replacement; final client/device teardown permits device reclamation.
Next-frame size/view detection alone is not the lifetime guarantee.

## Lighting semantics

The host collects actual sun/moon angles, weather, sky color, lightmap tints/factors, dimension ambient,
brightness, darkness, night vision and boss-darkening state. It does not depend on an active hardware
RT pipeline or mutable RT sky settings. Game display tints are interpreted as sRGB and decoded for the
linear Compute path; this is an explicit approximation, not the exact vanilla lightmap equation.

The shader reads propagated light immediately outside a hit surface. All terms are linear radiance in
π-normalized units: outgoing = albedo × (ambient + key + sky/bounce + block) + albedo × emission.

- Key light: one cone sample over the Sun's angular radius (0.2666°), one shadow ray through translucent
  filters. A ray that leaves the grid is scaled by vanilla sky-light response, so caves deeper than the
  grid get no sunlight.
- Sky and bounce: one cosine-weighted ray. On escape it returns the sky color × sky factor, scaled by
  vanilla sky-light response at the surface. On a hit it returns that surface's albedo × (ambient + vanilla
  sky + vanilla block + traced key light). The display sky color is interpreted as a uniform sky radiance.
  The former flat sky ambient with a 0.35 factor is gone.
- Block light: next-event estimation over listed emitters plus unexplained vanilla light, as described
  in the alpha.8 pass above. An emitter's own appearance is albedo × response(level) × block color,
  which matches vanilla's full-bright look.
- Presentation: brightness/darkness/boss darkening and Reinhard x/(1+x) are applied after accumulation.

Still open: PBR roughness/metalness/normals, multiple bounces, glossy reflections on opaque surfaces,
and colored emitter spectra (emitters use the game's block-light tint).

## Activation

Install the **Bedray alpha.13 player JAR** for the appropriate loader. It includes the matching Compute
provider and host API 1.6. Remove older external `bedray-compute-*.jar` files to avoid duplicate mod IDs.
For development, `-host-only.jar` plus the matching separate Compute artifact remains available. The nested
provider can be extracted from `META-INF/jars/` (Fabric) or `META-INF/jarjar/` (NeoForge); use it with the
matching host-only artifact, replacing the included-provider JAR. Do not install both distributions together.

Open Sodium Video Settings → **Bedray** → **World Renderer** → **Voxel (Experimental)**.
The missing add-on and initialization failures appear in Renderer Status. The saved hardware RT mode
is retained for switching back. The selector is stored as `renderer` in `config/bedray.toml`.

Voxel currently selects Bilinear and disables Frame Generation. Its own temporal accumulation is internal:
there are still no matching compute depth/motion outputs for an external upscaler or FG. Borrowing raster depth for composition
does not make it a matching compute temporal guide.

Expert diagnostics select synthetic/live scenes and shaded/normals/distance/occupancy views.
Session JVM alternatives remain `-Dbedray.compute.demo=true` and `-Dbedray.compute.world=true`.
The live override takes precedence. `scene_recorded` means accepted command recording, not presentation.

## Focused personal checks (alpha.8 additions)

F3 shows two lines. `Voxel:` is the request status. `Voxel data:` contains section/cube/model counts,
`raster cubes` (cubes without a palette entry, kept as raster: expected 0 in ordinary terrain),
`cell-tinted`, `emitters listed/found` and `accumulation` (`steady, window n/64`, `moving` or
`scene update` with window 8, or `reset (reason)`).

1. Biome border with grass, leaves and water: no gray cubes; `raster cubes` should stay at 0.
2. Torch, lantern, glowstone, lava in a dark room: visible light pools with shadows behind blocks,
   and `emitters` nonzero. Compare brightness against raster by switching renderers.
3. Stand still after moving: noise should settle within about one second (64 frames). It should
   reappear briefly while walking (window 8), without long ghost trails.
4. Water and stained glass from above: reflection at grazing angles, the bottom visible and darker
   with depth. Under water, the surface keeps raster.
5. Cave deeper than 48 blocks below the surface: no sunlight on the walls.

## Focused personal checks

Use the exact installed version shown in the fresh log and F3. Do not compare a new source revision to
an older JAR or log. Build/package evidence is separate from each scenario below:

1. Select live Voxel; loaded/textured/model counts must become nonzero in a suitable scene.
2. Compare stone, dirt, layered grass, slabs, stairs, fences, doors, leaves and redstone. Inspect cutout
   edges against a contrasting block; verify the occupancy view identifies explicit unsupported inputs.
3. Place/remove a torch in a closed room; change time/weather outside. Confirm light-only updates as
   well as stone→dirt material changes within the bounded polling interval.
4. Walk, sprint, bob, turn, change FOV and cross a section boundary. Check surface/light registration
   and the transition to raster outside the window.
5. Inspect glass, water, waterlogged blocks, entities and particles. Transparent primary surfaces retain
   raster appearance; missing physical optics and depthless particle composition are known limits.
6. Reload resources with F3+T, resize, leave/rejoin and switch renderers. The image should recover
   without stale atlas/target handles. A new failure needs its matching fresh log.

## Compilation and evidence

Use JDK 25 and [BUILDING](BUILDING.md). Source-only compilation:

```text
gradlew :renderer-api:compileJava :common:compileJava :compute-common:compileJava \
        :fabric:compileJava :neoforge:compileJava :compute-fabric:compileJava :compute-neoforge:compileJava
```

Build separate candidates with `:neoforge:jar :fabric:jar :compute-neoforge:jar :compute-fabric:jar`.
Historical unit/offscreen probe commands remain available in the Gradle modules, but a previous result
is not evidence for a later revision. A Vulkan1.0 GLSL compile does not dispatch on GPU or run Minecraft.
Native libraries/dependencies used by local build tools are not published as product binaries.

The earlier alpha.4 live run failed on a nonfinite inverse projection. Readiness/retry and current-frame
camera guards were subsequently added; the original upstream matrix values were not captured.
Alpha.6 archives were installed but had no matching live game run at the last audit. Alpha.7's image,
camera, light, reload, resize and cross-vendor performance require new matching-version game evidence.

## Iris integration

Iris source through `086b98a` is integrated with one world frame loop. The actual successful producer
is selected from Compute, hardware RT or raster; optional Iris post runs once before hand/HUD. The
shared typed host frame contract and bounded current-frame MRT graph are retained. Only raster
publishes a matching depth guide; RT/Compute color-only post must not consume old raster depth as a
matching temporal/depth guide. Iris is still explicitly opt-in; this is not general shaderpack support.

The [longer specification](SOFTWARE_RENDERER_MOD_SPEC.md) describes future targets, not proof they work.
