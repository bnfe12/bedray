package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
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
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Screen-space depth composite pipeline: blends rasterized foreground entities (mobs, items, chests, particles)
 * from the engine render target over the ray-traced world (rt_output).
 */
public final class RtCompositePipeline {
    public static final String SHADER_PATH = "/shaders/glsl/rt_composite.comp";
    public static final int PUSH_BYTES = 80; // mat4 (64) + uvec2 (8) + float (4) + float (4)

    private final VkDevice device;
    private final long shaderModule;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long pool;
    private final long sampler;
    private final long descriptorSet;

    public record Views(long rasterColor, long rasterDepth, long rtColor, long rtDepth, long outComposite) {
        boolean sameAs(Views o) {
            return o != null && rasterColor == o.rasterColor && rasterDepth == o.rasterDepth
                && rtColor == o.rtColor && rtDepth == o.rtDepth && outComposite == o.outComposite;
        }
    }

    private Views bound;

    public RtCompositePipeline(VkDevice device) {
        this.device = device;
        ByteBuffer spirv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_composite.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Nearest sampler for pixel-accurate depth & color fetches
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod(0f).unnormalizedCoordinates(false);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_composite_sampler");
            this.sampler = p.get(0);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            RtFailure.check(VK10.vkCreateShaderModule(device, moduleInfo, null, p), "create_composite_shader_module");
            this.shaderModule = p.get(0);

            // Bindings:
            // 0: combined image sampler (rasterColor)
            // 1: combined image sampler (rasterDepth)
            // 2: combined image sampler (rtColor)
            // 3: combined image sampler (rtDepth)
            // 4: storage image (outComposite)
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(5, stack);
            b.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, p), "create_composite_set_layout");
            this.setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, plInfo, null, p), "create_composite_pipeline_layout");
            this.pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer pi = VkComputePipelineCreateInfo.calloc(1, stack);
            pi.get(0).sType$Default().layout(pipelineLayout);
            pi.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            RtFailure.check(RtVulkanPipelineCache.createCompute(device, pi, null, p), "create_composite_compute_pipeline");
            this.pipeline = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, poolInfo, null, p), "create_composite_pool");
            this.pool = p.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, allocInfo, p), "allocate_composite_descriptor_set");
            this.descriptorSet = p.get(0);
        }
    }

    public void bind(Views v) {
        if (v.sameAs(bound)) {
            return;
        }
        if (bound != null) {
            VK10.vkDeviceWaitIdle(device);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);

            sampled(writes.get(0), stack, 0, v.rasterColor());
            sampled(writes.get(1), stack, 1, v.rasterDepth());
            sampled(writes.get(2), stack, 2, v.rtColor());
            sampled(writes.get(3), stack, 3, v.rtDepth());
            storage(writes.get(4), stack, 4, v.outComposite());

            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
        bound = v;
    }

    private void sampled(VkWriteDescriptorSet write, MemoryStack stack, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
    }

    private void storage(VkWriteDescriptorSet write, MemoryStack stack, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    public void record(VkCommandBuffer cmd,
                       Views views,
                       Matrix4f invViewProj,
                       int width,
                       int height,
                       float depthThreshold) {
        bind(views);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);

            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            Matrix4f m = (invViewProj != null) ? invViewProj : new Matrix4f();
            m.get(0, pc);
            pc.putInt(64, width);
            pc.putInt(68, height);
            pc.putFloat(72, depthThreshold);
            pc.putFloat(76, 0.0f); // pad

            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    public long pipeline() {
        return pipeline;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long descriptorSet() {
        return descriptorSet;
    }

    public void destroy() {
        VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroySampler(device, sampler, null);
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        VK10.vkDestroyShaderModule(device, shaderModule, null);
    }
}
