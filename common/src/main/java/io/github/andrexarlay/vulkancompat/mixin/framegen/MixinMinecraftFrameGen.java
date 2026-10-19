package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame hooks of DLSS Frame Generation in {@code public void renderFrame(boolean)} of {@link Minecraft}. The call order inside the
 * method (javap -c on libs/minecraft-26.3-client.jar, decompile local reference l.1340-1470):
 * acquireNextTexture, update, extract, GameRenderer.render(), GpuSurface.blitFromTexture, CommandEncoder.submit(), GpuSurface.present().
 * Signatures: {@code GameRenderer.render()V}, {@code GpuSurface.present()V} (interface method, invokeinterface).
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MixinMinecraftFrameGen {
    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void vkb$fgFrameHead(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onFrameHead();
        }
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void vkb$fgBeforeRender(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onBeforeRender();
        }
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private void vkb$fgAfterRender(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onAfterRender();
        }
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
    private void vkb$fgPresentStart(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onPresentStart();
        }
    }

    @Inject(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void vkb$fgPresentEnd(boolean advanceGameTime, CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onPresentEnd();
        }
    }
}
