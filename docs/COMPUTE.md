# Bedray Compute — experimental development

Stage: bounded software voxel rendering on ordinary Vulkan compute. Bedray owns the game data,
device, queue, targets and settings; Bedray Compute remains a separate companion JAR. Development
candidates are for focused personal checks. Compilation and archive inspection do not establish
presentation, stability, performance or compatibility in Minecraft.

No RT extensions, acceleration structures, ray queries, device addresses, Int64, vendor SDKs or
vendor DLLs are required by the Compute provider. In-game use still requires Minecraft 26.3's Vulkan
backend baseline and the provider's queue/format/descriptor checks. This is not an arbitrary-GPU claim.

## Alpha.7 scope

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

The resident window remains **3×3×3 loaded sections (48 blocks per axis)**. Capture polls two sections
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
captured rather than inventing full fluid cubes. Translucent raster preservation is still the presentation
policy. Physically correct transmission, colored glass shadows and fluid optical transport remain future work.

## Public data contract

`renderer-api` major 1/minor 4 keeps prior constructors and callbacks. Providers explicitly opt into
`supportsModelGeometry()`; older providers continue receiving the cube-only representation.
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

Model/cube generations must agree within and across sections. Geometry, tint, UV, light-only and resource
epoch changes participate in content equality and world revisions. Stable unchanged sections retain
object identity. A new session replaces previous world data; snapshot membership/order changes require
new revisions.

GPU cells remain one uint: occupancy bits 0..7, cube/model reference 8..15, block light 16..19,
sky light 20..23, intrinsic emission 24..27, lighting-present bit 28. `0xffffffff` is unknown before
any decode. The grid is 432 KiB plus its 16-byte header; cube palettes are 648 KiB. One combined model
SSBO has 55,296 bytes of headers followed by 27 fixed 786,432-byte quad slabs (21,288,960 bytes total).
Only used quad spans are uploaded, in aligned chunks no larger than 65,536 bytes. A small 96-byte
lighting SSBO keeps the pipeline at four storage buffers; push constants remain 112 bytes.

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

The shader reads propagated light immediately outside a hit surface, separates block illumination
from sky illumination, and traces one alpha-aware key-light shadow ray. Sealed rooms do not receive
an unconditional fixed ambient term. The moon uses an explicit illuminated-fraction phase policy.
Intrinsic block emission is a scalar self-light approximation, not a per-texel emission mask or colored
physical light transport. PBR roughness/metalness/normals, multiple scattering, GI and reflections remain open.

## Activation

Install the matching **Bedray + Bedray Compute alpha.7 pair**, for the same loader. The companion requires
host alpha.7 or newer for API minor 4. Updating only one JAR cannot supply the other side's contracts.

Open Sodium Video Settings → **Bedray** → **World Renderer** → **Voxel (Experimental)**.
The missing add-on and initialization failures appear in Renderer Status. The saved hardware RT mode
is retained for switching back. The selector is stored as `renderer` in `config/bedray.toml`.

Voxel currently selects Bilinear and disables Frame Generation. Matching compute depth/motion/history
outputs for temporal reconstruction and FG are still absent. Borrowing raster depth for composition
does not make it a matching compute temporal guide.

Expert diagnostics select synthetic/live scenes and shaded/normals/distance/occupancy views.
Session JVM alternatives remain `-Dbedray.compute.demo=true` and `-Dbedray.compute.world=true`.
The live override takes precedence. `scene_recorded` means accepted command recording, not presentation.

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
