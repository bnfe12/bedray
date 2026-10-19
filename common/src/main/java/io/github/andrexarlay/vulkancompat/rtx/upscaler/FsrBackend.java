package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FfxNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrCamera;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AMD FidelityFX Super Resolution (FSR 3.1.x / 2.x through the official ffx-api Vulkan DLL). The context lifecycle lives in
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
     * <li>States: inputs COMMON, output UNORDERED_ACCESS, both map to VK_IMAGE_LAYOUT_GENERAL in the VK backend
     *     (ffx_vk.cpp getVKImageLayoutFromResourceState); the backend transitions inputs to SHADER_READ_ONLY itself and
     *     restores every imported image to that initial state at the end (UnregisterResourcesVK), so the engine's GENERAL
     *     assumption stays true. Full memory barriers on both sides order it against the engine's own work.</li>
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

        FfxNative.DispatchParams p = new FfxNative.DispatchParams(
            cmd.address(),
            new FfxNative.Image(color.image(), FfxNative.FORMAT_R8G8B8A8_UNORM, color.width(), color.height(), FfxNative.USAGE_READ_ONLY, FfxNative.STATE_COMMON),
            new FfxNative.Image(depth.image(), FfxNative.FORMAT_R32_FLOAT, depth.width(), depth.height(), FfxNative.USAGE_DEPTHTARGET, FfxNative.STATE_COMMON),
            new FfxNative.Image(mv.image(), FfxNative.FORMAT_R16G16_FLOAT, mv.width(), mv.height(), FfxNative.USAGE_READ_ONLY, FfxNative.STATE_COMMON),
            new FfxNative.Image(out.image(), FfxNative.FORMAT_R8G8B8A8_UNORM, out.width(), out.height(), FfxNative.USAGE_UAV, FfxNative.STATE_UNORDERED_ACCESS),
            ctx.jitterPixelsX() * FsrConfig.jitterSignX(), ctx.jitterPixelsY() * FsrConfig.jitterSignY(),
            1.0f, 1.0f,
            ctx.renderWidth(), ctx.renderHeight(), ctx.targetWidth(), ctx.targetHeight(),
            sharpness > 0.0f, sharpness, frameMs, ctx.exposureScale(),
            ctx.resetHistory(), cam.near(), cam.far(), cam.fovY(), 1.0f);

        VkBarriers.fullMemoryBarrier(cmd);
        int result;
        try {
            result = lib.dispatchUpscale(context, p);
        } finally {
            VkBarriers.fullMemoryBarrier(cmd);
        }
        if (result != FfxNative.RETURN_OK) {
            FsrRuntime.executeFailed(String.valueOf(result));
            return false;
        }
        return true;
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
