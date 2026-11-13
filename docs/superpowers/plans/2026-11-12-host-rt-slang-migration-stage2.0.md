# Host RT Slang Migration — Stage 2.0 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Tier 1 Slang toolchain for the host RT path and an offscreen RT measurement harness that captures a GLSL-reference baseline (`docs/RT_BASELINE.md`), so every later shader port is gated by baseline + A/B. No production shader is ported in this stage.

**Architecture:** Extend `gradle/slang.gradle` (pinned Slang v2026.22) with a Tier 1 compile path in `common/build.gradle` (`-target spirv -profile spirv_1_4 -emit-spirv-directly`), proven on a checked-in representative ray-query probe. A cap-audit test pins the Tier 1 SPIR-V capability set empirically. A new offscreen harness (`common/src/test/java/.../rt/harness/`) creates its own RT-enabled Vulkan device, builds a fixed synthetic BLAS/TLAS scene, renders `rt_test.comp` and `rt_rays.comp` (and `rt_path.comp` accum) through the existing GLSL/shaderc path, reads back the image, hashes it, times the GPU dispatch, and emits `docs/RT_BASELINE.md` via `:common:rtBaseline`.

**Tech Stack:** Java 25, Gradle 9.5.1, LWJGL 3.3.3 (vulkan + shaderc), JUnit 5, Slang v2026.22, Vulkan 1.2+ ray query (SPIR-V 1.4). Dev GPU: NVIDIA RTX 5060 Ti (Vulkan 1.4 driver).

**Spec:** `docs/superpowers/specs/2026-11-12-host-rt-slang-migration-design.md` (subproject 2, Stage 2.0).

## Global Constraints

Every task implicitly includes these (copied verbatim from the spec):

- Worktree `C:\Dev\Sky\scratch\bedray-compute-dev-20261020` (branch `integration/combined-jar-20261025`, HEAD `78f529b6`). Do not touch other lines/repos; do not edit `…\bedray-public-export-20261020`; do not touch the installed instance.
- **No `git commit` / `git push` without explicit owner permission. No `git add -A`. Do not revert others' changes.** This plan replaces every commit step with a verification checkpoint.
- **No installed-instance deploy without explicit owner permission.**
- Code and comments in **English** (match the tree); chat reports in **Russian**.
- **Measure-before-shader:** every shader change gated by baseline + A/B. Raster fallback sacred (no black/gray cubes).
- Third-party shaders (vanilla / Sodium / Iris / FSR / NIS / NRD / DLSS / Streamline) stay as-is; interop via SPIR-V.
- **Canonical URLs only** (`github.com/shader-slang/slang`); no proxy substitution; re-verify each URL. Pinned Slang SHA-256 `dd0c4a86bd44cdb7e9cf712af0c8ebf2fff1bba19fa5e3c128ff87384e8ff17b`.
- **Never run two Gradle tasks concurrently.** Buffered stdout; **XML is truth** for test results (`build/test-results/**/*.xml`), not console noise.
- Tier 1 = SPIR-V 1.4 / Vulkan 1.2+ ray query (host RT). Tier 0 = SPIR-V 1.0 / Vulkan 1.0 (voxel) stays Int64-free and unchanged.
- LWJGL native stack: set `-Dorg.lwjgl.system.stackSize=256` (driver extension enumeration exceeds 64 KiB) and a per-task `org.lwjgl.system.SharedLibraryExtractPath` (a running game locks the shared DLL cache on Windows).
- Evidence level **L2 offscreen** (real Vulkan device with RT). L4 in-game is a later owner-verified step, not claimed here.

## File Structure

| File | Responsibility |
|---|---|
| `common/src/main/resources/shaders/slang/rt_tier1_probe.slang` (create) | Toolchain-validation probe exercising the full Tier 1 cap set. NOT a production shader port. Removed in Stage 2.3. |
| `common/build.gradle` (modify) | Apply `gradle/slang.gradle`; `compileSlangShaders` (Tier 1); `rtProbe` Test task; `rtBaseline` JavaExec task; wire generated `.spv` into `processResources`. |
| `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtSlangCapabilityTest.java` (create) | Cap-audit: SPIR-V 1.4, capabilities ⊆ pinned set, required extensions, PhysicalStorageBuffer64 memory model. |
| `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineProbe.java` (create) | Standalone RT-enabled Vulkan device + queue + command buffer + output image + readback + GPU timestamp helper. Mirrors `VulkanComputeProbeTest.Probe` with the RT feature chain. |
| `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineScene.java` (create) | Fixed synthetic RT scene: vertex buffers, 64-byte `Section` table, atlases, lightmap, 512-byte `StatsBuf` slot, BLAS/TLAS, output/depth/accum images. |
| `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineProbeTest.java` (create) | Offscreen RT test (`@EnabledIfSystemProperty bedray.rt.rtProbe=true`): renders `rt_test` + `rt_rays`, asserts substantial hits + stable hash, writes PNGs. |
| `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineRunner.java` (create) | `:common:rtBaseline` main: 30 warmup + 120 measured frames per scene, SHA-256 + GPU timings + counters, emits `docs/RT_BASELINE.md`. |
| `docs/RT_BASELINE.md` (generated) | The GLSL-reference RT baseline (hashes + timings). Produced BEFORE any port; never regenerated to hide a delta. |

Existing classes reused unchanged (device-agnostic, take a `VkDevice`): `rt/poc/SimpleAccelerationStructureBuilder`, `rt/poc/RtTestPipeline`, `rt/world/RtPipeline`, `rt/world/RtMemory`, `rt/world/RtInstanceWriter`, `rt/world/RtFrameParams`, `rt/world/RtStatsLayout`, `rt/world/RtShaderCompiler`.

---

### Task 1: Tier 1 Slang toolchain + representative probe

**Files:**
- Create: `common/src/main/resources/shaders/slang/rt_tier1_probe.slang`
- Modify: `common/build.gradle` (apply slang.gradle after line 1; add tasks before `publishing`)

**Interfaces:**
- Consumes: `gradle/slang.gradle` (`fetchSlang`, `ext.slangc`, `ext.slangHome`); pinned Slang v2026.22.
- Produces: `:common:compileSlangShaders` → `build/generated/shaders/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv`, packaged on the classpath at `/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv`. Task 2 loads that resource.

- [ ] **Step 1: Author the Tier 1 probe**

Create `common/src/main/resources/shaders/slang/rt_tier1_probe.slang`. This combines the two proven GO spike probes (`build/slang/spike-rt/probe_rayquery.slang` + `probe_bufref_uvec2.slang`) into one artifact that exercises the entire host-RT capability set. It is a NEW toolchain-validation probe, not a port of any production shader.

```slang
// Tier 1 toolchain-validation probe (NOT a production shader port). Exercises the full host-RT
// capability set: ray query, subgroup ballot, device-address pointer (uvec2 -> uint64_t -> T*),
// storage image and structured buffer. Compiled by :common:compileSlangShaders; audited by
// RtSlangCapabilityTest. Removed in Stage 2.3 once real Slang RT variants exist.
struct Stats {
    float4 camRel;
    uint entityTris;
    uint pad0;
    uint pad1;
    uint pad2;
};

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::binding(1, 0)]] RWTexture2D<float4> outImage;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> scratch;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint2> addrBuf;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID)
{
    RayQuery<RAY_FLAG_NONE> q;
    RayDesc ray;
    ray.Origin = float3(0, 0, 0);
    ray.Direction = float3(0, 0, 1);
    ray.TMin = 0.0;
    ray.TMax = 100.0;
    q.TraceRayInline(tlas, RAY_FLAG_NONE, 0xFF, ray);
    while (q.Proceed()) { }
    float t = 0.0;
    if (q.CommittedStatus() != COMMITTED_NOTHING) {
        t = q.CommittedRayT();
    }
    uint2 a = addrBuf[0];
    uint64_t addr = ((uint64_t)a.y << 32) | (uint64_t)a.x;
    Stats* p = (Stats*)addr;
    uint4 ballot = WaveActiveBallot(t > 0.0);
    scratch[tid.x] = ballot.x;
    outImage[tid.xy] = float4(t, p[0].camRel.x, float(ballot.x), float(WaveGetLaneCount()));
}
```

- [ ] **Step 2: Wire the Tier 1 compile path into `common/build.gradle`**

Add `apply from: rootProject.file('gradle/slang.gradle')` immediately after line 1 (`apply from: rootProject.file('gradle/loader-module.gradle')`). Then add the following block before the `publishing { … }` block. It mirrors `compute-common/build.gradle`'s `compileSlangShaders` but with the Tier 1 route (`spirv_1_4`, `-emit-spirv-directly`) and a cache key covering compiler binary + base args + defines + source.

