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
    private static GraphicsBackendRegistry.SceneRequest appliedRequest = GraphicsBackendRegistry.request();
    private static boolean enabled() { return appliedRequest.mode() != GraphicsBackendRegistry.SceneMode.OFF; }
    private static String sceneLabel() { return appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE
        ? "Live opaque debug" : "Synthetic scene"; }
    private static long failedRevision = Long.MIN_VALUE;
    private static VulkanDevice checkedDevice;
    private static boolean computeSupported;
    private static boolean cleanupBroken;
    private static int resourceWidth, resourceHeight, resourceFormat;
    private static final ComputeWorldCapture worldCapture = new ComputeWorldCapture();

    private ComputeRenderBridge() {}

    public static void onFrameBegin(boolean hasWorld) {
        frameIndex++;
        var nextRequest = GraphicsBackendRegistry.request();
        boolean requestChanged = appliedRequest != nextRequest;
        appliedRequest = nextRequest;
        if (requestChanged) {
            if (!releaseResources()) return;
            worldCapture.clear();
            if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
        }
        if (cleanupBroken) {
            GraphicsBackendRegistry.noteFailure("Cleanup failed; restart the client before retrying", appliedRequest);
            return;
        }
        if (!hasWorld || !enabled()) {
            worldCapture.clear();
            if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
            if (!releaseResources()) return;
            if (GraphicsBackendRegistry.backend() != null && hasWorld) {
                noteIdle("Synthetic scene disabled");
            } else if (GraphicsBackendRegistry.backend() != null) {
                noteIdle(sceneLabel() + " waiting for a world");
            }
        }
    }

    /** Called after the world camera/projection was captured, independently of hardware RT support. */
    public static void captureWorld(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        GraphicsBackend backend = GraphicsBackendRegistry.backend();
        if (cleanupBroken || backend == null || !enabled() || !backend.supportsSceneMode(appliedRequest.mode())
            || (!backend.wantsWorldSnapshots() && appliedRequest.mode() != GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE) || camera == null
            || failedRevision == appliedRequest.revision()) return;
        try {
            backend.acceptWorldSnapshot(worldCapture.capture(camera));
        } catch (RuntimeException | LinkageError error) {
            worldCapture.clear();
            backend.clearWorldSnapshot();
            fail("World snapshot capture failed: " + error.getMessage(), error);
        }
    }

    /** Called before the hand pass, with no render pass open; false keeps the normal renderer. */
    public static boolean renderDiagnostic(RenderTarget target) {
        GraphicsBackend backend = GraphicsBackendRegistry.backend();
        if (cleanupBroken || !enabled() || backend == null
            || failedRevision == appliedRequest.revision()) {
            return false;
        }
        if (!backend.supportsSceneMode(appliedRequest.mode())) {
            if (!releaseResources()) return false;
            noteIdle("Installed companion does not support " + sceneLabel());
            return false;
        }
        // Neither diagnostic provides matching world depth/motion guides for temporal reconstruction or FG.
        if (TemporalUpscaler.isActive() || FrameGenSettings.mode() != FrameGenSettings.Mode.OFF
            || FrameGenRuntime.isActive()) {
            if (!releaseResources()) return false;
            noteIdle("Disable temporal upscaling and frame generation for compute diagnostics");
            return false;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null || target == null || target.width <= 0 || target.height <= 0
            || target.getColorTexture() == null || target.getColorTexture().isClosed()) {
            noteIdle("Synthetic scene waiting for a Vulkan target");
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
                format, target.width, target.height, frameIndex, appliedRequest.mode()));
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
            GraphicsBackendRegistry.noteRendered(backend.id(), appliedRequest);
        } else if (failedRevision != appliedRequest.revision()) {
            noteIdle(sceneLabel() + " waiting for scene data; normal renderer retained");
        }
        return recorded;
    }

    /** Device close hook runs before the command encoder and Vulkan device are destroyed. */
    public static void onDeviceClose(VulkanDevice closingDevice) {
        worldCapture.clear();
        if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
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

    private static void noteIdle(String reason) {
        GraphicsBackendRegistry.noteIdle(reason, appliedRequest);
    }

    private static void fail(String reason, Throwable error) {
        failedRevision = appliedRequest.revision();
        GraphicsBackendRegistry.noteFailure(reason, appliedRequest);
        if (error == null) LOGGER.warn("[BEDRAY-COMPUTE] {}", reason);
        else LOGGER.error("[BEDRAY-COMPUTE] {}", reason, error);
    }
}
