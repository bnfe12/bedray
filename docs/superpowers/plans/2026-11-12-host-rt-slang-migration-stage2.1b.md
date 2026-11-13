# Host RT Slang Migration — Stage 2.1b (Standalone Post Shaders: iris_depth, rt_particles_composite, rt_composite) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the three standalone (no `#include`, no `buffer_reference`, no `inverse()`) plain compute post shaders — `iris_depth`, `rt_particles_composite`, `rt_composite` — to Slang Tier 1, each gated by a frozen GLSL baseline + byte-identical A/B under the production ABI, then wire each production loader to the build-time `.spv` and delete the legacy GLSL.

**Architecture:** Reuse the Stage 2.1a `PostShaderProbe` offscreen compute harness unchanged. Extend `PostBaselineRunner` with three new dispatch helpers (shared by the baseline runner and the A/B tests) + deterministic synthetic inputs that exercise every branch. The GLSL reference is compiled with shaderc exactly as the production loader does; the Slang candidate is the build-time `compileSlangShaders` `.spv`. Both run under the IDENTICAL production ABI (bindings, push-constant byte layout, dispatch dims). After each A/B passes, the loader switches to a shared `RtShaderCompiler.loadPrecompiled(...)` helper, the GLSL is copied byte-identical into test resources and deleted from main, and the two `RtShaderCompiler.warmupVariants()` entries that reference the deleted GLSL are removed.

**Tech Stack:** Java 25, LWJGL 3.3.3 (Vulkan 1.2, shaderc), JOML, JUnit 5, Gradle 9.5.1, slangc v2026.22 (`-target spirv -profile spirv_1_4 -emit-spirv-directly -entry main`). GPU: NVIDIA RTX 5060 Ti, Vulkan 1.4.357.

**Spec:** `docs/superpowers/specs/2026-11-12-host-rt-slang-migration-design.md` (approved; owner delegated autonomous execution).

## Global Constraints

Every task implicitly includes these (verbatim from the spec / work order):

