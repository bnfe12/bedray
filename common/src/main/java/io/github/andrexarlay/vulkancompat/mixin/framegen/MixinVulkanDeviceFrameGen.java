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
        var device = (com.mojang.renderpearl.backend.vulkan.VulkanDevice) (Object) this;
        try {
            try {
                io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge.onDeviceClose(device);
                io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge.onDeviceClose(device);
            } finally {
                try {
                    FrameGenRuntime.onDeviceClose(); // retire its borrowed guides before releasing RR
                } finally {
                    io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.onDeviceClose(device);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            if (org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(device.vkDevice()) != org.lwjgl.vulkan.VK10.VK_ERROR_DEVICE_LOST)
                throw failure;
            org.slf4j.LoggerFactory.getLogger("VKB-RT").error("[VKB-RT] host close provider failed on a lost device\n{}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.stack(failure));
        }
    }

    @Inject(method = "close", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;destroy()V"))
    private void vkb$retireRtAfterEncoderClose(CallbackInfo ci) {
        io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.afterHostEncoderClose(
            (com.mojang.renderpearl.backend.vulkan.VulkanDevice) (Object) this);
    }
}
