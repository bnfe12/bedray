package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtFailure;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostSelector;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * I3.5: per-frame driver of the Iris pack-level host, called from {@code MixinGameRenderer} (render thread only).
 * <p>
 * The host is read from the {@link ShaderHosts} session selection; when the active host is NATIVE every method here
 * is a no-op, so the native path stays byte-identical. When IRIS is active, {@link #onFrameBegin()} advances the Iris
 * uniform timing once per frame and {@link #renderWorldPost} records the pack's composite/final passes over the engine
 * color image at the one documented frame point (head of {@code render3dHud}, after the RT copy and the world depth
 * copy: engine color and depth are final, the hand is not drawn yet, no render pass is open, GUI and upscale follow).
 * One {@code [VKB-IRIS]} line is logged per world frame. Any executor failure disables the iris host for the session
 * (honest fallback: the untouched engine image is shown) and tears the executor down.
 * <p>
 * {@link #teardown} runs on world unload/change ({@code Minecraft.setLevel}) and resource reload
 * ({@code Minecraft.reloadResourcePacks}), both on the render thread: GPU images are destroyed while the device is
 * alive and rebuilt lazily on the next world frame; no stale images survive a world change.
 */
public final class IrisFrameHook {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/IrisHost");

    private IrisFrameHook() {}

    /** Head of {@code GameRenderer.render()}: advances frameTimeCounter/frameCounter/frameTime once per frame. */
    public static void onFrameBegin() {
        if (!irisActive()) {
            return;
        }
        IrisRenderBridge.updateTiming();
    }

    /**
     * Records the active pack's passes over the engine color image. Returns the honest {@code recorded} flag;
     * every guard failure is logged with a reason and leaves the engine image untouched.
     */
    public static boolean renderWorldPost(RenderTarget target, CameraRenderState camera, LevelRenderState levelState) {
        ShaderHostSelector.Selection sel = ShaderHosts.selection();
        if (sel == null || sel.active() != ShaderHostKind.IRIS) {
            return false;
        }
        IrisShaderHost host = ShaderHosts.iris();
        ShaderHostSelector selector = ShaderHosts.selector();
        boolean disabled = selector != null && selector.isDisabledForSession(ShaderHostKind.IRIS);
        ShaderHostState state = disabled ? ShaderHostState.DISABLED_FOR_SESSION
            : host != null ? host.state() : ShaderHostState.NOT_INSTALLED;
        IrisPassGraph.FullResult full = host != null ? host.activeFullGraph() : null;
        int passes = full != null ? full.passes().size() : 0;

        boolean recorded = false;
        String reason;
        if (disabled) {
            reason = "disabled_for_session";
        } else if (host == null || state != ShaderHostState.READY || full == null || full.passes().isEmpty()) {
            reason = "host_not_ready";
        } else if (target == null) {
            reason = "no_target";
        } else {
            long colorImage = WorldTargetManager.extractVkImage(target);
            long colorView = WorldTargetManager.extractVkImageView(target.getColorTextureView());
            long depthView = WorldTargetManager.extractVkImageView(target.getDepthTextureView());
            int width = target.width;
            int height = target.height;
            if (colorImage == 0L || colorView == 0L || depthView == 0L) {
                reason = "no_vk_handles";
            } else if (width <= 0 || height <= 0) {
                reason = "bad_size";
            } else {
                VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
                if (device == null) {
                    reason = "no_device";
                } else {
                    String failureReason = null;
                    try {
                        FsrCamera cam = FsrCamera.fromCurrentProjection();
                        VulkanCommandEncoder encoder = device.createCommandEncoder();
                        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
                        VkBarriers.fullMemoryBarrier(cmd);
                        recorded = IrisRenderBridge.render(cmd, host, colorImage, colorView, depthView, width, height,
                            camera, levelState, IrisRenderBridge.frameTimeCounter(), IrisRenderBridge.frameCounter(),
                            cam.near(), cam.far(), IrisRenderBridge.frameTime());
                        if (recorded) {
                            VkBarriers.fullMemoryBarrier(cmd);
                            RtFailure.check(VK10.vkEndCommandBuffer(cmd), "iris_end_command_buffer");
                            encoder.execute(cmd);
                            reason = null;
                        } else {
                            reason = "executor_failed";
                            failureReason = "Iris executor could not record the frame";
                        }
                    } catch (RuntimeException | LinkageError error) {
                        recorded = false;
                        reason = "exception:" + error.getClass().getSimpleName();
                        LOGGER.error("[VKB-IRIS] executor failure, disabling the iris host for this session", error);
                        failureReason = "Iris post failed: " + error.getClass().getSimpleName();
                    }
                    if (failureReason != null) disableForSession(failureReason);
                }
            }
        }
        ShaderHostSelector.Selection current = ShaderHosts.selection();
        if (current != null && current.active() != sel.active()) {
            state = current.requestedStatus().state();
        }
        LOGGER.info(logLine(sel.requested(), current == null ? sel.active() : current.active(), state, passes, recorded, reason));
        return recorded;
    }

    /** World unload/change and resource reload: destroy the executor's GPU objects while the device is alive. */
    public static void teardown(String reason) {
        if (!irisActive()) {
            return;
        }
        boolean hadExecutor = IrisRenderBridge.isInitialized();
        IrisRenderBridge.teardown();
        IrisRenderBridge.resetTiming();
        if (hadExecutor) {
            LOGGER.info("[VKB-IRIS] event=teardown reason={}", reason);
        }
    }

    /** The per-frame verifier line: {@code [VKB-IRIS] requested=... active=... state=... passes=N recorded=...}. */
    static String logLine(ShaderHostKind requested, ShaderHostKind active, ShaderHostState state,
                          int passes, boolean recorded, String reason) {
        StringBuilder sb = new StringBuilder("[VKB-IRIS] requested=").append(requested.configName())
            .append(" active=").append(active.configName())
            .append(" state=").append(state)
            .append(" passes=").append(passes)
            .append(" recorded=").append(recorded);
        if (reason != null) {
            sb.append(" reason=\"").append(reason).append('"');
        }
        return sb.toString();
    }

    private static boolean irisActive() {
        ShaderHostSelector.Selection sel = ShaderHosts.selection();
        return sel != null && sel.active() == ShaderHostKind.IRIS;
    }

    static void disableForSession(String why) {
        ShaderHosts.noteIrisFailure(why);
        IrisRenderBridge.teardown();
    }
}