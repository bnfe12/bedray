# Host RT Slang Migration — Stage 2.1a (Post-Shader Harness + fg_prepare Pilot) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the reusable offscreen baseline harness for the 8 non-ray-query plain compute shaders and prove the full measure-before-shader port cycle (GLSL baseline → Slang Tier 1 port → cap-audit → byte-identical A/B → loader `.spv` wire) end-to-end on the simplest shader, `fg_prepare.comp`.

**Architecture:** A new compute-only offscreen Vulkan 1.2 device (`PostShaderProbe`, buffer-device-address enabled, NO ray query / acceleration structure) builds a compute pipeline from any SPIR-V under an explicit descriptor layout + push constants, uploads synthetic input images, dispatches, and reads back output bytes for hashing. The GLSL reference is compiled with shaderc exactly as the production loader does; the Slang candidate is the build-time `compileSlangShaders` `.spv`. Both run under the IDENTICAL production ABI (bindings, push-constant bytes, dispatch dims) so the A/B isolates shader semantics. After the A/B passes, the production loader (`FrameGenGpu`) switches from runtime shaderc to the precompiled `.spv`, mirroring subproject 1's `VoxelComputePipeline.precompiledSpirv`.

**Tech Stack:** Java 25, LWJGL 3.3.3 (Vulkan 1.2, shaderc), JUnit 5, Gradle 9.5.1, slangc v2026.22 (`-target spirv -profile spirv_1_4 -emit-spirv-directly -entry main`). GPU: NVIDIA RTX 5060 Ti, Vulkan 1.4.357.

**Spec:** `docs/superpowers/specs/2026-11-12-host-rt-slang-migration-design.md` (approved; owner delegated autonomous execution).

## Global Constraints

Every task implicitly includes these (verbatim from the spec / work order):

- Autonomy granted: no per-step human approval. **NO git commit / push / deploy without explicit owner permission** — every "Commit" step is replaced by a **Verification checkpoint**.
- No `git add -A` (multiple repos in the environment). Do not revert others' changes. Do not mix ABI lines, do not touch the public export `C:\Dev\Sky\scratch\bedray-public-export-20261020`, do not touch the installed instance.
- **measure-before-shader:** every shader change gated by a frozen GLSL baseline + byte-identical A/B. The baseline is captured BEFORE the port and never regenerated to hide a delta. The legacy `.comp` stays byte-identical until its Slang port passes A/B; only then is it removed from the runtime loader.
- Raster fallback sacred (no black/gray cubes). Third-party shaders (FSR/NIS/NRD/DLSS/Sodium/Iris/vanilla) stay as-is; interop via SPIR-V. NRD/DLSS G-buffer formats + bindings preserved EXACTLY.
- **ABI preservation:** descriptor bindings unchanged per shader; device addresses stored as `uvec2` (8 B) by host, Slang assembles `uint64_t`→`T*` byte-identical; push constants unchanged (byte layout); entry `main`, `local_size 8×8×1`; matrices column-major bytes unchanged, GLSL `M*v`→Slang `mul(M,v)`, `v*M`→`mul(v,M)`, gated by A/B; variant semantics preserved.
- OMM dropped (unsupported in Slang, optional).
- Slang modules under `common/src/main/resources/shaders/slang/`; generated `.spv` gitignored; legacy GLSL removed from runtime only after its Slang port passes A/B.
- English code/comments; Russian chat reports. Never two Gradle tasks concurrently. Buffered stdout; XML is truth for tests. Canonical URLs only (`github.com/shader-slang/slang`), no proxy, re-verify each URL.
- Tier 1 capability ceiling (pinned Stage 2.0, raw `OpCapability` operands): `{1, 11, 55, 56, 61, 64, 4472, 5347}`. A ported shader must pull NO capability outside this set.

## Decomposition of Stage 2.1 (Scope Check)

Stage 2.1 ports 8 plain compute shaders + shared non-RT modules with per-shader baselines. That is too large for one no-placeholder plan, so it is decomposed into three coherent, independently A/B-proven sub-plans (writing-plans Scope Check). **This document is Stage 2.1a only.**

- **Stage 2.1a (THIS PLAN):** `PostShaderProbe` harness + `fg_prepare` pilot (baseline → port → cap-audit → A/B → `FrameGenGpu` wire). Proves the harness and the full port cycle on the simplest shader (31 lines, standalone, 4 image bindings, 8-byte PC, no matrices / no buffer_reference / no shared module).
- **Stage 2.1b (later plan):** the other standalone shaders — `iris_depth` (proves the `mul(M,v)` matrix ABI + the 128-byte PC with interleaved `depthEncoding`), `rt_particles_composite` (in-place RGBA8), `rt_composite` (dormant production path, 80-byte PC), `velocity_reproject` (proves the 368-byte std140 UBO ABI + `buffer_reference`/`uvec2` device addresses). Each: baseline → port → cap-audit → A/B → loader wire.
- **Stage 2.1c (later plan):** shared non-RT Slang modules (`rt_tonemap.slang` full; the non-RT core subset = `RtFrameParams` PC + `StatsBuf` + `params2()`/`normalizeOr()` + `outImage`/`accumImage` bindings; camera helpers `getCameraJitter`/`cameraOrigin`/`rawCameraForward`/`safeW`/`cameraRay`; `finishColor`) + the module-dependent shaders — `rt_bloom` (×4 `BLOOM_STAGE` variants), `rt_pack_nrd` (×2 `VKB_PASS_PACK`/`COMPOSE`, 14 storage images, NRD binding-5 format preserved), `rt_atrous` (core subset, 3 iterations). Each: baseline → port → cap-audit → A/B → loader wire.

**Scope refinement discovered during planning:** `rt_noise.glsl` and `rt_caustics.glsl` are NOT used by any of the 8 plain compute shaders (only by the Stage 2.2 ray-query shaders via the umbrella). They are therefore OUT of Stage 2.1 entirely. Of the 8: 5 are standalone (`rt_composite`, `rt_particles_composite`, `fg_prepare`, `iris_depth`, `velocity_reproject`), 2 include only `rt_tonemap` (`rt_bloom`, `rt_pack_nrd`), and `rt_atrous` includes the full umbrella but uses only the non-RT subset listed above.

