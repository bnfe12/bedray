package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FfxNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AMD FSR Upscaling 4, 3.1 and 2 through the official Vulkan providers. The context lifecycle lives in
 * {@link FsrRuntime}; this class records {@code ffxDispatch(ffxDispatchDescUpscale)} into the frame's command buffer.
 * The old {@code init/process} entry points stay refused: they cannot carry depth, motion vectors and jitter.
 */
public class FsrBackend implements IUpscalerBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrBackend");

    private long lastDispatchNanos;
    private boolean cameraLogged;

    @Override
    public boolean init(int inW, int inH, int outW, int outH) {
        LOGGER.warn("FSR backend init({}x{} -> {}x{}) refused: the context is created by FsrRuntime.onFrameBegin", inW, inH, outW, outH);
        return false;
    }

    @Override
    public boolean process(RenderTarget input, RenderTarget output) {
        return false;
    }

    /**
     * Records FSR into {@code ctx.commandBuffer()}.
     * <ul>
     * <li>Resources as {@code ffxApiGetResourceVK} would describe them: color RGBA8 (read), depth R32_FLOAT with usage
     *     DEPTHTARGET (the VK backend then makes a depth-aspect view, ffx_vk.cpp getImageAspect), motion vectors RG16F,
     *     output RGBA8 with usage UAV, every image mipCount 1.</li>
     * <li>SDK 2.4 expects sampled inputs in SHADER_READ_ONLY_OPTIMAL. The legacy SDK imports them in GENERAL
     *     and manages its own transitions. Output is GENERAL in both cases.</li>
     * <li>Motion vectors: pixels of the render resolution, previous minus current, without jitter (VelocityPipeline), which is
     *     FSR's convention ("motion from current to previous", range [-w,w]): motionVectorScale (1,1).</li>
     * <li>Jitter: CameraJitterManager pixel jitter times {@link FsrConfig#jitterSignX()} / Y.</li>
     * </ul>
     */
    @Override
    public boolean dispatch(FrameContext ctx) {
        if (!FsrRuntime.isReady()) {
            return false;
        }
        FfxNative lib = FsrRuntime.nativeLib();
        FfxNative.Context context = FsrRuntime.context();
        VkCommandBuffer cmd = ctx.commandBuffer();
        FrameContext.ImageRef color = ctx.color();
        FrameContext.ImageRef depth = ctx.depth();
        FrameContext.ImageRef mv = ctx.motionVectors();
        FrameContext.ImageRef out = ctx.output();

        long now = System.nanoTime();
        float frameMs = lastDispatchNanos == 0L ? 16.6f : (float) ((now - lastDispatchNanos) / 1.0e6);
        lastDispatchNanos = now;
        float sharpness = FsrConfig.sharpness(VulkanRtxConfig.fsrSharpness);
        FsrCamera cam = FsrCamera.fromCurrentProjection();
        if (!cameraLogged) {
            cameraLogged = true;
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=camera {} infinite_flag={} projection=\n{}", cam,
                (FsrRuntime.activeFlags() & FfxNative.CREATE_DEPTH_INFINITE) != 0,
                io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.currentProjection());
        }

        int inputState = lib.modern() ? FfxNative.STATE_COMPUTE_READ : FfxNative.STATE_COMMON;
        FfxNative.DispatchParams p = new FfxNative.DispatchParams(
            cmd.address(),
            new FfxNative.Image(color.image(), FfxNative.FORMAT_R8G8B8A8_UNORM, color.width(), color.height(), FfxNative.USAGE_READ_ONLY, inputState),
            new FfxNative.Image(depth.image(), FfxNative.FORMAT_R32_FLOAT, depth.width(), depth.height(), FfxNative.USAGE_DEPTHTARGET, inputState),
            new FfxNative.Image(mv.image(), FfxNative.FORMAT_R16G16_FLOAT, mv.width(), mv.height(), FfxNative.USAGE_READ_ONLY, inputState),
            new FfxNative.Image(out.image(), FfxNative.FORMAT_R8G8B8A8_UNORM, out.width(), out.height(), FfxNative.USAGE_UAV, FfxNative.STATE_UNORDERED_ACCESS),
            ctx.jitterPixelsX() * FsrConfig.jitterSignX(), ctx.jitterPixelsY() * FsrConfig.jitterSignY(),
            1.0f, 1.0f,
            ctx.renderWidth(), ctx.renderHeight(), ctx.targetWidth(), ctx.targetHeight(),
            sharpness > 0.0f, sharpness, frameMs, ctx.exposureScale(),
            ctx.resetHistory(), cam.near(), cam.far(), cam.fovY(), 1.0f);

        if (lib.modern()) {
            transitionInputs(cmd, color, depth, mv, VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
        VkBarriers.fullMemoryBarrier(cmd);
        int result;
        try {
            result = lib.dispatchUpscale(context, p);
        } finally {
            VkBarriers.fullMemoryBarrier(cmd);
            if (lib.modern()) {
                transitionInputs(cmd, color, depth, mv, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
        if (result != FfxNative.RETURN_OK) {
            FsrRuntime.executeFailed(String.valueOf(result));
            return false;
        }
        return true;
    }

    private static void transitionInputs(VkCommandBuffer command, FrameContext.ImageRef color,
                                         FrameContext.ImageRef depth, FrameContext.ImageRef motion, int from, int to) {
        VkBarriers.transition(command, color.image(), color.aspectMask(), from, to);
        VkBarriers.transition(command, depth.image(), depth.aspectMask(), from, to);
        VkBarriers.transition(command, motion.image(), motion.aspectMask(), from, to);
    }

    @Override
    public void cleanup() {
        lastDispatchNanos = 0L;
        cameraLogged = false;
    }

    @Override
    public boolean isAvailable() {
        return FsrLibrary.isUsable();
    }

    @Override
    public boolean isInitialized() {
        return FsrRuntime.isReady();
    }
}
