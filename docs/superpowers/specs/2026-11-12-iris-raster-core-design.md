# Iris raster-core pack support (I4+I5) — design

Date: 2026-11-12
Scope approved by owner: "ядро raster-паков" (real packs launch and render visibly; exotic optional features skipped with honest named log).
Architecture fixed: pack-level host, no runtime Iris, no GL context, no Iris jar patching. Bedray owns device/queue/swapchain/frame graph; host records passes. Never mix native and iris host in one frame.

## Goal
Real Iris/OptiFine shaderpacks (BSL, Complementary, Sildur's) run on Bedray's Vulkan backend and produce a visible pack effect in game. Acceptance: pack launches, characteristic effect visible in a screenshot, no crash, honest log; unsupported required feature => UNSUPPORTED_PACK_FEATURES naming it; pack-declared optional or settings-disabled feature => skipped with named log.

## Approach (chosen: A)
Capture-fed host forward renderer. Reuse existing RT geometry capture (Sodium mesh-build -> DecodedSection; submit -> EntityGeometryFrame / RtSceneApi) as the geometry source; upload to raster vertex buffers in Iris attribute layout; host records gbuffers_* + shadow into pack targets, then deferred/composite/final; final output becomes the world image. Rejected: B (engine draw-call interception, too invasive/fragile), C (G-buffer synthesis from engine color, forbidden "approximate").

## Components
1. IrisGeometrySource: reads SectionGeometryRegistry per frame; builds raster vertex buffers (vaPosition=positions+section origin, vaUV0=uvs, vaColor=colors, vaUV1=light block/sky, vaNormal=normals, at_midBlock derived); indices via SodiumMeshDecoder.fillTriangleIndices; GPU upload via RtMemory.createBuffer; cache by section key+registry version; invalidate via listener. Un-derivable attribute => named unsupported.
2. Capture gate: RtGeometryGate enabled when (RT active OR iris host active), decided once per frame at render head (RtCaptureControl.onFrameBegin).
3. IrisPackSettings: parse shaders.properties (screen/profile/sliders + defaults), evaluate program.<dim>/<prog>.enabled and option #define macros; dimension folder selection (world0 overworld, world-1 nether, world1 end); custom textures from tex/.
4. IrisTargets: pack-declared colortex0..15 formats, depthtex0..2, shadowtex0/1, shadowcolor0/1; ping-pong; pack clear colors; sized to render target; recreate on resize.
5. IrisUniforms (extended): full Iris UBO + per-pass matrices (modelView/projection per draw for gbuffers, shadow matrices, ortho for composite) + samplers for all colortex/depth/shadow/lightmap/custom textures; pack uniform.* expression evaluator (too complex => named unsupported).
6. IrisForwardExecutor: records shadow pass (shadow matrix, shadowMapResolution, culling reversed) then gbuffers (terrain SOLID/CUTOUT, water/translucent by material byte, entities, hand, skybasic/skytextured, clouds/weather) then deferred* then composite* then final; dynamic rendering; reuses IrisPostExecutor pipeline/descriptor/sampler precedent and transient-CB splice + VkBarriers.
7. Frame integration: when active=iris, host final output blitted to engine mainRenderTarget at the I3.5 hook point (before HUD/upscale). Engine vanilla world still rendered but its color unused. Hand/HUD drawn by engine on top initially (hand not pack-shaded until a later step) — honest limitation.

## Fallback policy
Required unsupported program/feature => whole-pack UNSUPPORTED_PACK_FEATURES naming it. Pack-declared optional (iris.features.optional) or settings-disabled program => skip with named log, pack still runs. Runtime executor failure => disableForSession + native fallback.

## Testing / verification
L2 unit: attribute mapping, settings/profile evaluator, dimension selection, target formats, uniform layout, pass ordering, fallback naming. L3: offline SPIR-V compile of BSL gbuffers/composite. L4/L5: in-game BSL screenshot shows pack sky/tonemap/shadows; A/B vs native; log contract; measured fps cost. Then Complementary/Sildur's.

## Staging
(i) settings/dimensions/targets/uniforms model; (ii) geometry upload + gbuffers_terrain + sky; (iii) shadow; (iv) entities/water/hand; (v) deferred/composite/final + integration; (vi) BSL verification + cost.

## Out of scope (this marathon)
compute/SSBO/custom images/RT/Distant Horizons (I6+); pixel-parity with Iris/GL; device-recreation handling.