```gradle
// ---- Tier 1 Slang toolchain (host RT). Mirrors compute-common's Tier 0 path; SPIR-V 1.4 direct. ----
def slangShaderOut = layout.buildDirectory.dir('generated/shaders')
def slangRtVariants = [
    [name: 'rt_tier1_probe', defines: []],
]
def slangRtBaseArgs = ['-target', 'spirv', '-profile', 'spirv_1_4', '-emit-spirv-directly', '-entry', 'main']
tasks.register('compileSlangShaders') {
    dependsOn tasks.named('fetchSlang')
    inputs.dir('src/main/resources/shaders/slang')
    // Fingerprint the compiler binary, flags and variant defines so a flag change recompiles
    // instead of silently reusing a stale .spv (the cache key must cover every compiler input).
    inputs.file(slangc)
    inputs.property('slangRtVariants', slangRtVariants)
    inputs.property('slangRtBaseArgs', slangRtBaseArgs)
    outputs.dir(slangShaderOut)
    doLast {
        def outDir = new File(slangShaderOut.get().asFile, 'assets/bedray_rt/shaders')
        outDir.mkdirs()
        def src = file('src/main/resources/shaders/slang/rt_tier1_probe.slang').absolutePath
        slangRtVariants.each { v ->
            def args = [slangc.absolutePath, src] + slangRtBaseArgs
            v.defines.each { d -> args << "-D${d}".toString() }
            args << '-o' << new File(outDir, "${v.name}_v14.spv").absolutePath
            def proc = new ProcessBuilder(args).redirectErrorStream(true).start()
            def log = proc.inputStream.text
            if (proc.waitFor() != 0) throw new GradleException("slangc failed (${v.name}):\n${log}")
        }
    }
}
sourceSets.main.resources.srcDir(slangShaderOut)
tasks.named('processResources') { dependsOn tasks.named('compileSlangShaders') }
```

- [ ] **Step 3: Compile and confirm the probe SPIR-V exists**

Run (single Gradle task, buffered stdout):

```
./gradlew :common:compileSlangShaders --console=plain
```

Expected: BUILD SUCCESSFUL; `common/build/generated/shaders/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv` exists and is non-empty. If `slangc` fails direct emission for any construct, the verified fallback is `-emit-spirv-via-glsl` (swap that one flag in `slangRtBaseArgs`, re-run, and record the route used). The spike proved direct emission works for these constructs, so direct is expected.

- [ ] **Step 4: Dump the probe's SPIR-V version, capabilities, extensions and memory model**

This pins the cap set empirically for Task 2 (do not guess). Run the spike's Node dumper against the freshly compiled probe:

```
node build/slang/spike-rt/parse_caps.js common/build/generated/shaders/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv
```

Empirical result (raw `OpCapability` operands read directly from the compiled probe, slangc v2026.22 direct SPIR-V 1.4): version `0x00010400` (1.4); capabilities `{1, 11, 55, 56, 61, 64, 4472, 5347}` = Shader=1, Int64=11, StorageImageReadWithoutFormat=55, StorageImageWriteWithoutFormat=56, GroupNonUniform=61, GroupNonUniformBallot=64, RayQueryKHR=4472, PhysicalStorageBufferAddresses=5347; extensions `SPV_KHR_ray_query`, `SPV_KHR_storage_buffer_storage_class`, `SPV_KHR_physical_storage_buffer`; memory model addressing `PhysicalStorageBuffer64` (5348), memory model GLSL450 (1). ImageQuery (50) is NOT pulled (the probe issues no image-size queries). NOTE: the spike's `parse_caps.js` name map is unreliable (it mislabels PhysicalStorageBufferAddresses as 5343 and prints some caps as names); the raw-integer read is the truth. Task 2's pinned constant equals this raw set. If `parse_caps.js` is absent (gitignored throwaway cleaned), recreate a minimal dumper: read the `.spv` little-endian, word 1 = version, walk instructions (`count = word >>> 16`, `opcode = word & 0xffff`), opcode 17 = OpCapability (operand 0 = cap), opcode 10 = OpExtension (literal string), opcode 14 = OpMemoryModel (operand 0 = addressing model).

- [ ] **Step 5: Verification checkpoint (no commit)**

Confirm: (a) `compileSlangShaders` is up-to-date on a second run (cache key works); (b) touching `slangRtBaseArgs` forces a recompile; (c) the `.spv` is on the test classpath after `processResources`. Do NOT commit. Report progress in Russian.

---

