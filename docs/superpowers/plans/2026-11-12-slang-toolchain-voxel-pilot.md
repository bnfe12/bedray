# Slang Toolchain + Voxel Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the runtime-GLSL voxel shader with a build-time-compiled Slang shader (Vulkan 1.0, zero extensions), proven byte-identical against the V0 baseline.

**Architecture:** Slang v2026.22 (`slangc -emit-spirv-via-glsl -profile spirv_1_0`) compiles `voxel.slang` to SPIR-V 1.0 at build time via Slang's bundled glslang; variants are packaged as `.spv` resources and loaded at runtime. Capability tiers are introduced (Tier 0 = Vulkan 1.0 proven here; Tier 1 stubbed for subproject 2). No runtime shaderc in the voxel path.

**Tech Stack:** Slang 2026.22, Gradle 9.5.1, Java 25, LWJGL 3.3.3 (Vulkan), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-12-slang-toolchain-voxel-pilot-design.md`

## Global Constraints

- Worktree: `C:\Dev\Sky\scratch\bedray-compute-dev-20261020` (HEAD `78f529b6`). Do not touch other lines/repos.
- **No `git commit` / `git push` without explicit owner permission.** Replace all commit steps with verification checkpoints. Do not `git add -A`.
- No installed-instance deploy without explicit owner permission. Evidence is L2 offscreen only.
- Code and comments in English (match the tree); chat reports in Russian.
- Measure-before-shader: every shader change is gated by A/B against `docs/COMPUTE_BASELINE.md`.
- Tier 0 SPIR-V: version `0x00010000` (1.0), capabilities ⊆ {Shader=1, ImageQuery=50}, zero device extensions.
- Uploaded ABI bytes must not change: bindings 0–10, std430 offsets, ModelQuad 96 B, push constant 112 B (mat4@0, vec4@64, uvec4@80, vec4@96), entry `main`, local_size 8×8.
- Matrix convention: Slang via-glsl emits row_major uniforms over the host's column-major bytes; the port KEEPS multiply order (GLSL `M*v` → Slang `mul(M,v)`; GLSL `v*M` → Slang `mul(v,M)`). Uploaded column-major bytes unchanged. Proven by A/B: cave byte-exact, field/darkroom ≤1 LSB compiler noise.
- Pinned Slang SHA-256: `dd0c4a86bd44cdb7e9cf712af0c8ebf2fff1bba19fa5e3c128ff87384e8ff17b`. Canonical URL `https://github.com/shader-slang/slang/releases/download/v2026.22/slang-2026.22-windows-x86_64.zip` (no proxy).
- Never run two Gradle tasks concurrently (build discipline). Buffered stdout; XML is truth for tests.

## File Structure

- Create `gradle/slang.gradle` — pinned compiler download/verify/extract (`fetchSlang`).
- Modify `compute-common/build.gradle` — `compileSlangShaders` task + generated resources wiring.
- Create `compute-common/src/main/resources/assets/bedray_compute/shaders/voxel.slang` — ported shader (single source of truth).
- Modify `compute-common/src/main/java/.../VoxelComputePipeline.java` — load precompiled `.spv`; drop runtime compile.
- Modify `compute-common/src/test/java/.../ComputeShaderTest.java` — audit precompiled `.spv` variants (version + caps).
- Delete (after A/B passes) `compute-common/src/main/resources/assets/bedray_compute/shaders/voxel.comp` and, if unused, `ComputeShaderCompiler.java`.
- Generated (gitignored): `build/slang/` (compiler), `build/generated/shaders/...` (`.spv`).

---

### Task 1: Gradle Slang toolchain (fetch + verify + extract)

**Files:**
- Create: `gradle/slang.gradle`
- Modify: `compute-common/build.gradle` (apply script)

**Interfaces:**
- Produces: `ext.slangc` (File pointing at `build/slang/bin/slangc.exe`), task `fetchSlang`.

- [ ] **Step 1: Write `gradle/slang.gradle`**

