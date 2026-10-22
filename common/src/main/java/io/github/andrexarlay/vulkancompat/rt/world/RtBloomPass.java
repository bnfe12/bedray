package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Bloom and sun glare on the final image ({@code rt_bloom.comp}, see its header): prefilter + 13 tap downsample chain of
 * {@link #LEVELS} RGBA16F mips, in-place scatter-weighted upsample, then one apply dispatch that adds the glow (and the optional sun
 * halo) before the tone map shoulder. Like {@link RtVolumetricPass} it only reads and writes the final image after the denoiser, so
 * no guide buffer of NRD / DLSS Ray Reconstruction is touched. All sizes derive from the image size, so the glow radius scales with
 * the render resolution and works the same with an upscaler (applied at render resolution, before the upscale).
 */
final class RtBloomPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    static final int LEVELS = 6;
    static final int PUSH_BYTES = 128;
    private static final int STAGES = 4;
    private static final String[] STAGE_NAMES = {"prefilter_down", "down", "up", "apply"};

    /** Everything the apply stage needs per frame (all but {@code strength} are inputs of the sun glare). */
    record Params(Matrix4f invViewProj, float[] sunDir, float glareStrength, float starAmount, float camX, float camY, float camZ,
                  boolean flipY, boolean tonemapped, float threshold, float knee, float strength, float scatter, float sunNdcX,
                  float sunNdcY) {
    }

    private final VkDevice device;
    private final long[] shaderModules = new long[STAGES];
    private final long[] pipelines = new long[STAGES];
    private final long setLayout;
    private final long pipelineLayout;
    private final long linearSampler;
    private final long nearestSampler;
    private long pool;
    private final long[] sets = new long[2 * LEVELS];   // down0, down1..L-1, up L-2..0, apply
    private RtMemory memory;
    private final RtMemory.Img[] mips = new RtMemory.Img[LEVELS];
    private long boundOutput;
    private long boundDepth;
    private int w;
    private int h;

    RtBloomPass(VkDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR).mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0f).maxLod(0f);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler");
            linearSampler = p.get(0);
            si.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler_nearest");
            nearestSampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, stack);
            int[] types = {VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
            for (int i = 0; i < 4; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, li, null, p), "create_bloom_set_layout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pl = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, pl, null, p), "create_bloom_pipeline_layout");
            pipelineLayout = p.get(0);

            for (int s = 0; s < STAGES; s++) {
                ByteBuffer spirv = RtShaderCompiler.compileResource("/shaders/rt_bloom.comp", "rt_bloom.comp+" + STAGE_NAMES[s],
                    "#define BLOOM_STAGE " + s + (char) 10);
                VkShaderModuleCreateInfo mi = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                RtFailure.check(VK10.vkCreateShaderModule(device, mi, null, p), "create_bloom_shader_module");
                shaderModules[s] = p.get(0);
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
                ci.get(0).sType$Default().layout(pipelineLayout);
                ci.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModules[s]).pName(stack.UTF8("main"));
                RtFailure.check(RtVulkanPipelineCache.createCompute(device, ci, null, p), "create_bloom_pipeline");
                pipelines[s] = p.get(0);
            }
        }
    }

    /** Size of mip {@code i} (0 = half resolution) of a {@code size}-wide image. */
    static int mipSize(int size, int i) {
        return Math.max(1, (size + (1 << (i + 1)) - 1) >> (i + 1));
    }

    /** (Re)creates the mips and the descriptor sets when the size or one of the views changed. */
    private void ensure(VkCommandBuffer cmd, RtMemory mem, int width, int height, long outputView, long depthView) {
        boolean sizeChanged = mips[0] == null || w != width || h != height;
        if (!sizeChanged && boundOutput == outputView && boundDepth == depthView) {
            return;
        }
        VK10.vkDeviceWaitIdle(device); // descriptor sets and images may still be used by frames in flight
        memory = mem;
        if (sizeChanged) {
            for (int i = 0; i < LEVELS; i++) {
                if (mips[i] != null) {
                    mem.destroy(mips[i]);
                }
                mips[i] = mem.createImage("rt_bloom_mip" + i, mipSize(width, i), mipSize(height, i), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
                VkBarriers.transition(cmd, mips[i].image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            w = width;
            h = height;
            LOGGER.info("[VKB-ATMO] rt_bloom {}x{} mips={} smallest={}x{} RGBA16F", width, height, LEVELS,
                mips[LEVELS - 1].width, mips[LEVELS - 1].height);
        }
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
            pool = 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = sets.length;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2 * count);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2 * count);
            VkDescriptorPoolCreateInfo pi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(count).pPoolSizes(sizes);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, pi, null, p), "create_bloom_pool");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, setLayout);
            }
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(layouts);
            LongBuffer out = stack.mallocLong(count);
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, ai, out), "allocate_bloom_sets");
            for (int i = 0; i < count; i++) {
                sets[i] = out.get(i);
            }
            // set 0: output -> mip0 ; 1..L-1: mip(i-1) -> mip i ; L..2L-2: upsample mip(i+1) -> mip i (i = L-2..0) ; 2L-1: apply (mip0 -> output)
            write(stack, sets[0], outputView, mips[0].view, outputView, depthView);
            for (int i = 1; i < LEVELS; i++) {
                write(stack, sets[i], mips[i - 1].view, mips[i].view, outputView, depthView);
            }
            for (int i = LEVELS - 2; i >= 0; i--) {
                write(stack, sets[LEVELS + (LEVELS - 2 - i)], mips[i + 1].view, mips[i].view, outputView, depthView);
            }
            write(stack, sets[2 * LEVELS - 1], mips[0].view, mips[0].view, outputView, depthView);
        }
        boundOutput = outputView;
        boundDepth = depthView;
    }

    private void write(MemoryStack stack, long set, long srcView, long dstView, long finalView, long depthView) {
        VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(4, stack);
        sampled(wr.get(0), stack, set, 0, srcView, linearSampler);
        storage(wr.get(1), stack, set, 1, dstView);
        storage(wr.get(2), stack, set, 2, finalView);
        sampled(wr.get(3), stack, set, 3, depthView, nearestSampler);
        VK10.vkUpdateDescriptorSets(device, wr, null);
    }

    private static void sampled(VkWriteDescriptorSet wr, MemoryStack stack, long set, int binding, long view, long sampler) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        wr.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
    }

    private static void storage(VkWriteDescriptorSet wr, MemoryStack stack, long set, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        wr.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    /**
     * Records the chain and the apply dispatch. With {@code bloom == false} only the sun glare is applied (the chain is skipped and
     * the bloom strength pushed as 0). {@code output} must be in GENERAL and hold the final image.
     */
    void record(VkCommandBuffer cmd, RtMemory mem, int width, int height, long outputView, long depthView, Params pr, boolean bloom) {
        ensure(cmd, mem, width, height, outputView, depthView);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            pc.clear();
            pr.invViewProj.get(0, pc);
            pc.putFloat(64, pr.sunDir[0]).putFloat(68, pr.sunDir[1]).putFloat(72, pr.sunDir[2]).putFloat(76, pr.glareStrength);
            pc.putFloat(80, pr.camX).putFloat(84, pr.camY).putFloat(88, pr.camZ).putFloat(92, pr.flipY ? 1f : 0f);
            pc.putFloat(96, pr.threshold).putFloat(100, pr.knee).putFloat(104, bloom ? pr.strength : 0f).putFloat(108, pr.starAmount);
            pc.putFloat(112, pr.tonemapped ? 1f : 0f).putFloat(116, pr.scatter).putFloat(120, pr.sunNdcX).putFloat(124, pr.sunNdcY);
            if (bloom) {
                dispatch(cmd, 0, sets[0], pc, mips[0].width, mips[0].height);
                for (int i = 1; i < LEVELS; i++) {
                    dispatch(cmd, 1, sets[i], pc, mips[i].width, mips[i].height);
                }
                for (int i = LEVELS - 2; i >= 0; i--) {
                    dispatch(cmd, 2, sets[LEVELS + (LEVELS - 2 - i)], pc, mips[i].width, mips[i].height);
                }
            }
            dispatch(cmd, 3, sets[2 * LEVELS - 1], pc, width, height);
        }
    }

    private void dispatch(VkCommandBuffer cmd, int stage, long set, ByteBuffer pc, int gx, int gy) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[stage]);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (gx + 7) / 8, (gy + 7) / 8, 1);
        }
        VkBarriers.fullMemoryBarrier(cmd);
    }

    void destroy() {
        VK10.vkDeviceWaitIdle(device);
        for (int i = 0; i < LEVELS; i++) {
            if (mips[i] != null) {
                memory.destroy(mips[i]);
                mips[i] = null;
            }
        }
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
            pool = 0L;
        }
        for (int s = 0; s < STAGES; s++) {
            VK10.vkDestroyPipeline(device, pipelines[s], null);
            VK10.vkDestroyShaderModule(device, shaderModules[s], null);
        }
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        VK10.vkDestroySampler(device, linearSampler, null);
        VK10.vkDestroySampler(device, nearestSampler, null);
    }
}