### Task 2: Tier 1 capability-audit test

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtSlangCapabilityTest.java`

**Interfaces:**
- Consumes: `/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv` (Task 1); the cap dump from Task 1 Step 4.
- Produces: a green `:common:test --tests '*RtSlangCapabilityTest'` gate. Later stages extend `TIER1_CAPS`/variant list as real shaders are added; the audit asserts every variant stays within the pinned set.

- [ ] **Step 1: Write the cap-audit test**

Create `RtSlangCapabilityTest.java`. It mirrors `compute-common`'s `ComputeShaderTest` instruction walk, but asserts SPIR-V **1.4** (`0x00010400`), the pinned Tier 1 capability set, the required extensions, and the PhysicalStorageBuffer64 addressing model. Replace `TIER1_CAPS` with the exact numbers dumped in Task 1 Step 4 if they differ.

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 cap audit: the Slang-compiled host-RT SPIR-V must be version 1.4 and stay within the
 * capability set pinned empirically from the compiled probe (Stage 2.0). A toolchain regression
 * that pulls an unexpected capability (e.g. an RT-pipeline or Int64-only construct) fails here.
 */
class RtSlangCapabilityTest {
    // Pinned from the raw OpCapability operands of rt_tier1_probe_v14.spv (Task 1 cap dump).
    // Shader=1, Int64=11, StorageImageReadWithoutFormat=55, StorageImageWriteWithoutFormat=56,
    // GroupNonUniform=61, GroupNonUniformBallot=64, RayQueryKHR=4472, PhysicalStorageBufferAddresses=5347.
    private static final Set<Integer> TIER1_CAPS = Set.of(1, 11, 55, 56, 61, 64, 4472, 5347);
    private static final int ADDRESSING_PHYSICAL_STORAGE_BUFFER64 = 5348;

    @Test void tier1ProbeIsSpirv14WithinPinnedCapabilities() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/rt_tier1_probe_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");

        Set<Integer> caps = new TreeSet<>();
        Set<String> extensions = new HashSet<>();
        int addressingModel = -1;
        int words = spirv.remaining() / 4;
        for (int word = 5; word < words;) {
            int instruction = spirv.getInt(word * 4);
            int count = instruction >>> 16;
            int opcode = instruction & 0xffff;
            assertTrue(count > 0, "Invalid SPIR-V instruction word count at word " + word);
            if (opcode == 17) { // OpCapability
                caps.add(spirv.getInt((word + 1) * 4));
            } else if (opcode == 10) { // OpExtension
                extensions.add(readString(spirv, (word + 1) * 4));
            } else if (opcode == 14) { // OpMemoryModel
                addressingModel = spirv.getInt((word + 1) * 4);
            }
            word += count;
        }
        assertTrue(TIER1_CAPS.containsAll(caps),
            "Unexpected Tier 1 SPIR-V capabilities: " + caps + " (pinned " + TIER1_CAPS + ")");
        assertTrue(caps.contains(4472), "RayQueryKHR capability missing");
        assertTrue(caps.contains(5347), "PhysicalStorageBufferAddresses capability missing");
        assertTrue(extensions.contains("SPV_KHR_ray_query"), "SPV_KHR_ray_query extension missing");
        assertTrue(extensions.contains("SPV_KHR_physical_storage_buffer"),
            "SPV_KHR_physical_storage_buffer extension missing");
        assertEquals(ADDRESSING_PHYSICAL_STORAGE_BUFFER64, addressingModel,
            "OpMemoryModel addressing must be PhysicalStorageBuffer64");
    }

    private static String readString(ByteBuffer spirv, int byteOffset) {
        int end = byteOffset;
        while (spirv.get(end) != 0) end++;
        byte[] bytes = new byte[end - byteOffset];
        for (int i = 0; i < bytes.length; i++) bytes[i] = spirv.get(byteOffset + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ByteBuffer load(String path) {
        try (InputStream stream = RtSlangCapabilityTest.class.getResourceAsStream(path)) {
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

- [ ] **Step 2: Run the audit (single Gradle task; XML is truth)**

```
./gradlew :common:test --tests '*RtSlangCapabilityTest' --console=plain
```

Expected: PASS. Then read `common/build/test-results/test/TEST-io.github.andrexarlay.vulkancompat.rt.harness.RtSlangCapabilityTest.xml` and confirm `failures="0" errors="0"`. If the cap assertion fails, the dump in Task 1 Step 4 and `TIER1_CAPS` disagree — reconcile against the actual `.spv` (do not widen the set to hide an unexpected capability; investigate which construct pulled it).

- [ ] **Step 3: Verification checkpoint (no commit)**

Confirm the XML shows the test passed. Do NOT commit. Report in Russian.

---

### Task 3: Offscreen RT harness device + `rt_test` baseline

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineProbe.java`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineProbeTest.java`
- Modify: `common/build.gradle` (add `rtProbe` Test task)

**Interfaces:**
- Consumes: `VulkanComputeProbeTest.Probe` device/readback pattern; `RtDeviceRequirements` extension/feature set; `SimpleAccelerationStructureBuilder` (BLAS/TLAS); `RtTestPipeline` (2-binding ray-query compute, shaderc GLSL reference).
- Produces: `RtBaselineProbe` (AutoCloseable: `device`, `queue`, `command`, `width`, `height`, `memory()` → `RtMemory`, `renderTest(tlas, view)` → `byte[]`, `gpuDispatchNs()`); a stable `rt_test` baseline hash. Task 4/5 reuse `RtBaselineProbe`.

- [ ] **Step 1: Write the RT-enabled device probe**

Create `RtBaselineProbe.java`. It mirrors `VulkanComputeProbeTest.Probe` (instance → physical-device pick requiring GRAPHICS|COMPUTE → device → queue → command pool/buffer → output image → readback buffer) but enables the RT extensions and feature chain from `RtDeviceRequirements`, and gives the output image `STORAGE` usage so `imageStore` works. The device feature chain order matters: `VkPhysicalDeviceVulkan12Features.bufferDeviceAddress(true)` → `VkPhysicalDeviceAccelerationStructureFeaturesKHR.accelerationStructure(true)` → `VkPhysicalDeviceRayQueryFeaturesKHR.rayQuery(true)` (a separate `VkPhysicalDeviceBufferDeviceAddressFeatures` next to Vulkan12Features violates VUID-VkDeviceCreateInfo-pNext-02829).

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * Standalone offscreen RT device: a Vulkan 1.2 instance/device with ray query, acceleration
 * structure and buffer device address enabled (the host RT requirement set). Owns the output
 * image (STORAGE|TRANSFER_SRC|TRANSFER_DST), a host-visible readback buffer and one command
 * buffer. Mirrors VulkanComputeProbeTest.Probe; the game device is created by the engine, this
 * harness creates its own so RT can be measured offscreen (L2).
 */
final class RtBaselineProbe implements AutoCloseable {
    final int width, height, pixelBytes;
    VkInstance instance;
    VkDevice device;
    VkQueue queue;
    VkCommandBuffer command;
    String deviceName;
    int driverVersion, apiVersion;
    long commandPool, target, targetMemory, targetView, readback, readbackMemory;
    private RtMemory memory;
    private boolean targetInitialized;

    RtBaselineProbe(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixelBytes = width * height * 4;
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    RtMemory memory() {
        return memory;
    }

    private void create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Bedray RT offscreen baseline"))
                .applicationVersion(1).pEngineName(stack.UTF8("Bedray RT"))
                .engineVersion(1).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application);
            check(vkCreateInstance(info, null, pointer), "Create Vulkan 1.2 instance");
            instance = new VkInstance(pointer.get(0), info);

            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "Count physical devices");
            assertTrue(count.get(0) > 0, "No Vulkan physical devices");
            PointerBuffer physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals), "Enumerate physical devices");
            VkPhysicalDevice physical = null;
            int family = -1;
            for (int i = 0; i < physicals.remaining() && physical == null; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(i), instance);
                // Require the RT extensions on this physical device.
                if (!hasExtensions(candidate, stack)) continue;
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int j = 0; j < families.capacity(); j++) {
                    int flags = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                    if (families.get(j).queueCount() > 0 && (families.get(j).queueFlags() & flags) == flags) {
                        physical = candidate;
                        family = j;
                        break;
                    }
                }
            }
            if (physical == null) {
                throw new IllegalStateException("No RT-capable Vulkan device (ray query + acceleration structure "
                    + "+ buffer device address). The harness fails rather than skipping: this is L2 RT evidence.");
            }
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            deviceName = properties.deviceNameString();
            driverVersion = properties.driverVersion();
            apiVersion = properties.apiVersion();

            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));

            // Feature chain: Vulkan12.bufferDeviceAddress -> AS features -> ray query features.
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default().bufferDeviceAddress(true);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default()
                    .accelerationStructure(true).pNext(vk12.address());
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery =
                VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default()
                    .rayQuery(true).pNext(asFeatures.address());

            PointerBuffer extensions = stack.pointers(
                stack.UTF8("VK_KHR_acceleration_structure"),
                stack.UTF8("VK_KHR_ray_query"),
                stack.UTF8("VK_KHR_buffer_device_address"),
                stack.UTF8("VK_KHR_deferred_host_operations"));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues).ppEnabledExtensionNames(extensions).pNext(rayQuery.address());
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "Create RT device");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
            memory = new RtMemory(device);

            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, handle), "Create command pool");
            commandPool = handle.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer), "Allocate command buffer");
            command = new VkCommandBuffer(pointer.get(0), device);

            RtMemory.Img img = memory.createImage("rt_baseline_output", width, height, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            target = img.image;
            targetMemory = img.memory;
            targetView = img.view;
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(pixelBytes)
                .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "Create readback buffer");
            readback = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, readback, requirements);
            readbackMemory = allocate(physical, requirements,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
            check(vkBindBufferMemory(device, readback, readbackMemory, 0), "Bind readback memory");
        }
    }

    private static boolean hasExtensions(VkPhysicalDevice physical, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, null), "Count device extensions");
        VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physical, (String) null, count, props), "Enumerate device extensions");
        boolean as = false, rq = false, bda = false, dho = false;
        for (int i = 0; i < props.capacity(); i++) {
            String name = props.get(i).extensionNameString();
            as |= name.equals("VK_KHR_acceleration_structure");
            rq |= name.equals("VK_KHR_ray_query");
            bda |= name.equals("VK_KHR_buffer_device_address");
            dho |= name.equals("VK_KHR_deferred_host_operations");
        }
        return as && rq && bda && dho;
    }

    long allocate(VkPhysicalDevice physical, VkMemoryRequirements requirements, int flags, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, mem);
        for (int type = 0; type < mem.memoryTypeCount(); type++) {
            if ((requirements.memoryTypeBits() & (1 << type)) != 0
                && (mem.memoryTypes(type).propertyFlags() & flags) == flags) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type), null, handle), "Allocate probe memory");
                return handle.get(0);
            }
        }
        throw new AssertionError("No compatible probe memory type for flags " + flags);
    }

    /** Records {@code recorder} (build + dispatch) between an output-image transition and a readback copy; returns RGBA bytes. */
    byte[] render(java.util.function.Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command, 0), "Reset command buffer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin command buffer");
            // Clear the output to a known black before every dispatch so the baseline is reproducible.
            // rt_test.comp blends with the existing pixel (imageLoad + mix), so an uncleared image
            // accumulates across renders and is not idempotent; clearing fixes the initial state.
            int oldLayout = targetInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
            VkImageMemoryBarrier.Buffer toClear = VkImageMemoryBarrier.calloc(1, stack);
            toClear.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(oldLayout)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            toClear.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toClear);
            VkClearColorValue black = VkClearColorValue.calloc(stack);
            black.float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 0f);
            VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
            clearRange.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(command, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, black, clearRange);
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, stack);
            toGeneral.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            toGeneral.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGeneral);
            recorder.accept(command);
            VkImageMemoryBarrier.Buffer ready = VkImageMemoryBarrier.calloc(1, stack);
            ready.get(0).sType$Default().image(target).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            ready.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, ready);
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            copy.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyImageToBuffer(command, target, VK_IMAGE_LAYOUT_GENERAL, readback, copy);
            VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack);
            host.get(0).sType$Default().buffer(readback).offset(0).size(pixelBytes)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, null);
            check(vkEndCommandBuffer(command), "End command buffer");
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0).sType$Default().pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "Submit RT probe");
            check(vkQueueWaitIdle(queue), "Wait for RT probe");
            targetInitialized = true;
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, readbackMemory, 0, pixelBytes, 0, mapped), "Map probe readback");
            try {
                ByteBuffer bytes = MemoryUtil.memByteBuffer(mapped.get(0), pixelBytes);
                byte[] result = new byte[pixelBytes];
                bytes.get(result);
                return result;
            } finally {
                vkUnmapMemory(device, readbackMemory);
            }
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @Override public void close() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (readback != 0) vkDestroyBuffer(device, readback, null);
            if (readbackMemory != 0) vkFreeMemory(device, readbackMemory, null);
            if (targetView != 0) vkDestroyImageView(device, targetView, null);
            if (target != 0) vkDestroyImage(device, target, null);
            if (targetMemory != 0) vkFreeMemory(device, targetMemory, null);
            if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
            if (memory != null) memory.close();
            vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }
}
```

- [ ] **Step 2: Write the offscreen `rt_test` test**

Create `RtBaselineProbeTest.java`. It builds the one-triangle BLAS/TLAS via `SimpleAccelerationStructureBuilder`, renders `rt_test.comp` through `RtTestPipeline` (shaderc GLSL reference), and asserts the green triangle hit is present and the hash is stable across two renders. `rt_test.comp` writes bright green `(0,1,0.2)` on a committed triangle hit and a purple center beacon otherwise.

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline;
import io.github.andrexarlay.vulkancompat.rt.poc.SimpleAccelerationStructureBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Offscreen RT dispatch + readback on a real ray-query device (L2). Fails rather than skips if no RT device. */
@EnabledIfSystemProperty(named = "bedray.rt.rtProbe", matches = "true")
class RtBaselineProbeTest {
    private static final int WIDTH = 320, HEIGHT = 240;

