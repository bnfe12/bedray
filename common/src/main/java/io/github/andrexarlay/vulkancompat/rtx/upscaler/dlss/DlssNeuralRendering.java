package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.FrameContext.ImageRef;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityRecordingOwner;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/** Render-thread ownership of the optional display-grid Neural Rendering pass after direct DLSS SR. */
public final class DlssNeuralRendering implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssNeuralRendering");
    private static final int DISPLAY_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    // NVIDIA's native Vulkan raster sample uses FP32 scratch before blitting back to the SDR display image.
    private static final int OUTPUT_FORMAT = VK_FORMAT_R32G32B32A32_SFLOAT;

    private final VelocityRecordingOwner recordings;
    private final DlssFeatureOwner feature = new DlssFeatureOwner();
    private VulkanDevice device;
    private DlssNrNative api;
    private RtMemory memory;
    private RtMemory.Img output;
    private boolean acceptedThisFrame;
    private boolean acceptedPreviousFrame;
    private boolean loggedSubmission;
    private boolean disabled;
    private boolean retirementFailed;

    public DlssNeuralRendering(VelocityRecordingOwner recordings) {
        this.recordings = recordings;
    }

    public boolean ownsResources() {
        return device != null;
    }

    public void beginFrame(boolean eligible) {
        acceptedPreviousFrame = acceptedThisFrame;
        acceptedThisFrame = false;
        if (ownsResources() && (!eligible || disabled) && !retirementFailed) {
            retireForDevice(device);
        }
    }

    /** SR has been accepted independently. A false result leaves the normal SR output available for presentation. */
    public boolean render(VulkanDevice host, DlssNrNative bridge, ImageRef color, ImageRef depth,
                          ImageRef motion, ImageRef target, boolean reset) {
        if (disabled) return false;
        if (!validImages(color, depth, motion, target)) {
            return fail("frame_inputs", "expected sampled SDR RGBA8 color, reverse-Z depth, render-grid motion and writable RGBA8 destination");
        }

        try {
            if (ownsResources() && (device != host || api != bridge
                || output.width != color.width() || output.height != color.height())) {
                close();
            }
            if (!ownsResources() && !prepare(host, bridge, color.width(), color.height())) return false;
        } catch (RuntimeException | LinkageError failure) {
            return fail("prepare", LogPaths.show(failure.toString()));
        }

        try {
            boolean recorded = recordings.record(host, cmd -> record(cmd, color, depth, motion, target, reset));
            if (!recorded) return false;
            acceptedThisFrame = true;
            if (!loggedSubmission) {
                loggedSubmission = true;
                LOGGER.info("[DLSS-NR] stage=record accepted=true input={}x{} output={}x{} provider=ngx_vulkan",
                    depth.width(), depth.height(), color.width(), color.height());
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return fail("record", LogPaths.show(failure.toString()));
        }
    }

    private boolean prepare(VulkanDevice host, DlssNrNative bridge, int width, int height) {
        var caps = bridge.queryCaps();
        if (!caps.status().ok() || !caps.available()) return fail("capabilities", caps.toString());
        if (!supportsFormats(host)) return fail("formats", "FP32 storage/blit source or RGBA8 blit destination unsupported");

        RtMemory allocator = new RtMemory(host.vkDevice());
        try {
            output = allocator.createImage("dlss_nr_output", width, height, OUTPUT_FORMAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        } catch (RuntimeException | Error failure) {
            allocator.close();
            throw failure;
        }
        memory = allocator;
        device = host;
        api = bridge;
        return true;
    }

    private boolean record(VkCommandBuffer command, ImageRef color, ImageRef depth, ImageRef motion,
                           ImageRef target, boolean reset) {
        VkBarriers.fullMemoryBarrier(command);
        boolean create = feature.empty();
        if (create) {
            long[] created = new long[1];
            DlssNative.Status status = api.createFeature(command.address(), output.width, output.height, created);
            feature.adopt(created[0], status.ok());
            if (!status.ok()) return fail("create", status.toString());
            VkBarriers.transition(command, output.image, VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.fullMemoryBarrier(command);
        }

        // SR has resolved color to the display grid. Only depth and jitter-free pixel motion remain at render size.
        var destination = new DlssNative.Image(output.image, output.view, OUTPUT_FORMAT,
            output.width, output.height, VK_IMAGE_ASPECT_COLOR_BIT, true);
        var info = new DlssNrNative.EvalInfo(reset || create || !acceptedPreviousFrame,
            input(color), destination, input(depth), input(motion), DlssNative.Image.NONE, 0.0f, 0.0f,
            (float) color.width() / motion.width(), (float) color.height() / motion.height(),
            1.0f, 1.0f, 1.0f, 1.0f, 0, false, true);
        DlssNative.Status status = api.evaluate(command.address(), feature.handle(), info);
        if (!status.ok()) return fail("evaluate", status.toString());

        // Only a successful evaluation can replace the presented world. All host textures stay GENERAL externally.
        blit(command, target);
        return true;
    }

    private void blit(VkCommandBuffer command, ImageRef target) {
        VkBarriers.transition(command, output.image, VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        VkBarriers.transition(command, target.image(), VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).srcOffsets(1).set(output.width, output.height, 1);
            region.get(0).dstOffsets(1).set(target.width(), target.height(), 1);
            vkCmdBlitImage(command, output.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.image(),
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
        }
        VkBarriers.transition(command, output.image, VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        VkBarriers.transition(command, target.image(), VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
        VkBarriers.fullMemoryBarrier(command);
    }

    private static boolean supportsFormats(VulkanDevice host) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            var physical = host.vkDevice().getPhysicalDevice();
            vkGetPhysicalDeviceFormatProperties(physical, DISPLAY_FORMAT, props);
            int display = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
            if ((props.optimalTilingFeatures() & display) != display) return false;
            vkGetPhysicalDeviceFormatProperties(physical, OUTPUT_FORMAT, props);
            int scratch = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT;
            return (props.optimalTilingFeatures() & scratch) == scratch;
        }
    }

    private static boolean validImages(ImageRef color, ImageRef depth, ImageRef motion, ImageRef target) {
        return matches(color, DISPLAY_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT)
            && matches(target, DISPLAY_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT)
            && matches(depth, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT)
            && matches(motion, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT)
            && color.width() == target.width() && color.height() == target.height()
            && depth.width() == motion.width() && depth.height() == motion.height()
            && color.image() != target.image();
    }

    private static boolean matches(ImageRef image, int format, int aspect) {
        return image != null && image.image() != 0 && image.view() != 0 && image.width() > 0 && image.height() > 0
            && image.vkFormat() == format && image.aspectMask() == aspect;
    }

    private static DlssNative.Image input(ImageRef image) {
        return new DlssNative.Image(image.image(), image.view(), image.vkFormat(), image.width(), image.height(),
            image.aspectMask(), false);
    }

    private boolean fail(String stage, String reason) {
        disabled = true;
        LOGGER.warn("[DLSS-NR] stage={} disabled=true fallback=dlss_sr reason={}", stage, LogPaths.show(reason));
        return false;
    }

    public void retireForDevice(VulkanDevice closingDevice) {
        if (device == null || device != closingDevice) return;
        try {
            close();
        } catch (RuntimeException | LinkageError failure) {
            disabled = true;
            retirementFailed = true;
            LOGGER.error("[DLSS-NR] stage=retire retained=true reason={}", LogPaths.show(failure.toString()));
        }
    }

    @Override
    public void close() {
        if (!ownsResources()) return;
        recordings.drain(device);
        feature.retire(() -> {}, handle -> {
            DlssNative.Status status = api.releaseFeature(handle);
            if (!status.ok()) throw new IllegalStateException(status.toString());
        });
        memory.destroy(output);
        memory.close();
        output = null;
        memory = null;
        api = null;
        device = null;
        acceptedThisFrame = false;
        acceptedPreviousFrame = false;
        loggedSubmission = false;
        retirementFailed = false;
    }
}
