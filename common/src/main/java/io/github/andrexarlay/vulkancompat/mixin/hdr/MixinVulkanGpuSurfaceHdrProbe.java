package io.github.andrexarlay.vulkancompat.mixin.hdr;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rtx.hdr.HdrProbe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passive: after {@code VulkanGpuSurface(VulkanDevice, long)} has created the window surface, logs every format and colour
 * space it offers. Fields {@code device} and {@code surface} verified in the decompile of VulkanGpuSurface.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface", remap = false)
public abstract class MixinVulkanGpuSurfaceHdrProbe {
    @Shadow
    @Final
    private VulkanDevice device;
    @Shadow
    @Final
    private long surface;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vkb$hdrProbeSurface(CallbackInfo ci) {
        // Only for players who asked for HDR10 or the probe: everyone else pays no startup work and gets no extra log lines.
        if (!HdrProbe.wantColorspaceExtension() && !io.github.andrexarlay.vulkancompat.rtx.hdr.HdrSettings.wantsHdr10()) {
            return;
        }
        HdrProbe.logSurface(this.device.vkDevice().getPhysicalDevice(), this.surface);
    }
}