- Autonomy granted: no per-step human approval. **NO git commit / push / deploy without explicit owner permission** — every "Commit" step is replaced by a **Verification checkpoint**.
- No `git add -A` (multiple repos in the environment). Do not revert others' changes. Do not mix ABI lines, do not touch the public export `C:\Dev\Sky\scratch\bedray-public-export-20261020`, do not touch the installed instance.
- **measure-before-shader:** every shader change gated by a frozen GLSL baseline + byte-identical A/B. The baseline is captured BEFORE the port and never regenerated to hide a delta. The legacy `.comp` stays byte-identical until its Slang port passes A/B; only then is it removed from the runtime loader.
- Raster fallback sacred (no black/gray cubes). Third-party shaders (FSR/NIS/NRD/DLSS/Sodium/Iris/vanilla) stay as-is; interop via SPIR-V. NRD/DLSS G-buffer formats + bindings preserved EXACTLY.
- **ABI preservation:** descriptor bindings unchanged per shader; push constants unchanged (byte layout); entry `main`, `local_size 8×8×1`; matrices column-major bytes unchanged, GLSL `M*v`→Slang `mul(M,v)`, `v*M`→`mul(v,M)`, gated by A/B; GLSL `mat3(col0,col1,col2)` (columns) → Slang `transpose(float3x3(col0,col1,col2))` (Slang's `float3x3(a,b,c)` builds ROWS), gated by A/B; `mix`→`lerp`.
- OMM dropped (unsupported in Slang, optional).
- Slang modules under `common/src/main/resources/shaders/slang/`; generated `.spv` gitignored; legacy GLSL removed from runtime only after its Slang port passes A/B.
- English code/comments; Russian chat reports. Never two Gradle tasks concurrently. Buffered stdout; XML is truth for tests (a background exit code lies both ways). Canonical URLs only (`github.com/shader-slang/slang`), no proxy, re-verify each URL.
- Tier 1 capability ceiling (pinned Stage 2.0, raw `OpCapability` operands): `{1, 11, 55, 56, 61, 64, 4472, 5347}`. Stage 2.1a established that `49` (`StorageImageExtendedFormats`) is a legitimate Tier 1 cap for non-core-mandatory storage formats (`rg16f`); the post-shader ceiling is therefore `{1,11,55,56,61,64,4472,5347,49}`. The three 2.1b shaders use only core-mandatory storage formats (`r32f`, `rgba8`), so their measured caps are expected to be `[1]`; the audit fails on anything outside the ceiling.

## Decomposition of Stage 2.1 (Scope Check — revised)

Stage 2.1 ports 8 plain compute shaders + shared non-RT modules. The Stage 2.1a plan listed `velocity_reproject` under 2.1b. **Reading the source during 2.1b planning changed that:** `velocity_reproject.comp` uses `inverse(mat4)` (line 115) whose bit-exactness against shaderc is a feasibility risk, plus three `buffer_reference` types (`MotionBuf`/`MaskBuf`/`DepthBuf`) addressed by `uvec2` and a 368-byte std140 UBO. It is grouped with `rt_pack_nrd` (also `buffer_reference`) in a dedicated later stage with an `inverse()`/`buffer_reference` feasibility spike. The revised decomposition:

- **Stage 2.1a (DONE):** `PostShaderProbe` harness + `fg_prepare` pilot. Frozen `fg_prepare glsl_sha256=a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39`.
- **Stage 2.1b (THIS PLAN):** the three clean standalone shaders — `iris_depth` (128-byte PC, `mat3`-from-columns + `mat4` ABI), `rt_particles_composite` (in-place RGBA8, 16-byte PC), `rt_composite` (80-byte PC, `mat4`, 4 CIS + 1 storage). No shared modules, no `buffer_reference`, no `inverse()`.
- **Stage 2.1c (later plan):** shared non-RT Slang modules (`rt_tonemap.slang`; the non-RT core subset of `rt_common`) + the module-dependent shaders `rt_bloom` (×4 `BLOOM_STAGE`) and `rt_atrous` (core subset).
- **Stage 2.1d (later plan):** `velocity_reproject` + `rt_pack_nrd` (×2 `VKB_PASS_PACK`/`COMPOSE`) — `buffer_reference`/`uvec2` device addresses, `inverse()` feasibility spike, NRD binding-5 format preserved.

`rt_noise.glsl` / `rt_caustics.glsl` remain OUT of Stage 2.1 (only the Stage 2.2 ray-query shaders use them).

## File Structure

**Create:**
- `common/src/main/resources/shaders/slang/iris_depth.slang`
- `common/src/main/resources/shaders/slang/rt_particles_composite.slang`
- `common/src/main/resources/shaders/slang/rt_composite.slang`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/IrisDepthSlangCapabilityTest.java`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/ParticlesSlangCapabilityTest.java`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/CompositeSlangCapabilityTest.java`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/IrisDepthAbTest.java`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/ParticlesAbTest.java`
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/CompositeAbTest.java`
- `common/src/test/resources/shaders/iris_depth.comp` (frozen byte-identical copy, created at wire time)
- `common/src/test/resources/shaders/rt_particles_composite.comp` (frozen copy)
- `common/src/test/resources/shaders/rt_composite.comp` (frozen copy)

**Modify:**
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostBaselineRunner.java` — add 3 dispatch helpers + synthetic generators + measure methods; extend `main` to emit all 4 rows.
- `common/build.gradle` — add 3 variants to `slangRtVariants`; widen `postProbe` filter to include the 3 new A/B tests.
- `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java` — add `loadPrecompiled(String)`; remove the `rt_composite` + `rt_particles_composite` warmup entries.
- `common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisDepthConverter.java` — load `.spv`.
- `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtParticleComposite.java` — load `.spv`.
- `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtCompositePipeline.java` — load `.spv`.
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/world/RtEntityCompositeTest.java` — `testShadercCompilation` → validate the precompiled `.spv`.
- `docs/POST_BASELINE.md` — regenerated with 4 frozen rows (fg_prepare hash MUST stay `a831a6ce…`); execution record re-appended.

**Delete (only after each A/B passes):**
- `common/src/main/resources/shaders/iris_depth.comp`
- `common/src/main/resources/shaders/rt_particles_composite.comp`
- `common/src/main/resources/shaders/rt_composite.comp`

**Leave untouched:** `RtIceGlassOpticsTest.java:114` is a comment + pure-Java simulation (no GLSL compile); `ShimTestRunner.testRtShaderSourcesCompile` compiles only rt_rays/rt_path/rt_atrous (not 2.1b); `testVelocityShaderCompiles` is velocity (2.1d).

---

### Task 1: Extend PostBaselineRunner + freeze the three GLSL baselines

**Files:**
- Modify: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostBaselineRunner.java`
- Generate: `docs/POST_BASELINE.md` (regenerated with 4 rows)

**Interfaces:**
- Consumes: `PostShaderProbe` (Stage 2.1a, unchanged); production ABIs —
  - `iris_depth` (IrisDepthConverter): binding 0 CIS `rasterDepth`, binding 1 STORAGE `irisDepth` (r32f); PC 128 B = `mat4 inverseProjection`(0–63) + `vec4 inverseView0`(64–79, xyz=inverseModelView column 0, w=encoding) + `vec4 inverseView1`(80–95, xyz=column 1) + `vec4 inverseView2`(96–111, xyz=column 2) + `vec4 parameters`(112–127 = near,far,scale,bias); offsets 92/108 are zero padding; dispatch `((w+7)/8,(h+7)/8,1)`.
  - `rt_particles_composite` (RtParticleComposite): binding 0 CIS `particleLayer` (rgba8), binding 1 STORAGE `rtImage` (rgba8, read+write in place); PC 16 B = `uvec2 size`(0–7) + `float strength`(8–11) + `float pad`(12–15); dispatch `((w+7)/8,(h+7)/8,1)`.
  - `rt_composite` (RtCompositePipeline): bindings 0–3 CIS `rasterColor`(rgba8)/`rasterDepth`(r32f)/`rtColor`(rgba8)/`rtDepth`(r32f), binding 4 STORAGE `outComposite`(rgba8); PC 80 B = `mat4 invViewProj`(0–63, column-major) + `uvec2 size`(64–71) + `float depthThreshold`(72–75) + `float pad`(76–79); dispatch `((w+7)/8,(h+7)/8,1)`.
- Produces (later tasks rely on these exact package-private statics in `PostBaselineRunner`):
  - `byte[] dispatchIrisDepth(PostShaderProbe probe, ByteBuffer spirv)` — returns the concatenation of the 4 encoding outputs (each W*H*4 r32f bytes).
  - `byte[] dispatchParticles(PostShaderProbe probe, ByteBuffer spirv)` — returns the in-place rtImage bytes (W*H*4).
  - `byte[] dispatchComposite(PostShaderProbe probe, ByteBuffer spirv)` — returns outComposite bytes (W*H*4).
  - `docs/POST_BASELINE.md` machine lines `iris_depth glsl_sha256=<64hex> size=70x50`, `rt_particles_composite glsl_sha256=<64hex> size=70x50`, `rt_composite glsl_sha256=<64hex> size=70x50` (plus the existing `fg_prepare` line, unchanged).

**Synthetic inputs (deterministic, W=70 H=50, exercise every branch + non-multiple-of-8 bounds):**
- `iris_depth`: depth image with NaN / +Inf / 0 / 1 / negative / normal values; 4 dispatches (encoding 0,1,2,3) with a fixed invertible `inverseProjection` and a fixed rotation `inverseView` (encoding 3 exercises the `mat3`-from-columns path). near=0.05, far=1000, scale=1, bias=0.
- `rt_particles_composite`: layer regions untouched(0,0,0,0) / opaque(a=255) / translucent(a=128) / low(a=32); rtImage initialized to a known gradient; strength=0.75 (exercises the strength multiply + the untouched early-out).
- `rt_composite`: region-mapped cases sky / entity-in-front / terrain-match / occluded / entity-vs-sky / hand(<1.2 m) / near-terrain, with matching reverse-Z raster depth and ray distance; translucent raster alpha; depthThreshold=0.08.

- [ ] **Step 1: Add JOML imports + the synthetic generators + dispatch helpers to `PostBaselineRunner.java`**

Add to the imports (after the existing `import java.nio.ByteOrder;`):
```java
import org.joml.Matrix4f;
import org.joml.Vector4f;
```

Add these package-private statics inside `PostBaselineRunner` (after the existing `mvBytes()` / `floatToHalf(...)` block, before `compileGlsl`). They are shared by the baseline runner and the A/B tests:

```java
    // ---- Stage 2.1b: iris_depth ----

    static Matrix4f irisInverseProjection() {
        return new Matrix4f().perspective((float) Math.toRadians(90.0), 1.0f, 0.05f, 1000.0f, true).invert();
    }

    static Matrix4f irisInverseView() {
        return new Matrix4f().rotateY(0.3f).rotateX(0.2f);
    }

    static byte[] irisDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                int k = (x + y * W) % 10;
                if (k == 0) d = Float.NaN;
                else if (k == 1) d = Float.POSITIVE_INFINITY;
                else if (k == 2) d = 0.0f;
                else if (k == 3) d = 1.0f;
                else if (k == 4) d = -0.5f;
                else d = (float) (x + 1) / (float) (W + 1);
                b.putFloat(d);
            }
        }
        return b.array();
    }

    /** Mirrors IrisDepthConverter.record(): mat4 at 0, inverseView columns at 64/80/96, encoding at 76, parameters at 112. */
    static byte[] irisPc(int encoding, Matrix4f invProj, Matrix4f invView, float near, float far, float scale, float bias) {
        ByteBuffer b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        invProj.get(0, b);
        b.putFloat(64, invView.m00()).putFloat(68, invView.m01()).putFloat(72, invView.m02());
        b.putFloat(76, (float) encoding);
        b.putFloat(80, invView.m10()).putFloat(84, invView.m11()).putFloat(88, invView.m12());
        b.putFloat(96, invView.m20()).putFloat(100, invView.m21()).putFloat(104, invView.m22());
        b.putFloat(112, near).putFloat(116, far).putFloat(120, scale).putFloat(124, bias);
        return b.array();
    }

    static byte[] dispatchIrisDepth(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img depthIn = mem.createImage("iris_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img out = mem.createImage("iris_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(depthIn, irisDepthBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            Matrix4f invProj = irisInverseProjection(), invView = irisInverseView();
            byte[] all = new byte[4 * W * H * 4];
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 128)) {
                pipe.image(0, depthIn.view, true);
                pipe.image(1, out.view, false);
                for (int enc = 0; enc < 4; enc++) {
                    byte[] pc = irisPc(enc, invProj, invView, 0.05f, 1000.0f, 1.0f, 0.0f);
                    final int e = enc;
                    probe.submit(cmd -> {
                        if (e == 0) probe.toGeneral(cmd, out.image, VK_IMAGE_LAYOUT_UNDEFINED);
                        pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                    });
                    byte[] back = probe.readback(out, 4);
                    System.arraycopy(back, 0, all, enc * W * H * 4, back.length);
                }
            }
            return all;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(out);
        }
    }

    // ---- Stage 2.1b: rt_particles_composite ----

    static byte[] particleLayerBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                int k = (x + y * W) % 4;
                if (k == 0) { a[i] = 0; a[i + 1] = 0; a[i + 2] = 0; a[i + 3] = 0; }
                else {
                    a[i] = (byte) (x * 3); a[i + 1] = (byte) (y * 5); a[i + 2] = (byte) ((x + y) * 2);
                    a[i + 3] = (byte) (k == 1 ? 255 : k == 2 ? 128 : 32);
                }
            }
        }
        return a;
    }

    static byte[] particleRtInitialBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (255 - x * 2); a[i + 1] = (byte) (y * 4); a[i + 2] = (byte) ((x * y) % 256); a[i + 3] = (byte) 255;
            }
        }
        return a;
    }

    static byte[] particlePc(float strength) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(W).putInt(H).putFloat(strength).putFloat(0f).array();
    }

    static byte[] dispatchParticles(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img layer = mem.createImage("p_layer", W, H, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rt = mem.createImage("p_rt", W, H, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        try {
            probe.upload(layer, particleLayerBytes(), 4);
            probe.upload(rt, particleRtInitialBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = particlePc(0.75f);
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 16)) {
                pipe.image(0, layer.view, true);
                pipe.image(1, rt.view, false);
                probe.submit(cmd -> pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1));
                return probe.readback(rt, 4);
            }
        } finally {
            mem.destroy(layer);
            mem.destroy(rt);
        }
    }

    // ---- Stage 2.1b: rt_composite ----

    static final Matrix4f COMPOSITE_PROJ =
        new Matrix4f().perspective((float) Math.toRadians(90.0), 1.0f, 1000.0f, 0.05f, true);

    static Matrix4f compositeInvViewProj() {
        return new Matrix4f(COMPOSITE_PROJ).invert();
    }

    static float reverseZ(float dist) {
        Vector4f c = COMPOSITE_PROJ.transform(new Vector4f(0f, 0f, -dist, 1f), new Vector4f());
        return c.z / c.w;
    }

    /** Region-mapped case index, shared by the raster-depth and ray-depth generators so they stay consistent. */
    static int compositeCase(int x, int y) {
        return (x / 10 + (y / 10) * 7) % 7;
    }

    static byte[] compositeRasterDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                switch (compositeCase(x, y)) {
                    case 0: d = 0.0f; break;            // sky
                    case 1: d = reverseZ(5.0f); break;  // entity in front of wall (ray 10)
                    case 2: d = reverseZ(12.0f); break; // terrain match (ray 12)
                    case 3: d = reverseZ(15.0f); break; // occluded entity (ray 8)
                    case 4: d = reverseZ(20.0f); break; // entity against sky (ray 1e6)
                    case 5: d = reverseZ(0.6f); break;  // hand (ray 1)
                    default: d = reverseZ(11.9f); break;// near terrain (ray 12)
                }
                b.putFloat(d);
            }
        }
        return b.array();
    }

    static byte[] compositeRtDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                switch (compositeCase(x, y)) {
                    case 0: d = 1.0e6f; break;
                    case 1: d = 10.0f; break;
                    case 2: d = 12.0f; break;
                    case 3: d = 8.0f; break;
                    case 4: d = 1.0e6f; break;
                    case 5: d = 1.0f; break;
                    default: d = 12.0f; break;
                }
                b.putFloat(d);
            }
        }
        return b.array();
    }

    static byte[] compositeRasterColorBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (x * 3); a[i + 1] = (byte) (y * 5); a[i + 2] = (byte) ((x + y) * 2);
                a[i + 3] = (byte) (compositeCase(x, y) % 2 == 0 ? 255 : 128); // opaque / translucent
            }
        }
        return a;
    }

    static byte[] compositeRtColorBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (255 - x * 2); a[i + 1] = (byte) (y * 4); a[i + 2] = (byte) ((x * y) % 256); a[i + 3] = (byte) 255;
            }
        }
        return a;
    }

    static byte[] compositePc() {
        ByteBuffer b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        compositeInvViewProj().get(0, b);
        b.putInt(64, W); b.putInt(68, H); b.putFloat(72, 0.08f); b.putFloat(76, 0f);
        return b.array();
    }

    static byte[] dispatchComposite(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img rc = mem.createImage("c_rc", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rd = mem.createImage("c_rd", W, H, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rtc = mem.createImage("c_rtc", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rtd = mem.createImage("c_rtd", W, H, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img out = mem.createImage("c_out", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(rc, compositeRasterColorBytes(), 4);
            probe.upload(rd, compositeRasterDepthBytes(), 4);
            probe.upload(rtc, compositeRtColorBytes(), 4);
            probe.upload(rtd, compositeRtDepthBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = compositePc();
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 80)) {
                pipe.image(0, rc.view, true); pipe.image(1, rd.view, true);
                pipe.image(2, rtc.view, true); pipe.image(3, rtd.view, true);
                pipe.image(4, out.view, false);
                probe.submit(cmd -> {
                    probe.toGeneral(cmd, out.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                });
                return probe.readback(out, 4);
            }
        } finally {
            mem.destroy(rc); mem.destroy(rd); mem.destroy(rtc); mem.destroy(rtd); mem.destroy(out);
        }
    }
```

- [ ] **Step 2: Add the three measure methods + extend `main`**

Add inside `PostBaselineRunner` (after `measureFgPrepare`):
```java
    private static String measureIrisDepth(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchIrisDepth(probe, compileGlsl("/shaders/iris_depth.comp", "iris_depth.comp"));
        String hash = sha256(out);
        machine.append("iris_depth glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }

    private static String measureParticles(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchParticles(probe, compileGlsl("/shaders/rt_particles_composite.comp", "rt_particles_composite.comp"));
        String hash = sha256(out);
        machine.append("rt_particles_composite glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }

    private static String measureComposite(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchComposite(probe, compileGlsl("/shaders/rt_composite.comp", "rt_composite.comp"));
        String hash = sha256(out);
        machine.append("rt_composite glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }
```

In `main`, replace the single-shader try block:
```java
        try (PostShaderProbe probe = new PostShaderProbe()) {
            String fgHash = measureFgPrepare(probe, machine);
            md.append("| fg_prepare | ").append(W).append('x').append(H).append(" | `").append(fgHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
        }
```
with:
```java
        try (PostShaderProbe probe = new PostShaderProbe()) {
            String fgHash = measureFgPrepare(probe, machine);
            md.append("| fg_prepare | ").append(W).append('x').append(H).append(" | `").append(fgHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String irisHash = measureIrisDepth(probe, machine);
            md.append("| iris_depth | ").append(W).append('x').append(H).append(" | `").append(irisHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String partHash = measureParticles(probe, machine);
            md.append("| rt_particles_composite | ").append(W).append('x').append(H).append(" | `").append(partHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String compHash = measureComposite(probe, machine);
            md.append("| rt_composite | ").append(W).append('x').append(H).append(" | `").append(compHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
        }
```

Also update the table header in `main` from `GLSL SHA-256 (depth+mv)` to `GLSL SHA-256 (output)` (the new shaders hash a single output image / concatenated encodings, not depth+mv). Change the line:
```java
        md.append("| shader | size | GLSL SHA-256 (depth+mv) | device |\n|---|---|---|---|\n");
```
to:
```java
        md.append("| shader | size | GLSL SHA-256 (output) | device |\n|---|---|---|---|\n");
```

Note: `main` regenerates the whole doc, so the Stage 2.1a execution record appended to `docs/POST_BASELINE.md` is overwritten. That is expected — Task 5 re-appends a combined execution record. The `fg_prepare` hash is recomputed from its byte-identical frozen test GLSL and MUST still equal `a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39`; Step 3 verifies this (a change means the GLSL drifted — STOP).

- [ ] **Step 3: Run the baseline (single Gradle task, buffered stdout)**

Run: `./gradlew :common:postBaseline --console=plain > build/postbaseline-2.1b.log 2>&1` (from the worktree root).
Expected: BUILD SUCCESSFUL; stdout `[BEDRAY-POST-BASELINE]` then FOUR machine lines (fg_prepare, iris_depth, rt_particles_composite, rt_composite). Verify in the log:
- `fg_prepare glsl_sha256=a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39` (UNCHANGED — if different, STOP: the frozen GLSL drifted).
- three new 64-hex hashes. Record all four in the task list / execution notes.
`docs/POST_BASELINE.md` now has a 4-row table + 4 machine lines.

- [ ] **Step 4: Freeze the references — record the GLSL source hashes**

Run (Git Bash): `sha256sum common/src/main/resources/shaders/iris_depth.comp common/src/main/resources/shaders/rt_particles_composite.comp common/src/main/resources/shaders/rt_composite.comp`. Record the three values. These are the freeze guards: each `.comp` MUST stay byte-identical until its A/B passes (Tasks 2–4).

- [ ] **Step 5: Verification checkpoint (NO commit)**

Confirm: `docs/POST_BASELINE.md` has 4 machine lines; fg_prepare hash unchanged; three new hashes + three source sha256 recorded. Do NOT git commit/push. The baselines are now frozen.

---

### Task 2: iris_depth.slang port + cap-audit + A/B + IrisDepthConverter wire

**Files:**
- Create: `common/src/main/resources/shaders/slang/iris_depth.slang`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/IrisDepthSlangCapabilityTest.java`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/IrisDepthAbTest.java`
- Modify: `common/build.gradle` (add `iris_depth` variant; widen `postProbe` filter)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java` (add `loadPrecompiled`)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisDepthConverter.java`
- Create (at wire time): `common/src/test/resources/shaders/iris_depth.comp`
- Delete (after A/B): `common/src/main/resources/shaders/iris_depth.comp`

**Interfaces:**
- Consumes: `PostBaselineRunner.dispatchIrisDepth/compileGlsl/sha256` (Task 1); frozen `iris_depth glsl_sha256=` (Task 1); `compileSlangShaders`.
- Produces: `iris_depth_v14.spv`; `RtShaderCompiler.loadPrecompiled(String resourcePath) → ByteBuffer` (reused by Tasks 3–4); `IrisDepthConverter` loading `/assets/bedray_rt/shaders/iris_depth_v14.spv`.

- [ ] **Step 1: Write `iris_depth.slang`**

```slang
// Slang Tier 1 port of iris_depth.comp (host reverse-Z raster depth -> Iris/OpenGL 0-near/1-far depth).
// ABI preserved: binding 0 sampler2D rasterDepth, binding 1 r32f storage irisDepth; push constant 128 bytes
// (mat4 inverseProjection + 3 vec4 inverseView columns with encoding in inverseView0.w + vec4 parameters);
// entry main, numthreads 8x8x1. GLSL mat3(col0,col1,col2) builds COLUMNS; Slang float3x3(a,b,c) builds ROWS,
// so the column form is transpose(float3x3(...)). Matrix bytes are column-major; GLSL M*v -> mul(M,v).
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    float4x4 inverseProjection;
    float4 inverseView0;
    float4 inverseView1;
    float4 inverseView2;
    float4 parameters;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> rasterDepth;
[[vk::binding(1, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> irisDepth;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 pixel = int2(id.xy);
    uint w, h;
    irisDepth.GetDimensions(w, h);
    int2 size = int2(int(w), int(h));
    if (pixel.x >= size.x || pixel.y >= size.y) {
        return;
    }
    float depth = rasterDepth.Load(int3(pixel, 0)).r;
    float converted = 1.0;
    int encoding = int(pc.inverseView0.w);
    if (!isnan(depth) && !isinf(depth)) {
        if (encoding == 0) {
            converted = clamp(depth * pc.parameters.z + pc.parameters.w, 0.0, 1.0);
        } else if (encoding == 1) {
            converted = clamp(depth, 0.0, 1.0);
        } else if (depth > 0.0) {
            float distance = depth;
            if (encoding == 3) {
                float2 ndc = (float2(pixel) + 0.5) / float2(size) * 2.0 - 1.0;
                float4 view = mul(pc.inverseProjection, float4(ndc, 0.0, 1.0));
                if (abs(view.w) > 1e-8) {
                    float3 ray = view.xyz / view.w;
                    float3x3 inverseView = transpose(float3x3(pc.inverseView0.xyz, pc.inverseView1.xyz, pc.inverseView2.xyz));
                    float worldLength = length(mul(inverseView, ray));
                    distance = worldLength > 1e-8 ? depth * abs(ray.z) / worldLength : 1e30;
                } else {
                    distance = 1e30;
                }
            }
            float near = pc.parameters.x;
            float far = pc.parameters.y;
            converted = clamp((far / (far - near)) * (1.0 - near / max(distance, near)), 0.0, 1.0);
        }
    }
    irisDepth[pixel] = converted;
}
```

- [ ] **Step 2: Add the `iris_depth` build variant**

In `common/build.gradle`, add to `slangRtVariants` (after the `fg_prepare` entry):
```gradle
    [name: 'iris_depth', src: 'iris_depth', defines: []],
```

- [ ] **Step 3: Compile the Slang shaders (single Gradle task)**

Run: `./gradlew :common:compileSlangShaders --console=plain > build/slang-iris.log 2>&1`.
Expected: BUILD SUCCESSFUL; `common/build/generated/shaders/assets/bedray_rt/shaders/iris_depth_v14.spv` exists. If slangc fails, read the redirected stderr in the GradleException and fix the Slang source — do NOT proceed with a stale/missing `.spv`.

- [ ] **Step 4: Write the cap-audit `IrisDepthSlangCapabilityTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cap audit of the iris_depth Slang port: SPIR-V 1.4 and NO capability outside the Tier 1 post-shader ceiling
 * (Stage 2.0 base + StorageImageExtendedFormats=49 from Stage 2.1a). iris_depth uses r32f storage (core mandatory),
 * no ray query / Int64 / device address / subgroup, so its caps are expected to be [1]. Runs in plain :common:test
 * (loads the .spv only, no shaderc).
 */
class IrisDepthSlangCapabilityTest {
    private static final Set<Integer> CEILING = Set.of(1, 11, 49, 55, 56, 61, 64, 4472, 5347);

    @Test void irisDepthIsSpirv14WithinCeiling() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/iris_depth_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
        Set<Integer> caps = caps(spirv);
        assertTrue(CEILING.containsAll(caps), "iris_depth pulled capabilities outside the Tier 1 ceiling: " + caps);
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] iris_depth caps=%s%n", caps);
    }

    static Set<Integer> caps(ByteBuffer spirv) {
        Set<Integer> caps = new TreeSet<>();
        int words = spirv.remaining() / 4;
        for (int word = 5; word < words;) {
            int instruction = spirv.getInt(word * 4);
            int count = instruction >>> 16;
            int opcode = instruction & 0xffff;
            assertTrue(count > 0, "Invalid SPIR-V instruction word count at word " + word);
            if (opcode == 17) caps.add(spirv.getInt((word + 1) * 4)); // OpCapability
            word += count;
        }
        return caps;
    }

    static ByteBuffer load(String path) {
        try (InputStream stream = IrisDepthSlangCapabilityTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = stream.readAllBytes();
            ByteBuffer copy = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            copy.put(bytes).flip();
            return copy.order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
    }
}
```

- [ ] **Step 5: Run the cap-audit (single Gradle task)**

Run: `./gradlew :common:test --tests "*IrisDepthSlangCapabilityTest" --console=plain > build/caps-iris.log 2>&1`.
Expected: PASS; stdout `[BEDRAY-POST-CAPS] iris_depth caps=[1]` (or another subset of the ceiling). XML truth: `common/build/test-results/test/TEST-*IrisDepthSlangCapabilityTest.xml` `failures="0" errors="0"`. Record the measured cap set.

- [ ] **Step 6: Write the A/B test `IrisDepthAbTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * measure-before-shader A/B for iris_depth: the Slang Tier 1 port must be byte-identical to the frozen GLSL
 * reference under the production ABI (2 image bindings, 128-byte PC, 4 depth encodings). Gated like rtProbe.
 */
class IrisDepthAbTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = PostBaselineRunner.dispatchIrisDepth(probe, PostBaselineRunner.compileGlsl("/shaders/iris_depth.comp", "iris_depth.comp"));
            byte[] slang = PostBaselineRunner.dispatchIrisDepth(probe, loadSpv("/assets/bedray_rt/shaders/iris_depth_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] iris_depth frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (iris_depth.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    private static String frozenGlslHash() throws Exception {
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("iris_depth glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No iris_depth glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = IrisDepthAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}
```

- [ ] **Step 7: Widen the `postProbe` filter**

In `common/build.gradle`, change the `postProbe` filter line:
```gradle
    filter { includeTestsMatching '*PostShaderProbeTest'; includeTestsMatching '*FgPrepareAbTest' }
```
to:
```gradle
    filter { includeTestsMatching '*PostShaderProbeTest'; includeTestsMatching '*FgPrepareAbTest'; includeTestsMatching '*IrisDepthAbTest' }
```

- [ ] **Step 8: Run the A/B (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-iris.log 2>&1`.
Expected: BUILD SUCCESSFUL; `IrisDepthAbTest.slangMatchesFrozenGlslBaseline` PASSED (plus the earlier probe/fg_prepare tests). Stdout `[BEDRAY-POST-AB] iris_depth frozen=<h> glsl=<h> slang=<h>` with all three EQUAL. XML truth: `common/build/test-results/postProbe/TEST-*IrisDepthAbTest.xml` `failures="0" errors="0"`.

If the Slang hash differs: STOP (systematic-debugging). Do NOT edit the baseline or the GLSL to force a match. The likely causes, in order: (a) the `mat3` column/row convention — confirm `transpose(float3x3(...))` is used; (b) the `float4x4 inverseProjection` push-constant matrix layout (column-major bytes) — if Slang interprets it row-major, `mul(pc.inverseProjection, v)` computes the transpose; investigate the layout qualifier rather than guessing; (c) `GetDimensions` vs `imageSize`. The frozen baseline and `iris_depth.comp` are sacred.

- [ ] **Step 9: Add the shared `loadPrecompiled` helper to `RtShaderCompiler.java`**

Add this public static method (after `compileResource(String, String, String)`, before `prewarmAsync`). `BufferUtils`, `IOException`, `InputStream`, `ByteBuffer` are already imported; `RtFailure` is in the same package:
```java
    /** Loads a build-time precompiled SPIR-V resource (Slang Tier 1) into a direct buffer. No runtime shaderc. */
    public static ByteBuffer loadPrecompiled(String resourcePath) {
        try (InputStream in = RtShaderCompiler.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RtFailure("shader_load", "precompiled SPIR-V not found: " + resourcePath + " (run :common:compileSlangShaders)");
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer direct = BufferUtils.createByteBuffer(bytes.length);
            direct.put(bytes);
            direct.flip();
            return direct;
        } catch (IOException e) {
            throw new RtFailure("shader_load", "cannot read " + resourcePath, e);
        }
    }
```

- [ ] **Step 10: Wire `IrisDepthConverter` to the `.spv` (only after Step 8 passes)**

In `IrisDepthConverter.java`, replace the source-load + compile block (lines 47–52):
```java
            String source;
            try(var stream=IrisDepthConverter.class.getResourceAsStream("/shaders/iris_depth.comp")) {
                if(stream==null)throw new IllegalStateException("Missing iris_depth.comp");
                source=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            }
            var bytecode=SpirvCompiler.compile(source,org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader,"iris_depth.comp");
```
with:
```java
            var bytecode=io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler.loadPrecompiled("/assets/bedray_rt/shaders/iris_depth_v14.spv");
```
Remove the now-unused `import java.nio.charset.StandardCharsets;` (line 11) — verify no other usage remains (it was only used at line 50). The `SpirvCompiler` import (line 5) becomes unused for this class; remove it only if no other reference remains in the file (there is none). Leave `SpirvCompiler.java` itself intact (Iris GLSL translation still uses it).

- [ ] **Step 11: Freeze the GLSL into test resources, then delete it from main**

Order matters — create the test copy BEFORE deleting main so the harness never loses its reference:
1. Copy `common/src/main/resources/shaders/iris_depth.comp` → `common/src/test/resources/shaders/iris_depth.comp` (byte-identical; `cp` in Git Bash). Verify `sha256sum` of the copy equals the Task 1 Step 4 recorded value.
2. Delete `common/src/main/resources/shaders/iris_depth.comp`.
3. The harness path `/shaders/iris_depth.comp` now resolves to the test resource (test classpath). No harness code change needed.

- [ ] **Step 12: Re-run the A/B after the move (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-iris-postmove.log 2>&1`.
Expected: still PASSED, identical iris_depth hashes (the test GLSL is byte-identical to the deleted main resource). This proves the freeze survived the move.

- [ ] **Step 13: Verification checkpoint (NO commit)**

Confirm: `iris_depth.slang` + `.spv` exist; cap-audit green; A/B byte-identical before AND after the move; `IrisDepthConverter` loads `.spv`; `iris_depth.comp` removed from main, frozen copy in test resources; `RtShaderCompiler.loadPrecompiled` added. Do NOT git commit/push.

---

### Task 3: rt_particles_composite.slang port + cap-audit + A/B + RtParticleComposite wire

**Files:**
- Create: `common/src/main/resources/shaders/slang/rt_particles_composite.slang`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/ParticlesSlangCapabilityTest.java`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/ParticlesAbTest.java`
- Modify: `common/build.gradle` (add variant; widen `postProbe` filter)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtParticleComposite.java`
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java` (remove the particles warmup entry)
- Create (at wire time): `common/src/test/resources/shaders/rt_particles_composite.comp`
- Delete (after A/B): `common/src/main/resources/shaders/rt_particles_composite.comp`

**Interfaces:**
- Consumes: `PostBaselineRunner.dispatchParticles/compileGlsl/sha256`; frozen `rt_particles_composite glsl_sha256=`; `RtShaderCompiler.loadPrecompiled` (Task 2).
- Produces: `rt_particles_composite_v14.spv`; `RtParticleComposite` loading `/assets/bedray_rt/shaders/rt_particles_composite_v14.spv`.

- [ ] **Step 1: Write `rt_particles_composite.slang`**

```slang
// Slang Tier 1 port of rt_particles_composite.comp: the vanilla particle layer (premultiplied colour + coverage)
// over the ray traced image, in place. out.rgb = layer.rgb*strength + rt.rgb*(1-layer.a); rt alpha kept.
// Untouched pixels (a<=0 and rgb==0) early-out with no read/write of the RT image.
// ABI preserved: binding 0 sampler2D particleLayer, binding 1 rgba8 storage rtImage (read+write);
// push constant uvec2 size + float strength + float pad (16 bytes); entry main, numthreads 8x8x1.
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    uint2 size;
    float strength;
    float pad;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> particleLayer;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> rtImage;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= int(pc.size.x) || p.y >= int(pc.size.y)) {
        return;
    }
    float4 layer = particleLayer.Load(int3(p, 0));
    if (layer.a <= 0.0 && dot(layer.rgb, float3(1.0)) <= 0.0) {
        return;
    }
    float4 rt = rtImage[p];
    float a = clamp(layer.a, 0.0, 1.0);
    float3 outRgb = layer.rgb * pc.strength + rt.rgb * (1.0 - a);
    rtImage[p] = float4(clamp(outRgb, 0.0, 1.0), rt.a);
}
```

- [ ] **Step 2: Add the build variant**

In `common/build.gradle` `slangRtVariants`, add:
```gradle
    [name: 'rt_particles_composite', src: 'rt_particles_composite', defines: []],
```

- [ ] **Step 3: Compile (single Gradle task)**

Run: `./gradlew :common:compileSlangShaders --console=plain > build/slang-particles.log 2>&1`.
Expected: BUILD SUCCESSFUL; `rt_particles_composite_v14.spv` exists. Fix any slangc error before proceeding.

- [ ] **Step 4: Write the cap-audit `ParticlesSlangCapabilityTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cap audit of the rt_particles_composite Slang port: SPIR-V 1.4, caps within the Tier 1 post-shader ceiling. */
class ParticlesSlangCapabilityTest {
    private static final Set<Integer> CEILING = Set.of(1, 11, 49, 55, 56, 61, 64, 4472, 5347);

    @Test void particlesIsSpirv14WithinCeiling() {
        ByteBuffer spirv = IrisDepthSlangCapabilityTest.load("/assets/bedray_rt/shaders/rt_particles_composite_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
        Set<Integer> caps = IrisDepthSlangCapabilityTest.caps(spirv);
        assertTrue(CEILING.containsAll(caps), "rt_particles_composite pulled capabilities outside the Tier 1 ceiling: " + caps);
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] rt_particles_composite caps=%s%n", caps);
    }
}
```

- [ ] **Step 5: Run the cap-audit (single Gradle task)**

Run: `./gradlew :common:test --tests "*ParticlesSlangCapabilityTest" --console=plain > build/caps-particles.log 2>&1`.
Expected: PASS; `[BEDRAY-POST-CAPS] rt_particles_composite caps=[1]`. XML `failures="0" errors="0"`. Record the cap set.

- [ ] **Step 6: Write the A/B test `ParticlesAbTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * measure-before-shader A/B for rt_particles_composite: byte-identical to the frozen GLSL reference under the
 * production ABI (2 image bindings, in-place rgba8, 16-byte PC). The untouched-pixel early-out must leave the
 * RT image unchanged. Gated like rtProbe.
 */
class ParticlesAbTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = PostBaselineRunner.dispatchParticles(probe, PostBaselineRunner.compileGlsl("/shaders/rt_particles_composite.comp", "rt_particles_composite.comp"));
            byte[] slang = PostBaselineRunner.dispatchParticles(probe, loadSpv("/assets/bedray_rt/shaders/rt_particles_composite_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] rt_particles_composite frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (rt_particles_composite.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    private static String frozenGlslHash() throws Exception {
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("rt_particles_composite glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No rt_particles_composite glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = ParticlesAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}
```

- [ ] **Step 7: Widen the `postProbe` filter**

Append `; includeTestsMatching '*ParticlesAbTest'` to the `postProbe` filter line (keep the existing entries).

- [ ] **Step 8: Run the A/B (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-particles.log 2>&1`.
Expected: PASSED; `[BEDRAY-POST-AB] rt_particles_composite frozen=<h> glsl=<h> slang=<h>` all EQUAL. XML `failures="0" errors="0"`.
If it differs: STOP (systematic-debugging). Likely causes: the in-place read (`rtImage[p]` must read the uploaded initial content), the early-out condition, or `lerp`/clamp ordering. Do NOT edit the baseline or GLSL.

- [ ] **Step 9: Wire `RtParticleComposite` (only after Step 8 passes)**

In `RtParticleComposite.java`:
- Change line 32: `static final String SHADER_PATH = "/shaders/rt_particles_composite.comp";` → `static final String SHADER_PATH = "/assets/bedray_rt/shaders/rt_particles_composite_v14.spv";`
- Change line 48: `ByteBuffer spirv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_particles_composite.comp");` → `ByteBuffer spirv = RtShaderCompiler.loadPrecompiled(SHADER_PATH);`
- Update the class javadoc (line 27) `{@code rt_particles_composite.comp}` → `{@code rt_particles_composite.slang}`.

In `RtShaderCompiler.java`, remove the particles warmup entry (line 140):
```java
            new ShaderVariant("/shaders/rt_particles_composite.comp", "rt_particles_composite.comp+warmup", "")
```
(and fix the trailing comma on the preceding `rt_pack_nrd ... compose` entry so the `List.of(...)` stays valid — the compose entry becomes the last element).

- [ ] **Step 10: Freeze GLSL into test resources, then delete from main**

1. Copy `common/src/main/resources/shaders/rt_particles_composite.comp` → `common/src/test/resources/shaders/rt_particles_composite.comp` (byte-identical; verify sha256 against Task 1 Step 4).
2. Delete `common/src/main/resources/shaders/rt_particles_composite.comp`.

- [ ] **Step 11: Re-run the A/B after the move (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-particles-postmove.log 2>&1`.
Expected: still PASSED, identical hashes.

- [ ] **Step 12: Verification checkpoint (NO commit)**

Confirm: port + `.spv` + cap-audit + A/B (before/after move) green; `RtParticleComposite` loads `.spv`; warmup entry removed; GLSL deleted from main, frozen test copy present. Do NOT git commit/push.

---

### Task 4: rt_composite.slang port + cap-audit + A/B + RtCompositePipeline wire + test update

**Files:**
- Create: `common/src/main/resources/shaders/slang/rt_composite.slang`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/CompositeSlangCapabilityTest.java`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/CompositeAbTest.java`
- Modify: `common/build.gradle` (add variant; widen `postProbe` filter)
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtCompositePipeline.java`
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java` (remove the composite warmup entry)
- Modify: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/world/RtEntityCompositeTest.java`
- Create (at wire time): `common/src/test/resources/shaders/rt_composite.comp`
- Delete (after A/B): `common/src/main/resources/shaders/rt_composite.comp`

**Interfaces:**
- Consumes: `PostBaselineRunner.dispatchComposite/compileGlsl/sha256`; frozen `rt_composite glsl_sha256=`; `RtShaderCompiler.loadPrecompiled`.
- Produces: `rt_composite_v14.spv`; `RtCompositePipeline` loading `/assets/bedray_rt/shaders/rt_composite_v14.spv`; `RtEntityCompositeTest.testPrecompiledSpirv` validating it.

- [ ] **Step 1: Write `rt_composite.slang`**

```slang
// Slang Tier 1 port of rt_composite.comp: screen-space depth composite of rasterized foreground entities over
// the ray-traced world. Reverse-Z raster depth (sky<=1e-6 -> RT sky); reconstruct camera-relative distance via
// invViewProj; adaptive threshold; entity alpha blend (mix->lerp); terrain replaced by RT.
// ABI preserved: bindings 0-3 sampler2D rasterColor/rasterDepth/rtColor/rtDepth, binding 4 rgba8 storage
// outComposite; push constant mat4 invViewProj + uvec2 size + float depthThreshold + float pad (80 bytes);
// entry main, numthreads 8x8x1. Matrix bytes column-major; GLSL M*v -> mul(M,v).
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    float4x4 invViewProj;
    uint2 size;
    float depthThreshold;
    float pad;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> rasterColor;
[[vk::binding(1, 0)]] Sampler2D<float4> rasterDepth;
[[vk::binding(2, 0)]] Sampler2D<float4> rtColor;
[[vk::binding(3, 0)]] Sampler2D<float4> rtDepth;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outComposite;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= int(pc.size.x) || p.y >= int(pc.size.y)) {
        return;
    }
    float2 uv = (float2(p) + 0.5) / float2(pc.size);
    float4 rColor = rasterColor.Load(int3(p, 0));
    float dRaster = rasterDepth.Load(int3(p, 0)).r;
    float4 rtCol = rtColor.Load(int3(p, 0));
    float dRay = rtDepth.Load(int3(p, 0)).r;

    if (dRaster <= 1.0e-6) {
        outComposite[p] = rtCol;
        return;
    }

    float4 clip = float4(uv * 2.0 - 1.0, dRaster, 1.0);
    float4 worldPosRel = mul(pc.invViewProj, clip);
    float distRaster = (abs(worldPosRel.w) > 1.0e-7) ? length(worldPosRel.xyz / worldPosRel.w) : 1.0e6;

    float thresh = max(pc.depthThreshold > 0.0 ? pc.depthThreshold : 0.20, dRay * 0.05);

    bool isEntity = false;
    if (dRay >= 1.0e5) {
        if (distRaster <= 256.0) {
            isEntity = true;
        }
    } else {
        if (distRaster < dRay - thresh) {
            isEntity = true;
        } else if (distRaster < 1.2 && distRaster < dRay - 0.05) {
            isEntity = true;
        }
    }

    if (isEntity && rColor.a > 0.0) {
        float a = clamp(rColor.a, 0.0, 1.0);
        float3 blended = lerp(rtCol.rgb, rColor.rgb, a);
        outComposite[p] = float4(blended, max(rtCol.a, a));
        return;
    }

    outComposite[p] = rtCol;
}
```

- [ ] **Step 2: Add the build variant**

In `common/build.gradle` `slangRtVariants`, add:
```gradle
    [name: 'rt_composite', src: 'rt_composite', defines: []],
```

- [ ] **Step 3: Compile (single Gradle task)**

Run: `./gradlew :common:compileSlangShaders --console=plain > build/slang-composite.log 2>&1`.
Expected: BUILD SUCCESSFUL; `rt_composite_v14.spv` exists.

- [ ] **Step 4: Write the cap-audit `CompositeSlangCapabilityTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cap audit of the rt_composite Slang port: SPIR-V 1.4, caps within the Tier 1 post-shader ceiling. */
class CompositeSlangCapabilityTest {
    private static final Set<Integer> CEILING = Set.of(1, 11, 49, 55, 56, 61, 64, 4472, 5347);

    @Test void compositeIsSpirv14WithinCeiling() {
        ByteBuffer spirv = IrisDepthSlangCapabilityTest.load("/assets/bedray_rt/shaders/rt_composite_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
        Set<Integer> caps = IrisDepthSlangCapabilityTest.caps(spirv);
        assertTrue(CEILING.containsAll(caps), "rt_composite pulled capabilities outside the Tier 1 ceiling: " + caps);
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] rt_composite caps=%s%n", caps);
    }
}
```

- [ ] **Step 5: Run the cap-audit (single Gradle task)**

Run: `./gradlew :common:test --tests "*CompositeSlangCapabilityTest" --console=plain > build/caps-composite.log 2>&1`.
Expected: PASS; `[BEDRAY-POST-CAPS] rt_composite caps=[1]`. XML `failures="0" errors="0"`. Record the cap set.

- [ ] **Step 6: Write the A/B test `CompositeAbTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * measure-before-shader A/B for rt_composite: byte-identical to the frozen GLSL reference under the production
 * ABI (4 CIS + 1 storage, 80-byte PC with a column-major mat4). Exercises sky / entity / terrain / hand /
 * translucent branches. Gated like rtProbe.
 */
class CompositeAbTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = PostBaselineRunner.dispatchComposite(probe, PostBaselineRunner.compileGlsl("/shaders/rt_composite.comp", "rt_composite.comp"));
            byte[] slang = PostBaselineRunner.dispatchComposite(probe, loadSpv("/assets/bedray_rt/shaders/rt_composite_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] rt_composite frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (rt_composite.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    private static String frozenGlslHash() throws Exception {
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("rt_composite glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No rt_composite glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = CompositeAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}
```

- [ ] **Step 7: Widen the `postProbe` filter**

Append `; includeTestsMatching '*CompositeAbTest'` to the `postProbe` filter line (keep the existing entries).

- [ ] **Step 8: Run the A/B (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-composite.log 2>&1`.
Expected: PASSED; `[BEDRAY-POST-AB] rt_composite frozen=<h> glsl=<h> slang=<h>` all EQUAL. XML `failures="0" errors="0"`.
If it differs: STOP (systematic-debugging). Likely causes: the `float4x4 invViewProj` push-constant matrix layout (column-major), `mul` order, or `lerp` argument order (`lerp(rtCol.rgb, rColor.rgb, a)` == GLSL `mix(rtCol.rgb, rColor.rgb, a)`). Do NOT edit the baseline or GLSL.

- [ ] **Step 9: Wire `RtCompositePipeline` + update `RtEntityCompositeTest` (only after Step 8 passes)**

In `RtCompositePipeline.java`:
- Change line 29: `public static final String SHADER_PATH = "/shaders/rt_composite.comp";` → `public static final String SHADER_PATH = "/assets/bedray_rt/shaders/rt_composite_v14.spv";`
- Change line 52: `ByteBuffer spirv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_composite.comp");` → `ByteBuffer spirv = RtShaderCompiler.loadPrecompiled(SHADER_PATH);`

In `RtShaderCompiler.java`, remove the composite warmup entry (line 137):
```java
            new ShaderVariant(RtCompositePipeline.SHADER_PATH, "rt_composite.comp+warmup", ""),
```

In `RtEntityCompositeTest.java`, replace `testShadercCompilation` (lines 22–31) with a precompiled-`.spv` validation (the other 7 tests are pure-Java simulations and stay unchanged):
```java
    @Test
    public void testPrecompiledSpirv() {
        ByteBuffer spirv = RtShaderCompiler.loadPrecompiled(RtCompositePipeline.SHADER_PATH);
        assertNotNull(spirv, "Precompiled SPIR-V buffer must not be null");
        assertTrue(spirv.remaining() > 0, "SPIR-V bytes must not be empty");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic number mismatch");
        assertEquals(80, RtCompositePipeline.PUSH_BYTES, "Push constant size must be exactly 80 bytes");
        assertEquals("/assets/bedray_rt/shaders/rt_composite_v14.spv", RtCompositePipeline.SHADER_PATH);
    }
```
Update the class javadoc (line 13) `compilation via Shaderc into valid SPIR-V` → `precompiled Slang SPIR-V validation`. (`RtEntityCompositeTest` is a JUnit test discovered by `:common:test`, not listed in `ShimTestRunner`, so renaming the method is safe.)

- [ ] **Step 10: Freeze GLSL into test resources, then delete from main**

1. Copy `common/src/main/resources/shaders/rt_composite.comp` → `common/src/test/resources/shaders/rt_composite.comp` (byte-identical; verify sha256 against Task 1 Step 4).
2. Delete `common/src/main/resources/shaders/rt_composite.comp`.

- [ ] **Step 11: Re-run the A/B after the move (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain > build/ab-composite-postmove.log 2>&1`.
Expected: still PASSED, identical hashes.

- [ ] **Step 12: Verification checkpoint (NO commit)**

Confirm: port + `.spv` + cap-audit + A/B (before/after move) green; `RtCompositePipeline` loads `.spv`; warmup entry removed; `RtEntityCompositeTest.testPrecompiledSpirv` updated; GLSL deleted from main, frozen test copy present. Do NOT git commit/push.

---

### Task 5: Full regression + loader smoke + report

**Files:**
- Modify: `docs/POST_BASELINE.md` (re-append the combined execution record)

- [ ] **Step 1: Full Java regression (single Gradle task)**

Run: `./gradlew :common:test --console=plain > build/test-2.1b.log 2>&1` (wait for completion).
Expected: BUILD SUCCESSFUL. XML truth — aggregate `common/build/test-results/test/TEST-*.xml`: `failures="0" errors="0"`. The count rises by the 3 new cap-audit tests (Stage 2.1a was 504/0/0/5-skipped → expect 507/0/0/5-skipped; the 3 new A/B tests are GPU-gated and skipped in plain `test`). Verify `RtEntityCompositeTest` is green (8 tests, including the renamed `testPrecompiledSpirv`).

- [ ] **Step 2: Shim regression (separate single Gradle task — never two at once)**

Run: `./gradlew :common:runShimTests --console=plain > build/shim-2.1b.log 2>&1`.
Expected: `Results: 36 passed, 0 failed` (the runner's printed tally is its truth; it is a JavaExec, not JUnit XML). The `FAILED` log lines are expected negative-path autotest assertions, not test failures. Confirm `testRtShaderSourcesCompile` still passes (it compiles rt_rays/rt_path/rt_atrous, untouched by 2.1b).

- [ ] **Step 3: Loader smoke (single Gradle task)**

Run: `./gradlew :common:processResources --console=plain > build/prores-2.1b.log 2>&1`.
Verify all three staged `.spv` exist with a valid SPIR-V 1.4 header (magic `0x07230203`, version `0x00010400`):
```bash
for s in iris_depth rt_particles_composite rt_composite; do
  f="common/build/resources/main/assets/bedray_rt/shaders/${s}_v14.spv"
  ls -la "$f"; od -An -tx4 -N8 "$f"
done
```
(A full in-game L3/L4 verification of Iris depth / particle composite / entity composite is out of scope for offscreen Stage 2.1b; the loader smoke + regression is the L2 gate. Note this honestly in the report.)

- [ ] **Step 4: Re-append the execution record to `docs/POST_BASELINE.md`**

The Task 1 regeneration overwrote the Stage 2.1a execution record. Append a combined record AFTER the machine-readable block (do NOT touch the frozen hash lines). Use the Edit tool to append after the closing ``` of the machine block:

```markdown
## Execution record

### Stage 2.1a (fg_prepare pilot)
Ported fg_prepare to Slang Tier 1. A/B byte-identical: frozen == glsl == slang =
`a831a6ce674136cb2e0e742b488d4f9d805d3e175b06c5d4ac688435b20a6c39` (depth+mv, 70x50). Caps `[1,49]`
(StorageImageExtendedFormats=49 is inherent to the rg16f motion-vector ABI, identical to the shaderc GLSL
reference; the Stage 2.0 ceiling was pinned from rgba8-only rt_tier1_probe). FrameGenGpu loads
fg_prepare_v14.spv; legacy fg_prepare.comp deleted from main, frozen copy in test resources. Regression
504/0/0/5-skipped + runShimTests 36/0.

### Stage 2.1b (iris_depth, rt_particles_composite, rt_composite)
Ported the three standalone post shaders to Slang Tier 1. Each A/B byte-identical to its frozen GLSL
reference under the production ABI (see the table above for the hashes). iris_depth proves the mat3-from-columns
(`transpose(float3x3(...))`) + 128-byte PC + column-major mat4 ABI; rt_composite proves the 80-byte PC mat4 +
mix->lerp; rt_particles_composite proves in-place rgba8 + the untouched early-out. Measured caps recorded in
the execution notes. Loaders (IrisDepthConverter, RtParticleComposite, RtCompositePipeline) load the .spv via
RtShaderCompiler.loadPrecompiled; the two warmup entries removed; RtEntityCompositeTest validates the .spv.
Legacy GLSL deleted from main, frozen copies in test resources. Regression counts recorded in the Russian report.
GPU timings deferred to a later stage; the deliverable is the byte-identical A/B. Frozen hashes never regenerated.
```

- [ ] **Step 5: Verification checkpoint (NO commit) + Russian report**

Confirm: all three A/B byte-identical (frozen == glsl == slang); cap-audits within ceiling; loaders load `.spv`; GLSL removed from main, frozen test copies present; `:common:test` + `:common:runShimTests` + `processResources` green. Do NOT git commit/push/deploy (owner permission required). Write a Russian report: what was ported, the three frozen/A/B hashes, the measured cap sets, the matrix-ABI proof (mat3 transpose + column-major mat4), regression counts, the decomposition adjustment (velocity_reproject moved to 2.1d), and that Stage 2.1c is next.

---

## Self-Review

**1. Spec coverage (Stage 2.1b scope):**
- "extend the offscreen harness to capture a GLSL-reference baseline for each plain compute shader" → Task 1 extends PostBaselineRunner for 3 shaders (harness reused unchanged). ✓
- "feed synthetic inputs, dispatch through the production pipeline via shaderc, read back output hash" → Task 1 dispatch helpers use the exact production bindings/PC/dispatch; synthetic inputs exercise every branch; output hashed. GPU timings deferred (honest scope note, same as 2.1a). ✓
- "THEN port each to Slang and A/B against that baseline" → Tasks 2–4 (port + cap-audit + A/B vs frozen). ✓
- "baseline captured BEFORE the port and never regenerated to hide a delta" → Task 1 freezes all 3 before any port; each A/B asserts glsl==frozen and forbids editing the baseline on mismatch; fg_prepare hash verified unchanged on regeneration. ✓
- ABI preservation (bindings, PC bytes, entry main, numthreads 8×8×1, column-major matrices, mat3 columns, mix→lerp) → ports preserve all; A/B gates. ✓
- "legacy GLSL removed from runtime only after its Slang port passes A/B" → each task wires then deletes, frozen test copy first. ✓
- Tier 1 cap ceiling → per-shader cap-audit. ✓
- Regression surfaces: RtEntityCompositeTest updated (Task 4); warmup entries removed (Tasks 3–4); ShimTestRunner unaffected (verified). ✓

**2. Placeholder scan:** No TBD/TODO/"implement later"/"similar to Task N". All code blocks complete. The frozen hashes are parsed at runtime from docs (no fill-in). The execution-record text in Task 5 Step 4 is concrete. ✓

**3. Type consistency:** `PostShaderProbe.Binding(int,int)`, `Pipeline(ByteBuffer,Binding[],int)`, `image(int,long,boolean)`, `record(VkCommandBuffer,byte[],int,int,int)`, `upload(Img,byte[],int)`, `readback(Img,int)`, `toGeneral(VkCommandBuffer,long,int)`, `submit(Consumer)` — match Stage 2.1a. `PostBaselineRunner.dispatchIrisDepth/dispatchParticles/dispatchComposite(PostShaderProbe,ByteBuffer)→byte[]`, `compileGlsl(String,String)→ByteBuffer`, `sha256(byte[])→String`, `W/H` — consumed identically by the runner measure methods and the 3 A/B tests. `RtShaderCompiler.loadPrecompiled(String)→ByteBuffer` — used by all 3 loaders + RtEntityCompositeTest. `IrisDepthSlangCapabilityTest.load/caps` — package-private statics reused by the other 2 cap-audits. `RtMemory.Img{image,view,width,height}`, `createImage(String,int,int,int,int)`, `destroy(Img)` — match RtMemory. ✓

**Risk noted (gated by A/B, not assumed):** the column-major `float4x4` push-constant matrix layout and the `mat3`-from-columns transpose are the two constructs most likely to differ between shaderc GLSL and Slang direct SPIR-V. The A/B steps explicitly STOP-and-investigate on mismatch (systematic-debugging) rather than forcing a match; the frozen baseline and GLSL are sacred. If the matrix layout proves row-major in Slang, the fix is a layout qualifier / transpose discovered by evidence, applied to the Slang port only (never the GLSL or the baseline).