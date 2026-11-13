# Iris Raster-Core Pack Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real Iris shaderpacks (BSL first) launch and render a visible pack effect on Bedray's Vulkan backend via a capture-fed host forward renderer (gbuffers + shadow + deferred/composite/final).

**Architecture:** Pack-level host (no runtime Iris, no GL). Reuse RT geometry capture (DecodedSection / EntityGeometryFrame) as geometry source; upload raster vertex buffers in Iris attribute layout; host records shadow + gbuffers + deferred/composite/final into pack targets; final output becomes world image at the I3.5 hook point. Authored host shaders in Slang; pack GLSL translated 120→450 then compiled via slangc (fallback shaderc).

**Tech Stack:** Java 25, LWJGL Vulkan, renderpearl engine, Slang (slangc 2026.20) + shaderc fallback, JUnit (L2), Gradle :common:test.

**Spec:** docs/superpowers/specs/2026-11-12-iris-raster-core-design.md

## Global Constraints
- No runtime Iris, no OpenGL context, no Iris jar patching. Never mix native and iris host in one frame.
- No commits/pushes without owner permission (commit steps below are OPTIONAL; skip unless owner says commit). Never `git add -A`.
- Never run two Gradle tasks at once; XML test results are truth; targeted tests while iterating, full :common:test before deploy.
- Authored host shaders in Slang (.slang) compiled by SlangCompiler; pack GLSL compiled by SlangCompiler (GLSL frontend) with shaderc fallback when slangc absent.
- Unsupported REQUIRED feature/program => whole-pack UNSUPPORTED_PACK_FEATURES naming it. Pack-declared optional / settings-disabled => skip with named log. Runtime executor failure => disableForSession + native.
- Deploy jar AND natives to the agent test instance; verify in game (L4/L5) before claiming done.

---

### Task 1: SlangCompiler + Slang-authored fullscreen helpers
**Files:** Create `common/.../shaderhost/glsl/SlangCompiler.java`, `common/src/main/resources/shaders/iris/fullscreen.slang`, `common/src/main/resources/shaders/iris/blit.slang`; Test `common/src/test/java/.../shaderhost/glsl/SlangCompilerTest.java`.
**Interfaces:** Produces `SlangCompiler.locate(): Path|null` (checks `-Dbedray.slangc`, then PATH `slangc`); `SlangCompiler.compileSlang(String src, String stage, String entry, String label): ByteBuffer`; `SlangCompiler.compileGlsl450(String src, String stage, String entry, String label): ByteBuffer`; throws `SlangCompileException(errorLog)`. `SlangCompiler.available(): boolean`.
- [ ] Write failing test: locate() returns non-null on this host; compileSlang of a trivial vertex returns SPIR-V magic 0x07230203; compileGlsl450 of a #version 450 vertex returns SPIR-V; unavailable path throws.
- [ ] Run test, watch fail (class missing).
- [ ] Implement SlangCompiler: ProcessBuilder slangc `-profile spirv_1_5 -stage <stage> -entry <entry> -o <tmp.spv> <tmp.src>`; read bytes; on nonzero exit throw with stderr. locate(): property → PATH → null.
- [ ] Author fullscreen.slang (vertex: fullscreen triangle from SV_VertexID, out uv) and blit.slang (fragment: sample texture, out color) in Slang.
- [ ] Run test, watch pass. Full :common:test.
- [ ] Commit (only if owner permits).

### Task 2: IrisPackSettings (properties, profiles, enabled, dimension)
**Files:** Create `common/.../shaderhost/pack/IrisPackSettings.java`; Test `.../pack/IrisPackSettingsTest.java`.
**Interfaces:** Consumes PackSource/PackReport. Produces `IrisPackSettings.parse(PackSource): IrisPackSettings`; `option(String): String` (default value); `profile(): String`; `isProgramEnabled(String dim, String prog): boolean` (evaluates `program.<dim>/<prog>.enabled` boolean expr over options); `dimensionFolder(String worldDim): String` (world0/world-1/world1); `customTexture(String sampler): String|null`; `colortexFormat(int): String`; `defineMacros(): Map<String,String>`.
- [ ] Failing test with a synthetic shaders.properties: profile.DEFAULT, option defaults, `program.world0/shadow.enabled=SHADOW`, dimension mapping, texture.noise.
- [ ] Run fail; implement parser (line-based, `key=value`, profile.* expansion, simple boolean expr evaluator over option truthiness + `!`/`&&`/`||`); run pass; full suite.
- [ ] Commit (optional).

### Task 3: IrisTargets (formats, ping-pong, clear, resize)
**Files:** Create `common/.../shaderhost/iris/IrisTargets.java`; Test `.../iris/IrisTargetsTest.java`.
**Interfaces:** Consumes IrisPackSettings + device handles. Produces `IrisTargets.ensure(VulkanDevice, int w, int h, IrisPackSettings): IrisTargets`; `colorView(int i): long`; `depthView(int i): long`; `shadowDepthView(int i): long`; `shadowColorView(int i): long`; `pingPongSwap(int i)`; `close()`. Formats from settings (RGBA8/RGBA16F/R32F/D32). L2 test uses device=null => pure layout/format logic only.
- [ ] Failing test: format selection, count of colortex from settings, ping-pong index math, resize triggers recreate flag.
- [ ] Run fail; implement (image creation deferred to executor when device!=null; L2 covers layout); run pass; full suite.
- [ ] Commit (optional).