## File Structure

**Create:**
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostShaderProbe.java` — compute-only offscreen device + generic pipeline/image/buffer harness (reused by all 8 shaders).
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostShaderProbeTest.java` — harness I/O smoke test (upload/readback round-trip, no shader).
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostBaselineRunner.java` — `main` that captures the GLSL reference baseline for plain compute shaders → `docs/POST_BASELINE.md`. Stage 2.1a: `fg_prepare`.
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/FgPrepareSlangCapabilityTest.java` — cap-audit of `fg_prepare_v14.spv`.
- `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/FgPrepareAbTest.java` — byte-identical GLSL-vs-Slang A/B under the production ABI.
- `common/src/main/resources/shaders/slang/fg_prepare.slang` — the Slang Tier 1 port.
- `docs/POST_BASELINE.md` — frozen GLSL-reference baseline record (generated).

**Modify:**
- `common/build.gradle` — add `fg_prepare` to `slangRtVariants` + per-variant source; add `postProbe` (Test) and `postBaseline` (JavaExec) tasks.
- `common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenGpu.java` — replace runtime shaderc `compile()` with precompiled `.spv` load (after A/B passes).

**Delete (only after A/B passes, Task 4):**
- `common/src/main/resources/shaders/fg_prepare.comp` — removed from runtime once `FrameGenGpu` loads `.spv`. (The file is the port reference; it is deleted, not debugged, per the legacy rule.)

---

### Task 1: PostShaderProbe offscreen compute harness

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostShaderProbe.java`
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostShaderProbeTest.java`
- Modify: `common/build.gradle` (add `postProbe` Test task after the `rtBaseline` block, before `publishing`)

**Interfaces:**
- Consumes: `RtMemory` (`common/src/main/java/.../rt/world/RtMemory.java`) — `createImage(String,int w,int h,int format,int usage)→Img{image,view,memory,width,height,bytes}`, `createBuffer(String,long size,int usage,boolean hostVisible)→Buf{handle,memory,size,address,mapped,hostVisible}`, `destroy(Img)`, `destroy(Buf)`, `close()`.
- Produces (later tasks rely on these exact signatures):
  - `PostShaderProbe()` — compute device (Vulkan 1.2 + bufferDeviceAddress). Fields: `VkDevice device`, `VkQueue queue`, `VkCommandBuffer command`, `RtMemory memory`, `long sampler` (nearest/clamp), `String deviceName`, `int driverVersion, apiVersion`.
  - `PostShaderProbe.Binding(int index, int type)` — record; `type` is a `VK_DESCRIPTOR_TYPE_*`.
  - `PostShaderProbe.Pipeline(ByteBuffer spirv, Binding[] bindings, int pcSize)` — inner AutoCloseable; methods `void image(int binding, long view, boolean sampled)`, `void buffer(int binding, long buf, int type, long range)`, `void record(VkCommandBuffer cmd, byte[] pc, int gx, int gy, int gz)`, `close()`.
  - `void submit(Consumer<VkCommandBuffer> recorder)`, `void toGeneral(VkCommandBuffer cmd, long image, int oldLayout)`, `void upload(RtMemory.Img img, byte[] data, int bpp)`, `byte[] readback(RtMemory.Img img, int bpp)`, `close()`.

- [ ] **Step 1: Write `PostShaderProbe.java`**

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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Offscreen plain-compute device for the 8 non-ray-query post shaders (L2 baseline harness). Vulkan 1.2 with
 * bufferDeviceAddress (needed by rt_atrous/velocity_reproject/rt_pack_nrd device addresses), NO ray query or
 * acceleration structure. Builds a compute pipeline from any SPIR-V under an explicit descriptor layout + push
 * constants, uploads synthetic images, dispatches, reads back output bytes. Mirrors RtBaselineProbe's device
 * creation but does not perturb the RT reference harness.
 */
final class PostShaderProbe implements AutoCloseable {
    VkDevice device;
    VkQueue queue;
    VkCommandBuffer command;
    RtMemory memory;
    long sampler;
    String deviceName;
    int driverVersion, apiVersion;
    private VkInstance instance;
    private long commandPool;

