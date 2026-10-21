package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code VulkanDevice.close()} (public, javap on libs/minecraft-26.3-client.jar) destroys the command encoder, VMA and the
 * VkDevice; Streamline must be shut down before that (ProgrammingGuideDLSS_G.md 2.0: slShutdown before the Vulkan device is destroyed).
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDevice", remap = false)
public abstract class MixinVulkanDeviceFrameGen {
    @Inject(method = "close", at = @At("HEAD"))
    private void vkb$shutdownStreamline(CallbackInfo ci) {
        io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge.onDeviceClose(
            (com.mojang.renderpearl.backend.vulkan.VulkanDevice) (Object) this);
        FrameGenRuntime.onDeviceClose();
    }
}
