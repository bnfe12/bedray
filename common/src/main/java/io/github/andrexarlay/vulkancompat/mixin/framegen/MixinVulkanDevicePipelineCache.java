package io.github.andrexarlay.vulkancompat.mixin.framegen;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtVulkanPipelineCache;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flushes and destroys Bedray's cache before Minecraft destroys its Vulkan device. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanDevice", remap = false)
public abstract class MixinVulkanDevicePipelineCache {
    @Shadow
    public abstract VkDevice vkDevice();

    @Inject(method = "close()V", at = @At("HEAD"))
    private void bedray$savePipelineCache(CallbackInfo ci) {
        RtVulkanPipelineCache.closeDevice(this.vkDevice());
    }
}