    @Test void rtTestRendersStableTriangleHit() throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             SimpleAccelerationStructureBuilder as = new SimpleAccelerationStructureBuilder(probe.device);
             RtTestPipeline pipeline = new RtTestPipeline(probe.device)) {
            byte[] first = probe.render(cmd -> {
                as.buildAll(cmd);
                pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT);
            });
            byte[] second = probe.render(cmd -> pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT));
            int greenHits = 0;
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                int r = Byte.toUnsignedInt(first[i * 4]), g = Byte.toUnsignedInt(first[i * 4 + 1]), b = Byte.toUnsignedInt(first[i * 4 + 2]);
                if (g > 180 && r < 80 && b < 120) greenHits++;
            }
            assertTrue(greenHits > 100, "rt_test must hit the hardware triangle: greenHits=" + greenHits);
            assertEquals(hash(first), hash(second), "rt_test baseline must be deterministic across renders");
            System.out.printf("[BEDRAY-RT-PROBE] device=%s rt_test greenHits=%d hash=%s%n",
                probe.deviceName, greenHits, hash(first));
        }
    }

    static String hash(byte[] rgba) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba));
    }
}
```

- [ ] **Step 3: Add the `rtProbe` Test task to `common/build.gradle`**

Add after the `compileSlangShaders` block. shaderc natives are not on the Maven test classpath; take them from the launcher libraries exactly as `runShimTests` does.

```gradle
def shadercNative = file("${rootProject.lwjgl_libs_dir}/lwjgl-shaderc-natives-windows/${rootProject.lwjgl_natives_version}/lwjgl-shaderc-natives-windows-${rootProject.lwjgl_natives_version}.jar")
tasks.register('rtProbe', Test) {
    group = 'verification'
    description = 'Offscreen Vulkan 1.2 ray-query dispatch + readback (L2, not in-game). Fails if no RT device.'
    dependsOn tasks.named('testClasses')
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath + files(shadercNative)
    useJUnitPlatform()
    filter { includeTestsMatching '*RtBaselineProbeTest' }
    systemProperty 'bedray.rt.rtProbe', 'true'
    systemProperty 'org.lwjgl.system.stackSize', '256'
    jvmArgs '--enable-native-access=ALL-UNNAMED'
    doFirst {
        if (!shadercNative.isFile()) {
            throw new GradleException("LWJGL shaderc natives jar not found (set BEDRAY_LAUNCHER_LIBRARIES or lwjgl_libs_dir, see docs/BUILDING.md): " + shadercNative)
        }
        systemProperty 'org.lwjgl.system.SharedLibraryExtractPath', new File(temporaryDir, 'lwjgl-natives').absolutePath
    }
    testLogging { events 'passed', 'failed'; showStandardStreams = true; exceptionFormat = 'full' }
}
```

- [ ] **Step 4: Run the offscreen RT probe (single Gradle task; XML is truth)**

```
./gradlew :common:rtProbe --console=plain
```

Expected: PASS; console prints `[BEDRAY-RT-PROBE] device=… rt_test greenHits=… hash=…`. Read `common/build/test-results/rtProbe/TEST-…RtBaselineProbeTest.xml` → `failures="0" errors="0"`. If the device lacks ray query, the harness throws (correct: this is RT evidence, not a skip). Record the `rt_test` hash — it is the first entry of `docs/RT_BASELINE.md`.

- [ ] **Step 5: Verification checkpoint (no commit)**

Confirm the XML passed and the hash is stable. Do NOT commit. Report in Russian.

---

### Task 4: Synthetic RT scene + `rt_rays` baseline

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineScene.java`
- Modify: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineProbeTest.java` (add `rtRaysRendersStableScene`)

**Interfaces:**
- Consumes: `RtBaselineProbe` (Task 3); `RtMemory.createBuffer/createImage`, `RtInstanceWriter.write`, `RtStatsLayout` offsets, `RtFrameParams`, `RtPipeline` + `RtPipeline.Views`; the `Section`/`StatsBuf` ABI from `rt_common_core.glsl`.
- Produces: `RtBaselineScene` (AutoCloseable) exposing `tlas`, `table` (`RtMemory.Buf`), `outputView`, `atlasView`, `materialView`, `normalView`, `depthView`, `lightmapView`, `accumView`, `statsAddress`, `params(frame, accumCount)` → `RtFrameParams`, `build(cmd)`, `hitFloor()`. Task 5 reuses it for `rt_rays`/`rt_path`.

The scene is ONE section (slot 0) with solid quads (pass bits 0 → `candidateAccepted` returns true without alpha test, the simplest decode path): a floor and a back wall in front of a camera at the origin looking down −Z. Vertex buffers follow the GLSL layout exactly: `posAddr` float3/vertex, `uvAddr` float2/vertex, `colAddr` uint/vertex (`R|G<<8|B<<16|A<<24`), `lightAddr` uint/vertex (`blockLight|skyLight<<8|material<<16`, light bytes `clamp(level*16+8,8,248)`); 4 vertices/quad; primitive `2k`=(0,1,2), `2k+1`=(2,3,0) of quad `k`. The BLAS reads the same `pos` buffer; the TLAS instance `customIndex=0`, identity transform, so BLAS-local == camera-relative world == `sec.posAddr` space.

- [ ] **Step 1: Write the synthetic scene builder**

Create `RtBaselineScene.java`.

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtInstanceWriter;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rt.world.RtStatsLayout;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * Fixed synthetic RT scene for the offscreen baseline: one section of solid quads (floor + back
 * wall), a synthetic block atlas, flat material/normal atlases, a synthetic lightmap, a 512-byte
 * StatsBuf slot and a BLAS/TLAS. Camera at the origin looking down -Z. The ABI matches
 * rt_common_core.glsl exactly (Section 64 B, StatsBuf 512 B, push constants 128 B).
 */
final class RtBaselineScene implements AutoCloseable {
    private static final int QUADS = 2; // floor + back wall, geometry 0 (solid)
    private static final int VERTS = QUADS * 4;
    private static final int TRIS = QUADS * 2;
    private static final float[] IDENTITY_3X4 = {1,0,0,0, 0,1,0,0, 0,0,1,0};

    private final RtBaselineProbe probe;
    private final RtMemory memory;
    final int width, height;

    private RtMemory.Buf pos, uv, col, light, indices, instances, blasBuf, tlasBuf, scratch, table, stats;
    private RtMemory.Img atlas, material, normal, lightmap, depth, accum;
    private long blasHandle, tlasHandle, blasAddress;
    private VkAccelerationStructureGeometryKHR.Buffer blasGeometry, tlasGeometry;
    long tlas, outputView, atlasView, materialView, normalView, depthView, lightmapView, accumView, statsAddress;

    RtBaselineScene(RtBaselineProbe probe) {
        this.probe = probe;
        this.memory = probe.memory();
        this.width = probe.width;
        this.height = probe.height;
        this.outputView = probe.targetView;
        build();
    }

    private void build() {
        // ---- vertex buffers (camera-relative world; camera at origin looking -Z) ----
        // floor quad y=-1, x[-3,3], z[-2,-8]; back wall quad z=-8, x[-3,3], y[-1,3].
        float[] positions = {
            -3,-1,-2,  3,-1,-2,  3,-1,-8,  -3,-1,-8,   // floor (quad 0)
            -3,-1,-8,  3,-1,-8,  3, 3,-8,  -3, 3,-8    // back wall (quad 1)
        };
        float[] uvs = {
            0,0, 1,0, 1,1, 0,1,
            0,0, 1,0, 1,1, 0,1
        };
        int[] colors = new int[VERTS];   // opaque white albedo (biome tint * AO)
        int[] lights = new int[VERTS];   // full sky light, no block light, material 0
        for (int i = 0; i < VERTS; i++) {
            colors[i] = 0xFFFFFFFF;
            lights[i] = 8 | (248 << 8);  // blockLight byte 8 (level 0), skyLight byte 248 (level 15)
        }
        int[] idx = new int[TRIS * 3];
        for (int q = 0; q < QUADS; q++) {
            int b = q * 4, o = q * 6;
            idx[o] = b; idx[o+1] = b+1; idx[o+2] = b+2;       // prim 2q
            idx[o+3] = b+2; idx[o+4] = b+3; idx[o+5] = b;     // prim 2q+1
        }
        int inputUsage = RtMemory.USAGE_AS_INPUT;
        pos = memory.createBuffer("rt_pos", positions.length * 4L, inputUsage, true);
        uv = memory.createBuffer("rt_uv", uvs.length * 4L, inputUsage, true);
        col = memory.createBuffer("rt_col", colors.length * 4L, inputUsage, true);
        light = memory.createBuffer("rt_light", lights.length * 4L, inputUsage, true);
        indices = memory.createBuffer("rt_idx", idx.length * 4L, inputUsage, true);
        MemoryUtil.memFloatBuffer(pos.mapped, positions.length).put(positions);
        MemoryUtil.memFloatBuffer(uv.mapped, uvs.length).put(uvs);
        MemoryUtil.memIntBuffer(col.mapped, colors.length).put(colors);
        MemoryUtil.memIntBuffer(light.mapped, lights.length).put(lights);
        MemoryUtil.memIntBuffer(indices.mapped, idx.length).put(idx);

        // ---- section table (one 64-byte Section, slot 0) ----
        table = memory.createBuffer("rt_sections", 64L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        long t = table.mapped;
        MemoryUtil.memPutLong(t, pos.address);          // posAddr  @0
        MemoryUtil.memPutLong(t + 8, uv.address);       // uvAddr   @8
        MemoryUtil.memPutLong(t + 16, col.address);     // colAddr  @16
        MemoryUtil.memPutLong(t + 24, light.address);   // lightAddr@24
        MemoryUtil.memPutInt(t + 32, 0);                // quadStart.x = first quad of geom 0
        MemoryUtil.memPutInt(t + 36, 0);                // quadStart.y
        MemoryUtil.memPutInt(t + 40, 0);                // quadStart.z
        MemoryUtil.memPutInt(t + 44, 1);                // quadStart.w = geometry count
        MemoryUtil.memPutInt(t + 48, 0);                // origin.x
        MemoryUtil.memPutInt(t + 52, 0);                // origin.y
        MemoryUtil.memPutInt(t + 56, 0);                // origin.z
        MemoryUtil.memPutInt(t + 60, 0);                // origin.w = pass bits (0 = solid)

        // ---- StatsBuf slot (512 B): zero, then the fields the primary-hit shade path reads ----
        stats = memory.createBuffer("rt_stats", RtStatsLayout.SLOT_STRIDE,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
        java.util.Arrays.fill(MemoryUtil.memByteBuffer(stats.mapped, RtStatsLayout.SLOT_STRIDE).array(), (byte) 0);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.EXPOSURE, 1.0f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.SUN_RADIUS, 0.0046f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.HISTORY_CAP, RtFrameParams.MAX_ACCUM);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.GI_DIST, 64f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.EMIS_DIST, 32f);
        MemoryUtil.memPutFloat(stats.mapped + RtStatsLayout.AO_RADIUS, 2.5f);
        statsAddress = stats.address;

        // ---- atlases / lightmap / depth / accum images ----
        atlas = memory.createImage("rt_atlas", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        material = memory.createImage("rt_material", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        normal = memory.createImage("rt_normal", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        lightmap = memory.createImage("rt_lightmap", 16, 16, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        depth = memory.createImage("rt_depth", width, height, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        accum = memory.createImage("rt_accum", width, height, VK_FORMAT_R32G32B32A32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        atlasView = atlas.view; materialView = material.view; normalView = normal.view;
        lightmapView = lightmap.view; depthView = depth.view; accumView = accum.view;
        uploadAtlases();

        buildAccelerationStructures();
    }

    /** Clears the synthetic atlases to fixed colours and transitions all images to GENERAL. */
    private void uploadAtlases() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(probe.command, 0), "Reset scene command buffer");
            check(vkBeginCommandBuffer(probe.command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin scene command buffer");
            RtMemory.Img[] imgs = {atlas, material, normal, lightmap, depth, accum};
            VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                toTransfer.get(i).sType$Default().image(imgs[i].image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                toTransfer.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            // atlas: opaque mid-gray albedo; material: roughness 0.5 / f0 0.04 / no emission; normal: flat; lightmap: white.
            clear(stack, atlas, 0.5f, 0.5f, 0.5f, 1.0f, range);
            clear(stack, material, 0.5f, 0.04f, 0.0f, 0.0f, range);
            clear(stack, normal, 0.5f, 0.5f, 1.0f, 1.0f, range);
            clear(stack, lightmap, 1.0f, 1.0f, 1.0f, 1.0f, range);
            VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(imgs.length, stack);
            for (int i = 0; i < imgs.length; i++) {
                toGeneral.get(i).sType$Default().image(imgs[i].image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                toGeneral.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            vkCmdPipelineBarrier(probe.command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGeneral);
            check(vkEndCommandBuffer(probe.command), "End scene command buffer");
            VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack);
            submit.get(0).sType$Default().pCommandBuffers(stack.pointers(probe.command.address()));
            check(vkQueueSubmit(probe.queue, submit, VK_NULL_HANDLE), "Submit scene upload");
            check(vkQueueWaitIdle(probe.queue), "Wait for scene upload");
        }
    }

    private void clear(MemoryStack stack, RtMemory.Img img, float r, float g, float b, float a, VkImageSubresourceRange.Buffer range) {
        VkClearColorValue color = VkClearColorValue.calloc(stack);
        color.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
        vkCmdClearColorImage(probe.command, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
    }

    private void buildAccelerationStructures() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // BLAS geometry: triangles from the pos buffer, indexed.
            blasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            blasGeometry.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = blasGeometry.get(0).geometry().triangles();
            triangles.sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(3 * Float.BYTES)
                .maxVertex(VERTS - 1).indexType(VK_INDEX_TYPE_UINT32);
            triangles.vertexData().deviceAddress(pos.address);
            triangles.indexData().deviceAddress(indices.address);

            VkAccelerationStructureBuildGeometryInfoKHR blasBuild = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1).pGeometries(blasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR blasSize = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(probe.device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, blasBuild, new int[]{TRIS}, blasSize);
            int asUsage = KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
            blasBuf = memory.createBuffer("rt_blas", blasSize.accelerationStructureSize(), asUsage, false);
            VkAccelerationStructureCreateInfoKHR blasCreate = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(blasBuf.handle).size(blasSize.accelerationStructureSize())
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            LongBuffer pBlas = stack.mallocLong(1);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(probe.device, blasCreate, null, pBlas), "Create BLAS");
            blasHandle = pBlas.get(0);
            VkAccelerationStructureDeviceAddressInfoKHR blasAddr = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                .sType$Default().accelerationStructure(blasHandle);
            blasAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(probe.device, blasAddr);

            // Instance buffer: one instance, customIndex 0, identity transform, cull disabled.
            instances = memory.createBuffer("rt_instances", RtInstanceWriter.SIZEOF, RtMemory.USAGE_AS_INPUT, true);
            RtInstanceWriter.write(instances.mapped, 0, 0xFF, blasAddress, IDENTITY_3X4);

            // TLAS geometry: instances.
            tlasGeometry = VkAccelerationStructureGeometryKHR.calloc(1);
            tlasGeometry.get(0).sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            tlasGeometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address);
            VkAccelerationStructureBuildGeometryInfoKHR tlasBuild = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .geometryCount(1).pGeometries(tlasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR tlasSize = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(probe.device,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, tlasBuild, new int[]{1}, tlasSize);
            tlasBuf = memory.createBuffer("rt_tlas", tlasSize.accelerationStructureSize(), asUsage, false);
            VkAccelerationStructureCreateInfoKHR tlasCreate = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(tlasBuf.handle).size(tlasSize.accelerationStructureSize())
                .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            LongBuffer pTlas = stack.mallocLong(1);
            check(KHRAccelerationStructure.vkCreateAccelerationStructureKHR(probe.device, tlasCreate, null, pTlas), "Create TLAS");
            tlasHandle = pTlas.get(0);
            tlas = tlasHandle;

            long scratchSize = Math.max(blasSize.buildScratchSize(), tlasSize.buildScratchSize());
            scratchSize = (scratchSize + 255L) & ~255L;
            if (scratchSize == 0) scratchSize = 256;
            scratch = memory.createBuffer("rt_scratch", scratchSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
        }
    }

    /** Records BLAS build -> barrier -> TLAS build -> barrier onto the command buffer. */
    void build(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer blas = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            blas.get(0).sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(blasHandle).geometryCount(1).pGeometries(blasGeometry);
            blas.get(0).scratchData().deviceAddress(scratch.address);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer blasRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            blasRange.get(0).primitiveCount(TRIS).primitiveOffset(0).firstVertex(0).transformOffset(0);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, blas, stack.pointers(blasRange.address()));
            barrier(stack, cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            tlasInfo.get(0).sType$Default().type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .dstAccelerationStructure(tlasHandle).geometryCount(1).pGeometries(tlasGeometry);
            tlasInfo.get(0).scratchData().deviceAddress(scratch.address);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer tlasRange = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            tlasRange.get(0).primitiveCount(1).primitiveOffset(0).firstVertex(0).transformOffset(0);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, tlasInfo, stack.pointers(tlasRange.address()));
            barrier(stack, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
    }

    private void barrier(MemoryStack stack, VkCommandBuffer cmd, int dstStage) {
        VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
            .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        vkCmdPipelineBarrier(cmd, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, dstStage, 0, b, null, null);
    }

    /** Fixed camera at the origin looking down -Z; mode LIT, no optional flags (primary-hit shade path). */
    RtFrameParams params(int frame, int accumCount) {
        RtFrameParams p = new RtFrameParams();
        float aspect = (float) width / height;
        new Matrix4f().perspective((float) Math.toRadians(70), aspect, 0.05f, 1000f).invert().get(p.invViewProj);
        p.statsAddress = statsAddress;
        p.mode = 5; // MODE_LIT
        p.frame = frame;
        p.accumCount = accumCount;
        p.tonemap = true;
        p.flags = 0;
        p.key.dir[0] = 0.3f; p.key.dir[1] = 0.8f; p.key.dir[2] = -0.5f; p.key.day = 1.0f;
        p.key.color[0] = 1.0f; p.key.color[1] = 0.95f; p.key.color[2] = 0.9f;
        p.skyColor[0] = 0.47f; p.skyColor[1] = 0.65f; p.skyColor[2] = 1.0f;
        return p;
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }

    @Override public void close() {
        vkDeviceWaitIdle(probe.device);
        if (tlasHandle != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(probe.device, tlasHandle, null);
        if (blasHandle != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(probe.device, blasHandle, null);
        if (blasGeometry != null) blasGeometry.free();
        if (tlasGeometry != null) tlasGeometry.free();
        for (RtMemory.Img img : new RtMemory.Img[]{atlas, material, normal, lightmap, depth, accum})
            if (img != null) memory.destroy(img);
        for (RtMemory.Buf buf : new RtMemory.Buf[]{pos, uv, col, light, indices, instances, blasBuf, tlasBuf, scratch, table, stats})
            if (buf != null) memory.destroy(buf);
    }
}
```

