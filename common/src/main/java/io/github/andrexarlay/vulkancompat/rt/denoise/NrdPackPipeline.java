package io.github.andrexarlay.vulkancompat.rt.denoise;

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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * The two compute pipelines of {@code rt_pack_nrd.comp} (pack: raw G-buffer to NRD inputs; compose: NRD output plus noise-free light to
 * the final image) with ONE shared descriptor set of 9 storage images:
 * 0 viewZ, 1 normal+roughness, 2 diffuse+hit distance, 3 albedo, 4 direct light, 5 NRD in normal/roughness, 6 NRD in diffuse,
 * 7 NRD out diffuse, 8 RT output. The set is written when the images change (after a device-idle wait by the caller).
 */
final class NrdPackPipeline {
    static final String SHADER_PATH = "/shaders/rt_pack_nrd.comp";
    static final int BINDINGS = 9;
    /** 4 uint + vec4 + uvec2, see the PC block of the shader. */
    static final int PUSH_BYTES = 44;

    private final VkDevice device;
    private long packModule;
    private long composeModule;
    private long setLayout;
    private long pipelineLayout;
    private long packPipeline;
    private long composePipeline;
    private long pool;
    private long set;

    NrdPackPipeline(VkDevice device) {
        this.device = device;
        ByteBuffer packSpv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_pack_nrd.comp:pack", "#define VKB_PASS_PACK 1\n");
        ByteBuffer composeSpv = RtShaderCompiler.compileResource(SHADER_PATH, "rt_pack_nrd.comp:compose", "#define VKB_PASS_COMPOSE 1\n");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(packSpv), null, p),
                "nrd_pack_shader_module");
            packModule = p.get(0);
            RtFailure.check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(composeSpv), null, p),
                "nrd_compose_shader_module");
            composeModule = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device,
                VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, p), "nrd_pack_set_layout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, p), "nrd_pack_pipeline_layout");
            pipelineLayout = p.get(0);

            packPipeline = createPipeline(stack, packModule, "nrd_pack_pipeline");
            composePipeline = createPipeline(stack, composeModule, "nrd_compose_pipeline");

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(1).pPoolSizes(sizes), null, p), "nrd_pack_pool");
            pool = p.get(0);
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), p), "nrd_pack_alloc_set");
            set = p.get(0);
        }
    }

    private long createPipeline(MemoryStack stack, long module, String stage) {
        VkComputePipelineCreateInfo.Buffer pi = VkComputePipelineCreateInfo.calloc(1, stack);
        pi.get(0).sType$Default().layout(pipelineLayout);
        pi.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        LongBuffer p = stack.mallocLong(1);
        RtFailure.check(VK10.vkCreateComputePipelines(device, VK10.VK_NULL_HANDLE, pi, null, p), stage);
        return p.get(0);
    }

    /** Writes the 9 views (all in GENERAL). The caller guarantees no frame in flight uses the set. */
    void writeViews(long[] views) {
        if (views.length != BINDINGS) {
            throw new IllegalArgumentException("need " + BINDINGS + " views, got " + views.length);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDINGS, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                infos.get(i).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).dstArrayElement(0).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(infos.slice(i, 1));
            }
            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** Push constants of the shader (layout in {@link #PUSH_BYTES}). */
    static void writePush(ByteBuffer b, int normalEncoding, int roughnessEncoding, int denoiser, int flags,
                          NrdReblurParams p, float denoisingRange, int width, int height, float exposure) {
        b.putInt(0, normalEncoding).putInt(4, roughnessEncoding).putInt(8, denoiser).putInt(12, flags);
        b.putFloat(16, p.hitDistA()).putFloat(20, p.hitDistB()).putFloat(24, p.hitDistC()).putFloat(28, denoisingRange);
        b.putInt(32, width).putInt(36, height).putFloat(40, exposure);
    }

    void recordPack(VkCommandBuffer cmd, ByteBuffer push, int width, int height) {
        record(cmd, packPipeline, push, width, height);
    }

    void recordCompose(VkCommandBuffer cmd, ByteBuffer push, int width, int height) {
        record(cmd, composePipeline, push, width, height);
    }

    private void record(VkCommandBuffer cmd, long pipeline, ByteBuffer push, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    /** Device must be idle. */
    void destroy() {
        VK10.vkDestroyPipeline(device, packPipeline, null);
        VK10.vkDestroyPipeline(device, composePipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        VK10.vkDestroyShaderModule(device, packModule, null);
        VK10.vkDestroyShaderModule(device, composeModule, null);
    }
}
