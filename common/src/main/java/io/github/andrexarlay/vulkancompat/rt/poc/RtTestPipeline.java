package io.github.andrexarlay.vulkancompat.rt.poc;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Hardware Ray Tracing test pipeline (Ray Query PoC).
 * <p>
 * Manages compute shader compilation (via Shaderc or precompiled SPIR-V bytecode),
 * Vulkan compute pipeline, descriptor sets (TLAS acceleration structure at set 0 binding 0,
 * storage image at set 0 binding 1), and dispatches 8x8 workgroups to trace rays.
 */
public final class RtTestPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/RtTestPipeline");

    public static final String SHADER_COMP_PATH = "/shaders/rt_test.comp";
    public static final String SHADER_SPV_PATH = "/shaders/rt_test.spv";

    /**
     * Wrapper for Vulkan image view handle for typed call sites.
     */
    public record VkImageView(long handle) {
        public static VkImageView of(long handle) {
            return new VkImageView(handle);
        }
    }

    private final VkDevice device;

    private long shaderModule = 0L;
    private long descriptorSetLayout = 0L;
    private long pipelineLayout = 0L;
    private long computePipeline = 0L;
    private long descriptorPool = 0L;
    private long descriptorSet = 0L;

    private boolean initialized = false;

    // Descriptor contents currently written into descriptorSet (0 = nothing written yet).
    private long boundTargetView = 0L;
    private long boundTlas = 0L;
    private boolean bindingStale = false;

    public RtTestPipeline(VkDevice device) {
        this(device, true);
    }

    public RtTestPipeline(VkDevice device, boolean autoInit) {
        this.device = Objects.requireNonNull(device, "VkDevice must not be null");
        if (autoInit) {
            init();
        }
    }

    /**
     * Compiles or loads SPIR-V and creates all Vulkan pipeline resources.
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        LOGGER.info("[RtTestPipeline] Initializing Ray Query PoC Compute Pipeline...");

        ByteBuffer spirv = obtainSpirv();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create Shader Module
            VkShaderModuleCreateInfo moduleCreateInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default()
                .pCode(spirv);

            LongBuffer pModule = stack.mallocLong(1);
            int err = VK10.vkCreateShaderModule(device, moduleCreateInfo, null, pModule);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateShaderModule failed: " + err);
            }
            this.shaderModule = pModule.get(0);

            // 2. Create Descriptor Set Layout
            // Binding 0: Top-Level Acceleration Structure (uniform accelerationStructureEXT topLevelAS)
            // Binding 1: Output Storage Image (layout(binding = 1, rgba8) uniform image2D outImage)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                .binding(0)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            bindings.get(1)
                .binding(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(bindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            err = VK10.vkCreateDescriptorSetLayout(device, layoutCreateInfo, null, pSetLayout);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout failed: " + err);
            }
            this.descriptorSetLayout = pSetLayout.get(0);

            // 3. Create Pipeline Layout
            VkPipelineLayoutCreateInfo pipeLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(this.descriptorSetLayout));

            LongBuffer pPipeLayout = stack.mallocLong(1);
            err = VK10.vkCreatePipelineLayout(device, pipeLayoutCreateInfo, null, pPipeLayout);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed: " + err);
            }
            this.pipelineLayout = pPipeLayout.get(0);

            // 4. Create Compute Pipeline
            VkComputePipelineCreateInfo.Buffer pipeCreateInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeCreateInfo.get(0).sType$Default()
                .layout(this.pipelineLayout);
            pipeCreateInfo.get(0).stage()
                .sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .module(this.shaderModule)
                .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            err = RtVulkanPipelineCache.createCompute(device, pipeCreateInfo, null, pPipeline);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines failed: " + err);
            }
            this.computePipeline = pPipeline.get(0);

            // 5. Create Descriptor Pool
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0)
                .type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1);
            poolSizes.get(1)
                .type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1);

            VkDescriptorPoolCreateInfo poolCreateInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default()
                .maxSets(1)
                .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            err = VK10.vkCreateDescriptorPool(device, poolCreateInfo, null, pPool);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorPool failed: " + err);
            }
            this.descriptorPool = pPool.get(0);

            // 6. Allocate Descriptor Set
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default()
                .descriptorPool(this.descriptorPool)
                .pSetLayouts(stack.longs(this.descriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            err = VK10.vkAllocateDescriptorSets(device, allocInfo, pSet);
            if (err != VK10.VK_SUCCESS) {
                throw new RuntimeException("vkAllocateDescriptorSets failed: " + err);
            }
            this.descriptorSet = pSet.get(0);
        }

        this.initialized = true;
        LOGGER.info("[RtTestPipeline] Initialized compute pipeline: pipeline=0x{}, layout=0x{}, descSet=0x{}",
            Long.toHexString(this.computePipeline), Long.toHexString(this.pipelineLayout), Long.toHexString(this.descriptorSet));
    }

    /**
     * Obtains SPIR-V bytecode by first attempting runtime compilation through Shaderc,
     * and falling back to precompiled SPIR-V binary from resources if Shaderc is unavailable.
     */
    public static ByteBuffer obtainSpirv() {
        // Attempt 1: Runtime compilation via Shaderc
        try {
            String glsl = loadGlslSource(SHADER_COMP_PATH);
            if (glsl != null && !glsl.isBlank()) {
                ByteBuffer compiled = compileWithShaderc(glsl);
                if (compiled != null && compiled.remaining() > 0) {
                    LOGGER.info("[RtTestPipeline] Runtime Shaderc compilation succeeded (SPIR-V size: {} bytes)", compiled.remaining());
                    return compiled;
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[RtTestPipeline] Runtime Shaderc compilation note: {}. Checking precompiled SPIR-V fallback...", t.getMessage());
        }

        // Attempt 2: Load precompiled SPIR-V resource
        try {
            ByteBuffer precompiled = loadPrecompiledSpirv(SHADER_SPV_PATH);
            if (precompiled != null && precompiled.remaining() > 0) {
                LOGGER.info("[RtTestPipeline] Loaded precompiled SPIR-V bytecode from classpath (size: {} bytes)", precompiled.remaining());
                return precompiled;
            }
        } catch (Throwable t) {
            LOGGER.warn("[RtTestPipeline] Failed to load precompiled SPIR-V fallback: {}", t.getMessage());
        }

        throw new IllegalStateException("Failed to obtain Ray Query SPIR-V: neither Shaderc runtime compilation nor precompiled rt_test.spv were accessible.");
    }

    /**
     * Compiles GLSL compute shader text with {@code #extension GL_EXT_ray_query : enable}
     * into SPIR-V bytecode using Shaderc.
     */
    public static ByteBuffer compileWithShaderc(String glslSource) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("Failed to initialize Shaderc compiler");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(
                options,
                Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2
            );
            Shaderc.shaderc_compile_options_set_target_spirv(
                options,
                Shaderc.shaderc_spirv_version_1_4
            );
            Shaderc.shaderc_compile_options_set_optimization_level(
                options,
                Shaderc.shaderc_optimization_level_performance
            );

            long result = Shaderc.shaderc_compile_into_spv(
                compiler,
                glslSource,
                Shaderc.shaderc_glsl_compute_shader,
                "rt_test.comp",
                "main",
                options
            );

            if (result == 0L) {
                throw new IllegalStateException("Shaderc compilation returned null result handle");
            }

            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String errorMsg = Shaderc.shaderc_result_get_error_message(result);
                    throw new IllegalStateException("Shaderc compilation failed (status=" + status + "): " + errorMsg);
                }

                ByteBuffer spirvBytes = Shaderc.shaderc_result_get_bytes(result);
                // Copy into standalone direct ByteBuffer to outlive Shaderc result release
                ByteBuffer directCopy = BufferUtils.createByteBuffer(spirvBytes.remaining());
                directCopy.put(spirvBytes);
                directCopy.flip();
                return directCopy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Loads GLSL shader source text from classpath resources.
     */
    public static String loadGlslSource(String resourcePath) throws IOException {
        InputStream stream = RtTestPipeline.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            String alt = resourcePath.startsWith("/") ? resourcePath.substring(1) : "/" + resourcePath;
            stream = RtTestPipeline.class.getResourceAsStream(alt);
        }
        if (stream == null) {
            throw new IOException("GLSL shader resource not found: " + resourcePath);
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads precompiled SPIR-V binary from classpath resources.
     */
    public static ByteBuffer loadPrecompiledSpirv(String resourcePath) throws IOException {
        InputStream stream = RtTestPipeline.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            String alt = resourcePath.startsWith("/") ? resourcePath.substring(1) : "/" + resourcePath;
            stream = RtTestPipeline.class.getResourceAsStream(alt);
        }
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        }
    }

    /**
     * Dispatches the Ray Query compute test shader on the provided command buffer.
     * Updates descriptor sets with the supplied TLAS handle and target image view.
     *
     * @param cmd Command buffer to record dispatch commands into
     * @param targetView Vulkan image view handle of the target image
     * @param tlasHandle Handle of the Top-Level Acceleration Structure
     * @param width Render width in pixels
     * @param height Render height in pixels
     */
    public void dispatchTest(VkCommandBuffer cmd, long targetView, long tlasHandle, int width, int height) {
        if (!initialized) {
            init();
        }
        if (cmd == null) {
            throw new IllegalArgumentException("VkCommandBuffer must not be null");
        }
        if (width <= 0 || height <= 0) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Update the descriptor set only when its contents change (resize / new TLAS). Rewriting the same set
            // every frame while earlier frames are still in flight violates VUID-vkUpdateDescriptorSets-None-03047.
            if (targetView != this.boundTargetView || tlasHandle != this.boundTlas || this.bindingStale) {
                if (this.boundTargetView != 0L || this.boundTlas != 0L) {
                    // The set may be referenced by in-flight command buffers: drain the GPU before rewriting it.
                    VK10.vkDeviceWaitIdle(this.device);
                }
                updateDescriptorSet(stack, targetView, tlasHandle);
                this.boundTargetView = targetView;
                this.boundTlas = tlasHandle;
                this.bindingStale = false;
            }

            // Bind compute pipeline
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.computePipeline);

            // Bind descriptor set
            LongBuffer pSets = stack.longs(this.descriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, pSets, null);

            // Dispatch compute: workgroup size is 8x8 in rt_test.comp
            int groupCountX = (width + 7) / 8;
            int groupCountY = (height + 7) / 8;
            VK10.vkCmdDispatch(cmd, Math.max(1, groupCountX), Math.max(1, groupCountY), 1);
        }
    }

    private void updateDescriptorSet(MemoryStack stack, long targetView, long tlasHandle) {
            VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType$Default()
                .pAccelerationStructures(stack.longs(tlasHandle));

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0)
                .imageView(targetView)
                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            // Binding 0: Top-Level Acceleration Structure
            writes.get(0).sType$Default()
                .dstSet(this.descriptorSet)
                .dstBinding(0)
                .dstArrayElement(0)
                .descriptorCount(1)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .pNext(asWrite);

            // Binding 1: Output storage image
            writes.get(1).sType$Default()
                .dstSet(this.descriptorSet)
                .dstBinding(1)
                .dstArrayElement(0)
                .descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(imageInfo);

            VK10.vkUpdateDescriptorSets(this.device, writes, null);
    }

    /**
     * Marks the descriptor contents as stale (the target image was destroyed or recreated, which may reuse the
     * same VkImageView handle value). The next dispatch rewrites the set after draining the GPU.
     */
    public synchronized void invalidateBinding() {
        this.bindingStale = true;
    }

    /**
     * Typed overload for {@link #dispatchTest(VkCommandBuffer, long, long, int, int)}.
     */
    public void dispatchTest(VkCommandBuffer cmd, VkImageView targetView, long tlasHandle, int width, int height) {
        long viewHandle = targetView != null ? targetView.handle() : 0L;
        dispatchTest(cmd, viewHandle, tlasHandle, width, height);
    }

    @Override
    public synchronized void close() {
        cleanup();
    }

    public synchronized void cleanup() {
        if (!initialized) {
            return;
        }
        LOGGER.info("[RtTestPipeline] Releasing pipeline and descriptor resources...");

        // Previously submitted frames may still use the pipeline and descriptor set.
        VK10.vkDeviceWaitIdle(device);

        if (computePipeline != 0L) {
            VK10.vkDestroyPipeline(device, computePipeline, null);
            computePipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (descriptorPool != 0L) {
            VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = 0L;
        }
        if (descriptorSetLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = 0L;
        }
        if (shaderModule != 0L) {
            VK10.vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = 0L;
        }

        descriptorSet = 0L;
        boundTargetView = 0L;
        boundTlas = 0L;
        initialized = false;
        LOGGER.info("[RtTestPipeline] Cleanup completed.");
    }

    public VkDevice getDevice() {
        return device;
    }

    public long getShaderModule() {
        return shaderModule;
    }

    public long getDescriptorSetLayout() {
        return descriptorSetLayout;
    }

    public long getPipelineLayout() {
        return pipelineLayout;
    }

    public long getComputePipeline() {
        return computePipeline;
    }

    public long getDescriptorPool() {
        return descriptorPool;
    }

    public long getDescriptorSet() {
        return descriptorSet;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