- [ ] **Step 2: Add the `rt_rays` offscreen test**

Add this test method to `RtBaselineProbeTest.java` (and the imports `io.github.andrexarlay.vulkancompat.rt.world.RtPipeline`, `io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams`). It renders `rt_rays.comp` through the production `RtPipeline` (GLSL reference via `RtShaderCompiler`), binds the full `Views`, dispatches with the 128-byte push constants, and asserts substantial non-black hits + a stable hash.

```java
    @Test void rtRaysRendersStableScene() throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe);
             RtPipeline pipeline = new RtPipeline(probe.device, false)) {
            RtPipeline.Views views = new RtPipeline.Views(scene.tlas, scene.table, scene.outputView,
                scene.atlasView, scene.materialView, scene.normalView, 0L, null, scene.depthView, scene.lightmapView);
            pipeline.bind(views);
            byte[] first = probe.render(cmd -> {
                scene.build(cmd);
                pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT);
            });
            byte[] second = probe.render(cmd -> {
                scene.build(cmd);
                pipeline.dispatch(cmd, scene.params(1, 0), WIDTH, HEIGHT);
            });
            int hits = 0;
            for (int i = 0; i < WIDTH * HEIGHT; i++) {
                int r = Byte.toUnsignedInt(first[i * 4]), g = Byte.toUnsignedInt(first[i * 4 + 1]), b = Byte.toUnsignedInt(first[i * 4 + 2]);
                if (r + g + b > 24) hits++;
            }
            assertTrue(hits > WIDTH * HEIGHT / 10, "rt_rays camera must see the scene: hits=" + hits);
            // frame differs (water/time noise) so compare a re-render at the SAME frame for determinism.
            byte[] again = probe.render(cmd -> {
                scene.build(cmd);
                pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT);
            });
            assertEquals(hash(first), hash(again), "rt_rays baseline must be deterministic at a fixed frame");
            System.out.printf("[BEDRAY-RT-PROBE] device=%s rt_rays hits=%d hash=%s%n", probe.deviceName, hits, hash(first));
        }
    }
```

