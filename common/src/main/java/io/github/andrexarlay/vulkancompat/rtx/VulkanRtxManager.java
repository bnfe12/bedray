package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;

/** Window target synchronization and built-in 3D scale calculations. */
public final class VulkanRtxManager {

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
        return calculateScaledSize(displayWidth, scaleFactor, 320);
    }

    public static int calculateScaledHeight(int displayHeight, float scaleFactor) {
        return calculateScaledSize(displayHeight, scaleFactor, 240);
    }

    private static int calculateScaledSize(int output, float scale, int minimum) {
        if (output <= 0 || scale >= 1.0f) {
            return output;
        }
        int scaled = Math.max(minimum, Math.round(output * scale));
        return scaled >= output ? output : Math.min(output, (scaled + 1) & ~1);
    }

    /**
     * Syncs the engine's window-sized targets and GUI metrics. The next world phase owns allocation of scaled targets.
     */
    public static void applyResolutionScalingNow() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.getWindow() == null) {
            return;
        }
        int winW = mc.getWindow().getWidth();
        int winH = mc.getWindow().getHeight();
        if (winW <= 0 || winH <= 0) {
            return;
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
        };

        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            resizeTask.run();
        } else {
            com.mojang.blaze3d.systems.RenderSystem.queueFencedTask(resizeTask);
        }
    }
}
