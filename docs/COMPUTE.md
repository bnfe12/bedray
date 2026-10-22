# Bedray Compute — experimental development

Stage: experimental software voxel traversal. Local candidate JARs can be built for validation; no stable release
or game compatibility is established by a build. The companion remains a separate module so it can later move into Bedray while
retaining one host-owned device, queue, configuration and presentation lifecycle.

The standalone verification probe targets Vulkan 1.0. In-game use inherits Minecraft 26.3's Vulkan
backend baseline of Vulkan 1.2 and its normal required device features, plus this provider's compute-queue
and target-format checks. Hardware RT feature sets are optional in the host; no arbitrary-GPU support
claim follows from that separation. Devices without a compatible Vulkan backend cannot run this mode.

## Current implemented scope

Two opt-in diagnostic modes share ordinary Vulkan compute; neither requests RT extensions, acceleration
structures, ray queries, device addresses, Int64, vendor SDKs or native vendor libraries:

- **Synthetic**: the existing 32×24×32 scene, fixed camera, direct light and traced shadows.
- **Live opaque debug**: a 48×48×48 window of real loaded world sections, the current game camera with
  the host's current-frame unjittered/bob projection and effective camera origin, exact opaque full-cube
  baked faces, albedo sampled from the live block atlas, and fixed diagnostic sun/shadows.

Shaded occupancy, surface normals, distance and occupancy-mask views are implemented. CPU diagnostics
report loaded sections, textured/opaque counts and omitted unsupported cells. Shaded/normal/distance views omit
unknown/unloaded and unsupported cells. The occupancy view shows unsupported cells as magenta markers
and unloaded sections as blue markers. These diagnostic bounds are separate from shaded geometry.
Partial shapes, cutouts, fluids, entities, items and particles are omitted
from the live replacement image; the later hand/HUD passes remain host-owned. No universal GPU support,
performance, visual correctness or game compatibility is claimed.

The diagnostic currently accepts 8-bit RGBA/BGRA UNORM or sRGB host targets. Destination sRGB encoding
is accounted for in the shader; floating/HDR target integration is deferred until the shared HDR/tonemap
contract is defined. Temporal upscaling and Frame Generation remain unavailable for these modes because
the required matching depth/motion/history guides have not been implemented.

## World input and upload

`renderer-api` major 1/minor 3 publishes immutable `WorldSnapshot`, `SectionSnapshot` and `CameraSnapshot`
data through optional callbacks. `common` owns all Minecraft access, independently of the hardware RT
geometry gate. `compute-common` consumes only API snapshots; it imports no game/loader/private Bedray
implementation classes.

The producer polls a 3×3×3 section window around the camera, at most two section reads per rendered
frame, retaining at most 27 sections (108 KiB occupancy payload; up to 972 KiB raw occupancy, material
references and palettes, excluding Java object overhead). A stationary window is revisited within
14 frames. Only already loaded chunks are read with creation disabled. Unloads and out-of-window sections
are removed before publication. World sessions and content revisions prevent cross-world/stale data reuse.
Unchanged section objects are retained; negative coordinates use floor division.
An external snapshot producer must keep section instance identity and list order stable while session
and revision stay unchanged; camera-only changes may reuse that revision. Changed content, membership
or order requires a newer revision. Section content/coordinates must never change behind an existing revision.

The GPU representation is a fixed 27-tile storage buffer (432 KiB cells plus a 16-byte header), plus
648 KiB of face-material palettes in the textured pipeline. A changed section records one 16 KiB cell
update and at most one 24 KiB palette update; missing slots explicitly receive unknown values. Camera
window moves/session changes may update all 27 slots, bounded at 1080 KiB. Camera movement inside the
same section updates push constants, without rebuilding/reallocating the scene. Command-buffer updates
and transfer/compute barriers avoid overwriting a mapped staging range still used by a previous frame.
Capture requires the client owning thread and uses cached static occlusion properties without world-aware
collision callbacks. The top build section is included. Steady-state block updates do not call device-idle; mode/size/device changes submit pending host work
before releasing provider resources.

### Full-cube albedo materials