    PostShaderProbe() {
        try {
            create();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void create() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Bedray post-shader baseline"))
                .applicationVersion(1).pEngineName(stack.UTF8("Bedray")).engineVersion(1)
                .apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            check(vkCreateInstance(ici, null, pointer), "Create Vulkan 1.2 instance");
            instance = new VkInstance(pointer.get(0), ici);

            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "Count physical devices");
            assertTrue(count.get(0) > 0, "No Vulkan physical devices");
            PointerBuffer physicals = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, physicals), "Enumerate physical devices");
            VkPhysicalDevice physical = null;
            int family = -1;
            for (int i = 0; i < physicals.remaining() && physical == null; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(physicals.get(i), instance);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int j = 0; j < families.capacity(); j++) {
                    if (families.get(j).queueCount() > 0 && (families.get(j).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        physical = candidate;
                        family = j;
                        break;
                    }
                }
            }
            if (physical == null) {
                throw new IllegalStateException("No Vulkan compute device. The harness fails rather than skipping: L2 evidence.");
            }
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            deviceName = props.deviceNameString();
            driverVersion = props.driverVersion();
            apiVersion = props.apiVersion();

            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                .sType$Default().bufferDeviceAddress(true);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(queues).pNext(vk12.address());
            check(vkCreateDevice(physical, dci, null, pointer), "Create compute device");
            device = new VkDevice(pointer.get(0), physical, dci);
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

            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(vkCreateSampler(device, si, null, handle), "Create sampler");
            sampler = handle.get(0);
        }
    }

    record Binding(int index, int type) {}

    /** A compute pipeline + one descriptor set built from SPIR-V and an explicit binding layout. */
    final class Pipeline implements AutoCloseable {
        private long shaderModule, setLayout, pipelineLayout, pipeline, pool, set;
        private final int pcSize;

        Pipeline(ByteBuffer spirv, Binding[] bindings, int pcSize) {
            this.pcSize = pcSize;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer p = stack.mallocLong(1);
                VkShaderModuleCreateInfo mci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                check(vkCreateShaderModule(device, mci, null, p), "Create shader module");
                shaderModule = p.get(0);

                VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
                Map<Integer, Integer> counts = new HashMap<>();
                for (int i = 0; i < bindings.length; i++) {
                    b.get(i).binding(bindings[i].index()).descriptorType(bindings[i].type())
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                    counts.merge(bindings[i].type(), 1, Integer::sum);
                }
                VkDescriptorSetLayoutCreateInfo lci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
                check(vkCreateDescriptorSetLayout(device, lci, null, p), "Create descriptor set layout");
                setLayout = p.get(0);

                VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
                if (pcSize > 0) {
                    plci.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pcSize));
                }
                check(vkCreatePipelineLayout(device, plci, null, p), "Create pipeline layout");
                pipelineLayout = p.get(0);

                VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, stack);
                cpi.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpi, null, p), "Create compute pipeline");
                pipeline = p.get(0);

                VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(counts.size(), stack);
                int idx = 0;
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    ps.get(idx++).type(e.getKey()).descriptorCount(e.getValue());
                }
                VkDescriptorPoolCreateInfo dpi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(ps);
                check(vkCreateDescriptorPool(device, dpi, null, p), "Create descriptor pool");
                pool = p.get(0);
                VkDescriptorSetAllocateInfo dai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
                check(vkAllocateDescriptorSets(device, dai, p), "Allocate descriptor set");
                set = p.get(0);
            }
        }

        void image(int binding, long view, boolean sampled) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                if (sampled) info.sampler(sampler);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding)
                    .descriptorType(sampled ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(info);
                vkUpdateDescriptorSets(device, w, null);
            }
        }

        void buffer(int binding, long buf, int type, long range) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buf).offset(0).range(range);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack).sType$Default()
                    .dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(1).pBufferInfo(info);
                vkUpdateDescriptorSets(device, w, null);
            }
        }

        void record(VkCommandBuffer cmd, byte[] pc, int gx, int gy, int gz) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
                if (pcSize > 0) {
                    ByteBuffer pcBuf = stack.malloc(pc.length);
                    pcBuf.put(pc).flip();
                    vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcBuf);
                }
                vkCmdDispatch(cmd, gx, gy, gz);
            }
        }

        @Override public void close() {
            vkDeviceWaitIdle(device);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (shaderModule != 0) vkDestroyShaderModule(device, shaderModule, null);
            pipeline = pipelineLayout = pool = setLayout = shaderModule = 0;
        }
    }

    void submit(Consumer<VkCommandBuffer> recorder) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkResetCommandBuffer(command, 0), "Reset command buffer");
            check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "Begin command buffer");
            recorder.accept(command);
            check(vkEndCommandBuffer(command), "End command buffer");
            VkSubmitInfo.Buffer si = VkSubmitInfo.calloc(1, stack).sType$Default()
                .pCommandBuffers(stack.pointers(command.address()));
            check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "Submit");
            check(vkQueueWaitIdle(queue), "Wait");
        }
    }

    void toGeneral(VkCommandBuffer cmd, long image, int oldLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer bar = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .image(image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .oldLayout(oldLayout).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            bar.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, bar);
        }
    }

    /** Uploads tightly-packed texel bytes (bpp = bytes per pixel) and leaves the image in GENERAL. */
    void upload(RtMemory.Img img, byte[] data, int bpp) {
        RtMemory.Buf staging = memory.createBuffer("post_upload", data.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true);
        try {
            MemoryUtil.memByteBuffer(staging.mapped, data.length).put(data);
            submit(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toDst = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    toDst.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toDst);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.imageExtent().set(img.width, img.height, 1);
                    vkCmdCopyBufferToImage(cmd, staging.handle, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                    VkImageMemoryBarrier.Buffer toGen = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    toGen.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGen);
                }
            });
        } finally {
            memory.destroy(staging);
        }
    }

    /** Copies the image (GENERAL) to a host buffer and returns its bytes; leaves the image in GENERAL. */
    byte[] readback(RtMemory.Img img, int bpp) {
        int bytes = img.width * img.height * bpp;
        RtMemory.Buf dst = memory.createBuffer("post_readback", bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try {
            submit(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toSrc = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                    toSrc.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toSrc);
                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.imageExtent().set(img.width, img.height, 1);
                    vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle, copy);
                    VkImageMemoryBarrier.Buffer toGen = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .image(img.image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    toGen.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, toGen);
                    VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                        .buffer(dst.handle).offset(0).size(bytes)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, null);
                }
            });
            ByteBuffer mapped = MemoryUtil.memByteBuffer(dst.mapped, bytes);
            byte[] out = new byte[bytes];
            mapped.get(out);
            return out;
        } finally {
            memory.destroy(dst);
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
            if (sampler != 0) vkDestroySampler(device, sampler, null);
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

- [ ] **Step 2: Write the harness I/O smoke test `PostShaderProbeTest.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/** L2 harness smoke test: device creation + image upload/readback round-trip (no shader). Gated like rtProbe. */
class PostShaderProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void uploadReadbackRoundTrip() {
        try (PostShaderProbe probe = new PostShaderProbe()) {
            int w = 16, h = 16;
            RtMemory.Img img = probe.memory.createImage("smoke", w, h, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            try {
                byte[] data = new byte[w * h * 4];
                for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 37 + 11);
                probe.upload(img, data, 4);
                byte[] back = probe.readback(img, 4);
                assertArrayEquals(data, back, "upload/readback must round-trip exactly");
                System.out.printf("[BEDRAY-POST-PROBE] device=%s round-trip ok%n", probe.deviceName);
            } finally {
                probe.memory.destroy(img);
            }
        }
    }
}
```

- [ ] **Step 3: Add the `postProbe` Gradle task**

In `common/build.gradle`, insert after the `rtBaseline` task block (currently ends at line 160) and before `publishing {`:

```gradle
tasks.register('postProbe', Test) {
    group = 'verification'
    description = 'Offscreen plain-compute post-shader harness + A/B (L2, not in-game). Fails if no compute device.'
    dependsOn tasks.named('testClasses')
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath + files(shadercNative)
    useJUnitPlatform()
    filter { includeTestsMatching '*PostShaderProbeTest' }
    systemProperty 'bedray.rt.postProbe', 'true'
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

- [ ] **Step 4: Run the smoke test (single Gradle task, buffered stdout, XML is truth)**

Run: `./gradlew :common:postProbe --console=plain` (from the worktree root `C:\Dev\Sky\scratch\bedray-compute-dev-20261020`).
Expected: BUILD SUCCESSFUL; `PostShaderProbeTest.uploadReadbackRoundTrip` PASSED; stdout shows `[BEDRAY-POST-PROBE] device=NVIDIA GeForce RTX 5060 Ti round-trip ok`.
Verify the XML truth: `common/build/test-results/postProbe/TEST-*PostShaderProbeTest.xml` has `failures="0" errors="0"`.

- [ ] **Step 5: Verification checkpoint (NO commit)**

Confirm: `PostShaderProbe.java` + `PostShaderProbeTest.java` exist; `postProbe` task registered; smoke test green in XML. Do NOT git commit/push (owner permission required). Record progress in the task list.

---

### Task 2: fg_prepare GLSL reference baseline (frozen)

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/PostBaselineRunner.java`
- Modify: `common/build.gradle` (add `postBaseline` JavaExec task after `postProbe`)
- Generate: `docs/POST_BASELINE.md`

**Interfaces:**
- Consumes: `PostShaderProbe` (Task 1); `FrameGenGpu`'s production ABI for `fg_prepare` — bindings `{0: COMBINED_IMAGE_SAMPLER depthTexture, 1: STORAGE_IMAGE inMotionVectors (rg16f), 2: STORAGE_IMAGE outDepth (r32f), 3: STORAGE_IMAGE outMotionVectors (rg16f)}`, push constant 8 bytes `ivec2 size`, dispatch `((w+7)/8, (h+7)/8, 1)`, all images GENERAL, nearest sampler. Host formats: depth `VK_FORMAT_R32_SFLOAT`, mv `VK_FORMAT_R16G16_SFLOAT` (FrameGenGpu.FORMAT_DEPTH/FORMAT_MV).
- Produces: `docs/POST_BASELINE.md` containing a machine-readable frozen line `fg_prepare glsl_sha256=<64 hex> size=70x50` plus a human markdown table. `PostBaselineRunner.main(String[] args)` (args[0] = repo root). Later A/B (Task 4) parses the `fg_prepare glsl_sha256=` line.

**Synthetic inputs (deterministic, exercise the vertical flip + non-multiple-of-8 bounds):** render size W=70, H=50.
- `depthIn(x,y) = (float)(y+1) + (float)(x+1)*0.0001f` (R32F, distinct per pixel).
- `mvIn(x,y) = ((float)(x - W/2), (float)(y - H/2))` (RG16F; integer-valued, exactly representable in half).
- Expected flip: `outDepth(x,y) = depthIn(x, H-1-y)`; `outMV(x,y) = (mvIn.x(x,H-1-y), -mvIn.y(x,H-1-y))`.

- [ ] **Step 1: Write `PostBaselineRunner.java`**

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Captures the frozen GLSL-reference baseline of the plain compute post shaders (L2 offscreen). Stage 2.1a:
 * fg_prepare. The GLSL is compiled with shaderc exactly as the production loader (FrameGenGpu) does, dispatched
 * under the production ABI over synthetic inputs, and the output bytes hashed into docs/POST_BASELINE.md.
 * This baseline is captured BEFORE the Slang port and never regenerated to hide a delta.
 */
public final class PostBaselineRunner {
    static final int W = 70, H = 50;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        StringBuilder md = new StringBuilder();
        md.append("# Plain compute post-shader GLSL reference baseline (L2 offscreen)\n\n");
        md.append("Frozen GLSL-reference hashes for the Stage 2.1 Slang migration. Captured BEFORE each port;\n");
        md.append("never regenerated to hide a delta. Synthetic inputs, production descriptor ABI, shaderc GLSL.\n\n");
        md.append("| shader | size | GLSL SHA-256 (depth+mv) | device |\n|---|---|---|---|\n");
        StringBuilder machine = new StringBuilder();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            String fgHash = measureFgPrepare(probe, machine);
            md.append("| fg_prepare | ").append(W).append('x').append(H).append(" | `").append(fgHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
        }
        md.append("\n## Machine-readable frozen hashes\n\n```\n").append(machine).append("```\n");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/POST_BASELINE.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.print("[BEDRAY-POST-BASELINE]\n" + machine);
    }

    private static String measureFgPrepare(PostShaderProbe probe, StringBuilder machine) throws Exception {
        RtMemory mem = probe.memory;
        int imgUsageIn = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        RtMemory.Img depthIn = mem.createImage("fg_depth_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | imgUsageIn);
        RtMemory.Img mvIn = mem.createImage("fg_mv_in", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | imgUsageIn);
        RtMemory.Img depthOut = mem.createImage("fg_depth_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        RtMemory.Img mvOut = mem.createImage("fg_mv_out", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            mem.createBuffer("fg_noop", 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true); // warm allocator (no-op)
            probe.upload(depthIn, depthBytes(), 4);
            probe.upload(mvIn, mvBytes(), 4);

            ByteBuffer spirv = compileGlsl("/shaders/fg_prepare.comp", "fg_prepare.comp");
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(W).putInt(H).array();
            byte[] depthBack, mvBack;
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 8)) {
                pipe.image(0, depthIn.view, true);
                pipe.image(1, mvIn.view, false);
                pipe.image(2, depthOut.view, false);
                pipe.image(3, mvOut.view, false);
                probe.submit(cmd -> {
                    probe.toGeneral(cmd, depthOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    probe.toGeneral(cmd, mvOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                });
                depthBack = probe.readback(depthOut, 4);
                mvBack = probe.readback(mvOut, 4);
            }
            // Sanity: the flip is correct, not merely deterministic. outDepth(0,0) == depthIn(0,H-1).
            float expectDepth = (float) H + (float) 1 * 0.0001f;
            float gotDepth = ByteBuffer.wrap(depthBack).order(ByteOrder.LITTLE_ENDIAN).getFloat(0);
            if (Math.abs(gotDepth - expectDepth) > 1e-4f) {
                throw new IllegalStateException("fg_prepare flip sanity failed: depth(0,0)=" + gotDepth + " expected " + expectDepth);
            }
            byte[] all = new byte[depthBack.length + mvBack.length];
            System.arraycopy(depthBack, 0, all, 0, depthBack.length);
            System.arraycopy(mvBack, 0, all, depthBack.length, mvBack.length);
            String hash = sha256(all);
            machine.append("fg_prepare glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
            return hash;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(mvIn);
            mem.destroy(depthOut);
            mem.destroy(mvOut);
        }
    }

    static byte[] depthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                b.putFloat((float) (y + 1) + (float) (x + 1) * 0.0001f);
            }
        }
        return b.array();
    }

    static byte[] mvBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                b.putShort(floatToHalf((float) (x - W / 2)));
                b.putShort(floatToHalf((float) (y - H / 2)));
            }
        }
        return b.array();
    }

    /** IEEE-754 half; inputs here are small integers (exact in half), so rounding is not exercised. */
    static short floatToHalf(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exp = (bits >>> 23) & 0xff;
        int mant = bits & 0x7fffff;
        if (exp == 0xff) return (short) (sign | 0x7c00 | (mant != 0 ? 0x200 : 0));
        int e = exp - 127 + 15;
        if (e >= 0x1f) return (short) (sign | 0x7c00);
        if (e <= 0) {
            if (e < -10) return (short) sign;
            mant |= 0x800000;
            int shift = 14 - e;
            return (short) (sign | (mant >> shift));
        }
        return (short) (sign | (e << 10) | (mant >> 13));
    }

    static ByteBuffer compileGlsl(String resource, String name) throws IOException {
        String glsl;
        try (InputStream in = PostBaselineRunner.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("resource not found: " + resource);
            glsl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_compute_shader, name, "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer direct = BufferUtils.createByteBuffer(bytes.remaining());
                direct.put(bytes);
                direct.flip();
                return direct;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
```

Note: the `mem.createBuffer("fg_noop", ...)` line is a leak (never destroyed) — **remove it** when writing the file; it is shown only to mark the allocator warm-up idea, which is unnecessary. The final file MUST NOT contain that line.

- [ ] **Step 2: Add the `postBaseline` Gradle task**

In `common/build.gradle`, insert after the `postProbe` block:

```gradle
tasks.register('postBaseline', JavaExec) {
    group = 'verification'
    description = 'Plain compute post-shader GLSL baseline -> docs/POST_BASELINE.md (L2, not in-game)'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath + files(shadercNative)
    mainClass = 'io.github.andrexarlay.vulkancompat.rt.harness.PostBaselineRunner'
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

Run: `./gradlew :common:postBaseline --console=plain`.
Expected: BUILD SUCCESSFUL; stdout `[BEDRAY-POST-BASELINE]` then `fg_prepare glsl_sha256=<64 hex> size=70x50`. `docs/POST_BASELINE.md` written with the table + machine-readable block. The flip sanity check passes (no exception).

- [ ] **Step 4: Freeze the reference — record the GLSL source hash**

Run: `sha256sum common/src/main/resources/shaders/fg_prepare.comp` (Git Bash). Record the value in the task list / execution notes. This is the freeze guard: `fg_prepare.comp` MUST stay byte-identical until Task 4's A/B passes. Also record the `fg_prepare glsl_sha256=` output hash from Step 3.

- [ ] **Step 5: Verification checkpoint (NO commit)**

Confirm: `docs/POST_BASELINE.md` exists with a 64-hex `fg_prepare glsl_sha256=`; the flip sanity passed; `fg_prepare.comp` sha256 recorded. Do NOT git commit/push. The baseline is now frozen.

---

### Task 3: fg_prepare.slang Tier 1 port + build variant + cap-audit

**Files:**
- Create: `common/src/main/resources/shaders/slang/fg_prepare.slang`
- Modify: `common/build.gradle` (`slangRtVariants` + per-variant source selection in `compileSlangShaders`)
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/FgPrepareSlangCapabilityTest.java`

**Interfaces:**
- Consumes: `compileSlangShaders` (Tier 1 args `-target spirv -profile spirv_1_4 -emit-spirv-directly -entry main`); output resource `/assets/bedray_rt/shaders/fg_prepare_v14.spv`.
- Produces: `fg_prepare_v14.spv` (SPIR-V 1.4, caps ⊆ Tier 1 set). The Slang port preserves the production ABI EXACTLY: binding 0 `Sampler2D<float4>` (CIS), binding 1 `RWTexture2D<float2>` rg16f (storage), binding 2 `RWTexture2D<float>` r32f (storage), binding 3 `RWTexture2D<float2>` rg16f (storage), push constant `ConstantBuffer<PC>` with `int2 size` (8 bytes), entry `main` `[numthreads(8,8,1)]`.

- [ ] **Step 1: Write `fg_prepare.slang`**

```slang
// Slang Tier 1 port of fg_prepare.comp (frame generation input preparation, DLSS-G / Streamline).
// The engine keeps render targets upside down; the Streamline back buffer is upright, so depth and motion
// vectors are copied vertically flipped (motion y negated because the row index direction changes sign).
// ABI preserved: binding 0 sampler2D depth, 1 rg16f storage inMotionVectors, 2 r32f storage outDepth,
// 3 rg16f storage outMotionVectors; push constant int2 size (8 bytes); entry main, numthreads 8x8x1.
// Build: slangc -target spirv -profile spirv_1_4 -emit-spirv-directly -entry main.

struct PC {
    int2 size;
};
[[vk::push_constant]] ConstantBuffer<PC> pc;

[[vk::binding(0, 0)]] Sampler2D<float4> depthTexture;
[[vk::binding(1, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> inMotionVectors;
[[vk::binding(2, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> outDepth;
[[vk::binding(3, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> outMotionVectors;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    int2 p = int2(id.xy);
    if (p.x >= pc.size.x || p.y >= pc.size.y) {
        return;
    }
    int2 src = int2(p.x, pc.size.y - 1 - p.y);
    float d = depthTexture.Load(int3(src, 0)).r;
    float2 mv = inMotionVectors[src];
    outDepth[p] = d;
    outMotionVectors[p] = float2(mv.x, -mv.y);
}
```

- [ ] **Step 2: Add the `fg_prepare` build variant**

In `common/build.gradle`, the current `compileSlangShaders` hardcodes `rt_tier1_probe.slang` as the single source (line 106). Generalize it to select the source per variant. Replace the `slangRtVariants` definition (lines 90-92) and the `doLast` source line (line 106):

Replace:
```gradle
def slangRtVariants = [
    [name: 'rt_tier1_probe', defines: []],
]
```
with:
```gradle
def slangRtVariants = [
    [name: 'rt_tier1_probe', src: 'rt_tier1_probe', defines: []],
    [name: 'fg_prepare', src: 'fg_prepare', defines: []],
]
```

Replace:
```gradle
        def src = file('src/main/resources/shaders/slang/rt_tier1_probe.slang').absolutePath
        slangRtVariants.each { v ->
            def args = [slangc.absolutePath, src] + slangRtBaseArgs
```
with:
```gradle
        slangRtVariants.each { v ->
            def src = file("src/main/resources/shaders/slang/${v.src}.slang").absolutePath
            def args = [slangc.absolutePath, src] + slangRtBaseArgs
```

(The rest of the loop — defines, `-o ${v.name}_v14.spv`, ProcessBuilder — is unchanged. `inputs.property('slangRtVariants', ...)` already fingerprints the variant list, so adding a variant triggers recompile.)

- [ ] **Step 3: Compile the Slang shaders (single Gradle task)**

Run: `./gradlew :common:compileSlangShaders --console=plain`.
Expected: BUILD SUCCESSFUL; `common/build/generated/shaders/assets/bedray_rt/shaders/fg_prepare_v14.spv` exists (and `rt_tier1_probe_v14.spv` still present). If slangc fails, read its stderr (redirected into the GradleException) and fix the Slang source — do NOT proceed to the cap-audit with a stale/missing `.spv`.

- [ ] **Step 4: Write the cap-audit `FgPrepareSlangCapabilityTest.java`**

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
 * Cap audit of the fg_prepare Slang port: SPIR-V 1.4 and NO capability outside the pinned Tier 1 ceiling
 * (Stage 2.0). fg_prepare uses no ray query / Int64 / device address / subgroup, so its caps are a strict
 * subset; this test fails if a port construct escalates beyond Tier 1.
 */
class FgPrepareSlangCapabilityTest {
    private static final Set<Integer> TIER1_CAPS = Set.of(1, 11, 55, 56, 61, 64, 4472, 5347);

    @Test void fgPrepareIsSpirv14WithinTier1Caps() {
        ByteBuffer spirv = load("/assets/bedray_rt/shaders/fg_prepare_v14.spv");
        assertEquals(0x07230203, spirv.getInt(0), "SPIR-V magic");
        assertEquals(0x00010400, spirv.getInt(4), "SPIR-V version must be 1.4");
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
        assertTrue(TIER1_CAPS.containsAll(caps),
            "fg_prepare pulled capabilities outside Tier 1: " + caps + " (ceiling " + TIER1_CAPS + ")");
        assertTrue(caps.contains(1), "Shader capability missing");
        System.out.printf("[BEDRAY-POST-CAPS] fg_prepare caps=%s%n", caps);
    }

    private static ByteBuffer load(String path) {
        try (InputStream stream = FgPrepareSlangCapabilityTest.class.getResourceAsStream(path)) {
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

Run: `./gradlew :common:test --tests "*FgPrepareSlangCapabilityTest" --console=plain`.
Expected: PASS; stdout `[BEDRAY-POST-CAPS] fg_prepare caps=[1, ...]` (a subset of `{1,11,55,56,61,64,4472,5347}`). XML truth: `common/build/test-results/test/TEST-*FgPrepareSlangCapabilityTest.xml` `failures="0" errors="0"`. Record the measured cap set in the execution notes.

- [ ] **Step 6: Verification checkpoint (NO commit)**

Confirm: `fg_prepare.slang` exists; `fg_prepare_v14.spv` built; cap-audit green and caps ⊆ Tier 1. `fg_prepare.comp` still byte-identical (re-run `sha256sum`, compare to Task 2 Step 4). Do NOT git commit/push.

---

### Task 4: fg_prepare A/B + FrameGenGpu `.spv` wire + regression

**Files:**
- Create: `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/harness/FgPrepareAbTest.java`
- Modify: `common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenGpu.java`
- Modify: `common/build.gradle` (widen `postProbe` filter to include the A/B test)
- Delete (after A/B passes): `common/src/main/resources/shaders/fg_prepare.comp`

**Interfaces:**
- Consumes: `PostShaderProbe` (Task 1); the frozen `fg_prepare glsl_sha256=` from `docs/POST_BASELINE.md` (Task 2); `fg_prepare_v14.spv` (Task 3); `PostBaselineRunner.compileGlsl/depthBytes/mvBytes/sha256/floatToHalf` (package-private statics, Task 2).
- Produces: a byte-identical A/B proof; `FrameGenGpu` loading `/assets/bedray_rt/shaders/fg_prepare_v14.spv` via a `precompiledSpirv()` helper mirroring `VoxelComputePipeline.precompiledSpirv`.

- [ ] **Step 1: Write the A/B test `FgPrepareAbTest.java`**

The test dispatches BOTH the unchanged GLSL reference (shaderc) and the Slang `.spv` under the identical production ABI over the identical synthetic inputs, asserts both equal the FROZEN baseline hash parsed from `docs/POST_BASELINE.md`, and asserts GLSL == Slang byte-for-byte.

```java
package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * measure-before-shader A/B for fg_prepare: the Slang Tier 1 port must be byte-identical to the frozen GLSL
 * reference under the production ABI (4 image bindings, 8-byte ivec2 PC, dispatch (w+7)/8). Gated like rtProbe.
 */
class FgPrepareAbTest {
    private static final int W = PostBaselineRunner.W, H = PostBaselineRunner.H;

    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = dispatch(probe, PostBaselineRunner.compileGlsl("/shaders/fg_prepare.comp", "fg_prepare.comp"));
            byte[] slang = dispatch(probe, loadSpv("/assets/bedray_rt/shaders/fg_prepare_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] fg_prepare frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (fg_prepare.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    /** Runs fg_prepare under the production ABI over the frozen synthetic inputs; returns depth+mv bytes. */
    private byte[] dispatch(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img depthIn = mem.createImage("ab_depth_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img mvIn = mem.createImage("ab_mv_in", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img depthOut = mem.createImage("ab_depth_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        RtMemory.Img mvOut = mem.createImage("ab_mv_out", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(depthIn, PostBaselineRunner.depthBytes(), 4);
            probe.upload(mvIn, PostBaselineRunner.mvBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(W).putInt(H).array();
            byte[] depthBack, mvBack;
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 8)) {
                pipe.image(0, depthIn.view, true);
                pipe.image(1, mvIn.view, false);
                pipe.image(2, depthOut.view, false);
                pipe.image(3, mvOut.view, false);
                probe.submit(cmd -> {
                    probe.toGeneral(cmd, depthOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    probe.toGeneral(cmd, mvOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                });
                depthBack = probe.readback(depthOut, 4);
                mvBack = probe.readback(mvOut, 4);
            }
            byte[] all = new byte[depthBack.length + mvBack.length];
            System.arraycopy(depthBack, 0, all, 0, depthBack.length);
            System.arraycopy(mvBack, 0, all, depthBack.length, mvBack.length);
            return all;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(mvIn);
            mem.destroy(depthOut);
            mem.destroy(mvOut);
        }
    }

    private static String frozenGlslHash() throws Exception {
        // The runner writes docs/POST_BASELINE.md at the repo root; tests run with workingDir = repo root.
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("fg_prepare glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No fg_prepare glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = FgPrepareAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}
```

- [ ] **Step 2: Widen the `postProbe` filter to include the A/B test**

In `common/build.gradle`, change the `postProbe` filter line:
```gradle
    filter { includeTestsMatching '*PostShaderProbeTest' }
```
to:
```gradle
    filter { includeTestsMatching '*PostShaderProbeTest'; includeTestsMatching '*FgPrepareAbTest' }
```

- [ ] **Step 3: Run the A/B (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain`.
Expected: BUILD SUCCESSFUL; both `PostShaderProbeTest.uploadReadbackRoundTrip` and `FgPrepareAbTest.slangMatchesFrozenGlslBaseline` PASSED. Stdout `[BEDRAY-POST-AB] fg_prepare frozen=<h> glsl=<h> slang=<h>` with all three hashes EQUAL. XML truth: `common/build/test-results/postProbe/TEST-*FgPrepareAbTest.xml` `failures="0" errors="0"`.

If the Slang hash differs: STOP (systematic-debugging). Do NOT edit the baseline or the GLSL to force a match. Investigate the port (image format qualifiers, `Load` vs `[]`, flip arithmetic, PC layout). The frozen baseline and `fg_prepare.comp` are sacred.

- [ ] **Step 4: Wire `FrameGenGpu` to the precompiled `.spv` (only after Step 3 passes)**

In `FrameGenGpu.java`, replace the runtime-shaderc `compile()` method (lines 226-257) with a precompiled-`.spv` loader mirroring `VoxelComputePipeline.precompiledSpirv`. Replace the entire `private static ByteBuffer compile() { ... }` method with:

```java
    private static ByteBuffer compile() {
        // Slang Tier 1 port (Stage 2.1a): load the build-time precompiled SPIR-V instead of runtime shaderc.
        // The GLSL fg_prepare.comp was removed after the byte-identical A/B passed (docs/POST_BASELINE.md).
        String path = "/assets/bedray_rt/shaders/fg_prepare_v14.spv";
        try (InputStream in = FrameGenGpu.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("precompiled SPIR-V not found: " + path + " (run :common:compileSlangShaders)");
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer direct = BufferUtils.createByteBuffer(bytes.length);
            direct.put(bytes);
            direct.flip();
            return direct;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
```

Remove the now-unused `import org.lwjgl.util.shaderc.Shaderc;` and `import java.nio.charset.StandardCharsets;` from `FrameGenGpu.java` (verify no other usage remains in the file first — `StandardCharsets` was only used by the old `compile()`; `Shaderc` likewise). Keep `import java.io.InputStream;`, `import java.io.IOException;`, `import org.lwjgl.BufferUtils;`.

- [ ] **Step 5: Delete the legacy GLSL from runtime**

Delete `common/src/main/resources/shaders/fg_prepare.comp`. (It is the port reference; the Slang port passed A/B, so per the legacy rule it is deleted, not kept.) Confirm no other code references `/shaders/fg_prepare.comp`:

Run (Grep tool, not shell): search `common/src` for `fg_prepare.comp`. Expected: only `PostBaselineRunner.java` / `FgPrepareAbTest.java` reference it — **these harness references must also be updated** (Step 6) so the build does not break.

- [ ] **Step 6: Repoint the harness GLSL reference to a frozen test resource**

The baseline/A/B harness still needs the GLSL reference to re-verify the frozen hash, but `fg_prepare.comp` is deleted from `main/resources`. Copy the frozen GLSL into a TEST resource BEFORE deleting it from main, and repoint the harness:

1. Create `common/src/test/resources/shaders/fg_prepare.comp` with the EXACT frozen content (the byte-identical source recorded in Task 2 Step 4). This is a test-only reference artifact, not runtime.
2. In `PostBaselineRunner.java` and `FgPrepareAbTest.java`, the resource path `/shaders/fg_prepare.comp` now resolves to the test resource (test classpath). No code change needed IF the test resource is on the classpath at `/shaders/fg_prepare.comp`. Verify `common/src/test/resources` is a test resource root (standard Gradle layout — it is).

Order matters: create the test resource (Step 6.1) BEFORE deleting the main resource (Step 5) so the harness never loses its reference. Re-run the A/B after the move to confirm the frozen hash still matches.

- [ ] **Step 7: Re-run the A/B + cap-audit after the wire (single Gradle task)**

Run: `./gradlew :common:postProbe --console=plain`.
Expected: still PASSED, identical hashes (the test resource GLSL is byte-identical to the deleted main resource). This proves the freeze survived the move.

- [ ] **Step 8: Full regression (single Gradle task, then a second separately)**

Run: `./gradlew :common:test --console=plain` (wait for completion).
Expected: BUILD SUCCESSFUL; all tests green (Stage 2.0 baseline was 501/0/0/3-skipped; the new cap-audit adds 1). XML truth: aggregate `common/build/test-results/test/*.xml` `failures="0" errors="0"`.

Then run separately (never two Gradle tasks at once): `./gradlew :common:runShimTests --console=plain`.
Expected: 36/0 (Stage 2.0 baseline). Confirm no regression in the shim path.

- [ ] **Step 9: Verify FrameGenGpu still constructs (L2 loader smoke)**

The `FrameGenGpu` constructor calls `compile()` → now loads `.spv`. Confirm the resource is on the MAIN runtime classpath after `processResources`: run `./gradlew :common:processResources --console=plain` and verify `common/build/resources/main/assets/bedray_rt/shaders/fg_prepare_v14.spv` exists. (A full in-game L3/L4 verification of frame generation is out of scope for the offscreen Stage 2.1a; the loader smoke + regression is the L2 gate. Note this honestly in the report.)

- [ ] **Step 10: Verification checkpoint (NO commit) + Russian report**

Confirm: A/B byte-identical (frozen == glsl == slang); cap-audit ⊆ Tier 1; `FrameGenGpu` loads `.spv`; `fg_prepare.comp` removed from main, frozen copy in test resources; `:common:test` and `:common:runShimTests` green. Do NOT git commit/push/deploy (owner permission required). Write a short Russian report: what was ported, the frozen/A/B hashes, the measured cap set, regression counts, and that Stage 2.1b is next. Update `docs/POST_BASELINE.md` execution notes if needed (append, do not regenerate the frozen hash).

---

## Self-Review

**1. Spec coverage (Stage 2.1a scope):**
- "extend the offscreen harness to capture a GLSL-reference baseline for each plain compute shader" → Task 1 (harness) + Task 2 (fg_prepare baseline). ✓ (remaining 7 shaders in 2.1b/2.1c)
- "feed synthetic inputs, dispatch through the production pipeline via shaderc, read back output hash + GPU timings" → Task 2 dispatches via shaderc under the production ABI (FrameGenGpu's exact bindings/PC/dispatch), synthetic inputs, output hash. **GPU timings:** deferred — the fg_prepare pilot hashes output; GPU timing capture (GpuTimer from RtBaselineRunner) is added to PostBaselineRunner in 2.1b when the runner is extended for multiple shaders. The pilot's deliverable is the byte-identical A/B, which is the measure-before-shader gate. (Honest scope note: timings are not in 2.1a.)
- "THEN port each to Slang and A/B against that baseline" → Task 3 (port) + Task 4 (A/B vs frozen). ✓
- "baseline captured BEFORE the port and never regenerated to hide a delta" → Task 2 freezes; Task 4 asserts GLSL==frozen and forbids editing the baseline on mismatch. ✓
- ABI preservation (bindings, PC bytes, entry main, numthreads 8×8×1) → fg_prepare.slang preserves all; A/B gates. ✓
- "legacy GLSL removed from runtime only after its Slang port passes A/B" → Task 4 Steps 4-6 (wire then delete, frozen test copy). ✓
- Tier 1 cap ceiling → Task 3 cap-audit. ✓

**2. Placeholder scan:** The `fg_noop` createBuffer line in Task 2 Step 1 is explicitly flagged for removal (it is a marked mistake, not a placeholder — the instruction is concrete: delete it). The frozen hash is parsed at runtime from docs (no fill-in TBD). All code blocks are complete. No "TODO"/"implement later"/"similar to Task N". ✓

**3. Type consistency:** `PostShaderProbe.Binding(int index, int type)`, `Pipeline(ByteBuffer, Binding[], int pcSize)`, `image(int,long,boolean)`, `buffer(int,long,int,long)`, `record(VkCommandBuffer,byte[],int,int,int)`, `upload(Img,byte[],int)`, `readback(Img,int)`, `toGeneral(VkCommandBuffer,long,int)`, `submit(Consumer)` — used identically in Tasks 2 and 4. `PostBaselineRunner.W/H/compileGlsl/depthBytes/mvBytes/sha256/floatToHalf` — package-private statics consumed by FgPrepareAbTest. `RtMemory.Img{image,view,width,height}`, `Buf{handle,mapped}` — match RtMemory.java. ✓

**Correction applied during self-review:** Task 2 Step 1 originally included a stray `mem.createBuffer("fg_noop", ...)` warm-up line that leaks; the plan explicitly instructs removing it. The executor writes the file WITHOUT that line.