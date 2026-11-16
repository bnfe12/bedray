package io.github.andrexarlay.vulkancompat.mixin.hdr;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrGpu;
import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrOutput;
import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrSettings;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * HDR10 output on the engine's swapchain. Bytecode of {@code VulkanGpuSurface} checked with javap on
 * libs/minecraft-26.3-client.jar:
 * <ul>
 *   <li>{@code pickSwapchainSurfaceFormat(VkSurfaceFormatKHR$Buffer)} accepts only colour space 0 with VkFormat 37 or 44;
 *       the constructor stores {@code .format()} of the result in the final {@code swapchainImageFormat}.</li>
 *   <li>{@code configure} calls {@code VkSwapchainCreateInfoKHR.imageColorSpace(I)} with the literal 0.</li>
 *   <li>{@code blitFromTexture} ends in one {@code VK12.vkCmdBlitImage(VkCommandBuffer,JIJILVkImageBlit$Buffer;I)V} from the
 *       engine target to the swapchain image: the point where the frame becomes PQ code values.</li>
 * </ul>
 * Nothing here runs unless {@link HdrSettings#wantsHdr10()} and the driver offers A2B10G10R10 + ST2084.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", remap = false)
public abstract class MixinVulkanGpuSurfaceHdr {
    @Shadow
    @Final
    private VulkanDevice device;
    @Shadow
    private long swapchain;

    @Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
    private void vkb$pickHdr10(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
        if (!HdrSettings.wantsHdr10()) {
            return;
        }
        int n = formats.limit();
        int[] fmt = new int[n];
        int[] cs = new int[n];
        for (int i = 0; i < n; i++) {
            fmt[i] = formats.get(i).format();
            cs[i] = formats.get(i).colorSpace();
        }
        int idx = HdrOutput.findHdr10(fmt, cs);
        boolean on = HdrOutput.decide(true, HdrOutput.blockedByFrameGeneration(), idx, new ArrayList<>());
        if (on) {
            HdrOutput.setActive(true);
            cir.setReturnValue(formats.get(idx));
        }
    }

    @Redirect(method = "configure", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
    private VkSwapchainCreateInfoKHR vkb$colorSpace(VkSwapchainCreateInfoKHR info, int engineChoice) {
        return info.imageColorSpace(HdrOutput.swapchainColorSpace(engineChoice));
    }

    @Redirect(method = "blitFromTexture", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"))
    private void vkb$blit(VkCommandBuffer cmd, long src, int srcLayout, long dst, int dstLayout, VkImageBlit.Buffer region, int filter) {
        if (!HdrGpu.blit(this.device, cmd, src, srcLayout, dst, dstLayout, region, filter)) {
            VK12.vkCmdBlitImage(cmd, src, srcLayout, dst, dstLayout, region, filter);
        }
    }

    /** {@code configure(Configuration)} has just created the swapchain: send the HDR metadata for it. */
    @Inject(method = "configure", at = @At("RETURN"))
    private void vkb$sendMetadata(CallbackInfo ci) {
        HdrGpu.sendMetadata(this.device.vkDevice(), this.swapchain);
    }

    /** {@code public void close()}: our images, pipelines and descriptors go before the swapchain and the device. */
    @Inject(method = "close", at = @At("HEAD"))
    private void vkb$releaseHdr(CallbackInfo ci) {
        HdrGpu.release();
    }
}
