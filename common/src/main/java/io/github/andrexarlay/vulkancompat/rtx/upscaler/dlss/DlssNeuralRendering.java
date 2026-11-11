package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.LibraryStatus;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.FrameContext;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VelocityRecordingOwner;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.lwjgl.vulkan.VK10.*;

/** Owns the optional NGX Neural Rendering feature and its two render-grid Vulkan images. Render thread only. */
public final class DlssNeuralRendering implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssNeuralRendering");
    private static final int FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    private final VelocityRecordingOwner recordings;
    private final DlssFeatureOwner feature = new DlssFeatureOwner();
    private VulkanDevice device;
    private DlssNative api;
    private RtMemory memory;
    private RtMemory.Img input;
    private RtMemory.Img output;
    private String failure;
    private boolean recorded;

    public DlssNeuralRendering(VelocityRecordingOwner recordings) {
        this.recordings = recordings;
    }

    /** Frame head. Toggle the intensity to zero to clear a failure before retrying. */
    public void prepare(VulkanDevice host, boolean eligible, boolean requested, int width, int height) {
        if (device != null && (!eligible || !requested || failure != null || host != device
            || input == null || input.width != width || input.height != height)) {
            try {
                close();
            } catch (RuntimeException | LinkageError e) {
                if (failure == null) fail("release", e.toString());
                return;
            }
        }
        if (!requested) failure = null;
        if (!eligible || !requested || failure != null || feature.usable()) return;
        try {
            var resolved = VulkanBridge.libraries().resolve(LibraryDescriptor.DLSS_NR.id());
            Path nr = resolved.file(LibraryDescriptor.DLSS_NR_DLL).orElse(null);
            if (nr == null || resolved.status().state() != LibraryStatus.State.VERIFIED) {
                fail("library", resolved.status().state() + " (" + resolved.status().detail() + ")");
                return;
            }
            if (!nr.getParent().equals(DlssRuntime.featureDirectory())) {
                fail("library", LibraryDescriptor.DLSS_NR_DLL + " must be beside the nvngx_dlss.dll used by NGX");
                return;
            }
            api = DlssRuntime.nativeLib();
            if (api == null || !api.hasNeuralRendering()) {
                fail("wrapper", "vkb_dlss.dll needs the Neural Rendering exports from native/dlss_bridge");
                return;
            }
            var caps = host.vkDevice().getCapabilities();
            if (!host.instance().getEnabledExtensions().containsAll(DlssDeviceRequirements.INSTANCE_EXTENSIONS)
                || !caps.VK_NVX_binary_import || !caps.VK_NVX_image_view_handle
                || !caps.VK_KHR_push_descriptor || !caps.VK_KHR_buffer_device_address) {
                fail("device_extensions", "logical device is missing Vulkan NGX Neural Rendering requirements");
                return;
            }
            checkFormats(host);
            memory = new RtMemory(host.vkDevice());
            device = host;
            input = memory.createImage("dlss_nr_input", width, height, FORMAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            output = memory.createImage("dlss_nr_output", width, height, FORMAT,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
            boolean submitted = recordings.record(host, cmd -> {
                long[] created = new long[1];
                DlssNative.Status status = api.createNeuralRendering(cmd.address(), width, height, created);
                feature.adopt(created[0], status.ok());
                if (!status.ok()) {
                    fail("create", status.toString());
                    return false;
                }
                VkBarriers.transition(cmd, input.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                VkBarriers.transition(cmd, output.image, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                return true;
            });
            // Create can record initialization commands. Confirm completion before the first evaluate.
            if (submitted) recordings.drain(host);
        } catch (RuntimeException | LinkageError e) {
            fail("prepare", e.toString());
        }
    }

    public boolean ready(int width, int height) {
        return failure == null && feature.usable() && input.width == width && input.height == height;
    }

    public String failureReason() {
        return failure;
    }

    /** Returns the SR input image, or null to discard the entire command buffer on a failure. */
    public FrameContext.ImageRef record(VkCommandBuffer cmd, FrameContext.ImageRef color, FrameContext.ImageRef depth,
                                        FrameContext.ImageRef motion, boolean reset, int percent) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(input.width, input.height, 1);
            blit.get(0).dstOffsets(1).set(input.width, input.height, 1);
            vkCmdBlitImage(cmd, color.image(), VK_IMAGE_LAYOUT_GENERAL, input.image, VK_IMAGE_LAYOUT_GENERAL,
                blit, VK_FILTER_NEAREST);
            FrameContext.ImageRef source = ref(input);
            FrameContext.ImageRef destination = ref(output);
            shaderRead(cmd, source, true);
            shaderRead(cmd, depth, true);
            shaderRead(cmd, motion, true);
            DlssNative.Status status = api.evaluateNeuralRendering(cmd.address(), feature.handle(),
                new DlssNative.EvalInfo(reset || !recorded, ngx(source, false), ngx(destination, true), ngx(depth, false),
                    ngx(motion, false), DlssNative.Image.NONE, 0, 0, 1, 1, 0, 1, 1, input.width, input.height),
                percent / 100.0f);
            if (!status.ok()) {
                fail("evaluate", status.toString());
                return null;
            }
            shaderRead(cmd, motion, false);
            shaderRead(cmd, depth, false);
            shaderRead(cmd, source, false);
            VkBarriers.fullMemoryBarrier(cmd);
            return destination;
        } catch (RuntimeException | LinkageError e) {
            fail("evaluate", e.toString());
            return null;
        }
    }

    public void onRecorded() {
        if (!recorded) {
            recorded = true;
            LOGGER.info("[DLSS-NR] recorded=true grid={}x{} provider=ngx_vulkan", input.width, input.height);
        }
    }

    private static void shaderRead(VkCommandBuffer cmd, FrameContext.ImageRef image, boolean read) {
        int general = VK_IMAGE_LAYOUT_GENERAL;
        int sampled = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkBarriers.transition(cmd, image.image(), image.aspectMask(), read ? general : sampled, read ? sampled : general);
    }

    private static DlssNative.Image ngx(FrameContext.ImageRef image, boolean writable) {
        return new DlssNative.Image(image.image(), image.view(), image.vkFormat(), image.width(), image.height(),
            image.aspectMask(), writable);
    }

    private static FrameContext.ImageRef ref(RtMemory.Img image) {
        return new FrameContext.ImageRef(image.image, image.view, FORMAT, VK_IMAGE_ASPECT_COLOR_BIT, image.width, image.height);
    }

    private static void checkFormats(VulkanDevice host) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            var physical = host.vkDevice().getPhysicalDevice();
            vkGetPhysicalDeviceFormatProperties(physical, VK_FORMAT_R8G8B8A8_UNORM, props);
            if ((props.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0)
                throw new IllegalStateException("RGBA8 world color lacks Vulkan blit source support");
            vkGetPhysicalDeviceFormatProperties(physical, FORMAT, props);
            int required = VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((props.optimalTilingFeatures() & required) != required)
                throw new IllegalStateException("RGBA16F lacks Vulkan blit, sampled image, or storage image support");
        }
    }

    private void fail(String stage, String reason) {
        failure = stage + ": " + reason;
        LOGGER.warn("[DLSS-NR] stage={} disabled=true reason={}", stage, reason);
    }

    @Override
    public void close() {
        if (device == null) return;
        recordings.drain(device);
        feature.retire(() -> {}, handle -> {
            DlssNative.Status status = api.releaseFeature(handle);
            if (!status.ok()) throw new IllegalStateException(status.toString());
        });
        if (output != null) memory.destroy(output);
        if (input != null) memory.destroy(input);
        memory.close();
        output = null;
        input = null;
        memory = null;
        device = null;
        api = null;
        recorded = false;
    }
}
