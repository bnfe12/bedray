# Bedray Compute — source-only development

Branch: `codex/software-voxel-tracing`. This work supplies source code only: no JAR artifacts or releases
are produced/published. The companion remains a separate module so it can later move into Bedray while
retaining one host-owned device, queue, configuration and presentation lifecycle.

## Current implemented scope

Two opt-in diagnostic modes share ordinary Vulkan compute; neither requests RT extensions, acceleration
structures, ray queries, device addresses, Int64, vendor SDKs or native vendor libraries:

- **Synthetic**: the existing 32×24×32 scene, fixed camera, direct light and traced shadows.
- **Live opaque debug**: a 48×48×48 window of real loaded world sections, the current game camera with
  the host's unjittered/bob projection and effective camera origin, conservative opaque full cubes,
  constant gray albedo, fixed diagnostic sun/shadows. This is not a textured playable renderer.

Shaded occupancy, surface normals, distance and occupancy-mask views are implemented. CPU diagnostics
report loaded sections, opaque counts and omitted unsupported cells. Unknown/unloaded sections stay
unknown and do not become solid. Partial shapes, cutouts, fluids, entities, items and particles are omitted
from the live replacement image; the later hand/HUD passes remain host-owned. No universal GPU support,
performance, visual correctness or game compatibility is claimed.

The diagnostic currently accepts 8-bit RGBA/BGRA UNORM or sRGB host targets. Destination sRGB encoding
is accounted for in the shader; floating/HDR target integration is deferred until the shared HDR/tonemap
contract is defined. Temporal upscaling and Frame Generation remain unavailable for these modes because
the required matching depth/motion/history guides have not been implemented.

## World input and upload

`renderer-api` major 1/minor 1 publishes immutable `WorldSnapshot`, `SectionSnapshot` and `CameraSnapshot`
data through optional callbacks. `common` owns all Minecraft access, independently of the hardware RT
geometry gate. `compute-common` consumes only API snapshots; it imports no game/loader/private Bedray
implementation classes.

The producer polls a 3×3×3 section window around the camera, at most two section reads per rendered
frame, retaining at most 27 sections (108 KiB occupancy payload). A stationary window is revisited within
14 frames. Only already loaded chunks are read with creation disabled. Unloads and out-of-window sections
are removed before publication. World sessions and content revisions prevent cross-world/stale data reuse.
Unchanged section objects are retained; negative coordinates use floor division.

The GPU representation is a fixed 27-tile storage buffer (432 KiB cells plus a 16-byte header). A changed
section records one 16 KiB `vkCmdUpdateBuffer`; missing slots explicitly receive unknown values. Camera
window moves/session changes may update all 27 slots, bounded at 432 KiB. Camera movement inside the
same section updates push constants, without rebuilding/reallocating the scene. Command-buffer updates
and transfer/compute barriers avoid overwriting a mapped staging range still used by a previous frame.
Steady-state block updates do not call device-idle; mode/size/device changes submit pending host work
before releasing provider resources.

## Future development activation

A local development instance must use matching host/companion source revision **0.1.1-alpha.2 or newer**;
older alpha.1 hosts lack the new scene-mode/diagnostic API. This is a source compatibility requirement,
not a released build announcement.

The existing Bedray graphics page cycles **Compute Scene (Experimental)** through Off, Synthetic and
Live opaque debug. Debug view and scene-data rows describe the requested mode and available CPU data.
JVM session alternatives:

```text
-Dbedray.compute.demo=true     # synthetic
-Dbedray.compute.world=true    # live opaque debug; takes precedence
```

`-Dbedray.compute.captureWorld=true` can collect world CPU snapshots while displaying the synthetic mode.
Requests remain session-only and are applied as one stable request at a frame boundary. Older synthetic-only providers do not expose live/debug modes they cannot execute. Initialization failures latch until the request changes; a failed cleanup
requires restart. A recorded command is not evidence of successful presentation.

## Compilation and evidence

Use JDK 25 and official local compile dependencies from [BUILDING](BUILDING.md). Compile sources only:

```text
gradlew :renderer-api:compileJava :common:compileJava :compute-common:compileJava \
        :fabric:compileJava :neoforge:compileJava :compute-fabric:compileJava :compute-neoforge:compileJava
```

Compile baseline: JDK 25.0.5, Gradle 9.5.1, Minecraft 26.3, Sodium 0.9.2, Iris 1.11.7, Fabric loader 0.19.5 and NeoForge 26.3.0.57-beta. Local dependency JARs are compile inputs only, excluded from Git and not published as product artifacts.

Both loaders and the companion modules compile. The updated voxel shader compiles to Vulkan 1.0 SPIR-V.
Per the current source-only request, no tests, GPU dispatch or game sessions were run for the live-GPU
change. Earlier CPU checks on the snapshot-only commit do not validate this newer implementation.
The existing optional CPU/GPU regression suites remain in the tree for future validation.

## Remaining implementation gates

1. Exact material/texture/emission snapshots through the host API, with resource reload/version ownership.
   Do not substitute map colors or guessed PBR values and advertise them as the pack's actual materials.
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
