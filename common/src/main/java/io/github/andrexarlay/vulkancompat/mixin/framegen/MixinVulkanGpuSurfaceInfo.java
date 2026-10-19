package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSurfaceInfo;
import it.unimi.dsi.fastutil.longs.LongList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes format/size/image count of the engine swapchain after {@code configure(Configuration)}.
 * Fields and method verified with javap on libs/minecraft-26.3-client.jar (and the decompile
 * local reference): {@code private final int swapchainImageFormat},
 * {@code private int swapchainWidth/swapchainHeight}, {@code private final LongList swapchainImages},
 * {@code public void configure(GpuSurface.Configuration) throws SurfaceException}.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", remap = false)
public abstract class MixinVulkanGpuSurfaceInfo {
    @Shadow
    @Final
    private int swapchainImageFormat;
    @Shadow
    private int swapchainWidth;
    @Shadow
    private int swapchainHeight;
    @Shadow
    @Final
    private LongList swapchainImages;

    @Inject(method = "configure", at = @At("RETURN"))
    private void vkb$publishSurface(CallbackInfo ci) {
        FrameGenSurfaceInfo.publish(this.swapchainImageFormat, this.swapchainWidth, this.swapchainHeight, this.swapchainImages.size());
    }

    /** DLSS-G must be off before the swapchain is destroyed and recreated (resize, vsync change): ProgrammingGuideDLSS_G.md 12.0. */
    @Inject(method = "configure", at = @At("HEAD"))
    private void vkb$beforeConfigure(CallbackInfo ci) {
        FrameGenRuntime.onSurfaceReconfigureBegin();
    }

    /** {@code public void close()}: generation off and our images freed before the swapchain/device go away. */
    @Inject(method = "close", at = @At("HEAD"))
    private void vkb$beforeClose(CallbackInfo ci) {
        FrameGenRuntime.onSurfaceClose();
    }
}
