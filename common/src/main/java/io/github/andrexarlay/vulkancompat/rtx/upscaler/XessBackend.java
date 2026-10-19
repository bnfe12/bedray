package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;

/**
 * Intel XeSS super resolution backend (experimental, enabled only by {@code -Dvkbridge.xess=<mode>}).
 * The context lifecycle lives in {@link XessRuntime}; this class records the per-frame {@code xessVKExecute}.
 * {@code init/process} stay refused (STUB): the old {@link IUpscalerBackend} entry points cannot carry depth, motion
 * vectors and jitter.
 */
public class XessBackend implements IUpscalerBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/XessBackend");

    private volatile boolean initialized = false;
    private volatile int inWidth;
    private volatile int inHeight;
    private volatile int outWidth;
    private volatile int outHeight;

    @Override
    public boolean init(int inW, int inH, int outW, int outH) {
        if (!isAvailable()) {
            LOGGER.debug("XeSS backend init skipped: native library not available");
            return false;
        }
        if (inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) {
            LOGGER.warn("Invalid dimensions for XeSS init: {}x{} -> {}x{}", inW, inH, outW, outH);
            return false;
        }
        // STUB: xessVKExecute is not wired yet (plan step X5). The experimental context lives in
        // rtx.upscaler.xess.XessRuntime (-Dvkbridge.xess); this backend must not report readiness it does not have.
        LOGGER.warn("XeSS backend init refused: execution is not implemented yet (STUB, step X5): {}x{} -> {}x{}", inW, inH, outW, outH);
        return false;
    }

    @Override
    public boolean process(RenderTarget input, RenderTarget output) {
        // The XeSS path needs depth, motion vectors and jitter: it is driven through dispatch(FrameContext) only.
        return false;
    }

    /**
     * Records {@code xessVKExecute} into {@code ctx.commandBuffer()} (step X5).
     * <ul>
     * <li>color, depth, motion vectors: GENERAL -> SHADER_READ_ONLY_OPTIMAL before, back to GENERAL after
     *     (XeSS inserts no barriers; the engine assumes GENERAL everywhere);</li>
     * <li>output stays GENERAL (storage write);</li>
     * <li>full memory barriers on both sides.</li>
     * </ul>
     * Jitter is passed with the experiment signs of {@link XessRuntime#jitterSignX()} (default guess, tuned by the verifier).
     *
     * @return true if XeSS recorded successfully; false on any error (logged as {@code stage=execute}; XeSS is then disabled)
     */
    @Override
    public boolean dispatch(FrameContext ctx) {
        if (!XessRuntime.isReady()) {
            return false;
        }
        XessNative lib = XessRuntime.nativeLib();
        MemorySegment context = XessRuntime.context();
        VkCommandBuffer cmd = ctx.commandBuffer();
        FrameContext.ImageRef color = ctx.color();
        FrameContext.ImageRef depth = ctx.depth();
        FrameContext.ImageRef mv = ctx.motionVectors();
        FrameContext.ImageRef out = ctx.output();

        VkBarriers.fullMemoryBarrier(cmd);
        VkBarriers.transition(cmd, color.image(), color.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkBarriers.transition(cmd, depth.image(), depth.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkBarriers.transition(cmd, mv.image(), mv.aspectMask(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        int result;
        try {
            result = lib.vkExecute(context, cmd.address(),
                view(color), view(mv), view(depth), view(out),
                ctx.jitterPixelsX() * XessRuntime.jitterSignX(), ctx.jitterPixelsY() * XessRuntime.jitterSignY(),
                ctx.exposureScale(), ctx.resetHistory(), ctx.renderWidth(), ctx.renderHeight());
        } finally {
            // layouts are restored even if the call threw, so the engine's GENERAL assumption stays true
            VkBarriers.transition(cmd, color.image(), color.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, depth.image(), depth.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.transition(cmd, mv.image(), mv.aspectMask(), VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        if (result != XessNative.RESULT_SUCCESS) {
            XessRuntime.executeFailed(String.valueOf(result));
            return false;
        }
        return true;
    }

    private static XessNative.ViewInfo view(FrameContext.ImageRef r) {
        return new XessNative.ViewInfo(r.view(), r.image(), r.aspectMask(), r.vkFormat(), r.width(), r.height());
    }

    @Override
    public void cleanup() {
        if (initialized) {
            initialized = false;
            inWidth = 0;
            inHeight = 0;
            outWidth = 0;
            outHeight = 0;
            LOGGER.debug("XeSS backend cleaned up");
        }
    }

    @Override
    public boolean isAvailable() {
        return UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.XESS);
    }

    @Override
    public boolean isInitialized() {
        return XessRuntime.isReady();
    }

    public int getInWidth() {
        return inWidth;
    }

    public int getInHeight() {
        return inHeight;
    }

    public int getOutWidth() {
        return outWidth;
    }

    public int getOutHeight() {
        return outHeight;
    }
}