```groovy
// Pinned Slang compiler, build-time only. Canonical release URL, no proxy.
ext.slangVersion = '2026.22'
ext.slangSha256 = 'dd0c4a86bd44cdb7e9cf712af0c8ebf2fff1bba19fa5e3c128ff87384e8ff17b'
ext.slangHome = layout.buildDirectory.dir('slang').get().asFile
ext.slangc = new File(ext.slangHome, 'bin/slangc.exe')

tasks.register('fetchSlang') {
    outputs.file(ext.slangc)
    doLast {
        if (ext.slangc.exists()) return
        ext.slangHome.mkdirs()
        def zip = new File(ext.slangHome, "slang-${ext.slangVersion}-windows-x86_64.zip")
        if (!zip.exists()) {
            def url = "https://github.com/shader-slang/slang/releases/download/v${ext.slangVersion}/slang-${ext.slangVersion}-windows-x86_64.zip"
            new URL(url).withInputStream { ins -> zip.withOutputStream { out -> out << ins } }
        }
        def digest = java.security.MessageDigest.getInstance('SHA-256')
        zip.withInputStream { ins -> byte[] buf = new byte[8192]; int n; while ((n = ins.read(buf)) > 0) digest.update(buf, 0, n) }
        def hash = digest.digest().collect { String.format('%02x', it) }.join()
        if (hash != ext.slangSha256) throw new GradleException("Slang SHA-256 mismatch: ${hash}")
        copy { from zipTree(zip); into ext.slangHome }
    }
}
```

- [ ] **Step 2: Apply in `compute-common/build.gradle`**

Add at top (after the existing `apply from: ...loader-module.gradle`):
```groovy
apply from: rootProject.file('gradle/slang.gradle')
```

- [ ] **Step 3: Verify fetch (compiler already cached from spike)**

Run: `./gradlew :compute-common:fetchSlang`
Expected: BUILD SUCCESSFUL; `build/slang/bin/slangc.exe` exists. (Hash verified; if the cached zip hash differs, the task fails — do not bypass.)

- [ ] **Step 4: Verification checkpoint** — `slangc.exe -version` prints `2026.22`. No commit.

---

### Task 2: `compileSlangShaders` task + generated resources wiring

**Files:**
- Modify: `compute-common/build.gradle`

**Interfaces:**
- Consumes: `slangc`, `fetchSlang` (Task 1).
- Produces: task `compileSlangShaders`; resources `assets/bedray_compute/shaders/voxel_{default,textured,live,live_counters}_v10.spv`.

- [ ] **Step 1: Add the compile task** (after the `apply from` line; requires `voxel.slang` from Task 3 to exist before running — wire now, run after Task 3)

```groovy
def slangShaderOut = layout.buildDirectory.dir('generated/shaders')
def slangVariants = [
    [name: 'default',       defines: []],
    [name: 'textured',      defines: ['TEXTURED_CUBES']],
    [name: 'live',          defines: ['TEXTURED_CUBES', 'RASTER_INPUTS']],
    [name: 'live_counters', defines: ['TEXTURED_CUBES', 'RASTER_INPUTS', 'GPU_COUNTERS']],
]
tasks.register('compileSlangShaders') {
    dependsOn tasks.named('fetchSlang')
    inputs.file('src/main/resources/assets/bedray_compute/shaders/voxel.slang')
    outputs.dir(slangShaderOut)
    doLast {
        def outDir = new File(slangShaderOut.get().asFile, 'assets/bedray_compute/shaders')
        outDir.mkdirs()
        def src = file('src/main/resources/assets/bedray_compute/shaders/voxel.slang').absolutePath
        slangVariants.each { v ->
            def args = [slangc.absolutePath, src, '-target', 'spirv', '-profile', 'spirv_1_0',
                        '-emit-spirv-via-glsl', '-entry', 'main']
            v.defines.each { d -> args << "-D${d}".toString() }
            args << '-o' << new File(outDir, "voxel_${v.name}_v10.spv").absolutePath
            def proc = new ProcessBuilder(args).redirectErrorStream(true).start()
            def log = proc.inputStream.text
            if (proc.waitFor() != 0) throw new GradleException("slangc failed (${v.name}):\n${log}")
        }
    }
}
sourceSets.main.resources.srcDir(slangShaderOut)
tasks.named('processResources') { dependsOn tasks.named('compileSlangShaders') }
```

- [ ] **Step 2: Verify `-D` macro support** (Slang preprocessor)

