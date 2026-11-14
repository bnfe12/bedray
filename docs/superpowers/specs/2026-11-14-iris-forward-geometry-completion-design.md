# Iris forward-path geometry completion — design

Date: 2026-11-14
Status: approved direction (owner: "B: достроить геометрию на текущем пути" + "Автономно делай все")
Base: main `b97adeb9` (clean). Scope B only — keep the current forward path and frame ownership.

## Goal

Make the Iris **forward** raster host render a complete, coherent BSL world frame on Bedray's
Vulkan backend, by completing the world-geometry feed and removing the gate that permanently
disables the host on the first empty frame. Full Iris parity for *any* pack is the north star;
this spec delivers the verified increment that makes the forward path actually run and draw a
complete world, then extends the missing geometry sources.

## What is already correct (do NOT change)

- **Frame ownership.** `IrisFrameHook.renderWorldPost` records the pack into a separate transient
  command buffer at the head of `render3dHud` (engine color+depth final, hand not drawn, no open
  render pass), then `encoder.execute`. The engine keeps **all** textures in
  `VK_IMAGE_LAYOUT_GENERAL` (`VkBarriers.transition` doc), so the final pass writing
  `engineColorView` has no layout hazard. The mid-frame host-switch crash is already fixed on main
  (`ShaderHosts.applyPendingRequest()` at the frame boundary, PR #106) and deployed.
- **Capture gate.** `RtCaptureControl.irisWantsCapture` publishes `irisActive` when the active host
  is IRIS and the full graph has a geometry pass; `RtGeometryGate.captureEnabled` = FORCED || active
  || irisActive. Sodium section capture (H1/H3/H4) and entity capture run in iris mode.
- **Existing geometry sources.** `IrisFrameGeometry.build` produces `terrain` (SOLID+CUTOUT),
  `water` (TRANSLUCENT) and `entities` (`EntityGeometryFrame`, which already includes block entities
  via `FLAG_BLOCK_ENTITY`). `IrisGeometrySource` has `buildTerrain` + `buildEntities`
  (29-float gbuffers layout: pos3,color4,uv0_2,uv1_2,normal3,midBlock3,mc_Entity4,mc_midTexCoord4,at_tangent4).

## Confirmed gaps (the work)

1. **Fatal self-disable on empty geometry.** `IrisForwardExecutor.record` returns `false` when
   `frameIsCoherent` is false (no gbuffers mesh this frame). `renderForward`→`render` propagate
   `false`; `IrisFrameHook.renderWorldPost` treats *any* `false` as `executor_failed` and calls
   `disableForSession`. So the first warmup frame (capture not yet populated) permanently disables
   iris for the session. This is the most likely reason the forward path was **never observed
   in-game** (no `forward executor: N pipelines` / `targets allocated` markers in any instance).
2. **Missing geometry sources.** `sky`, `hand`, `weather` have no builder, so those gbuffers always
   skip (`recordGeometry`: "no geometry source this frame"). Because the forward render clears
   colortex and the final pass overwrites `engineColorView` with `DONT_CARE` load, anything not
   drawn is lost — the sky background, first-person hand and weather vanish from the final frame.
3. **Coarse gate.** `frameIsCoherent` is an all-or-nothing "any geometry present" check, not
   completeness-by-construction.
4. **Never verified in-game.** The forward path has no L4 evidence. The existing `IrisScenario`
   autotest runs with `-Dbedray.iris.post=true`, which drives the **post** path, not the forward path.

## Design

### Phase 1 — forward-path reliability (foundation, highest priority)

Introduce a tri-state outcome at the render seam so a warmup frame is a graceful skip, not a failure.

- New enum `IrisRenderBridge.RenderOutcome { RECORDED, SKIPPED, FAILED }`.
  - `render(...)` returns `RenderOutcome` instead of `boolean`.
  - Post path: `RECORDED` on success, `FAILED` on guard/exception.
  - Forward path: `RECORDED` when the graph recorded; `SKIPPED` when there is no gbuffers geometry
    this frame (warmup); `FAILED` on a real executor error/exception.
- `IrisForwardExecutor.record` returns the same tri-state (or a forward-specific result mapped by
  `renderForward`). The `frameIsCoherent` false branch becomes `SKIPPED` (log once per state change,
  not every frame), **not** a failure.
- `IrisFrameHook.renderWorldPost`:
  - `RECORDED` → `fullMemoryBarrier` + end + `encoder.execute`, `reason=null`.
  - `SKIPPED` → leave the engine image untouched, `recorded=false`, `reason="no_geometry"`,
    **no** `disableForSession`; retry next frame.
  - `FAILED` → `disableForSession` (honest fallback), as today.
- The per-frame `[VKB-IRIS] requested=.. active=.. state=.. passes=N recorded=.. reason=..` line stays;
  `reason="no_geometry"` now distinguishes warmup from failure.
- Tests: update `IrisRenderBridgeTest` (boolean→outcome) and `IrisForwardExecutorTest`
  (`frameIsCoherent` semantics unchanged; add a record-result test for the skip path).

This phase alone makes the forward path run and draw terrain/water/entities/block-entities. It is
verified in-game before any new geometry is added (systematic discipline: confirm the existing path
works before extending it).

### Phase 2 — sky geometry source

Add `IrisGeometrySource.buildSky(...)` producing a camera-centered sky dome mesh keyed `"sky"` for
`gbuffers_skybasic` / `gbuffers_skytextured` (both already map to `"sky"` in `meshKey`).

- Procedural dome: a hemisphere/quad shell at far distance centered on the camera, positions
  camera-relative (matching the Iris `worldPos = vaPosition + cameraPosition` convention), color =
  white (the pack computes sky color from `skyColor`/`sunPosition`/`fogColor` uniforms), uv0 for the
  sky texture, lightmap = full sky, normal = outward, midBlock/mc_Entity/tangent defaults like terrain.
- Wired into `IrisFrameGeometry.build` (always present when a sky pass exists; not section-cached).
- Drawn with depth at far so terrain overwrites it; pass order in the pack graph places sky first.
- Empirical: BSL sky shaders are direction/uniform-driven, so dome shape is forgiving; exact UV/depth
  behavior is confirmed by in-game screenshots and iterated.

### Phase 3 — hand geometry source

Extend the first-person hand pass to record a hand **mesh** (view-space) keyed `"hand"` for
`gbuffers_hand`, alongside the existing RT emitter capture (`EntityCaptureManager.beginHandPass`).

- Reuse `CapturedVertexConsumer` (10-float interception) during `renderItemInHand` to capture the
  hand+item quads into a mesh, converted to the 29-float layout by a new
  `IrisGeometrySource.buildHand(...)`.
- The hand uses the hud projection (0.05 near, no jitter); the forward executor must draw it with the
  hand projection/depth range, not the world projection. This is the delicate part — confirmed
  in-game; if it cannot be made coherent it is reported honestly as a remaining gap, not faked.

### Phase 4 — weather + completeness-by-construction

- Weather: add a `"weather"` source (rain/snow quads) for `gbuffers_weather`, reusing the particle
  capture pattern (`RtWeatherLayer` / `ParticleVertexConsumer`) converted to the gbuffers layout.
- Replace the coarse `frameIsCoherent` all-or-nothing gate with completeness-by-construction: once
  geometry is present, record the full graph; each gbuffers pass draws its mesh or skips individually
  (existing `recordGeometry` behavior); the final pass always runs. The only frame-level skip is the
  Phase-1 warmup `SKIPPED` (no geometry at all yet).

### Verification (every phase)

- **Autotest forward scenario.** Run the real геймер instance via
  `freesmlauncher.exe --launch геймер` with JVM args injected through `instance.cfg` (backed up and
  restored; FreesmLauncher strips `JAVA_TOOL_OPTIONS`):
  `-Dvkbridge.autotest=iris -Dvkrtx.shader_host=iris -Dvkrtx.shaderhost.pack=BSL_v10.1.8.zip`
  and **without** `-Dbedray.iris.post` so the forward path drives. `IrisScenario` shoots
  morning/noon/sunset/night and logs host selection + F3 shader-host lines.
- **Objective evidence read back:** the screenshots (read as images) + `latest.log` markers
  (`forward executor: N pipelines`, `targets allocated`, `recorded=true`, `sections=N quads=Q`,
  absence of `disableForSession`/`DEVICE_LOST`).
- **L4 is owner-gated.** Autotest screenshots are developer evidence; the final "works in game"
  claim waits for the owner to play the deployed build. No feature is claimed working without L4.

## Build / deploy / merge constraints (verbatim, in force)

- One Gradle task: `neoforgeBedrayDistribution` with `-PbedrayCmake -PbedrayVulkanHeaders -PslangDir`.
  Java-only edits leave `bedray_core.dll` content-identical. XML test results are truth.
- Re-verify HEAD + mods right before build/deploy (main moves live; shared worktree). Backup before
  deploy; matched ABI2 jar+`core.dll` pair (jar alone = rt off). Never mix scene ABI2/ABI3.
- main is PR-protected: branch → PR → `gh pr merge --merge`. No force-push/reset --hard/checkout--;
  no `git add -A`; no commit/push without permission (owner pre-approved this autonomous run).
- Host via `bedray.toml` (launcher rewrites `instance.cfg`). Report in Russian; code/comments English.
- Do not patch the Iris jar, force GL mixins, pull an OpenGL backend, or port Vitrail wholesale.
  Unsupported pack features are refused by name, not approximated. Never mix native+iris in one frame.

## Out of scope (future phases)

- Approach A (iris owns/suppresses the vanilla world render) — too big; frame ownership stays as-is.
- Double-render perf waste (vanilla draws the world, then the forward path re-draws) — perf only, not
  correctness; deferred.
- Full parity for arbitrary packs beyond BSL (integer targets, SSBO, custom compute, etc.) — refused
  by name today; unchanged.