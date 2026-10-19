package io.github.andrexarlay.vulkancompat.mixin.debug;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame tail stamps in {@code public void renderFrame(boolean)} of {@code Minecraft} (javap -p): vineflower shows
 * {@code gameRenderer.render()} -> {@code windowSurface.blitFromTexture(..)} -> {@code createCommandEncoder().submit()} ->
 * {@code present()}. RENDER_END after render() returns (render-end hooks of all mods are recorded), FRAME_END right before
 * the single per-frame submit (swapchain blit recorded).
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraftFrameTiming {
    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private void vkb$renderEnd(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.mark(PerfMonitor.Mark.RENDER_END);
        }
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void vkb$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.frameEnd();
        }
    }
}
