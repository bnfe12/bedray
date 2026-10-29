package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.TextureLodBias;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Compute pipeline of an RT shader ({@code rt_rays.comp}, {@code rt_path.comp}; both include {@code rt_common.glsl}).
 * Set 0: TLAS (0), output storage image RGBA8 (1), section table SSBO (2), block atlas (3), [gbuffer variant: G-buffer storage
 * images 4..8 and 13..14], material atlas (9), normal atlas (10), [accum variant: RGBA32F accumulation storage image (11)];
 * 128 bytes of push constants ({@link RtFrameParams}).
 *
 * <p>One descriptor set, rewritten only when a view changes, after {@code vkDeviceWaitIdle} (rewriting a set that in-flight
 * frames still use violates VUID-vkUpdateDescriptorSets-None-03047). In practice only a resize or a resource reload rewrites it.
 */
public final class RtPipeline {
    public static final int PUSH_BYTES = RtFrameParams.BYTES;
    /** Shader of the Ray Tracing mode. */
    public static final String SHADER_PATH = "/shaders/glsl/rt_rays.comp";
    /** Shader of the Path Tracing mode. */
    public static final String PATH_SHADER_PATH = "/shaders/glsl/rt_path.comp";
    /** Number of G-buffer storage images (bindings 4..8) of the denoiser variant: viewZ, normal+roughness, diffuse, albedo, direct. */
    public static final int GBUFFER_IMAGES = 5;
    /** Specular radiance + hit distance of the denoiser variant (binding 13, after the material/normal/accum/depth slots). */
    public static final int BINDING_GBUFFER_SPECULAR = 13;
    /** Specular albedo (F0) guide of the denoiser variant (binding 14). */
    public static final int BINDING_GBUFFER_SPEC_ALBEDO = 14;
    /** Views the denoiser variant needs: the five contiguous images plus specular radiance and specular albedo. */
    public static final int GBUFFER_VIEWS = GBUFFER_IMAGES + 2;
    public static final String GBUFFER_DEFINE = "#define VKB_GBUFFER 1" + (char) 10;
    public static final int BINDING_MATERIAL = 9;
    public static final int BINDING_NORMAL = 10;
    public static final int BINDING_ACCUM = 11;
    public static final int BINDING_DEPTH = 12;
    public static final int BINDING_LIGHTMAP = 15;