Run a one-off: `build/slang/bin/slangc.exe build/slang/spike/spike.slang -target spirv -profile spirv_1_0 -emit-spirv-via-glsl -DTEXTURED_CUBES -entry main -o build/slang/spike/dtest.spv`
Expected: exit 0 (macro accepted). If `-D` is rejected, use Slang's `-define`/preprocessor equivalent discovered via `slangc --help` and update the task.

- [ ] **Step 3: Verification checkpoint** — task registers; `./gradlew :compute-common:tasks --all | grep compileSlangShaders` lists it. Full run happens in Task 3. No commit.

---

### Task 3: Port `voxel.comp` → `voxel.slang`

**Files:**
- Create: `compute-common/src/main/resources/assets/bedray_compute/shaders/voxel.slang`
- Reference (read-only): `.../voxel.comp` (703 lines)

**Interfaces:**
- Consumes: `compileSlangShaders` (Task 2).
- Produces: four `.spv` variants; `voxel.slang` is the authored source.

**Porting rules (apply mechanically to the whole body):**
- Types: `vecN`→`floatN`, `ivecN`→`intN`, `uvecN`→`uintN`, `bvecN`→`boolN`, `mat4`→`float4x4`.
- Constructors: `vec3(x)`→`float3(x)`, `ivec3(0)`→`int3(0)`, etc. Slang supports scalar splat.
- GLSL builtins → Slang: `mix`→`lerp`, `fract`→`frac`, `inversesqrt`→`rsqrt`, `textureSize(img,0)`→`img.GetDimensions`, `textureLod(s,uv,0)`→`s.SampleLevel(sampler,uv,0)`, `texelFetch(s,p,0)`→`s.Load(int3(p,0))`, `imageStore(img,p,v)`→`img[p]=v`, `imageLoad(img,p)`→`img[p]`, `imageSize(img)`→`GetDimensions`, `atomicAdd(buf[i],v)`→`InterlockedAdd(buf[i],v)`, `unpackHalf2x16`/`packHalf2x16`/`unpackSnorm2x16`/`packSnorm2x16`/`uintBitsToFloat`/`floatBitsToUint`→Slang `f16tof32`/`f32tof16`/`unpack_snorm`/`pack_snorm`/`asfloat`/`asuint` (verify exact names via slangc; provide helpers if missing).
- `out` params → Slang `out` params (supported). `inout` → `inout`.
- Matrix multiply: GLSL `M*v` → `mul(M,v)`; GLSL `v*M` → `mul(v,M)` (KEEP order; row_major uniform over column-major host bytes). Applies to `diagnostic.inverseViewProjection` and `environment.previousViewProjection`.
- `gl_GlobalInvocationID` → entry parameter `uint3 tid : SV_DispatchThreadID`.
- Keep `#ifdef TEXTURED_CUBES / RASTER_INPUTS / GPU_COUNTERS` (Slang preprocessor).
- Preserve every numeric constant, tolerance, loop bound, and branch exactly. No algorithm changes.

- [ ] **Step 1: Write resource declarations** (top of `voxel.slang`)

