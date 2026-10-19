package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolution scaling of Minecraft's 3D render target.
 * <p>
 * Real part only: computes the 3D target size from {@link VulkanRtxConfig} and resizes
 * {@code GameRenderer} accordingly. The stretch to the swapchain is done by the bilinear
 * blit in MixinVulkanGpuSurface.
 * <p>
 * STUB layers (DLSS/FSR/XeSS upscalers, NRD, ray tracing pipeline, velocity buffer) are intentionally
 * NOT called from here; see the {@code // STUB:} headers in their files.
 */
public final class VulkanRtxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/RtxManager");

    private static volatile boolean frameRendering = false;

    private VulkanRtxManager() {}

    public static void setFrameRendering(boolean rendering) {
        frameRendering = rendering;
    }

    public static boolean isFrameRendering() {
        return frameRendering;
    }

    /** Hook for the start of a frame (kept for the mixin call site); nothing is deferred any more. */
    public static void processPendingChanges() {
        // No deferred state: GameRenderer mixin reconciles the target size on every frame start.
    }

    public static int getTargetRenderWidth(int displayWidth) {
        if (VulkanRtxConfig.isScalingEnabled()) {
            return calculateScaledWidth(displayWidth, VulkanRtxConfig.qualityPreset.getScaleFactor());
        }
        return displayWidth;
    }

    public static int getTargetRenderHeight(int displayHeight) {
        if (VulkanRtxConfig.isScalingEnabled()) {
            return calculateScaledHeight(displayHeight, VulkanRtxConfig.qualityPreset.getScaleFactor());
        }
        return displayHeight;
    }

    public static int calculateScaledWidth(int displayWidth, float scaleFactor) {
        int w = Math.max(320, (int) Math.round(displayWidth * scaleFactor));
        return (w + 1) & ~1;
    }

    public static int calculateScaledHeight(int displayHeight, float scaleFactor) {
        int h = Math.max(240, (int) Math.round(displayHeight * scaleFactor));
        return (h + 1) & ~1;
    }

    /**
     * Applies the configured resolution scale to Minecraft's GameRenderer on the render thread.
     * If a frame is in flight the resize is skipped; MixinGameRenderer re-checks the target size
     * at the start of every frame, so the change is picked up there.
     */
    public static void applyResolutionScalingNow() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.getWindow() == null) {
            return;
        }
        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();
        final int targetW;
        final int targetH;
        if (mc.level != null && WorldTargetManager.isScalingActive()) {
            targetW = WorldTargetManager.getTargetWidth(winW);
            targetH = WorldTargetManager.getTargetHeight(winH);
        } else {
            targetW = winW;
            targetH = winH;
        }

        // GUI and scissor math must keep using full window size.
        var grs = mc.gameRenderer.gameRenderState();
        if (grs != null && grs.windowRenderState != null) {
            grs.windowRenderState.width = winW;
            grs.windowRenderState.height = winH;
        }

        Runnable resizeTask = () -> {
            if (frameRendering) {
                return;
            }
            if (mc.gameRenderer.mainRenderTarget().width != winW || mc.gameRenderer.mainRenderTarget().height != winH) {
                mc.gameRenderer.resize(winW, winH);
            }
            if (WorldTargetManager.isScalingActive() && mc.level != null) {
                WorldTargetManager.ensureTargets(targetW, targetH);
            }
            LOGGER.info("Applied dynamic render target resolution: {}x{} (window: {}x{})", targetW, targetH, winW, winH);
        };

        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            resizeTask.run();
        } else {
            com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(resizeTask);
        }
    }
}
