package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
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
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * One compute pipeline of a built-in spatial upscaler: shader module, descriptor set layout of image bindings, push
 * constants and a ring of {@link #SLOTS} descriptor sets (the engine keeps up to 2 submissions in flight, see
 * VelocityPipeline), so a set is never rewritten while an earlier frame may still read it. Images are in
 * {@code VK_IMAGE_LAYOUT_GENERAL} (the engine's layout); the caller adds the memory barriers.
 */
final class SpatialComputePass {
    static final int SLOTS = 3;

    /** Descriptor kinds used by the spatial shaders. */
    enum Binding {
        /** sampler2D (view + sampler). */
        COMBINED(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        /** separate {@code sampler}. */
        SAMPLER(VK10.VK_DESCRIPTOR_TYPE_SAMPLER),
        /** separate {@code texture2D}. */
        SAMPLED(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE),
        /** {@code image2D} written by the shader. */
        STORAGE(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

        final int vkType;

        Binding(int vkType) {
            this.vkType = vkType;
        }
    }

    final String name;
    private final VkDevice device;
    private final Binding[] bindings;
    private final int pushBytes;
    private long shaderModule;
    private long setLayout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private final long[] sets = new long[SLOTS];
    private int slot = -1;

    SpatialComputePass(VkDevice device, String name, String glsl, Binding[] bindings, int pushBytes) {
        this.device = device;
        this.name = name;
        this.bindings = bindings.clone();
        this.pushBytes = pushBytes;
        try {
            create(compile(glsl, name));
        } catch (RuntimeException | Error e) {
            destroy();
            throw e;
        }
    }

    private void create(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, p),
                "vkCreateShaderModule");
            shaderModule = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer lb = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                lb.get(i).binding(i).descriptorType(bindings[i].vkType).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(VK10.vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(lb), null, p),
                "vkCreateDescriptorSetLayout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack);
            range.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, p), "vkCreatePipelineLayout");
            pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule)
                .pName(stack.UTF8("main"));
            ci.get(0).layout(pipelineLayout);
            check(RtVulkanPipelineCache.createCompute(device, ci, null, p), "vkCreateComputePipelines");
            pipeline = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                sizes.get(i).type(bindings[i].vkType).descriptorCount(SLOTS);
            }
            check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SLOTS)
                .pPoolSizes(sizes), null, p), "vkCreateDescriptorPool");
            pool = p.get(0);
            for (int i = 0; i < SLOTS; i++) {
                check(VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "vkAllocateDescriptorSets");
                sets[i] = p.get(0);
            }
        }
    }

    /**
     * Writes the next ring slot and records bind + push + dispatch.
     *
     * @param views    image view per binding (0 for a SAMPLER binding)
     * @param sampler  sampler for COMBINED / SAMPLER bindings
     */
    void record(VkCommandBuffer cmd, long[] views, long sampler, ByteBuffer push, int groupsX, int groupsY) {
        if (views.length != bindings.length) {
            throw new IllegalArgumentException(name + ": " + views.length + " views for " + bindings.length + " bindings");
        }
        if (push.remaining() != pushBytes) {
            throw new IllegalArgumentException(name + ": push constants " + push.remaining() + " bytes, layout has " + pushBytes);
        }
        slot = (slot + 1) % SLOTS;
        long set = sets[slot];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                Binding b = bindings[i];
                if (b != Binding.SAMPLER && views[i] == 0L) {
                    throw new IllegalArgumentException(name + ": binding " + i + " (" + b + ") has no image view");
                }
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0)
                    .sampler(b == Binding.COMBINED || b == Binding.SAMPLER ? sampler : 0L)
                    .imageView(b == Binding.SAMPLER ? 0L : views[i])
                    .imageLayout(b == Binding.SAMPLER ? VK10.VK_IMAGE_LAYOUT_UNDEFINED : VK10.VK_IMAGE_LAYOUT_GENERAL);
                w.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(b.vkType).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(device, w, null);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            ByteBuffer direct = stack.malloc(pushBytes); // LWJGL needs a direct buffer; the caller's may be on the heap
            direct.put(push.duplicate()).flip();
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, direct);
            VK10.vkCmdDispatch(cmd, Math.max(1, groupsX), Math.max(1, groupsY), 1);
        }
    }

    /** The GPU must not use the pass any more (callers wait for idle). */
    void destroy() {
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
            pool = 0L;
        }
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(device, pipeline, null);
            pipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        if (setLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
            setLayout = 0L;
        }
        if (shaderModule != 0L) {
            VK10.vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = 0L;
        }
    }

    /** Shaderc GLSL -> SPIR-V (Vulkan 1.2 target). Throws with the compiler message; also used by the unit test. */
    static ByteBuffer compile(String glsl, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc_compiler_initialize returned 0");
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        // The NIS source with its coefficient tables is larger than the default MemoryStack (64 KiB) that the CharSequence
        // overload encodes into, so the source is encoded off-heap.
        ByteBuffer source = org.lwjgl.system.MemoryUtil.memUTF8(glsl, false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_compute_shader,
                stack.UTF8(name), stack.UTF8("main"), options);
            if (result == 0L) {
                throw new IllegalStateException(name + ": shaderc returned no result");
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException(name + ": shader compilation failed (" + status + "): "
                        + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = BufferUtils.createByteBuffer(bytes.remaining());
                copy.put(bytes).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(source);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void check(int r, String what) {
        if (r != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + r);
        }
    }
}