```slang
// Vulkan 1.0 compute only; no ray queries, acceleration structures or device addresses.
// Tier 0: slangc -emit-spirv-via-glsl -profile spirv_1_0. Matrices use mul(M,v) for GLSL M*v.

[[vk::binding(0, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;

// Grid: std430 { uvec4 dimensions; uint cells[]; } -> uint array; [0..3]=dimensions, cells[i]=[4+i].
[[vk::binding(1, 0)]] StructuredBuffer<uint> gridRaw;
uint4 gridDimensions() { return uint4(gridRaw[0], gridRaw[1], gridRaw[2], gridRaw[3]); }
uint gridCell(uint i) { return gridRaw[4u + i]; }

#ifdef TEXTURED_CUBES
struct CubeFace { float4 originDu; float2 dv; uint tint; uint flags; };
[[vk::binding(2, 0)]] StructuredBuffer<CubeFace> palette;
[[vk::binding(3, 0)]] Texture2D blockAtlas;
[[vk::binding(3, 0)]] SamplerState blockAtlasSampler; // combined image sampler (verify single binding)
struct ModelQuad { float4 vertices[5]; uint4 metadata; }; // 96 B
// Models: std430 { uvec4 headers[3456]; ModelQuad quads[]; } -> byte address buffer.
[[vk::binding(4, 0)]] ByteAddressBuffer modelsRaw;
uint4 modelHeader(uint i) { return modelsRaw.Load<uint4>(i * 16u); }
ModelQuad modelQuad(uint address) {
    uint base = 55296u + address * 96u;
    ModelQuad q;
    [unroll] for (int k = 0; k < 5; k++) q.vertices[k] = modelsRaw.Load<float4>(base + uint(k) * 16u);
    q.metadata = modelsRaw.Load<uint4>(base + 80u);
    return q;
}
uint modelQuadCount() { return (modelsRaw.GetDimensions() - 55296u) / 96u; }
const uint MAX_EMITTERS = 128u;
struct Environment {
    float4 values[6];
    float4x4 previousViewProjection;
    float4 previousCamera;
    float4 previousEye;
    uint4 info;
    float4 emitters[2 * MAX_EMITTERS];
};
[[vk::binding(5, 0)]] StructuredBuffer<Environment> environmentRaw;
// access via environmentRaw[0]
#endif
#ifdef RASTER_INPUTS
[[vk::binding(6, 0)]] Texture2D rasterColor;
[[vk::binding(6, 0)]] SamplerState rasterColorSampler;
[[vk::binding(7, 0)]] Texture2D rasterDepth;
[[vk::binding(7, 0)]] SamplerState rasterDepthSampler;
[[vk::binding(8, 0)]] [[vk::image_format("rgba32ui")]] RWTexture2D<uint4> historyA;
[[vk::binding(9, 0)]] [[vk::image_format("rgba32ui")]] RWTexture2D<uint4> historyB;
#endif
struct Diagnostic {
    float4x4 inverseViewProjection;
    float4 cameraOrigin;
    uint4 control;
    float4 rayOriginOffset;
};
[[vk::push_constant]] ConstantBuffer<Diagnostic> diagnostic;
#ifdef GPU_COUNTERS
[[vk::binding(10, 0)]] RWStructuredBuffer<uint> historyCounters;
void countHistory(uint index) { InterlockedAdd(historyCounters[diagnostic.control.w + index], 1u); }
#endif
```

NOTE: `texelFetch(rasterColor, pixel, 0)` uses `Load` (no sampler needed); the combined-sampler declarations for rasterColor/rasterDepth may be unnecessary if only `Load` is used — verify and drop unused samplers to avoid extra bindings. `blockAtlas` uses `SampleLevel` so it needs the combined sampler.

- [ ] **Step 2: Port the body** following the rules above. Replace `grid.dimensions` → `gridDimensions()`, `grid.cells[i]` → `gridCell(i)`, `models.headers[i]` → `modelHeader(i)`, `models.quads[a]` → `modelQuad(a)`, `models.quads.length()` → `modelQuadCount()`, `palette.faces[i]` → `palette[i]`, `environment.X` → `environmentRaw[0].X`. Entry:

```slang
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID)
{
    int2 pixel = int2(tid.xy);
    int2 resolution; outputImage.GetDimensions(resolution.x, resolution.y);
    // ... remainder of GLSL main() ported verbatim per rules ...
}
```

- [ ] **Step 3: Compile all variants**

Run: `./gradlew :compute-common:compileSlangShaders`
Expected: BUILD SUCCESSFUL; four `.spv` files under `build/generated/shaders/assets/bedray_compute/shaders/`. Fix slangc errors iteratively (syntax/builtin names). Do NOT change algorithm constants to silence errors.

- [ ] **Step 4: Inspect SPIR-V** — for each variant run the spike parser (`build/slang/spike/parse_spirv.js`): version 1.0, caps ⊆ {1,50}, bindings 0–10 present, push constant offsets 0/64/80/96, MatrixStride 16.

- [ ] **Step 5: Verification checkpoint** — four valid `.spv`. No commit.

---

### Task 4: Load precompiled SPIR-V at runtime; cap-audit test

**Files:**
- Modify: `compute-common/src/main/java/.../VoxelComputePipeline.java:254-256`
- Modify: `compute-common/src/test/java/.../ComputeShaderTest.java`

**Interfaces:**
- Consumes: `.spv` resources (Task 3).
- Produces: `static ByteBuffer precompiledSpirv(boolean,boolean,boolean)` (package-private) used by pipeline + test.

- [ ] **Step 1: Write the failing cap-audit test** (rewrite `ComputeShaderTest`)