The host accepts a model only when its selected, position-seeded baked variant contains exactly six
opaque SOLID quads on the six unit-cube boundary faces. UV transforms come from the quad geometry and
its actual UVs, preserving rotated/reversed faces; particle icons and guessed sprite rectangles are not
substitutes. Extra overlays, nonaffine UVs, partial shapes, cutouts and fluids remain unsupported.
Tint is the game's block tint-source result at that position, applied uniformly per face. This does not
implement Sodium's four-vertex smooth biome blending.

Each section has at most 128 immutable six-face materials and 4096 one-based references. Reference zero
retains gray shading for a proven opaque cube when no material fits the bounded palette. The F3 data
distinguishes textured cells from all opaque cells. Material-only edits such as stone to dirt, changes
of UV/tint, and resource-generation changes update content revisions even when occupancy is unchanged.
Model caches are bounded by resident sections and cleared on world/resource changes.

`AtlasFrame` borrows the host's current animated block atlas, in GENERAL layout and RGBA8 UNORM with
sRGB-encoded texels. The provider owns its nearest/LOD-zero sampler and descriptors; it never owns the
atlas view/image. The shader decodes atlas and tint sRGB once before fixed lighting, then encodes for
the destination target. PBR, normal maps, emission and physical light transport are separate future work.

The host blocks capture throughout resource reload, retires provider resources before atlas handles
close, and clears old section snapshots before publishing a new generation. Actual texture, view and
model-set identities are checked alongside the generation. Descriptors are immutable within an epoch;
reload or identity changes flush queued host commands before provider cleanup. Atlas/model epoch
mismatch skips the compute replacement image. Real Minecraft reload/animation and visual acceptance
still require matching-version game evidence.
If compute retirement fails, ordinary atlas replacement is aborted before borrowed handles close;
final client/device shutdown remains best effort so the device can reclaim the broken state.

## Activation and shared settings

A local development instance should use the matching **Bedray and Bedray Compute alpha.6 pair** for
the persistent shared selector. The mods remain separate: install both JARs for one matching loader.
Compute alpha.6 requires host alpha.6 or newer for material API minor 3; updating only the companion
cannot add the required host hooks. This is a development compatibility requirement, not a stable-release announcement.

Open Sodium Video Settings, the main **Bedray** page and **World Renderer**, then choose
**Voxel (Experimental)**. The main page also shows renderer status, including a missing Compute add-on.
This option is independent of hardware RT capability. The hardware RT mode preference is preserved
when changing renderer. The renderer choice is saved as `renderer` in `config/bedray.toml`.

Voxel diagnostics currently select Bilinear and switch off frame generation, because matching temporal
guides are absent. These changes are explicit in the setting tooltip. The separate Bedray configuration
screen uses the same selection API. Expert diagnostics expose synthetic/live scenes and shaded,
normals, distance and occupancy views. Debug scene/view choices remain session controls.
JVM session alternatives:

```text
-Dbedray.compute.demo=true     # synthetic
-Dbedray.compute.world=true    # live opaque debug; takes precedence
```

`-Dbedray.compute.captureWorld=true` can collect world CPU snapshots while displaying the synthetic mode.
JVM diagnostic overrides remain session-only until an explicit UI renderer choice. Requests are applied
as one stable request at a frame boundary. Initialization failures latch until the request changes; a failed cleanup
requires restart. A recorded command is not evidence of successful presentation.

With an unchanged section window/content revision, the uploader returns without building new slot
arrays or update lists. Scene counters are rebuilt on content revisions rather than camera-only frames.
Clearing residency reuses its fixed arrays; the host clears snapshots on mode/world transitions instead
of every hardware-rendered frame. These are source-level allocation reductions, not measured FPS claims.

### Camera readiness and failure diagnostics

A live alpha.4 run selected Voxel but failed while constructing the first camera snapshot: the inverse
projection contained nonfinite values. The host treated this as a permanent failure of that scene
request, so later frames could not recover. The original projection values were not logged; the upstream
source of the invalid camera has not been established.

Alpha.5 checks initialized camera state, finite origin/matrices and invertibility before publishing a
snapshot. An unavailable camera skips only that frame and retries on the next one. The previous camera
snapshot is never rendered while capture is pending. Matrix diagnostics are logged when the pending
reason changes, with a recovery marker when a valid snapshot arrives. Structural/provider/GPU failures
remain latched and produce a one-time on-screen message.

