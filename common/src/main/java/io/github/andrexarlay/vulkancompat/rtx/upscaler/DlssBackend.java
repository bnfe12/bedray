package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA DLSS Super Resolution backend (experimental, enabled only by {@code -Dvkbridge.dlss=<mode>} or
 * {@code active_backend=DLSS} in the toml; needs {@code nvngx_dlss.dll} and our {@code vkb_dlss.dll}, see
 * {@code docs/experiments/dlss.md}). The context lifecycle lives in {@link DlssRuntime}; this class records the
 * per-frame NGX evaluate. The old {@code init/process} entry points stay refused: they cannot carry depth, motion
 * vectors and jitter.
 */
public class DlssBackend implements IUpscalerBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssBackend");

    @Override
    public boolean init(int inW, int inH, int outW, int outH) {
        // The runtime is created from DlssRuntime.onFrameBegin (it needs the live device); this legacy entry point cannot do that.
        LOGGER.warn("DLSS backend init({}x{} -> {}x{}) refused: the context is created by DlssRuntime.onFrameBegin", inW, inH, outW, outH);
        return false;
    }

    @Override
    public boolean process(RenderTarget input, RenderTarget output) {
        // DLSS needs depth, motion vectors and jitter: it is driven through dispatch(FrameContext) only.
        return false;
    }

    /**
     * Records the DLSS evaluate into {@code ctx.commandBuffer()}.
     * <ul>
     * <li>color, depth, motion vectors: GENERAL -> SHADER_READ_ONLY_OPTIMAL before, back to GENERAL after (DLSS Programming
     *     Guide 3.4: inputs in read state with SAMPLED usage; the engine assumes GENERAL everywhere);</li>
     * <li>output stays GENERAL (STORAGE usage, guide 3.4);</li>
     * <li>full memory barriers on both sides; NGX binds its own pipelines inside the command buffer.</li>
     * </ul>
     * Flags (set at feature creation, {@link DlssRuntime#FEATURE_FLAGS}): reverse-Z depth, motion vectors at render resolution
     * in pixels (previous minus current, which is the DLSS guide 3.6 convention), LDR colour, DLSS auto exposure.
     *
     * @return true if DLSS recorded successfully; false on any error (logged as {@code stage=execute}; DLSS is then disabled)
     */
    /**
     * NGX sharpening of the super-resolution pass. Below native the upscaler is the only stage that can restore edge
     * contrast, and DLSS at 0 leaves block edges soft; at native (DLAA) there is no upscale to compensate.
     * {@code -Dvkrtx.dlss.sharpness} overrides the default.
     */
    private static float srSharpness(FrameContext ctx) {
        String v = System.getProperty("vkrtx.dlss.sharpness");
        if (v != null && !v.isBlank()) {
            try {
                return Math.max(0.0f, Math.min(1.0f, Float.parseFloat(v.trim())));
            } catch (NumberFormatException ignored) {
                LOGGER.warn("[DLSS] -Dvkrtx.dlss.sharpness='{}' is not a number, using the default", v);
            }
        }
        float scale = ctx.renderWidth() > 0 ? (float) ctx.targetWidth() / ctx.renderWidth() : 1.0f;
        return scale > 1.05f ? 0.3f : 0.0f;
    }

    @Override
    public boolean dispatch(FrameContext ctx) {
        if (!DlssRuntime.isReady()) {
            return false;
        }
        VkCommandBuffer cmd = ctx.commandBuffer();
        FrameContext.ImageRef color = ctx.color();
        FrameContext.ImageRef depth = ctx.depth();
        FrameContext.ImageRef mv = ctx.motionVectors();
        FrameContext.ImageRef out = ctx.output();

        VkBarriers.fullMemoryBarrier(cmd);
        VkBarriers.transition(cmd, color.image(), color.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkBarriers.transition(cmd, depth.image(), depth.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkBarriers.transition(cmd, mv.image(), mv.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        DlssNative.Status status = null;
        boolean featureOk;
        try {
            featureOk = DlssRuntime.ensureFeature(cmd.address());
            if (featureOk) {
                if (io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.dlssViaStreamline()) {
                    io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime.ensureConstants(
                        ctx.renderWidth(), ctx.renderHeight(), ctx.targetWidth(), ctx.targetHeight());
                }
                DlssNative.EvalInfo info = new DlssNative.EvalInfo(
                    ctx.resetHistory(),
                    image(color, false), image(out, true), image(depth, false), image(mv, false), DlssNative.Image.NONE,
                    ctx.jitterPixelsX() * DlssConfig.jitterSignX(), ctx.jitterPixelsY() * DlssConfig.jitterSignY(),
                    1.0f, 1.0f, srSharpness(ctx), 1.0f, ctx.exposureScale(),
                    ctx.renderWidth(), ctx.renderHeight());
                status = DlssRuntime.nativeLib().evaluate(cmd.address(), DlssRuntime.feature(), info);
            }
        } finally {
            // layouts are restored even if the call threw, so the engine's GENERAL assumption stays true
            VkBarriers.transition(cmd, color.image(), color.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, depth.image(), depth.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, mv.image(), mv.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        if (!featureOk) {
            return false; // ensureFeature already logged stage=execute and disabled DLSS
        }
        if (!status.ok()) {
            DlssRuntime.executeFailed("evaluate:" + status);
            return false;
        }
        return true;
    }

    private static DlssNative.Image image(FrameContext.ImageRef r, boolean readWrite) {
        return new DlssNative.Image(r.image(), r.view(), r.vkFormat(), r.width(), r.height(), r.aspectMask(), readWrite);
    }

    @Override
    public void cleanup() {
        // The context belongs to DlssRuntime (WorldTargetManager.destroyTargets calls DlssRuntime.cleanup through TemporalUpscaler).
    }

    @Override
    public boolean isAvailable() {
        return DlssAvailability.isUsable();
    }

    @Override
    public boolean isInitialized() {
        return DlssRuntime.isReady();
    }
}
