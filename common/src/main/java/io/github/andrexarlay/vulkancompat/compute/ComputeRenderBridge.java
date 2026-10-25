package io.github.andrexarlay.vulkancompat.compute;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackend;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.api.v1.LightingFrame;
import io.github.andrexarlay.bedray.api.v1.RasterFrame;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
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

/** Host-owned composition point for the optional experimental voxel renderer. */
public final class ComputeRenderBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Compute");
    private static GraphicsBackend resourceOwner;
    private static VulkanDevice resourceDevice;
    private static long frameIndex;
    private static GraphicsBackendRegistry.SceneRequest appliedRequest = GraphicsBackendRegistry.request();
    private static boolean enabled() { return appliedRequest.mode() != GraphicsBackendRegistry.SceneMode.OFF; }
    private static String sceneLabel() { return appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE
        ? "Live voxel scene" : "Synthetic scene"; }
    private static long failedRevision = Long.MIN_VALUE;
    private static VulkanDevice checkedDevice;
    private static boolean computeSupported;
    private static boolean cleanupBroken;
    private static boolean rendererSelectionInitialized;
    private static boolean hadWorld;
    private static boolean cameraPending;
    private static String pendingCameraReason;
    private static String pendingFrameReason, pendingRasterReason;
    private static long recordedRevision = Long.MIN_VALUE;
    private static int resourceWidth, resourceHeight, resourceFormat;
    private static long capturedAtlasGeneration, resourceAtlasGeneration;
    private static long capturedStreamingBudget;
    private static int capturedStreamingRadius, capturedMinSectionY, capturedMaxSectionY;
    private static long coverageLogNanos, limitedSinceNanos;
    private static int loggedResident = -1, loggedQueued = -1;
    private static boolean populatedLogged, limitedLogged;
    private static RenderTarget resourceTarget;
    private static long resourceColorView, resourceDepthView;
    private static LightingFrame currentLighting;
    private static final ComputeWorldCapture worldCapture = new ComputeWorldCapture();

    private ComputeRenderBridge() {}

    /** Accepted Sodium builds notify voxel extraction without enabling the hardware geometry decoder. */
    public static void sectionChanged(int x, int y, int z) {
        if (enabled()) worldCapture.sectionChanged(x, y, z);
    }
    public static void sectionRemoved(int x, int y, int z) {
        if (enabled()) worldCapture.sectionRemoved(x, y, z);
    }

    /** Render-thread scene applied at this frame's boundary; pair with a successful renderDiagnostic result. */
    public static GraphicsBackendRegistry.SceneMode appliedSceneMode() { return appliedRequest.mode(); }

    public static void onFrameBegin(boolean hasWorld) {
        if (!rendererSelectionInitialized) {
            // Config loading and Vulkan runtime setup have finished before the first rendered frame.
            VulkanRtxConfig.applyRendererSelection();
            rendererSelectionInitialized = true;
        }
        frameIndex++;
        currentLighting = null;
        var nextRequest = GraphicsBackendRegistry.request();
        boolean requestChanged = appliedRequest != nextRequest;
        boolean worldLost = hadWorld && !hasWorld;
        hadWorld = hasWorld;
        appliedRequest = nextRequest;
        cameraPending = appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        if (enabled()) VulkanRtxConfig.enforceComputeCompatibility();
        if (requestChanged) {
            if (!releaseResources()) return;
            worldCapture.clear();
            if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
            pendingCameraReason = null;
            pendingFrameReason = null;
            recordedRevision = Long.MIN_VALUE;
        }
        if (cleanupBroken) {
            GraphicsBackendRegistry.noteFailure("Cleanup failed; restart the client before retrying", appliedRequest);
            return;
        }
        if (!hasWorld || !enabled()) {
            if (!requestChanged && worldLost) {
                worldCapture.clear();
                if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
                pendingCameraReason = null;
                pendingFrameReason = null;
                recordedRevision = Long.MIN_VALUE;
            }
            if (!releaseResources()) return;
            if (GraphicsBackendRegistry.backend() != null && !enabled()) {
                noteIdle("Hardware / raster renderer selected");
            } else if (GraphicsBackendRegistry.backend() != null) {
                noteIdle(sceneLabel() + " waiting for a world");
            }
        }
    }

    /** Called after the world camera/projection was captured, independently of hardware RT support. */
    public static void captureWorld(net.minecraft.client.renderer.state.GameRenderState state) {
        if (state == null || state.levelRenderState == null) return;
        GraphicsBackend backend = GraphicsBackendRegistry.backend();
        if (enabled() && backend != null && backend.supportsModelGeometry())
            currentLighting = ComputeLightingCapture.collect(state);
        captureWorld(state.levelRenderState.cameraRenderState);
    }

    /** Compatibility entry point for callers that do not publish environment data. */
    public static void captureWorld(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        GraphicsBackend backend = GraphicsBackendRegistry.backend();
        if (cleanupBroken || backend == null || !enabled() || !backend.supportsSceneMode(appliedRequest.mode())
            || (!backend.wantsWorldSnapshots() && appliedRequest.mode() != GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE)
            || failedRevision == appliedRequest.revision()) return;
        if (!Boolean.getBoolean("bedray.compute.captureWorld") && (TemporalUpscaler.isActive()
            || FrameGenSettings.mode() != FrameGenSettings.Mode.OFF || FrameGenRuntime.isActive())) return;
        try {
            AtlasFrame atlas = ComputeAtlasBridge.current();
            if (atlas == null) {
                notePendingCamera("Waiting for block atlas and baked models", camera);
                return;
            }
            if (backend.supportsWorldStreaming()) {
                long budget = (long)VulkanRtxConfig.voxelMemoryBudgetMiB() * 1024 * 1024;
                var minecraft = net.minecraft.client.Minecraft.getInstance();
                int minecraftRadius = Math.clamp(minecraft.options.getEffectiveRenderDistance(), 1, 64);
                int radius = VulkanRtxConfig.voxelDistanceAuto() ? minecraftRadius
                    : Math.min(minecraftRadius, VulkanRtxConfig.voxelDistanceChunks());
                int minY = minecraft.level == null ? 0 : minecraft.level.getMinSectionY();
                int maxY = minecraft.level == null ? 0 : minecraft.level.getMaxSectionY();
                if (capturedStreamingBudget != 0 && (capturedStreamingBudget != budget
                        || capturedStreamingRadius != radius || capturedMinSectionY != minY || capturedMaxSectionY != maxY)) {
                    if (!releaseResources()) return;
                    backend.clearWorldSnapshot();
                    worldCapture.clear();
                }
                capturedStreamingBudget = budget;
                capturedStreamingRadius = radius; capturedMinSectionY = minY; capturedMaxSectionY = maxY;
                for (var key : backend.drainWorldInvalidations()) worldCapture.invalidated(key);
                var update = worldCapture.captureStreaming(camera, atlas, backend.supportsModelGeometry(),
                    backend.supportsCellTint(), backend.streamingStatus().queuedSections());
                if (update == null) { notePendingCamera(worldCapture.pendingReason(), camera); return; }
                backend.acceptWorldUpdate(update);
                capturedAtlasGeneration = atlas.generation();
                cameraPending = false;
                if (pendingCameraReason != null) {
                    LOGGER.info("[BEDRAY-COMPUTE] camera_ready stream_session={} revision={} distance={} discovered={}",
                        update.session(), update.revision(), update.bounds().effectiveRenderDistanceChunks(),
                        update.discoveredSections());
                    pendingCameraReason = null;
                }
                return;
            }
            var snapshot = worldCapture.capture(camera, atlas, backend.supportsModelGeometry(), backend.supportsCellTint());
            if (snapshot == null) {
                notePendingCamera(worldCapture.pendingReason(), camera);
                return;
            }
            backend.acceptWorldSnapshot(snapshot);
            capturedAtlasGeneration = atlas.generation();
            cameraPending = false;
            if (pendingCameraReason != null) {
                LOGGER.info("[BEDRAY-COMPUTE] camera_ready session={} revision={} sections={}",
                    snapshot.session(), snapshot.revision(), snapshot.sections().size());
                pendingCameraReason = null;
            }
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
            noteIdle("Selected renderer does not support " + sceneLabel());
            return false;
        }
        // Neither diagnostic provides matching world depth/motion guides for temporal reconstruction or FG.
        if (TemporalUpscaler.isActive() || FrameGenSettings.mode() != FrameGenSettings.Mode.OFF
            || FrameGenRuntime.isActive()) {
            if (!releaseResources()) return false;
            noteIdle("Disable temporal upscaling and frame generation for compute diagnostics");
            return false;
        }
        boolean live = appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        // current() may invalidate stale scene data after detecting an unexpected atlas/model identity change.
        AtlasFrame atlas = live ? ComputeAtlasBridge.current() : null;
        if (live && (cameraPending || atlas == null || capturedAtlasGeneration != atlas.generation())) {
            if (!cameraPending) noteIdle("Waiting for matching block atlas and world materials");
            return false;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null || target == null || target.width <= 0 || target.height <= 0
            || target.getColorTexture() == null || target.getColorTexture().isClosed()) {
            noteIdle(sceneLabel() + " waiting for a Vulkan target");
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
        RasterFrame raster = live && backend.supportsModelGeometry() ? rasterFrame(device,target) : null;
        if (live && backend.supportsModelGeometry() && (raster == null || currentLighting == null)) {
            String reason = raster == null ? "Raster input: " + pendingRasterReason : "";
            if (currentLighting == null) reason += (reason.isEmpty() ? "" : "; ")
                + "World lighting: " + ComputeLightingCapture.pendingReason();
            if (!java.util.Objects.equals(pendingFrameReason, reason)) {
                LOGGER.warn("[BEDRAY-COMPUTE] frame_pending reason={} target={}x{} format={} retry=next_frame",
                    reason,target.width,target.height,format);
                pendingFrameReason = reason;
            }
            noteIdle(reason + "; normal renderer retained");
            return false;
        }
        long atlasGeneration = atlas == null ? 0 : atlas.generation();
        long colorView = raster == null ? 0 : raster.colorView(), depthView = raster == null ? 0 : raster.depthView();
        if (resourceOwner != null && (resourceWidth != target.width || resourceHeight != target.height
            || resourceFormat != format || resourceAtlasGeneration != atlasGeneration
            || resourceColorView != colorView || resourceDepthView != depthView || resourceTarget != target)) {
            // Provider resize destroys its previous images. Submit queued uses before allowing that destruction.
            if (!releaseResources()) return false;
        }
        resourceOwner = backend;
        resourceDevice = device;
        resourceWidth = target.width;
        resourceHeight = target.height;
        resourceFormat = format;
        resourceAtlasGeneration = atlasGeneration;
        resourceTarget = target;
        resourceColorView = colorView;
        resourceDepthView = depthView;
        VulkanCommandEncoder encoder = device.createCommandEncoder();
        VkCommandBuffer commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
        boolean recorded = false;
        try {
            VkBarriers.fullMemoryBarrier(commandBuffer);
            recorded = backend.render(new ComputeFrameContext(device.vkDevice(), commandBuffer, image,
                format, target.width, target.height, frameIndex, appliedRequest.mode(), atlas, currentLighting, raster));
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
            if (pendingFrameReason != null) {
                LOGGER.info("[BEDRAY-COMPUTE] frame_inputs_ready target={}x{}", target.width,target.height);
                pendingFrameReason = null;
            }
            GraphicsBackendRegistry.noteRendered(backend.id(), appliedRequest);
            logStreamingCoverage(backend);
            if (recordedRevision != appliedRequest.revision()) {
                LOGGER.info("[BEDRAY-COMPUTE] scene_recorded mode={} backend={} target={}x{} detail={}",
                    appliedRequest.mode(), backend.id(), target.width, target.height, backend.sceneDetail());
                recordedRevision = appliedRequest.revision();
            }
        } else if (failedRevision != appliedRequest.revision()) {
            noteIdle(sceneLabel() + " waiting for scene data; normal renderer retained");
        }
        return recorded;
    }

    /** Device close hook runs before the command encoder and Vulkan device are destroyed. */
    public static void onDeviceClose(VulkanDevice closingDevice) {
        ComputeAtlasBridge.onDeviceClose();
        if (checkedDevice == closingDevice) checkedDevice = null;
    }

    /** Called before RenderTarget destroys any image/view, including same-size recreation. */
    public static boolean onTargetClosing(RenderTarget target) {
        if (cleanupBroken) return false;
        if (resourceTarget != target) return true;
        cameraPending = appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        return releaseResources();
    }

    private static RasterFrame rasterFrame(VulkanDevice device, RenderTarget target) {
        pendingRasterReason = null;
        var color = target.getColorTexture(); var depth = target.getDepthTexture();
        var colorView = target.getColorTextureView(); var depthView = target.getDepthTextureView();
        if (color == null || depth == null || colorView == null || depthView == null) {
            pendingRasterReason = "Waiting for world color/depth textures and views";
            return null;
        }
        if (color.isClosed() || depth.isClosed() || colorView.isClosed() || depthView.isClosed()) {
            pendingRasterReason = "World color/depth texture or view is closed";
            return null;
        }
        if ((color.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
            || (depth.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            pendingRasterReason = "World inputs lack sampled usage: color=" + color.usage() + " depth=" + depth.usage();
            return null;
        }
        int format = VulkanConst.toVk(color.getFormat()), depthFormat = VulkanConst.toVk(depth.getFormat());
        if (depthFormat != VK10.VK_FORMAT_D32_SFLOAT
            || (format != VK10.VK_FORMAT_R8G8B8A8_UNORM && format != VK10.VK_FORMAT_R8G8B8A8_SRGB
                && format != VK10.VK_FORMAT_B8G8R8A8_UNORM && format != VK10.VK_FORMAT_B8G8R8A8_SRGB)) {
            pendingRasterReason = "Unsupported sampled world formats: color=" + format + " depth=" + depthFormat;
            return null;
        }
        long colorHandle = WorldTargetManager.extractVkImageView(colorView);
        long depthHandle = WorldTargetManager.extractVkImageView(depthView);
        if (colorHandle == 0 || depthHandle == 0) {
            pendingRasterReason = "Waiting for Vulkan world image views";
            return null;
        }
        return new RasterFrame(device.vkDevice().address(), colorHandle, depthHandle, format, depthFormat,
            target.width,target.height);
    }

    /** Called before borrowed atlas handles can close or resource/model epochs can be mixed. */
    static boolean onMaterialsInvalidated() {
        boolean retired = releaseResources();
        worldCapture.clear();
        if (GraphicsBackendRegistry.backend() != null) GraphicsBackendRegistry.backend().clearWorldSnapshot();
        capturedAtlasGeneration = 0;
        cameraPending = appliedRequest.mode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE;
        pendingCameraReason = null;
        recordedRevision = Long.MIN_VALUE;
        // A previous failure remains unsafe even after resourceOwner was detached from its broken handles.
        return retired && !cleanupBroken;
    }

    private static boolean releaseResources() {
        GraphicsBackend owner = resourceOwner;
        VulkanDevice device = resourceDevice;
        resourceOwner = null;
        resourceDevice = null;
        resourceWidth = resourceHeight = resourceFormat = 0;
        resourceAtlasGeneration = 0;
        resourceTarget = null;
        resourceColorView = resourceDepthView = 0;
        if (owner == null) return true;
        try {
            // execute() queues transient buffers in the host encoder. Flush those before provider.close() waits idle.
            device.createCommandEncoder().submit();
            owner.close();
            // close() releases provider residency on resize, atlas retirement and budget changes as well
            // as world exit. Forget CPU delivery markers so the next frame republishes the loaded scene.
            worldCapture.clear();
            capturedStreamingBudget = capturedAtlasGeneration = 0;
            populatedLogged = limitedLogged = false;
            coverageLogNanos = limitedSinceNanos = 0;
            loggedResident = loggedQueued = -1;
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

    private static void logStreamingCoverage(GraphicsBackend backend) {
        if (!backend.supportsWorldStreaming()) return;
        var status = backend.streamingStatus();
        if (!status.available()) return;
        long now = System.nanoTime();
        if (status.memoryLimited()) {
            if (limitedSinceNanos == 0) limitedSinceNanos = now;
        } else limitedSinceNanos = 0;
        String event = null;
        if (!populatedLogged && status.residentSections() > 0) {
            event = "first_populated"; populatedLogged = true;
        } else if (!limitedLogged && limitedSinceNanos != 0 && now - limitedSinceNanos >= 2_000_000_000L) {
            event = "memory_limited"; limitedLogged = true;
        } else if (limitedLogged && !status.memoryLimited()) {
            event = "memory_recovered"; limitedLogged = false;
        } else if (populatedLogged && now - coverageLogNanos >= 5_000_000_000L
                && (status.residentSections() != loggedResident || status.queuedSections() != loggedQueued)) event = "progress";
        if (event == null) return;
        LOGGER.info("[BEDRAY-COMPUTE] coverage event={} requested={} effective={} resident={} queued={} used={} budget={} uploaded={} discovered={} empty={} limited={} reason={}",
            event, status.requestedRenderDistanceChunks(), status.effectiveRenderDistanceChunks(),
            status.residentSections(), status.queuedSections(), status.usedGpuBytes(), status.budgetGpuBytes(),
            status.uploadedBytes(), status.discoveredSections(), status.emptySections(), status.memoryLimited(), status.reason());
        coverageLogNanos = now;
        loggedResident = status.residentSections(); loggedQueued = status.queuedSections();
    }

    private static void notePendingCamera(String reason,
            net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        if (!java.util.Objects.equals(pendingCameraReason, reason)) {
            LOGGER.warn("[BEDRAY-COMPUTE] camera_pending reason={} retry=next_frame projection={} view={}", reason,
                WorldTargetManager.levelProjectionOr(camera == null ? null : camera.projectionMatrix),
                camera == null ? null : camera.viewRotationMatrix);
            pendingCameraReason = reason;
        }
        noteIdle(reason);
    }

    private static void fail(String reason, Throwable error) {
        boolean firstFailure = failedRevision != appliedRequest.revision();
        failedRevision = appliedRequest.revision();
        GraphicsBackendRegistry.noteFailure(reason, appliedRequest);
        if (error == null) LOGGER.warn("[BEDRAY-COMPUTE] {}", reason);
        else LOGGER.error("[BEDRAY-COMPUTE] {}", reason, error);
        if (firstFailure) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft != null && minecraft.gui != null && minecraft.gui.hud != null) {
                minecraft.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.translatable(
                    "bedray.compute.failure", reason), false);
            }
        }
    }
}
