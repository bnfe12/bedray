package io.github.andrexarlay.vulkancompat.rtx.hdr;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPass;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTHdrMetadata;
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
import org.lwjgl.vulkan.VkHdrMetadataEXT;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU side of HDR output. Two compute passes with their own tiny Vulkan pipelines (the Slang AOT SPIR-V of
 * {@code passes/hdr_scene.slang} and {@code passes/hdr_output.slang}), kept apart from the {@code bedray_core} recordings
 * because the second pass runs inside the engine's present command buffer, whose lifetime Bedray does not own.
 *
 * <ol>
 *   <li>{@link #recordScene}: in the world frame, after atmosphere and before the copy to the engine target. Builds the HDR
 *       picture ({@code hdrScene}) and a copy of the SDR picture ({@code sdrReference}).</li>
 *   <li>{@link #blit}: replaces the engine's final {@code vkCmdBlitImage} to the swapchain. Copies the finished frame (with
 *       the GUI) aside, runs the PQ pass, then blits the PQ image to the swapchain with the engine's own region.</li>
 * </ol>
 * Every image stays in {@code VK_IMAGE_LAYOUT_GENERAL}. Single render thread; a size change waits for the device.
 */
public final class HdrGpu {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");

    private static final int GENERAL = 1;
    private static final int STORAGE_IMAGE = 3;
    private static final int COMPUTE = 1;
    private static final int STAGE_COMPUTE = 0x20;
    private static final int FMT_RGBA8 = 37;
    private static final int FMT_RGBA16F = 97;
    private static final int USAGE_TRANSFER_SRC = 0x1;
    private static final int USAGE_TRANSFER_DST = 0x2;
    private static final int USAGE_STORAGE = 0x8;
    private static final int SCENE_PUSH_BYTES = 32;
    private static final int OUTPUT_PUSH_BYTES = 32;
    private static final int MAX_SETS = 64;
    /** Exposed luminance where the highlight expansion starts and where it is complete (see passes/hdr_scene.slang). */
    private static final float KNEE_LOW = 0.6f;
    private static final float KNEE_HIGH = 1.5f;

    private record Pipe(long setLayout, long layout, long pipeline, int pushBytes) {}

    private static final class Image {
        long image;
        long memory;
        long view;
        int width;
        int height;
        boolean needsInit = true;
    }

    private static VkDevice device;
    private static Pipe scenePipe;
    private static Pipe outputPipe;
    private static long pool;
    private static final Map<List<Long>, Long> SETS = new HashMap<>();
    private static Image hdrScene;
    private static Image sdrReference;
    private static Image frameCopy;
    private static Image pq;
    private static boolean sceneRecorded;
    private static int sceneWidth;
    private static int sceneHeight;
    private static boolean failed;
    private static boolean loggedFirst;
    private static boolean lastSceneValid;

    private HdrGpu() {}

    // ------------------------------------------------------------------ pass 1: the world frame

    /**
     * Records the HDR scene pass. {@code hdrSourceView} is the linear radiance (the denoiser's resolve or the raw export) in
     * {@code presented}'s extent; 0 when the frame has none, in which case the output pass treats the whole frame as SDR.
     */
    public static synchronized void recordScene(VulkanDevice vulkanDevice, VkCommandBuffer cmd, RtMemory.Img presented,
                                                long hdrSourceView, float exposure) {
        sceneRecorded = false;
        if (!HdrOutput.active() || failed || hdrSourceView == 0L) {
            return;
        }
        try {
            init(vulkanDevice);
            ensureScene(presented.width, presented.height);
            if (hdrScene.needsInit || sdrReference.needsInit) {
                VkBarriers.transition(cmd, hdrScene.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, GENERAL);
                VkBarriers.transition(cmd, sdrReference.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, GENERAL);
                hdrScene.needsInit = false;
                sdrReference.needsInit = false;
            }
            VkBarriers.fullMemoryBarrier(cmd);
            long set = descriptorSet(scenePipe, presented.view, hdrSourceView, hdrScene.view, sdrReference.view);
            float peakRatio = Math.max(1.0f, (float) HdrSettings.peakNits() / (float) HdrSettings.paperWhiteNits());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer push = stack.malloc(SCENE_PUSH_BYTES).order(ByteOrder.nativeOrder());
                push.putInt(0, presented.width).putInt(4, presented.height);
                push.putFloat(8, exposure).putFloat(12, peakRatio).putFloat(16, KNEE_LOW).putFloat(20, KNEE_HIGH)
                    .putFloat(24, 0f).putFloat(28, 0f);
                dispatch(cmd, scenePipe, set, push, presented.width, presented.height);
            }
            VkBarriers.fullMemoryBarrier(cmd);
            sceneRecorded = true;
            sceneWidth = presented.width;
            sceneHeight = presented.height;
        } catch (Throwable t) {
            fail("scene pass", t);
        }
    }

    // ------------------------------------------------------------------ pass 2: the engine's present command buffer

    /**
     * Replaces the engine's blit of its final target into the swapchain. Returns false when the engine's own blit must run
     * (the output pass is unavailable), in which case nothing was recorded.
     */
    public static synchronized boolean blit(VulkanDevice vulkanDevice, VkCommandBuffer cmd, long srcImage, int srcLayout,
                                            long dstImage, int dstLayout, VkImageBlit.Buffer region, int filter) {
        if (!HdrOutput.active() || failed) {
            return false;
        }
        try {
            init(vulkanDevice);
            int w = region.srcOffsets(1).x();
            int h = region.srcOffsets(1).y();
            if (w <= 0 || h <= 0) {
                return false;
            }
            ensureOutput(w, h);
            boolean sceneValid = sceneRecorded && sceneWidth == w && sceneHeight == h && hdrScene != null && sdrReference != null;
            sceneRecorded = false;
            if (sceneValid != lastSceneValid || !loggedFirst) {
                LOGGER.info("[VKB-HDR] output pass extent={}x{} scene={} paper_white={} peak={}", w, h, sceneValid,
                    HdrSettings.paperWhiteNits(), HdrSettings.peakNits());
                lastSceneValid = sceneValid;
                loggedFirst = true;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (frameCopy.needsInit || pq.needsInit) {
                    VkBarriers.transition(cmd, frameCopy.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, GENERAL);
                    VkBarriers.transition(cmd, pq.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED, GENERAL);
                    frameCopy.needsInit = false;
                    pq.needsInit = false;
                }
                VkBarriers.fullMemoryBarrier(cmd);

                // 1. the engine target (world + GUI) into an image the compute pass may read
                VkImageBlit.Buffer copy = VkImageBlit.calloc(1, stack);
                copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(region.srcSubresource().mipLevel())
                    .baseArrayLayer(0).layerCount(1);
                copy.srcOffsets(0).set(0, 0, 0);
                copy.srcOffsets(1).set(w, h, 1);
                copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copy.dstOffsets(0).set(0, 0, 0);
                copy.dstOffsets(1).set(w, h, 1);
                VK10.vkCmdBlitImage(cmd, srcImage, srcLayout, frameCopy.image, GENERAL, copy, VK10.VK_FILTER_NEAREST);
                VkBarriers.fullMemoryBarrier(cmd);

                // 2. PQ encode (HDR scene where the GUI left the world alone)
                long sdrView = sceneValid ? sdrReference.view : frameCopy.view;
                long hdrView = sceneValid ? hdrScene.view : pq.view;
                long set = descriptorSet(outputPipe, frameCopy.view, sdrView, hdrView, pq.view);
                ByteBuffer push = stack.malloc(OUTPUT_PUSH_BYTES).order(ByteOrder.nativeOrder());
                push.putInt(0, w).putInt(4, h).putInt(8, sceneValid ? 1 : 0);
                push.putFloat(12, HdrSettings.paperWhiteNits()).putFloat(16, HdrSettings.peakNits())
                    .putFloat(20, 0f).putFloat(24, 0f).putFloat(28, 0f);
                dispatch(cmd, outputPipe, set, push, w, h);
                VkBarriers.fullMemoryBarrier(cmd);
                HdrDump.afterOutputPass(device, cmd, pq.image, w, h, sceneValid);

                // 3. the engine's own region (it flips Y), now from the PQ image
                VkImageBlit.Buffer present = VkImageBlit.calloc(1, stack);
                present.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                present.srcOffsets(0).set(region.srcOffsets(0).x(), region.srcOffsets(0).y(), region.srcOffsets(0).z());
                present.srcOffsets(1).set(region.srcOffsets(1).x(), region.srcOffsets(1).y(), region.srcOffsets(1).z());
                present.dstSubresource().set(region.dstSubresource());
                present.dstOffsets(0).set(region.dstOffsets(0).x(), region.dstOffsets(0).y(), region.dstOffsets(0).z());
                present.dstOffsets(1).set(region.dstOffsets(1).x(), region.dstOffsets(1).y(), region.dstOffsets(1).z());
                VK10.vkCmdBlitImage(cmd, pq.image, GENERAL, dstImage, dstLayout, present, filter);
            }
            return true;
        } catch (Throwable t) {
            fail("output pass", t);
            return false;
        }
    }

    /**
     * Tells the display what the HDR10 swapchain carries: BT.2020 primaries, D65 white, the peak from the settings as mastering
     * and content peak, paper white as the frame average. Needs {@code VK_EXT_hdr_metadata} on the device (optional group,
     * {@link HdrDeviceRequirements}); without it the display uses its own limits and the log says so.
     */
    public static synchronized void sendMetadata(VkDevice dev, long swapchain) {
        if (!HdrOutput.active() || swapchain == 0L) {
            return;
        }
        try {
            if (!dev.getCapabilities().VK_EXT_hdr_metadata) {
                LOGGER.warn("[VKB-HDR] metadata not sent: {} is not enabled on the device (the display uses its own limits)", HdrProbe.EXT_HDR_METADATA);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkHdrMetadataEXT.Buffer md = VkHdrMetadataEXT.calloc(1, stack).sType$Default();
                md.displayPrimaryRed().set(0.708f, 0.292f);
                md.displayPrimaryGreen().set(0.170f, 0.797f);
                md.displayPrimaryBlue().set(0.131f, 0.046f);
                md.whitePoint().set(0.3127f, 0.3290f);
                md.maxLuminance(HdrSettings.peakNits());
                md.minLuminance(0.0f);
                md.maxContentLightLevel(HdrSettings.peakNits());
                md.maxFrameAverageLightLevel(HdrSettings.paperWhiteNits());
                EXTHdrMetadata.vkSetHdrMetadataEXT(dev, stack.longs(swapchain), md);
            }
            LOGGER.info("[VKB-HDR] metadata sent: primaries=BT.2020 white=D65 max_luminance={} max_frame_average={}",
                HdrSettings.peakNits(), HdrSettings.paperWhiteNits());
        } catch (Throwable t) {
            LOGGER.error("[VKB-HDR] metadata failed (HDR output continues without it)", t);
        }
    }

    // ------------------------------------------------------------------ lifetime

    /** Called when the engine surface closes: nothing of ours may outlive the device. */
    public static synchronized void release() {
        if (device == null) {
            HdrOutput.setActive(false);
            return;
        }
        try {
            VK10.vkDeviceWaitIdle(device);
        } catch (Throwable ignored) {
            // the device is going away anyway
        }
        HdrDump.release(device);
        destroyImages();
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
            pool = 0L;
        }
        SETS.clear();
        destroyPipe(scenePipe);
        destroyPipe(outputPipe);
        scenePipe = null;
        outputPipe = null;
        device = null;
        sceneRecorded = false;
        loggedFirst = false;
        HdrOutput.setActive(false);
    }

    private static void fail(String stage, Throwable t) {
        if (!failed) {
            LOGGER.error("[VKB-HDR] {} failed, the engine's own blit is used from now on (the picture will look wrong on an HDR10 swapchain)", stage, t);
        }
        failed = true;
        sceneRecorded = false;
    }

    private static void init(VulkanDevice vulkanDevice) {
        VkDevice d = vulkanDevice.vkDevice();
        if (device == d && scenePipe != null && outputPipe != null) {
            return;
        }
        device = d;
        scenePipe = createPipe("hdr_scene", 4, SCENE_PUSH_BYTES);
        outputPipe = createPipe("hdr_output", 4, OUTPUT_PUSH_BYTES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack)
                .type(STORAGE_IMAGE).descriptorCount(MAX_SETS * 4);
            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .maxSets(MAX_SETS).pPoolSizes(sizes);
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(device, ci, null, out), "vkCreateDescriptorPool");
            pool = out.get(0);
        }
        LOGGER.info("[VKB-HDR] gpu passes ready: hdr_scene + hdr_output");
    }

    // ------------------------------------------------------------------ pipelines and descriptors

    private static Pipe createPipe(String pass, int bindings, int pushBytes) {
        SlangPass compiled = SlangPassLibrary.load(pass)
            .orElseThrow(() -> new IllegalStateException("Slang pass '" + pass + "' is missing from this jar"));
        SlangPassManifest manifest = compiled.manifest();
        if (manifest.pushConstantBytes() != pushBytes || manifest.bindings().size() != bindings) {
            throw new IllegalStateException(pass + ": manifest push=" + manifest.pushConstantBytes() + " bindings="
                + manifest.bindings().size() + ", host expects " + pushBytes + "/" + bindings);
        }
        for (int i = 0; i < bindings; i++) {
            if (manifest.bindings().get(i).binding() != i) {
                throw new IllegalStateException(pass + ": binding order changed, host assumes 0.." + (bindings - 1));
            }
        }
        byte[] spirv = compiled.spirv();
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            code.put(spirv).flip();
            LongBuffer out = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings, stack);
            for (int i = 0; i < bindings; i++) {
                b.get(i).binding(i).descriptorType(STORAGE_IMAGE).descriptorCount(1).stageFlags(STAGE_COMPUTE);
            }
            check(VK10.vkCreateDescriptorSetLayout(device,
                VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, out), "vkCreateDescriptorSetLayout");
            long setLayout = out.get(0);

            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack).stageFlags(STAGE_COMPUTE).offset(0).size(pushBytes);
            check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range), null, out), "vkCreatePipelineLayout");
            long layout = out.get(0);

            check(VK10.vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out),
                "vkCreateShaderModule");
            long module = out.get(0);

            VkComputePipelineCreateInfo.Buffer pi = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(layout);
            pi.get(0).stage().sType$Default().stage(STAGE_COMPUTE).module(module).pName(stack.UTF8("main"));
            int r = VK10.vkCreateComputePipelines(device, 0L, pi, null, out);
            VK10.vkDestroyShaderModule(device, module, null);
            check(r, "vkCreateComputePipelines");
            return new Pipe(setLayout, layout, out.get(0), pushBytes);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static void destroyPipe(Pipe p) {
        if (p == null) {
            return;
        }
        VK10.vkDestroyPipeline(device, p.pipeline(), null);
        VK10.vkDestroyPipelineLayout(device, p.layout(), null);
        VK10.vkDestroyDescriptorSetLayout(device, p.setLayout(), null);
    }

    /** One immutable descriptor set per distinct view quadruple; the pool is recycled when it runs out. */
    private static long descriptorSet(Pipe pipe, long v0, long v1, long v2, long v3) {
        List<Long> key = List.of(pipe.pipeline(), v0, v1, v2, v3);
        Long cached = SETS.get(key);
        if (cached != null) {
            return cached;
        }
        if (SETS.size() >= MAX_SETS) {
            VK10.vkDeviceWaitIdle(device);
            check(VK10.vkResetDescriptorPool(device, pool, 0), "vkResetDescriptorPool");
            SETS.clear();
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(pipe.setLayout())), out), "vkAllocateDescriptorSets");
            long set = out.get(0);
            long[] views = {v0, v1, v2, v3};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(views.length, stack);
            for (int i = 0; i < views.length; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack).imageView(views[i]).imageLayout(GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(STORAGE_IMAGE).pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(device, writes, null);
            SETS.put(key, set);
            return set;
        }
    }

    private static void dispatch(VkCommandBuffer cmd, Pipe pipe, long set, ByteBuffer push, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, COMPUTE, pipe.pipeline());
            VK10.vkCmdBindDescriptorSets(cmd, COMPUTE, pipe.layout(), 0, stack.longs(set), null);
            VK10.vkCmdPushConstants(cmd, pipe.layout(), STAGE_COMPUTE, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    // ------------------------------------------------------------------ images

    private static void ensureScene(int w, int h) {
        if (hdrScene != null && hdrScene.width == w && hdrScene.height == h) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        resetDescriptors();
        destroy(hdrScene);
        destroy(sdrReference);
        hdrScene = create(w, h, FMT_RGBA16F, USAGE_STORAGE);
        sdrReference = create(w, h, FMT_RGBA8, USAGE_STORAGE);
    }

    private static void ensureOutput(int w, int h) {
        if (pq != null && pq.width == w && pq.height == h) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        resetDescriptors();
        destroy(frameCopy);
        destroy(pq);
        frameCopy = create(w, h, FMT_RGBA8, USAGE_STORAGE | USAGE_TRANSFER_DST);
        pq = create(w, h, FMT_RGBA16F, USAGE_STORAGE | USAGE_TRANSFER_SRC);
    }

    /** Every cached set may reference a view that is about to be destroyed. The caller already waited for the device. */
    private static void resetDescriptors() {
        if (pool != 0L) {
            VK10.vkResetDescriptorPool(device, pool, 0);
        }
        SETS.clear();
    }

    private static void destroyImages() {
        destroy(hdrScene);
        destroy(sdrReference);
        destroy(frameCopy);
        destroy(pq);
        hdrScene = null;
        sdrReference = null;
        frameCopy = null;
        pq = null;
    }

    private static Image create(int w, int h, int format, int usage) {
        Image img = new Image();
        img.width = w;
        img.height = h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK10.VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1).samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            check(VK10.vkCreateImage(device, ci, null, out), "vkCreateImage");
            img.image = out.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
            VK10.vkGetImageMemoryRequirements(device, img.image, req);
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
            int type = -1;
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                boolean allowed = (req.memoryTypeBits() & (1 << i)) != 0;
                boolean local = (props.memoryTypes(i).propertyFlags() & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
                if (allowed && local) {
                    type = i;
                    break;
                }
            }
            if (type < 0) {
                throw new IllegalStateException("no device-local memory type for the HDR images");
            }
            check(VK10.vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(req.size()).memoryTypeIndex(type), null, out), "vkAllocateMemory");
            img.memory = out.get(0);
            check(VK10.vkBindImageMemory(device, img.image, img.memory, 0), "vkBindImageMemory");

            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(img.image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            check(VK10.vkCreateImageView(device, vi, null, out), "vkCreateImageView");
            img.view = out.get(0);
        }
        return img;
    }

    private static void destroy(Image img) {
        if (img == null) {
            return;
        }
        VK10.vkDestroyImageView(device, img.view, null);
        VK10.vkDestroyImage(device, img.image, null);
        VK10.vkFreeMemory(device, img.memory, null);
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " returned " + result);
        }
    }

    /** Names of the passes this class needs, for the packaging test. */
    public static List<String> passNames() {
        return new ArrayList<>(List.of("hdr_scene", "hdr_output"));
    }
}