### Task 4: IrisUniforms full UBO + matrices + samplers + custom uniforms
**Files:** Modify `common/.../shaderhost/glsl/IrisUniforms.java`; Create `common/.../shaderhost/glsl/IrisUniformBlock.java`; Test `.../glsl/IrisUniformBlockTest.java`.
**Interfaces:** Produces `IrisUniformBlock.layout(Set<String> used): Layout` (std140 offsets for vec3/vec4/mat4/float/int + samplers separate); `Layout.bytes(): int`; `Layout.offset(String): int`; `IrisUniformBlock.write(ByteBuffer, Layout, UniformValues)`. `UniformValues` record carries cameraPosition, sunPosition, moonPosition, shadowLightPosition, worldTime, rainStrength, frameTimeCounter, frameCounter, near, far, viewWidth/Height, matrices. Custom `uniform.*` expressions: `IrisPackSettings.uniformExpr(name)`; evaluator supports clamp/abs/sin/cos/min/max +,-,*,/ and option refs; unsupported fn => named unsupported.
- [ ] Failing test: std140 offsets (vec3 padded to 16, mat4=64), sampler binding assignment, custom expr `clamp(1.0-abs(...))` evaluates.
- [ ] Run fail; implement; run pass; full suite.
- [ ] Commit (optional).

### Task 5: IrisGeometrySource (attribute mapping + upload + cache)
**Files:** Create `common/.../shaderhost/iris/IrisGeometrySource.java`; Test `.../iris/IrisGeometrySourceTest.java`.
**Interfaces:** Consumes SectionGeometryRegistry/DecodedSection, EntityGeometryFrame, RtMemory. Produces `IrisGeometrySource.buildTerrain(DecodedSection, int pass): Mesh` where `Mesh(float[] vertices, int[] indices, int strideFloats)`; vertex layout = pos3, color4, uv0_2, uv1_2, normal3, midBlock3 (stride 17); `buildEntities(EntityGeometryFrame): Mesh`; GPU `upload(Mesh): GpuMesh(handle, indexHandle, count)` via RtMemory.createBuffer; cache by section key+registry.version().
- [ ] Failing test: attribute mapping from a synthetic DecodedSection (positions+origin, color ABGR→rgba, light→uv1, normal, midBlock), index winding, stride.
- [ ] Run fail; implement CPU mapping (GPU upload guarded device!=null); run pass; full suite.
- [ ] Commit (optional).

### Task 6: Translator vertex attributes + gbuffers discovery/ordering
**Files:** Modify `common/.../shaderhost/glsl/IrisGlslTranslator.java` (accept Iris vertex attributes, map to layout locations), `common/.../shaderhost/iris/IrisPassGraph.java` (discover gbuffers/shadow/deferred + order, validate against settings-enabled set); Test extend `IrisPassGraphTest`, `IrisGlslTranslatorTest`.
**Interfaces:** Produces `IrisPassGraph.build(src, report, settings)` returning passes incl. GBUFFERS/SHADOW/DEFERRED families ordered shadow→gbuffers→deferred→composite→final; `Pass.family()`; attribute location table `IrisAttributes.LOCATIONS` (vaPosition=0, vaColor=1, vaUV0=2, vaUV1=3, vaNormal=4, at_midBlock=5).
- [ ] Failing test: BSL-like report with gbuffers+shadow+composite yields ordered graph; disabled program excluded; unsupported required attribute named.
- [ ] Run fail; implement; run pass; full suite.
- [ ] Commit (optional).

### Task 7: IrisForwardExecutor (shadow + gbuffers + chain recording)
**Files:** Create `common/.../shaderhost/iris/IrisForwardExecutor.java`; Test `.../iris/IrisForwardExecutorTest.java` (L2 layout) + L3 compile test `IrisBslCompileTest.java`.
**Interfaces:** Consumes IrisPassGraph, IrisTargets, IrisUniformBlock, IrisGeometrySource, SlangCompiler, EngineVk, VkBarriers, WorldTargetManager.extract*. Produces `record(VkCommandBuffer, FrameInputs): boolean` recording shadow pass then gbuffers draws (terrain solid/cutout, water, entities, hand, sky) then deferred/composite/final; `FrameInputs` carries meshes, camera/shadow matrices, uniform values, engine color/depth views, w/h. Reuses IrisPostExecutor pipeline/descriptor/sampler creation pattern; dynamic rendering; transient-CB splice + fullMemoryBarrier + RtFailure.check.
- [ ] Failing L2 test: pass ordering + binding map + push/UBO range; device=null returns early.
- [ ] L3 test: compile BSL gbuffers_terrain + composite via translator+SlangCompiler offline (skip if slangc absent).
- [ ] Run fail; implement; run pass; full suite.
- [ ] Commit (optional).

### Task 8: Frame integration + gate + BSL in-game verification
**Files:** Modify `common/.../rt/sodium/RtGeometryGate.java` + `RtCaptureControl` (enable capture when iris active), `common/.../shaderhost/iris/IrisFrameHook.java` (drive forward executor, blit final to mainRenderTarget), `MixinGameRenderer` (unchanged hook point). Deploy jar+natives; run BSL autotest; A/B vs native; measure fps.
- [ ] Gate test: iris active enables capture with RT off.
- [ ] In-game: BSL launches, `[VKB-IRIS] recorded=true`, screenshot shows BSL sky/tonemap/shadows; A/B diff>0; no crash; honest log for skipped optional.
- [ ] Report numbers + screenshots; pause for owner review.
- [ ] Commit (optional).