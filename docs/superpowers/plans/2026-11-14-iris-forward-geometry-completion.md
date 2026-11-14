# Iris forward-path geometry completion — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the Iris forward host run reliably (no self-disable on warmup) and draw a complete BSL world frame by completing the geometry feed (sky/hand/weather).

**Architecture:** Keep the current forward path + frame ownership (correct). Add a tri-state render outcome so empty-geometry frames skip gracefully; add missing geometry sources; refine the coherence gate to completeness-by-construction.

**Tech Stack:** Java 25, LWJGL Vulkan, JUnit 5, Gradle (neoforgeBedrayDistribution), FreesmLauncher autotest.

**Spec:** docs/superpowers/specs/2026-11-14-iris-forward-geometry-completion-design.md

## Global Constraints

- One Gradle task: `neoforgeBedrayDistribution` with `-PbedrayCmake -PbedrayVulkanHeaders -PslangDir`. Never two Gradle tasks at once. XML test results are truth.
- Re-verify HEAD + mods right before build/deploy. Backup before deploy; matched ABI2 jar+core.dll. No ABI2/ABI3 mixing.
- main PR-protected: branch → PR → `gh pr merge --merge`. No force-push/reset --hard/checkout--; no `git add -A`.
- Report Russian; code/comments English. No Iris jar patching, GL mixins, OpenGL backend, Vitrail port. Unsupported features refused by name. Never mix native+iris in one frame. No feature claimed working without L4 (owner play-test).

---

## Phase 1 — forward-path reliability (foundation)

### Task 1: tri-state render outcome + graceful skip

**Files:**
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisRenderBridge.java` (render/renderForward)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisFrameHook.java:97-115` (outcome switch)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/IrisShaderHost.java` (add setActiveFullGraphForTesting)
- Test: `common/src/test/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisRenderBridgeTest.java`

**Interfaces:**
- Produces: `IrisRenderBridge.RenderOutcome { RECORDED, SKIPPED, FAILED }`; `public static RenderOutcome renderWithOutcome(...)` (same params as `render`); `render(...)` stays boolean = `renderWithOutcome(...) == RECORDED`.
- Produces: `IrisShaderHost.setActiveFullGraphForTesting(IrisPassGraph.FullResult)`.
- Consumes: `IrisForwardExecutor.frameIsCoherent(List<FullPass>, Set<String>)` (unchanged), `IrisForwardExecutor.record(cmd, in)` boolean (unchanged).

- [ ] **Step 1: Write the failing test** — add to IrisRenderBridgeTest a test that builds a full graph (shadow+gbuffers_terrain+composite+final), sets it on a READY host via setActiveFullGraphForTesting, injects a test forward executor, and asserts `renderWithOutcome(...) == SKIPPED` with no geometry (L2 has empty registry), while `render(...) == false`. Initialize camera.pos/viewRotationMatrix/projectionMatrix if null.
- [ ] **Step 2: Run test, verify it fails** (RenderOutcome / renderWithOutcome / setActiveFullGraphForTesting do not exist → compile failure).
- [ ] **Step 3: Implement** — add RenderOutcome enum; rename current `render` body to `renderWithOutcome` returning RenderOutcome (guards → FAILED; post success → RECORDED; post fail → FAILED); `render` wraps it. `renderForward` returns RenderOutcome: after buildForwardInputs, `if (!frameIsCoherent(full.passes(), in.meshes().keySet())) return SKIPPED;` else `record ? RECORDED : FAILED`; catch → FAILED. Add setActiveFullGraphForTesting + setForwardExecutorForTesting.
- [ ] **Step 4: Run test, verify it passes.**
- [ ] **Step 5: Wire renderWorldPost** — call renderWithOutcome; switch: RECORDED → barrier+end+execute, reason=null; SKIPPED → recorded=false, reason="no_geometry", NO disableForSession; FAILED → disableForSession. Keep the per-frame log line.
- [ ] **Step 6: Run full :common:test (XML), verify green.**
- [ ] **Step 7: Commit** (branch, not main).

### Task 2: in-game verification of the existing forward path

- [ ] Build neoforgeBedrayDistribution from verified HEAD; deploy matched jar+dll to геймер (backup).
- [ ] Inject JVM args via instance.cfg (backup): `-Dvkbridge.autotest=iris -Dvkrtx.shader_host=iris -Dvkrtx.shaderhost.pack=BSL_v10.1.8.zip` (NO bedray.iris.post). Set bedray.toml shader_host="iris".
- [ ] Launch `freesmlauncher.exe --launch геймер`; wait for autotest exit; read screenshots + latest.log.
- [ ] Evidence: `forward executor: N pipelines`, `targets allocated`, `recorded=true`, `sections=N quads=Q`, no `disableForSession`/`DEVICE_LOST`. Restore instance.cfg.
- [ ] Report honestly (L4 owner-gated). If forward path still fails, systematic-debugging Phase 1 before Phase 2.

## Phases 2–4 (detailed after Phase 1 verification — empirical, in-game iterated)

- **Phase 2 sky:** `IrisGeometrySource.buildSky` camera-centered dome keyed "sky"; wire into IrisFrameGeometry.build; verify screenshots.
- **Phase 3 hand:** extend hand-pass capture to a view-space hand mesh keyed "hand"; `IrisGeometrySource.buildHand`; hand projection; verify.
- **Phase 4 weather + gate:** "weather" source; completeness-by-construction (each pass draws-or-skips, final always runs when geometry present); verify.

Each phase: TDD (CPU-side mesh builders are unit-testable), build, deploy, autotest screenshots, honest report. PR to main after verified increments.