    /**
     * Views bound for one frame; {@code gbuffer} non-null exactly for the gbuffer variant, {@code accum} != 0 exactly for
     * accum. {@code motion} is NRD's {@code IN_MV} buffer (one vec4 per pixel), non-zero only when the NRD layout is
     * selected; the legacy pipeline ignores it and {@link #sameAs} leaves it out because no legacy binding carries it.
     */
    public record Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum, long[] gbuffer,
                        long depth, long lightmap, long motion) {
        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum, long[] gbuffer) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, 0L, 0L, 0L);
        }

        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum, long[] gbuffer, long depth) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, 0L, 0L);
        }

        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum, long[] gbuffer,
                     long depth, long lightmap) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, lightmap, 0L);
        }

        boolean sameAs(Views o) {
            return o != null && tlas == o.tlas && table.handle == o.table.handle && output == o.output && atlas == o.atlas
                && material == o.material && normal == o.normal && accum == o.accum && java.util.Arrays.equals(gbuffer, o.gbuffer)
                && depth == o.depth && lightmap == o.lightmap;
        }
    }

    private final VkDevice device;
    private final boolean gbuffer;
    private final boolean accum;
    private final float lodBias;
    private long shaderModule;
    private long setLayout;
    private long pipelineLayout;
    private long pipeline;
    private long pool;
    private long sampler;
    private long lightmapSampler;
    private long descriptorSet;
    private Views bound;

    /** Ray Tracing pipeline; {@code gbuffer}: compiled with VKB_GBUFFER (bindings 4..8 for the NRD path). */
    public RtPipeline(VkDevice device, boolean gbuffer) {
        this(device, SHADER_PATH, "rt_rays.comp", "", gbuffer, false);
    }

    /**
     * {@code name} is the log name of the shader (without the +gbuffer suffix); {@code defines} go after #version;
     * {@code accum} adds the accumulation image binding 11.
     */
    public RtPipeline(VkDevice device, String shaderPath, String name, String defines, boolean gbuffer, boolean accum) {
        this(device, shaderPath, name, defines, gbuffer, accum, TextureLodBias.getEffectiveLodBias());
    }

    public RtPipeline(VkDevice device, String shaderPath, String name, String defines, boolean gbuffer, boolean accum, float lodBias) {
        this.device = device;
        this.gbuffer = gbuffer;
        this.accum = accum;
        this.lodBias = lodBias;
        ByteBuffer spirv = RtShaderCompiler.compileResource(shaderPath, gbuffer ? name + "+gbuffer" : name,
            (gbuffer ? GBUFFER_DEFINE : "") + defines);
        List<int[]> bindings = new ArrayList<>(); // {binding, type}
        bindings.add(new int[] {0, KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR});
        bindings.add(new int[] {1, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
        bindings.add(new int[] {2, VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER});
        bindings.add(new int[] {3, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});
        if (gbuffer) {
            for (int i = 0; i < GBUFFER_IMAGES; i++) {
                bindings.add(new int[] {4 + i, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
            }
            bindings.add(new int[] {BINDING_GBUFFER_SPECULAR, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
            bindings.add(new int[] {BINDING_GBUFFER_SPEC_ALBEDO, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
        }
        bindings.add(new int[] {BINDING_MATERIAL, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});
        bindings.add(new int[] {BINDING_NORMAL, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});
        if (accum) {
            bindings.add(new int[] {BINDING_ACCUM, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
        }
        bindings.add(new int[] {BINDING_DEPTH, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE});
        bindings.add(new int[] {BINDING_LIGHTMAP, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});
        int storageImages = 1 + (gbuffer ? GBUFFER_VIEWS : 0) + (accum ? 1 : 0) + 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Nearest, clamp, level 0 only: pixel-art look of the block atlas and exact material texels.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .mipLodBias(lodBias)
                .minLod(0f).maxLod(0f).unnormalizedCoordinates(false);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateSampler(device, samplerInfo, null, p), "create_sampler");
            sampler = p.get(0);
            // The lightmap is sampled at texel centres, but its texels are gradients: LINEAR so a night-vision or
            // gamma transition does not step between discrete light levels.
            VkSamplerCreateInfo lmInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .mipLodBias(0f)
                .minLod(0f).maxLod(0f).unnormalizedCoordinates(false);
            RtFailure.check(VK10.vkCreateSampler(device, lmInfo, null, p), "create_lightmap_sampler");
            lightmapSampler = p.get(0);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            RtFailure.check(VK10.vkCreateShaderModule(device, moduleInfo, null, p), "create_shader_module");
            shaderModule = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
            for (int i = 0; i < bindings.size(); i++) {
                b.get(i).binding(bindings.get(i)[0]).descriptorType(bindings.get(i)[1]).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, layoutInfo, null, p), "create_set_layout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, plInfo, null, p), "create_pipeline_layout");
            pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer pi = VkComputePipelineCreateInfo.calloc(1, stack);
            pi.get(0).sType$Default().layout(pipelineLayout);
            pi.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            RtFailure.check(RtVulkanPipelineCache.createCompute(device, pi, null, p), "create_compute_pipeline");
            pipeline = p.get(0);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storageImages);
            sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            sizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, poolInfo, null, p), "create_descriptor_pool");
            pool = p.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, allocInfo, p), "allocate_descriptor_set");
            descriptorSet = p.get(0);
        }
    }

    public boolean isGbuffer() {
        return gbuffer;
    }

    public float lodBias() {
        return lodBias;
    }

    /** Points the descriptor set at {@code v} (no-op when unchanged). */
    public void bind(Views v) {
        if (gbuffer != (v.gbuffer() != null) || (v.gbuffer() != null && v.gbuffer().length != GBUFFER_VIEWS)) {
            throw new IllegalArgumentException("gbuffer views must be given exactly for the gbuffer variant (" + GBUFFER_VIEWS + " views)");
        }
        if (accum != (v.accum() != 0L)) {
            throw new IllegalArgumentException("accum view must be given exactly for the accum variant");
        }
        if (v.sameAs(bound)) {
            return;
        }
        if (bound != null) {
            VK10.vkDeviceWaitIdle(device); // the set may be in use by frames in flight
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = 7 + (gbuffer ? GBUFFER_VIEWS : 0) + (accum ? 1 : 0) + 1;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
            int w = 0;
            VkWriteDescriptorSetAccelerationStructureKHR as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType$Default().pAccelerationStructures(stack.longs(v.tlas()));
            writes.get(w++).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(as);
            storage(writes.get(w++), stack, 1, v.output());
            VkDescriptorBufferInfo.Buffer buffer = VkDescriptorBufferInfo.calloc(1, stack);
            buffer.get(0).buffer(v.table().handle).offset(0).range(VK10.VK_WHOLE_SIZE);
            writes.get(w++).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(buffer);
            sampled(writes.get(w++), stack, 3, v.atlas());
            if (gbuffer) {
                for (int i = 0; i < GBUFFER_IMAGES; i++) {
                    storage(writes.get(w++), stack, 4 + i, v.gbuffer()[i]);
                }
                storage(writes.get(w++), stack, BINDING_GBUFFER_SPECULAR, v.gbuffer()[GBUFFER_IMAGES]);
                storage(writes.get(w++), stack, BINDING_GBUFFER_SPEC_ALBEDO, v.gbuffer()[GBUFFER_IMAGES + 1]);
            }
            sampled(writes.get(w++), stack, BINDING_MATERIAL, v.material());
            sampled(writes.get(w++), stack, BINDING_NORMAL, v.normal());
            if (accum) {
                storage(writes.get(w++), stack, BINDING_ACCUM, v.accum());
            }
            storage(writes.get(w++), stack, BINDING_DEPTH, v.depth() != 0L ? v.depth() : v.output());
            sampled(writes.get(w++), stack, BINDING_LIGHTMAP, v.lightmap() != 0L ? v.lightmap() : v.atlas(),
                v.lightmap() != 0L ? lightmapSampler : sampler);
            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
        bound = v;
    }

    private void storage(VkWriteDescriptorSet write, MemoryStack stack, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    private void sampled(VkWriteDescriptorSet write, MemoryStack stack, int binding, long view) {
        sampled(write, stack, binding, view, sampler);
    }

    private void sampled(VkWriteDescriptorSet write, MemoryStack stack, int binding, long view, long samplerHandle) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(samplerHandle).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        write.sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
    }

    /** Records bind + push constants + dispatch (8x8 groups). {@link #bind} must have been called for the current views. */
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            params.write(pc);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    public void destroy() {
        VK10.vkDeviceWaitIdle(device);
        VK10.vkDestroySampler(device, sampler, null);
        VK10.vkDestroySampler(device, lightmapSampler, null);
        VK10.vkDestroyPipeline(device, pipeline, null);
        VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(device, pool, null);
        VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        VK10.vkDestroyShaderModule(device, shaderModule, null);
    }
}
