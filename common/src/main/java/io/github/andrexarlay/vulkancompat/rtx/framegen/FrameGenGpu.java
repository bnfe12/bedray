package io.github.andrexarlay.vulkancompat.rtx.framegen;

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
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

/**
 * GPU side of the frame generation inputs, all plain Vulkan on the engine device (the engine keeps its own images in
 * GENERAL layout and so do these):
 * <ul>
 * <li>a ring of {@link #SETS} sets {hudless colour (window size, back buffer format), flipped depth (R32F), flipped motion
 *     vectors (RG16F)}; a ring because Streamline reads the tagged images asynchronously after {@code vkQueuePresentKHR}
 *     (ProgrammingGuideDLSS_G.md 5.2: tags valid until present), while the next frame already writes the next set;</li>
 * <li>the compute pass {@code fg_prepare.comp} (flip of depth and motion vectors, see the shader header) and a
 *     {@code vkCmdBlitImage} that copies the engine main target (world without GUI) vertically flipped into the hudless image.</li>
 * </ul>
 * Render thread only.
 */
public final class FrameGenGpu implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenGpu");
    public static final int SETS = 3;
    public static final int FORMAT_DEPTH = VK10.VK_FORMAT_R32_SFLOAT;
    public static final int FORMAT_MV = VK10.VK_FORMAT_R16G16_SFLOAT;
    private static final int USAGE = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT
        | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    /** One image with its view and memory. */
    public record Img(long image, long view, long memory, int format, int width, int height, int usage) {}

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;

    private final Img[] hudless = new Img[SETS];
    private final Img[] depth = new Img[SETS];
    private final Img[] mv = new Img[SETS];
    private boolean needInitialTransition;

    private long shaderModule, sampler, setLayout, pipelineLayout, pipeline, descriptorPool;
    private final long[] descriptorSets = new long[SETS];

    private int hudFormat, winW, winH, renderW, renderH;

    public FrameGenGpu(VkDevice device) {
        this.device = device;
        this.memProps = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memProps);
        createPipeline();
    }

    public Img hudless(int set) {
        return hudless[set];
    }

    public Img depth(int set) {
        return depth[set];
    }

    public Img mv(int set) {
        return mv[set];
    }

    public boolean matches(int hudFormat, int winW, int winH, int renderW, int renderH) {
        return hudless[0] != null && this.hudFormat == hudFormat && this.winW == winW && this.winH == winH
            && this.renderW == renderW && this.renderH == renderH;
    }

    /** (Re)creates the image sets. The caller guarantees the GPU is idle w.r.t. the old images (it waits for device idle). */
    public void ensure(int hudFormat, int winW, int winH, int renderW, int renderH) {
        if (matches(hudFormat, winW, winH, renderW, renderH)) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        destroyImages();
        for (int i = 0; i < SETS; i++) {
            hudless[i] = createImage(hudFormat, winW, winH);
            depth[i] = createImage(FORMAT_DEPTH, renderW, renderH);
            mv[i] = createImage(FORMAT_MV, renderW, renderH);
        }
        this.hudFormat = hudFormat;
        this.winW = winW;
        this.winH = winH;
        this.renderW = renderW;
        this.renderH = renderH;
        this.needInitialTransition = true;
        LOGGER.info("[VKB-FG] images created sets={} hudless={}x{} fmt={} depth/mv={}x{}", SETS, winW, winH, hudFormat, renderW, renderH);
    }

    private Img createImage(int format, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(USAGE)
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateImage(device, ci, null, p), "vkCreateImage");
            long image = p.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device, image, req);
            int type = findMemoryType(req.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) {
                throw new IllegalStateException("no device local memory type for the frame generation images");
            }
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type);
            check(VK10.vkAllocateMemory(device, ai, null, p), "vkAllocateMemory");
            long mem = p.get(0);
            check(VK10.vkBindImageMemory(device, image, mem, 0), "vkBindImageMemory");
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(VK10.vkCreateImageView(device, vi, null, p), "vkCreateImageView");
            return new Img(image, p.get(0), mem, format, w, h, USAGE);
        }
    }

    private void destroyImages() {
        for (int i = 0; i < SETS; i++) {
            for (Img[] arr : new Img[][] {hudless, depth, mv}) {
                Img im = arr[i];
                if (im != null) {
                    VK10.vkDestroyImageView(device, im.view(), null);
                    VK10.vkDestroyImage(device, im.image(), null);
                    VK10.vkFreeMemory(device, im.memory(), null);
                    arr[i] = null;
                }
            }
        }
    }

    private int findMemoryType(int filter, int props) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((filter & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & props) == props) {
                return i;
            }
        }
        return -1;
    }

    private void createPipeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spirv = compile();
            VkShaderModuleCreateInfo mci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(device, mci, null, p), "vkCreateShaderModule");
            shaderModule = p.get(0);

            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(VK10.vkCreateSampler(device, si, null, p), "vkCreateSampler");
            sampler = p.get(0);

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, stack);
            b.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 4; i++) {
                b.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo lci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            check(VK10.vkCreateDescriptorSetLayout(device, lci, null, p), "vkCreateDescriptorSetLayout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(pcr);
            check(VK10.vkCreatePipelineLayout(device, plci, null, p), "vkCreatePipelineLayout");
            pipelineLayout = p.get(0);

            VkComputePipelineCreateInfo.Buffer cpi = VkComputePipelineCreateInfo.calloc(1, stack);
            cpi.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            check(RtVulkanPipelineCache.createCompute(device, cpi, null, p), "vkCreateComputePipelines");
            pipeline = p.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, stack);
            ps.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SETS);
            ps.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(SETS * 3);
            VkDescriptorPoolCreateInfo dpi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SETS).pPoolSizes(ps);
            check(VK10.vkCreateDescriptorPool(device, dpi, null, p), "vkCreateDescriptorPool");
            descriptorPool = p.get(0);
            for (int i = 0; i < SETS; i++) {
                VkDescriptorSetAllocateInfo dai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
                check(VK10.vkAllocateDescriptorSets(device, dai, p), "vkAllocateDescriptorSets");
                descriptorSets[i] = p.get(0);
            }
        }
    }

    private static ByteBuffer compile() {
        String glsl;
        try (InputStream in = FrameGenGpu.class.getResourceAsStream("/shaders/glsl/fg_prepare.comp")) {
            if (in == null) {
                throw new IllegalStateException("resource /shaders/fg_prepare.comp not found");
            }
            glsl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fg_prepare.comp", e);
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_compute_shader, "fg_prepare.comp", "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException("fg_prepare.comp: " + Shaderc.shaderc_result_get_error_message(result));
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

    private static void check(int r, String what) {
        if (r != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + r);
        }
    }

    /** First use: UNDEFINED -> GENERAL for every image (they stay in GENERAL like all engine textures). */
    public void recordInitialTransitions(VkCommandBuffer cmd) {
        if (!needInitialTransition) {
            return;
        }
        needInitialTransition = false;
        for (int i = 0; i < SETS; i++) {
            for (Img im : new Img[] {hudless[i], depth[i], mv[i]}) {
                io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.transition(cmd, im.image(),
                    VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
    }

    /**
     * Records for one frame: vertically flipped copy of the engine main target colour into {@code hudless(set)} and the flip
     * pass depth/motion vectors -> {@code depth(set)}/{@code mv(set)}. All images GENERAL; the caller brackets with barriers.
     *
     * @param mainColorImage engine main target colour (world + hand, no GUI yet), window size, RGBA8
     * @param depthView      depth aspect view of the world depth copy (render size)
     * @param mvView         view of the engine motion vector image (render size, RG16F)
     */
    public void record(VkCommandBuffer cmd, int set, long mainColorImage, long depthView, long mvView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. hudless: blit with flipped destination offsets
            Img h = hudless[set];
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.srcOffsets(0).set(0, 0, 0);
            region.srcOffsets(1).set(h.width(), h.height(), 1);
            region.dstOffsets(0).set(0, h.height(), 0);
            region.dstOffsets(1).set(h.width(), 0, 1);
            VK10.vkCmdBlitImage(cmd, mainColorImage, VK10.VK_IMAGE_LAYOUT_GENERAL, h.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                region, VK10.VK_FILTER_NEAREST);

            // 2. depth + motion vectors flip
            VkDescriptorImageInfo.Buffer d0 = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(sampler).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer d1 = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(mvView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer d2 = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(depth[set].view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer d3 = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(mv[set].view()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(4, stack);
            w.get(0).sType$Default().dstSet(descriptorSets[set]).dstBinding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(d0);
            w.get(1).sType$Default().dstSet(descriptorSets[set]).dstBinding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(d1);
            w.get(2).sType$Default().dstSet(descriptorSets[set]).dstBinding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(d2);
            w.get(3).sType$Default().dstSet(descriptorSets[set]).dstBinding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(d3);
            VK10.vkUpdateDescriptorSets(device, w, null);
            // The blit above and this dispatch touch different images; a barrier before the dispatch is not needed for them.
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSets[set]), null);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(renderW, renderH));
            VK10.vkCmdDispatch(cmd, (renderW + 7) / 8, (renderH + 7) / 8, 1);
        }
    }

    @Override
    public void close() {
        VK10.vkDeviceWaitIdle(device);
        destroyImages();
        if (pipeline != 0L) VK10.vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(device, descriptorPool, null);
        if (setLayout != 0L) VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        if (sampler != 0L) VK10.vkDestroySampler(device, sampler, null);
        if (shaderModule != 0L) VK10.vkDestroyShaderModule(device, shaderModule, null);
        pipeline = pipelineLayout = descriptorPool = setLayout = sampler = shaderModule = 0L;
        memProps.free();
    }
}
