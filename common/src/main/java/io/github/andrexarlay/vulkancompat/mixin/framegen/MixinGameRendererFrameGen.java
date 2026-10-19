package io.github.andrexarlay.vulkancompat.mixin.framegen;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "World finished, GUI not started" point of {@code GameRenderer.render()}: right before {@code FogRenderer.endFrame()}, which
 * follows the {@code applyPostEffects()} block and precedes the depth clear and {@code guiRenderer.render()}
 * (javap -c GameRenderer: invokevirtual FogRenderer.endFrame()V, then CommandEncoder.clearDepthTexture, then the GUI;
 * decompile local reference l.529-535). At this point the main
 * target holds the world and hand after the temporal upscaler, i.e. the "HUD-less colour" of Streamline.
 */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class MixinGameRendererFrameGen {
    @Inject(method = "render()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void vkb$fgWorldReady(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            FrameGenRuntime.onWorldReady((GameRenderer) (Object) this);
        }
    }
}
