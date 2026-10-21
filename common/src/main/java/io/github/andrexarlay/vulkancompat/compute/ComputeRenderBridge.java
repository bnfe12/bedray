package io.github.andrexarlay.vulkancompat.compute;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Host-owned composition point for the optional synthetic voxel diagnostic scene. */
public final class ComputeRenderBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Compute");
    private static GraphicsBackend resourceOwner;
    private static VulkanDevice resourceDevice;
    private static long frameIndex;
    private static long failedRevision = Long.MIN_VALUE;
    private static VulkanDevice checkedDevice;
    private static boolean computeSupported;
    private static boolean cleanupBroken;
    private static int resourceWidth, resourceHeight, resourceFormat;

    private ComputeRenderBridge() {}

    public static void onFrameBegin(boolean hasWorld) {
        frameIndex++;
        if (cleanupBroken) {
            GraphicsBackendRegistry.noteFailure("Cleanup failed; restart the client before retrying");
            return;
        }
        if (!hasWorld || !GraphicsBackendRegistry.demoEnabled()) {
            if (!releaseResources()) return;
            if (GraphicsBackendRegistry.backend() != null && hasWorld) {
                GraphicsBackendRegistry.noteIdle("Synthetic scene disabled");
            } else if (GraphicsBackendRegistry.backend() != null) {
                GraphicsBackendRegistry.noteIdle("Synthetic scene waiting for a world");
            }
        }
    }

    /** Called before the hand pass, with no render pass open; false keeps the normal renderer. */
    public static boolean renderSynthetic(RenderTarget target) {
        GraphicsBackend backend = GraphicsBackendRegistry.backend();
        if (cleanupBroken || !GraphicsBackendRegistry.demoEnabled() || backend == null
            || failedRevision == GraphicsBackendRegistry.requestRevision()) {
            return false;
        }
        // The fixed synthetic camera has no world depth/motion signal for temporal reconstruction or FG.
        if (TemporalUpscaler.isActive() || FrameGenSettings.mode() != FrameGenSettings.Mode.OFF
            || FrameGenRuntime.isActive()) {
            if (!releaseResources()) return false;
            GraphicsBackendRegistry.noteIdle("Disable temporal upscaling and frame generation for synthetic demo");
            return false;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null || target == null || target.width <= 0 || target.height <= 0
            || target.getColorTexture() == null || target.getColorTexture().isClosed()) {
            GraphicsBackendRegistry.noteIdle("Synthetic scene waiting for a Vulkan target");
            return false;
        }
        if ((target.getColorTexture().usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            fail("Target does not permit image copies", null);
            return false;
        }
        long image = WorldTargetManager.extractVkImage(target);
        if (image == 0) {
            fail("Vulkan target image is unavailable", null);
            return false;
        }
        if (resourceDevice != null && resourceDevice != device) {
            // The device-close hook must run while the old device is alive. Never dereference a stale device here.
            fail("Vulkan device changed without compute cleanup", null);
            return false;
        }
        if (checkedDevice != device) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var count = stack.mallocInt(1);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, null);
                VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, properties);
                int family = device.graphicsQueue().queueFamilyIndex();
                computeSupported = family >= 0 && family < count.get(0)
                    && (properties.get(family).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) != 0;
            }
            checkedDevice = device;
        }
        if (!computeSupported) {
            fail("Host graphics queue does not support compute", null);
            return false;
        }
        int format = VulkanConst.toVk(target.getColorTexture().getFormat());
        if (resourceOwner != null && (resourceWidth != target.width || resourceHeight != target.height
            || resourceFormat != format)) {
            // Provider resize destroys its previous images. Submit queued uses before allowing that destruction.
            if (!releaseResources()) return false;
        }
        resourceOwner = backend;
        resourceDevice = device;
        resourceWidth = target.width;
        resourceHeight = target.height;
        resourceFormat = format;
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        boolean recorded = false;
        try {
            VkBarriers.fullMemoryBarrier(commandBuffer);
            recorded = backend.render(new ComputeFrameContext(device.vkDevice(), commandBuffer, image,
                format, target.width, target.height, frameIndex));
        } catch (RuntimeException | LinkageError error) {
            fail(error.getClass().getSimpleName() + ": " + error.getMessage(), error);
        } finally {
            VkBarriers.fullMemoryBarrier(commandBuffer);
            int result = VK10.vkEndCommandBuffer(commandBuffer);
            if (result != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Compute command buffer end failed: " + result);
            }
            encoder.execute(commandBuffer);
        }
        if (recorded) {
            GraphicsBackendRegistry.noteRendered(backend.id());
        } else if (failedRevision != GraphicsBackendRegistry.requestRevision()) {
            GraphicsBackendRegistry.noteIdle("Synthetic scene unavailable; normal renderer retained");
        }
        return recorded;
    }

    /** Device close hook runs before the command encoder and Vulkan device are destroyed. */
    public static void onDeviceClose(VulkanDevice closingDevice) {
        if (resourceDevice == closingDevice) {
            releaseResources();
        }
        if (checkedDevice == closingDevice) checkedDevice = null;
    }

    private static boolean releaseResources() {
        GraphicsBackend owner = resourceOwner;
        VulkanDevice device = resourceDevice;
        resourceOwner = null;
        resourceDevice = null;
        resourceWidth = resourceHeight = resourceFormat = 0;
        if (owner == null) return true;
        try {
            // execute() queues transient buffers in the host encoder. Flush those before provider.close() waits idle.
            device.createCommandEncoder().submit();
            owner.close();
            return true;
        } catch (RuntimeException | LinkageError error) {
            // A failed submit may leave unsubmitted references. Never reuse the provider's old device handles.
            // The Vulkan device teardown owns their final reclamation; this provider is retired until restart.
            cleanupBroken = true;
            fail("Cleanup failed; restart the client before retrying: " + error.getMessage(), error);
            return false;
        }
    }

    private static void fail(String reason, Throwable error) {
        failedRevision = GraphicsBackendRegistry.requestRevision();
        GraphicsBackendRegistry.noteFailure(reason);
        if (error == null) LOGGER.warn("[BEDRAY-COMPUTE] {}", reason);
        else LOGGER.error("[BEDRAY-COMPUTE] {}", reason, error);
    }
}