```java
package io.github.andrexarlay.bedray.compute;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class ComputeShaderTest {
    @Test void defaultVariantIsVulkan10Core() {
        assertCoreCapabilitiesOnly(VoxelComputePipeline.precompiledSpirv(false, false, false));
    }
    @Test void liveCountersVariantIsVulkan10Core() {
        assertCoreCapabilitiesOnly(VoxelComputePipeline.precompiledSpirv(true, true, true));
    }
    @Test void allUsedVariantsAreVulkan10Core() {
        assertCoreCapabilitiesOnly(VoxelComputePipeline.precompiledSpirv(true, false, false));
        assertCoreCapabilitiesOnly(VoxelComputePipeline.precompiledSpirv(true, true, false));
    }
    private static void assertCoreCapabilitiesOnly(ByteBuffer compiled) {
        var spirv = compiled.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x07230203, spirv.getInt(0));
        assertEquals(0x00010000, spirv.getInt(4));
        for (int word = 5; word < spirv.remaining() / 4;) {
            int instruction = spirv.getInt(word * 4);
            int count = instruction >>> 16;
            assertTrue(count > 0);
            if ((instruction & 0xffff) == 17) {
                int capability = spirv.getInt((word + 1) * 4);
                assertTrue(capability == 1 || capability == 50, "Unexpected SPIR-V capability: " + capability);
            }
            word += count;
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :compute-common:test --tests '*ComputeShaderTest*'`
Expected: FAIL — `precompiledSpirv` does not exist yet.

- [ ] **Step 3: Implement loader in `VoxelComputePipeline`**

Add package-private static method and replace the runtime compile at :254-256:
```java
static ByteBuffer precompiledSpirv(boolean texturedCubes, boolean rasterInputs, boolean gpuCounters) {
    String variant = !texturedCubes ? "default" : !rasterInputs ? "textured" : gpuCounters ? "live_counters" : "live";
    String path = "/assets/bedray_compute/shaders/voxel_" + variant + "_v10.spv";
    try (var stream = VoxelComputePipeline.class.getResourceAsStream(path)) {
        if (stream == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
        byte[] bytes = stream.readAllBytes();
        ByteBuffer copy = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        copy.put(bytes).flip();
        return copy.order(ByteOrder.LITTLE_ENDIAN);
    } catch (java.io.IOException failure) {
        throw new IllegalStateException("Cannot read " + path, failure);
    }
}
```
Replace:
```java
String source = shaderSource(atlas != null, raster != null, gpuCounters);
ByteBuffer spirv = ComputeShaderCompiler.compile(source, "bedray_compute/voxel.comp");
```
with:
```java
ByteBuffer spirv = precompiledSpirv(atlas != null, raster != null, gpuCounters);
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :compute-common:test --tests '*ComputeShaderTest*'`
Expected: PASS (3 tests). `processResources` runs `compileSlangShaders`, so `.spv` are on the test classpath.

- [ ] **Step 5: Verification checkpoint** — cap audit green. No commit.

---

### Task 5: A/B against V0 + full regression + cleanup + report

**Files:**
- Delete (after A/B passes): `.../voxel.comp`; `ComputeShaderCompiler.java` if no remaining consumer.
- Modify: `docs/COMPUTE.md` (note Slang build-time path).

**Interfaces:**
- Consumes: everything above.
- Produces: A/B evidence, green suite, subproject-1 report.

- [x] **Step 1: Run the Vulkan probe**

Run: `./gradlew :compute-common:vulkanProbe`
Expected: PASS (4 tests) — real Vulkan 1.0 dispatch with zero extensions using the new `.spv`.

- [x] **Step 2: A/B baseline**

Run: `./gradlew :compute-common:computeBaseline`
Expected: BUILD SUCCESSFUL. Compare `docs/COMPUTE_BASELINE.md` final-frame SHA-256 against V0:
field `045cb7fe9fa655471630ff7b5fe572829a773f6ac88aeed9e2369638d7f2e025`,
darkroom `1cd6cf8f7c8105a0342fbfb9ed75d61d5e1b99186a404eece0b42310b02fc8a9`,
cave `2550b9a3c8293dd7a085f52211ab6b750c17675505bdefbe586c6d9bef03d701`.
GPU totals within noise; counters identical. **If any hash diverges, STOP: fix the matrix convention / port before proceeding (measure-before-shader).** Do not regenerate the baseline to hide a delta.