Note: `second` is rendered to exercise a frame advance; the determinism assertion compares two renders at frame 0 (`first` vs `again`) because `rt_rays` uses `pc.frame` for temporal noise. If the scene still shows frame-to-frame noise at fixed frame (low-disc sequence), set `p.flags |= RtFrameParams.FLAG_LOWDISC` off and keep frame fixed; the assertion is at identical frame, so it must hold.

- [ ] **Step 3: Run the offscreen RT probe (single Gradle task; XML is truth)**

```
./gradlew :common:rtProbe --console=plain
```

Expected: both `rtTestRendersStableTriangleHit` and `rtRaysRendersStableScene` PASS; console prints `rt_rays hits=… hash=…`. Read the `rtProbe` XML → `failures="0" errors="0"`. If `hits` is too low, the camera/geometry placement is wrong (verify the floor/wall fill the view); if the hash is unstable at fixed frame, investigate temporal noise (do NOT regenerate to hide it). Record the `rt_rays` hash.

- [ ] **Step 4: Verification checkpoint (no commit)**

Confirm both tests pass in the XML and the `rt_rays` hash is stable. Do NOT commit. Report in Russian.

---

### Task 5: `:common:rtBaseline` runner + `docs/RT_BASELINE.md`

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/RtBaselineRunner.java`
- Modify: `common/build.gradle` (add `rtBaseline` JavaExec task)
- Generated: `docs/RT_BASELINE.md`

**Interfaces:**
- Consumes: `RtBaselineProbe`, `RtBaselineScene`, `RtTestPipeline`, `SimpleAccelerationStructureBuilder`, `RtPipeline` (Tasks 3–4); `ComputeBaselineRunner.report` markdown pattern.
- Produces: `:common:rtBaseline` → `docs/RT_BASELINE.md` with per-scene SHA-256 + GPU dispatch timings + device info. This is the GLSL-reference baseline that Stage 2.1/2.2 ports must reproduce (A/B).

- [ ] **Step 1: Write the baseline runner**

Create `RtBaselineRunner.java`. It mirrors `ComputeBaselineRunner`: 30 warmup + 120 measured frames per scene, GPU timestamp queries around the dispatch (harvested after a per-frame wait — harness wall time is an upper bound, like computeBaseline), SHA-256 of the final frame, PNG artifacts, and a markdown report. It renders three scenes: `rt_test` (triangle), `rt_rays` (synthetic section), `rt_path` (accum variant, accumulating over the measured window).

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.poc.RtTestPipeline;
import io.github.andrexarlay.vulkancompat.rt.poc.SimpleAccelerationStructureBuilder;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameParams;
import io.github.andrexarlay.vulkancompat.rt.world.RtPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.*;

/**
 * V0 RT baseline: three fixed offscreen scenes (rt_test triangle, rt_rays section, rt_path accum),
 * 30 warmup + 120 measured frames at 320x240. Writes docs/RT_BASELINE.md. L2 offscreen evidence on
 * a real Vulkan 1.2 ray-query device. The harness waits for the queue every frame, so harness wall
 * time is an upper bound; GPU dispatch times come from timestamp queries. This is the GLSL reference
 * captured BEFORE any Slang port; the port must reproduce these hashes (A/B), never regenerate them.
 */
public final class RtBaselineRunner {
    private static final int WIDTH = 320, HEIGHT = 240, WARMUP = 30, FRAMES = 120;

    private RtBaselineRunner() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "").toAbsolutePath();
        List<Result> results = new ArrayList<>();
        results.add(measureTest(root));
        results.add(measureRays(root));
        results.add(measurePath(root));
        String markdown = report(root, results);
        Path doc = root.resolve("docs").resolve("RT_BASELINE.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("[BEDRAY-RT-BASELINE] wrote " + doc);
    }

    private record Result(String scene, String hash, Path png, long gpuP50Ns, long gpuP95Ns, long gpuP99Ns,
                          long harnessP50Ns, int hits, String deviceName, int driverVersion, int apiVersion,
                          boolean gpuTimers, float periodNs) {}

    private static Result measureTest(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             SimpleAccelerationStructureBuilder as = new SimpleAccelerationStructureBuilder(probe.device);
             RtTestPipeline pipeline = new RtTestPipeline(probe.device);
             GpuTimer timer = new GpuTimer(probe)) {
            probe.render(cmd -> { as.buildAll(cmd); pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT); });
            for (int i = 0; i < WARMUP; i++)
                probe.render(cmd -> pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT));
            long[] gpu = new long[FRAMES], harness = new long[FRAMES];
            byte[] image = null;
            for (int i = 0; i < FRAMES; i++) {
                long start = System.nanoTime();
                final int frame = i;
                image = probe.render(cmd -> {
                    timer.begin(cmd);
                    pipeline.dispatchTest(cmd, probe.targetView, as.getTlasHandle(), WIDTH, HEIGHT);
                    timer.end(cmd);
                });
                harness[i] = System.nanoTime() - start;
                gpu[i] = timer.read();
            }
            return result("rt_test", probe, timer, gpu, harness, image, root, countHits(image, 24));
        }
    }

    private static Result measureRays(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe);
             RtPipeline pipeline = new RtPipeline(probe.device, false);
             GpuTimer timer = new GpuTimer(probe)) {
            pipeline.bind(new RtPipeline.Views(scene.tlas, scene.table, scene.outputView, scene.atlasView,
                scene.materialView, scene.normalView, 0L, null, scene.depthView, scene.lightmapView));
            probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT); });
            for (int i = 0; i < WARMUP; i++) {
                final int frame = i;
                probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(frame, 0), WIDTH, HEIGHT); });
            }
            long[] gpu = new long[FRAMES], harness = new long[FRAMES];
            byte[] image = null;
            for (int i = 0; i < FRAMES; i++) {
                long start = System.nanoTime();
                final int frame = WARMUP + i;
                image = probe.render(cmd -> {
                    timer.begin(cmd);
                    scene.build(cmd);
                    pipeline.dispatch(cmd, scene.params(frame, 0), WIDTH, HEIGHT);
                    timer.end(cmd);
                });
                harness[i] = System.nanoTime() - start;
                gpu[i] = timer.read();
            }
            return result("rt_rays", probe, timer, gpu, harness, image, root, countHits(image, 24));
        }
    }

    private static Result measurePath(Path root) throws Exception {
        try (RtBaselineProbe probe = new RtBaselineProbe(WIDTH, HEIGHT);
             RtBaselineScene scene = new RtBaselineScene(probe);
             RtPipeline pipeline = new RtPipeline(probe.device, RtPipeline.PATH_SHADER_PATH, "rt_path.comp", "", false, true);
             GpuTimer timer = new GpuTimer(probe)) {
            pipeline.bind(new RtPipeline.Views(scene.tlas, scene.table, scene.outputView, scene.atlasView,
                scene.materialView, scene.normalView, scene.accumView, null, scene.depthView, scene.lightmapView));
            probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(0, 0), WIDTH, HEIGHT); });
            for (int i = 0; i < WARMUP; i++) {
                final int frame = i;
                probe.render(cmd -> { scene.build(cmd); pipeline.dispatch(cmd, scene.params(frame, Math.min(i, RtFrameParams.MAX_ACCUM)), WIDTH, HEIGHT); });
            }
            long[] gpu = new long[FRAMES], harness = new long[FRAMES];
            byte[] image = null;
            for (int i = 0; i < FRAMES; i++) {
                long start = System.nanoTime();
                final int frame = WARMUP + i;
                final int accum = Math.min(WARMUP + i, RtFrameParams.MAX_ACCUM);
                image = probe.render(cmd -> {
                    timer.begin(cmd);
                    scene.build(cmd);
                    pipeline.dispatch(cmd, scene.params(frame, accum), WIDTH, HEIGHT);
                    timer.end(cmd);
                });
                harness[i] = System.nanoTime() - start;
                gpu[i] = timer.read();
            }
            return result("rt_path", probe, timer, gpu, harness, image, root, countHits(image, 24));
        }
    }

    private static Result result(String scene, RtBaselineProbe probe, GpuTimer timer, long[] gpu, long[] harness,
                                 byte[] image, Path root, int hits) throws Exception {
        java.util.Arrays.sort(gpu);
        java.util.Arrays.sort(harness);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        Path png = root.resolve("build").resolve("reports").resolve("rt").resolve("baseline-" + scene + ".png");
        writeImage(image, png);
        return new Result(scene, hash, png, percentile(gpu, 0.50), percentile(gpu, 0.95), percentile(gpu, 0.99),
            percentile(harness, 0.50), hits, probe.deviceName, probe.driverVersion, probe.apiVersion,
            timer.available, timer.periodNs);
    }

    private static int countHits(byte[] rgba, int threshold) {
        int hits = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            int r = Byte.toUnsignedInt(rgba[i * 4]), g = Byte.toUnsignedInt(rgba[i * 4 + 1]), b = Byte.toUnsignedInt(rgba[i * 4 + 2]);
            if (r + g + b > threshold) hits++;
        }
        return hits;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    private static void writeImage(byte[] rgba, Path output) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = (x + WIDTH * y) * 4;
            image.setRGB(x, y, Byte.toUnsignedInt(rgba[at + 3]) << 24 | Byte.toUnsignedInt(rgba[at]) << 16
                | Byte.toUnsignedInt(rgba[at + 1]) << 8 | Byte.toUnsignedInt(rgba[at + 2]));
        }
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(image, "png", output.toFile())) throw new IllegalStateException("PNG writer is unavailable");
    }

    private static String report(Path root, List<Result> results) {
        Result first = results.getFirst();
        StringBuilder md = new StringBuilder(4096);
        md.append("# RT baseline (Stage 2.0, GLSL reference)\n\n");
        md.append("Generated ").append(LocalDate.now()).append(" by `:common:rtBaseline`. ")
            .append("Evidence level: **L2 offscreen** on a real Vulkan 1.2 ray-query device. Not in-game (L4). ")
            .append("The harness waits for the queue every frame, so `harness_p50_ms` is an upper bound; ")
            .append("`gpu_dispatch_*` come from timestamp queries around the dispatch. ")
            .append("This is the GLSL reference captured BEFORE any Slang port; the port must reproduce these hashes (A/B).\n\n");
        md.append("- Device: ").append(first.deviceName()).append(", driverVersion raw `").append(first.driverVersion())
            .append("`, device Vulkan API ").append(VK_VERSION_MAJOR(first.apiVersion())).append('.')
            .append(VK_VERSION_MINOR(first.apiVersion())).append('.').append(VK_VERSION_PATCH(first.apiVersion())).append('\n');
        md.append("- GPU timestamp queries: ").append(first.gpuTimers() ? "available" : "UNAVAILABLE")
            .append(", period ").append(String.format(Locale.ROOT, "%.3f", first.periodNs())).append(" ns\n");
        md.append("- Resolution ").append(WIDTH).append('x').append(HEIGHT).append(", ").append(WARMUP)
            .append(" warmup frames discarded, ").append(FRAMES).append(" measured frames per scene; fixed camera, fixed lighting.\n\n");
        md.append("## GPU dispatch timings (").append(FRAMES).append(" frames)\n\n");
        md.append("| scene | gpu_p50_ms | gpu_p95_ms | gpu_p99_ms | harness_p50_ms | hits |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Result r : results)
            md.append("| ").append(r.scene()).append(" | ").append(ms(r.gpuP50Ns())).append(" | ").append(ms(r.gpuP95Ns()))
                .append(" | ").append(ms(r.gpuP99Ns())).append(" | ").append(ms(r.harnessP50Ns()))
                .append(" | ").append(r.hits()).append('/').append(WIDTH * HEIGHT).append(" |\n");
        md.append("\n## Images\n\n");
        md.append("| scene | SHA-256 (final frame) | PNG |\n|---|---|---|\n");
        for (Result r : results)
            md.append("| ").append(r.scene()).append(" | `").append(r.hash()).append("` | `")
                .append(root.relativize(r.png())).append("` |\n");
        md.append("\n## Scenes\n\n");
        md.append("- **rt_test**: one hardware triangle (SimpleAccelerationStructureBuilder), minimal ray-query shader.\n");
        md.append("- **rt_rays**: one synthetic section (floor + back wall, solid pass), MODE_LIT, no optional flags.\n");
        md.append("- **rt_path**: same scene, accumulation variant (VKB_ACCUM), accumulating over the measured window.\n");
        md.append("\n## Reproduce\n\n```\n./gradlew :common:rtBaseline\n```\n\n");
        md.append("A/B gate (Stage 2.1/2.2): the Slang port must reproduce these hashes (or a documented, justified delta), ")
            .append("GPU totals within measurement noise. Do not regenerate this baseline to hide a delta.\n");
        return md.toString();
    }

    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos * 1.0e-6); }

    /** Two timestamp queries around the dispatch; harvested after the per-frame queue wait. */
    private static final class GpuTimer implements AutoCloseable {
        private final RtBaselineProbe probe;
        private long pool;
        boolean available;
        float periodNs;
        GpuTimer(RtBaselineProbe probe) {
            this.probe = probe;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(probe.device.getPhysicalDevice(), props);
                periodNs = props.limits().timestampPeriod();
                available = props.limits().timestampComputeAndGraphics() != 0;
                LongBuffer handle = stack.mallocLong(1);
                int err = vkCreateQueryPool(probe.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, handle);
                if (err == VK_SUCCESS) pool = handle.get(0); else available = false;
            }
        }
        void begin(VkCommandBuffer cmd) { if (available) { vkCmdResetQueryPool(cmd, pool, 0, 2); vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, pool, 0); } }
        void end(VkCommandBuffer cmd) { if (available) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, pool, 1); }
        long read() {
            if (!available) return 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer data = stack.mallocLong(2);
                int err = vkGetQueryPoolResults(probe.device, pool, 0, 2, data, 8, VK_QUERY_RESULT_64_BIT);
                if (err != VK_SUCCESS) return 0;
                return (data.get(1) - data.get(0)) * (long) periodNs;
            }
        }
        @Override public void close() { if (pool != 0) vkDestroyQueryPool(probe.device, pool, null); }
    }
}
```

- [ ] **Step 2: Add the `rtBaseline` JavaExec task to `common/build.gradle`**

Add after the `rtProbe` task. Mirrors `compute-common`'s `computeBaseline` (workingDir root, args projectDir, stackSize 256, per-task native extract path, shaderc natives from launcher libs).

```gradle
tasks.register('rtBaseline', JavaExec) {
    group = 'verification'
    description = 'V0 RT baseline: three fixed offscreen ray-query scenes -> docs/RT_BASELINE.md (L2, not in-game)'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath + files(shadercNative)
    mainClass = 'io.github.andrexarlay.vulkancompat.rt.harness.RtBaselineRunner'
    workingDir = rootProject.projectDir
    args rootProject.projectDir.absolutePath
    systemProperty 'org.lwjgl.system.stackSize', '256'
    jvmArgs '--enable-native-access=ALL-UNNAMED'
    doFirst {
        if (!shadercNative.isFile()) {
            throw new GradleException("LWJGL shaderc natives jar not found (set BEDRAY_LAUNCHER_LIBRARIES or lwjgl_libs_dir, see docs/BUILDING.md): " + shadercNative)
        }
        systemProperty 'org.lwjgl.system.SharedLibraryExtractPath', new File(temporaryDir, 'lwjgl-natives').absolutePath
    }
}
```

- [ ] **Step 3: Run the baseline (single Gradle task)**

```
./gradlew :common:rtBaseline --console=plain
```

Expected: BUILD SUCCESSFUL; `docs/RT_BASELINE.md` written with three scene rows (rt_test, rt_rays, rt_path), each with a SHA-256 hash, GPU dispatch percentiles and hit counts; PNGs under `build/reports/rt/`. If `rt_path` fails to converge or the accum variant errors, investigate the accum binding/`accumCount` (do not drop the scene silently — the spec requires the rt_path baseline). Record all three hashes.

- [ ] **Step 4: Full regression of the touched module (single Gradle task; XML is truth)**

```
./gradlew :common:test :common:runShimTests --console=plain
```

Expected: existing `common` tests + `runShimTests` stay green (the new harness is additive; `RtBaselineProbeTest` is gated behind `bedray.rt.rtProbe` so it is skipped in the plain `test` run). Read the XML result dirs → `failures="0" errors="0"`. `compute-common` and `renderer-api` are untouched.

- [ ] **Step 5: Verification checkpoint (no commit)**

Confirm `docs/RT_BASELINE.md` exists with three stable hashes and the regression XML is green. Do NOT commit, push, or deploy. Report the Stage 2.0 completion in Russian: the Tier 1 toolchain + cap audit + offscreen RT baseline are in place; the GLSL reference hashes are recorded for the Stage 2.1/2.2 A/B gates.

---

## Self-Review

**1. Spec coverage (Stage 2.0):**
- Tier 1 toolchain in `common/build.gradle` extending `gradle/slang.gradle`, direct SPIR-V 1.4, via-glsl fallback noted → Task 1. ✓
- `compileSlangShaders` proven on a representative RT probe → Task 1 (probe exercises ray query + subgroup + device-address pointer + storage image). ✓
- Tier 1 cap-audit test, caps pinned empirically → Task 2 (dump in Task 1 Step 4, pinned constant in Task 2). ✓
- Offscreen RT harness `:common:rtBaseline` mirroring `ComputeBaselineRunner` → Tasks 3–5. ✓
- Fixed synthetic RT scene (TLAS/BLAS + section tables + atlas + StatsBuf + push constants) → Task 4 (`RtBaselineScene`). ✓
- Render GLSL reference via shaderc, read back image hashes + GPU timings → Tasks 3–5 (`RtTestPipeline`/`RtPipeline` use shaderc; `GpuTimer`). ✓
- `rt_rays` AND `rt_path` baselines → Task 4 (rt_rays) + Task 5 (rt_path accum). ✓
- Emit `docs/RT_BASELINE.md` → Task 5. ✓
- NO shader port → the probe is a NEW toolchain artifact, not a production port; `rt_test`/`rt_rays`/`rt_path` are rendered through the unchanged GLSL path. ✓
- Measure-before-shader: baseline captured before any port; A/B gate documented in the report. ✓
- Owner-permission guards: every commit step replaced with a verification checkpoint; no deploy. ✓

**2. Placeholder scan:** No TBD/TODO/"implement later"/"similar to Task N". Every code step has full literal code. The cap set is pinned empirically (Task 1 Step 4 dump) with the spike-derived expected values stated; Task 2 reconciles against the actual dump. ✓

**3. Type consistency:**
- `RtBaselineProbe`: `device`, `queue`, `command`, `targetView`, `width`, `height`, `memory()`, `render(Consumer<VkCommandBuffer>)`, `deviceName`, `driverVersion`, `apiVersion` — used consistently in Tasks 3/4/5. ✓
- `RtBaselineScene`: `tlas`, `table`, `outputView`, `atlasView`, `materialView`, `normalView`, `depthView`, `lightmapView`, `accumView`, `statsAddress`, `params(frame, accumCount)`, `build(cmd)` — match `RtPipeline.Views(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, lightmap)` and `RtFrameParams`. ✓
- `RtPipeline` constructors: `new RtPipeline(device, false)` (rt_rays), `new RtPipeline(device, PATH_SHADER_PATH, "rt_path.comp", "", false, true)` (rt_path accum) — match the restored `RtPipeline.java`. ✓
- `RtMemory.Buf` fields `handle/memory/size/address/mapped`, `RtMemory.Img` fields `image/view/memory`, `createBuffer(what,size,usage,hostVisible)`, `createImage(what,w,h,format,usage)`, `USAGE_AS_INPUT` — match `RtMemory.java`. ✓
- `RtInstanceWriter.write(address, customIndex, mask, blasAddress, transform[12])`, `SIZEOF=64` — match. ✓
- `RtStatsLayout.EXPOSURE/SUN_RADIUS/HISTORY_CAP/GI_DIST/EMIS_DIST/AO_RADIUS/SLOT_STRIDE` — match. ✓
- `RtFrameParams.MAX_ACCUM`, `params.write` via `RtPipeline.dispatch` — match. ✓
- `SimpleAccelerationStructureBuilder.buildAll(cmd)/getTlasHandle()`, `RtTestPipeline.dispatchTest(cmd, view, tlas, w, h)` — match. ✓

**Known execution risks (flagged, not hidden):**
- `rt_path` accum convergence offscreen may need tuning of `accumCount`/flags; if the accum variant cannot produce a stable baseline, stop and investigate (spec requires the rt_path baseline) rather than dropping it.
- The synthetic `rt_rays` scene uses flags=0 (primary-hit shade). If the shade path reads a StatsBuf field left zero that causes NaN, set that field explicitly (the layout is fully known) — investigate, do not regenerate the baseline.
- `parse_caps.js` is a gitignored throwaway; Task 1 Step 4 includes a fallback dumper spec if it was cleaned.

---

## Execution results & corrections (Stage 2.0 COMPLETE, verified 2026-11-12)

All five tasks executed inline (executing-plans) on the RTX 5060 Ti worktree. The plan code above had
several compile-breakers and API mismatches; the **on-disk files are the truth**. Corrections applied:

**Task 4 — `RtBaselineScene.java`:**
- `RtFrameParams.invViewProj` is a `Matrix4f`, not `float[]`. The plan's `new Matrix4f()...get(p.invViewProj)`
  is invalid. Corrected to in-place: `p.invViewProj.perspective((float) Math.toRadians(70), aspect, 0.05f, 1000f).invert();`
  (the `org.joml.Matrix4f` import is then unused and was dropped, along with unused `org.lwjgl.PointerBuffer`).
- StatsBuf zeroing: `MemoryUtil.memByteBuffer(...).array()` throws on a direct buffer. Corrected to
  `MemoryUtil.memSet(stats.mapped, 0, RtStatsLayout.SLOT_STRIDE);`.
- `table` must be **package-private** (the test reads `scene.table`); it was `private` in the plan field list.

**Task 4 — `RtBaselineProbeTest.rtRaysRendersStableScene`:**
- `RtPipeline` is `public final class` with `public void destroy()`, **not `AutoCloseable`**. The plan's
  try-with-resources on `RtPipeline` does not compile. Corrected: `probe`+`scene` stay in try-with-resources;
  `RtPipeline` is created in the body and released in `finally { pipeline.destroy(); }`.

**Task 5 — `RtBaselineRunner.java`:**
- Same `RtPipeline` fix in `measureRays`/`measurePath` (try/finally + `destroy()`); `RtTestPipeline` and
  `SimpleAccelerationStructureBuilder` ARE `AutoCloseable` (proven in Task 3), so `measureTest` is unchanged.
- `VkPhysicalDeviceLimits.timestampComputeAndGraphics()` returns **`boolean`** in LWJGL 3.3.3 (VkBool32 mapped
  to boolean), so `!= 0` is an incomparable-types error. Corrected to `available = props.limits().timestampComputeAndGraphics();`.
- `measurePath` warmup lambda captured the mutable loop var `i` in `Math.min(i, MAX_ACCUM)`. Corrected to
  `Math.min(frame, MAX_ACCUM)` (`frame == i`, already final).
- Dropped the unused `final int frame = i;` in `measureTest`'s measured loop (`dispatchTest` takes no frame).

**Verified baseline (`docs/RT_BASELINE.md`, GLSL reference via shaderc, 320x240, 30 warmup + 120 measured):**

| scene | SHA-256 (final frame) | hits | gpu_p50_ms |
|---|---|---|---|
| rt_test | `2da2605c6d5537722bcda95273e9e24a60ce32b1dfd5bdc0e5bbd03eddbc8930` | 40401/76800 | 0.005 |
| rt_rays | `1e92574e51e677c7f8037ff07e53dfe0a573f4b4b797c8109e89e309991d868e` | 28518/76800 | ~0.19 |
| rt_path | `cf7c8196d5c2f3e6d812d1e98c47adb362c2bd78e4e34cfb46d23d7f415caf0c` | 26963/76800 | ~0.21 |

- **Reproducibility proven**: two consecutive `:common:rtBaseline` runs produced byte-identical hashes for all
  three scenes (GPU timings vary within noise). `rt_path` is deterministic at fixed frame despite the accum
  image starting undefined (flags=0 → standalone frame, RNG seeded by frame).
- The runner's `rt_rays` hash (frame 149) legitimately differs from the probe test's `rt_rays` hash
  `1e7dade2b3a34519fa9ab7cc7771aa025cfcba460ddc3ff9542b13cca6e51e20` (frame 0): `rt_rays` uses `pc.frame`
  for temporal noise; hit count is identical (28518). The probe asserts determinism at a FIXED frame.
- Device: NVIDIA GeForce RTX 5060 Ti, Vulkan API 1.4.357, GPU timestamp queries available (period 1.0 ns).
- `rt_rays.comp` spv_bytes=2129152, `rt_path.comp` spv_bytes=991348 (runtime shaderc, source=miss — correct
  for the GLSL reference; no Slang port in this stage).

**Regression (XML is truth):**
- `:common:test` → 501 tests, **0 failures, 0 errors**, 3 skipped (the `bedray.rt.rtProbe`-gated
  `RtBaselineProbeTest` pair + one pre-existing skip). `RtSlangCapabilityTest` runs in the plain suite (green).
- `:common:runShimTests` → **36 passed, 0 failed** (console summary is its truth; no XML emitted).
- `:common:rtProbe` (gated) → 2 tests, 0 failures, 0 errors (rt_test + rt_rays).

**Guards honored:** no git commit/push, no deploy (owner permission required). All changes uncommitted on
branch `integration/combined-jar-20261025`. English code/comments; this report section is the living-doc record.

**Next:** Stage 2.1 (plain compute shaders → Slang) gets its own plan; the A/B gate is the three hashes above.