Alpha.6 also invalidates the retained world projection and bob origin at the start of each world frame.
When bob projection is enabled, capture waits until the current frame's world projection hook runs;
it cannot combine a previous frame's projection/bob with the new camera. This closes a source-level
stale-pose path; it does not establish the upstream cause of the alpha.4 nonfinite matrix.

F3 now includes `Voxel` status and `Voxel data` when the mode is selected. Renderer Status has the full
runtime detail in its tooltip. `scene_recorded` means that the host accepted a diagnostic command buffer;
it does not prove presentation. The readiness fix still requires fresh matching-version Minecraft
capture/presentation evidence; compilation and archive inspection cannot establish that outcome.

## Compilation and evidence

Use JDK 25 and official local compile dependencies from [BUILDING](BUILDING.md). To compile sources:

```text
gradlew :renderer-api:compileJava :common:compileJava :compute-common:compileJava \
        :fabric:compileJava :neoforge:compileJava :compute-fabric:compileJava :compute-neoforge:compileJava
```

Compile baseline: JDK 25, Gradle 9.5.1, Minecraft 26.3, Sodium 0.9.2, Fabric loader 0.19.5 and NeoForge 26.3.0.57-beta. Local dependency JARs are compile inputs only, excluded from Git and not published as product artifacts.

The incoming live-GPU commits originally recorded compile-only evidence. Integration has a separate
verification command; results apply to the exact checked-out revision, never automatically to future edits:

```text
gradlew :renderer-api:test :compute-common:test :compute-common:worldSnapshotContracts :compute-common:vulkanProbe
```

The explicit offscreen Vulkan probe requests Vulkan1.0 with zero enabled device extensions/features.
It compares synthetic and tiled world hit IDs to CPU DDA, tests successive revisions and a single-tile
edit, and checks excluded/unknown markers. It does not launch Minecraft or establish non-RT hardware
performance. Ordinary unit tests skip GPU dispatch; invoke `vulkanProbe` explicitly. For local candidate
JARs, build `:neoforge:jar :fabric:jar :compute-neoforge:jar :compute-fabric:jar` and install matching host/companion loader variants only.

Native test tasks extract their matching LWJGL libraries into separate task-local directories. This avoids
replacing a DLL locked by a running client in the shared user-temp cache. The GPU oracle reports raw
hit-ID differences separately from explained floating-point cell-boundary differences; any unexplained
hit fails the probe. Neither passing this tolerance nor producing an offscreen image establishes game acceptance.

## Remaining implementation gates

1. Validate the first full-cube albedo/material epoch path in game, then extend exact snapshots to layered
   materials, PBR/normal maps and emission. Do not advertise guessed PBR values as actual pack materials.
2. World light transport, accumulation, denoising and matching temporal guides; define bounded histories
   and a shared HDR/tonemap contract before enabling temporal upscalers or FG.
3. Accurate partial shapes/cutouts/fluids, entities and other unsupported geometry, each with an explicit
   cost/fallback policy. Unknown inputs must never become opaque/emissive cubes.
4. Event-assisted revision collection and larger hierarchical windows after CPU/upload/GPU profiling;
   polling and the current 27-tile layout are bounded prototype decisions, not a final optimization claim.
5. Game lifecycle/camera/resize/failure validation and NVIDIA/AMD/Intel measurements before release.

## Merge with the Iris branch

Bedray stays the sole world-data/device/queue/target owner. One world renderer records its image, followed
by the Iris post stage, then the host's hand/HUD and later presentation phases. In a combined hook, pass
`rasterDepthMatchesColor=false` to Iris when compute replaced the world color. Color-only post packs can
compose independently; depth-reading packs need matching compute guides and must not consume old raster
world depth. Merge the shared mixin/UI hooks once rather than retaining two frame loops. The companion
algorithms remain behind `renderer-api`, ready for later incorporation into Bedray shared modules.

The [longer specification](SOFTWARE_RENDERER_MOD_SPEC.md) describes remaining targets, not proof they work.
