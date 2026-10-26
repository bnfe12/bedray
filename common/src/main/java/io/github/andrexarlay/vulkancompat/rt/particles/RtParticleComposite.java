package io.github.andrexarlay.vulkancompat.rt.particles;

import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler;
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
 * Compute pipeline of {@code rt_particles_composite.comp}: the game's particle layer (sampled, GENERAL layout) over the ray traced
 * image (storage, in place). One descriptor set; it is rewritten (after a device idle) only when one of the two views changes
 * (resize, RT output re-created), never per frame.
 */
final class RtParticleComposite {
    static final String SHADER_PATH = "/shaders/glsl/rt_particles_composite.comp";
    private static final int PUSH_BYTES = 16; // uvec2 size, float strength, float pad

    private final VkDevice device;
    private final long shaderModule;
    private final long setLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long pool;
    private final long sampler;
    private final long descriptorSet;
    private long boundLayer;
    private long boundRt;

    RtParticleComposite(VkDevice device) {
        this.device = device;
        ByteBuffer spirv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_particles_composite.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod(0f);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_particle_sampler");
            sampler = p.get(0);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            RtFailure.check(VK10.vkCreateShaderModule(device, moduleInfo, null, p), "create_particle_shader_module");
            shaderModule = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            b.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, p), "create_particle_set_layout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, plInfo, null, p), "create_particle_pipeline_layout");
            pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer pi = VkComputePipelineCreateInfo.calloc(1, stack);
            pi.get(0).sType$Default().layout(pipelineLayout);
            pi.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            RtFailure.check(RtVulkanPipelineCache.createCompute(device, pi, null, p), "create_particle_pipeline");
            pipeline = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, poolInfo, null, p), "create_particle_pool");
            pool = p.get(0);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, allocInfo, p), "allocate_particle_descriptor_set");
            descriptorSet = p.get(0);
        }
    }

    private void bind(long layerView, long rtView) {
        if (layerView == boundLayer && rtView == boundRt) {
            return;
        }
        if (boundLayer != 0L) {
            VK10.vkDeviceWaitIdle(device); // an earlier frame may still read the set
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            VkDescriptorImageInfo.Buffer in0 = VkDescriptorImageInfo.calloc(1, stack);
            in0.get(0).sampler(sampler).imageView(layerView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(in0);
            VkDescriptorImageInfo.Buffer in1 = VkDescriptorImageInfo.calloc(1, stack);
            in1.get(0).imageView(rtView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(in1);
            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
        boundLayer = layerView;
        boundRt = rtView;
    }

    void record(VkCommandBuffer cmd, long layerView, long rtView, int width, int height, float strength) {
        bind(layerView, rtView);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            pc.putInt(0, width);
            pc.putInt(4, height);
            pc.putFloat(8, strength);
            pc.putFloat(12, 0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    void destroy() {
        VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroySampler(device, sampler, null);
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        VK10.vkDestroyShaderModule(device, shaderModule, null);
        boundLayer = 0L;
        boundRt = 0L;
    }
}