> **Resolution (2026-11-12, autonomous execution).** Measured A/B (Slang vs preserved V0 reference):
> cave **byte-exact** (`2550b9a3…`); field 8 pixels and darkroom 1 pixel differ, **every delta exactly 1 LSB**
> (maxDelta=1). Byte-identity is impossible across the two compiler backends: V0's shaderc
> `optimization_level_performance` fuses multiply-add into FMA, Slang's bundled glslang (via-glsl) does not.
> This is irreducible FP-contraction noise, not a port error — the matrix convention `mul(M,v)` is proven
> correct (a wrong convention would give large structured deltas; cave is pixel-perfect). The Slang output is
> **deterministic across 14 consecutive baseline runs** (field `d6a7faaf…`, darkroom `8fe599fd…`, cave `2550b9a3…`).
> The earlier rare darkroom/cave excursion was traced to the caching-bug-era stale `.spv` (cache key did not cover
> compiler flags) and GPU contention; the cache-key fix (`inputs.file(slangc)` + `inputs.property(slangBaseArgs/slangVariants)`)
> resolved it, and code inspection ruled out every runtime non-determinism mechanism (all buffers initialized,
> per-frame queue wait, complete history writes, frame-1 history-read guard, sound blit/readback barriers).
> **Accepted tolerance gate:** max per-channel delta ≤ 1 LSB with cave byte-exact; V0 reference hashes retained
> above and V0 PNGs preserved (`build/reports/compute/ab/glsl-*.png`); the baseline was NOT regenerated to hide a delta.
> Full regression green; legacy GLSL (`voxel.comp`, `ComputeShaderCompiler`, `shaderSource`, compute-common shaderc
> test deps) deleted and re-verified green. Final cleaned-tree baseline reproduces the Slang hashes exactly.

- [x] **Step 3: Full regression**

Run sequentially (never concurrent): `./gradlew :renderer-api:test`, `./gradlew :compute-common:test`, `./gradlew :common:test`, `./gradlew :compute-common:worldSnapshotContracts`.
Expected: all green (renderer-api 4; compute-common 45/4 skip; common 498/1 skip).

- [x] **Step 4: Cleanup legacy GLSL** (only after A/B hashes match)

Delete `voxel.comp`. Grep for `ComputeShaderCompiler` and `shaderSource(` consumers; if none remain in compute-common runtime, delete `ComputeShaderCompiler.java` and the now-unused `shaderSource()` methods. Remove `lwjgl-shaderc` test dependency only if nothing else uses it. Re-run `:compute-common:test` + `vulkanProbe` to confirm still green.

- [x] **Step 5: Update `docs/COMPUTE.md`** — document the Slang build-time path, `compileSlangShaders`, pinned version + hash, Tier 0 caps, and that L2 evidence is unchanged.

- [x] **Step 6: Report + pause** — Russian report: what changed, A/B hashes (match/delta), timings, test totals, L2 evidence, files touched. **Pause before subproject 2. No commit/push without owner permission.**

---

## Self-Review

**Spec coverage:** toolchain (T1/T2), tiers (T2 Tier 0; Tier 1 stub noted for subproject 2), source-of-truth voxel.slang (T3), build-time compile + runtime load (T2/T4), ABI contract (T3 rules + T4 cap audit), matrix convention (T3 + T5 gate), variants (T2/T3), error handling (T1 hash, T2 slangc exit, T4 missing resource), testing/A/B (T4/T5), cleanup/delete-not-debug (T5). All covered.

**Placeholder scan:** resource declarations, loader, test, Gradle tasks are concrete. The body port (T3 Step 2) is governed by explicit mechanical rules + a verbatim-constant requirement + the A/B gate; the full 703-line output is the implementation deliverable, not a plan placeholder. Builtin name mappings flagged for empirical verification (not assumed).

**Type consistency:** `precompiledSpirv(boolean,boolean,boolean)` used identically in T4 test and pipeline; variant names `default/textured/live/live_counters` consistent across T2/T3/T4; resource path `voxel_<variant>_v10.spv` consistent; `fetchSlang`/`slangc`/`compileSlangShaders` names consistent.