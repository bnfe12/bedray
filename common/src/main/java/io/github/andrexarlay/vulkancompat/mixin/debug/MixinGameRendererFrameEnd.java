package io.github.andrexarlay.vulkancompat.mixin.debug;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI_END stamp at the return of {@code GameRenderer.render()}. Priority 500 (lower than the default 1000 of other mods'
 * mixins, e.g. Bedray Core's {@code MixinGameRendererGlBridge} at render() RETURN): mixins are applied in ascending
 * priority and a RETURN callback is inserted right before the return instruction, so ours runs first and the GL bridge
 * composite falls into the next segment (GUI_END -> RENDER_END, see {@code MixinMinecraftFrameTiming}).
 */
@Mixin(value = GameRenderer.class, remap = false, priority = 500)
public abstract class MixinGameRendererFrameEnd {
    @Inject(method = "render()V", at = @At("RETURN"))
    private void vkb$guiEnd(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.mark(PerfMonitor.Mark.GUI_END);
        }
    }
}
