package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.DxgiPresentPolicy;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Minecraft 26.4 Snapshot 5 owns DXGI surface creation. Keep its request when compatible; frame generation needs the native
 * Vulkan swapchain, so this optional hook asks the game to create its Vulkan surface for that session.
 */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class MixinMinecraftDxgiPresent {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSurface(JLjava/util/function/BooleanSupplier;Z)Lcom/mojang/renderpearl/api/device/GpuSurface;"),
        index = 2, require = 0)
    private boolean bedray$keepVulkanPresentForFrameGeneration(boolean requested) {
        FrameGenProvider.Active provider = FrameGenProvider.active();
        boolean allowed = DxgiPresentPolicy.allow(requested, provider);
        if (requested && !allowed) {
            LoggerFactory.getLogger("Bedray/DXGI").warn(
                "[Bedray/DXGI] using Minecraft's Vulkan present path because {} frame generation requires VkSwapchainKHR", provider);
        }
        return allowed;
    }
}
