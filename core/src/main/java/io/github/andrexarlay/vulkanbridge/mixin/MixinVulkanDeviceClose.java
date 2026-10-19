package io.github.andrexarlay.vulkanbridge.mixin;

import io.github.andrexarlay.vulkanbridge.glbridge.GlBridgeImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shutdown point of the GL bridge: head of {@code VulkanDevice.close()} (javap -p on libs/minecraft-26.3-client.jar: {@code public void close()};
 * decompiled body in <local decompiled sources> checkpointExtension.close, commandEncoder.destroy (queue waitIdle +
 * destruction queue), vmaDestroyAllocator, vkDestroyDevice, instance.close). the core releases its Vulkan objects while the device is alive.
 * Does nothing when the core never started.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDevice", remap = false)
public abstract class MixinVulkanDeviceClose {
    @Inject(method = "close()V", at = @At("HEAD"))
    private void vkb$glBridgeShutdown(CallbackInfo ci) {
        GlBridgeImpl.INSTANCE.shutdown();
        io.github.andrexarlay.vulkanbridge.core.FramePhasesImpl.INSTANCE.onDeviceClosed();
    }
}
