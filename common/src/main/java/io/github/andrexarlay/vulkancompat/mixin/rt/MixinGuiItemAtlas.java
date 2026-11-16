package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.hand.IconRtLight;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory icons are drawn one by one into an atlas texture by {@code GuiItemAtlas.drawToSlot} (javap 26.3: it submits the item at
 * packed light 15728880 and calls prepareFrame inside). While a slot is drawn, {@link IconRtLight} swaps the lightmap.
 */
@Mixin(GuiItemAtlas.class)
public class MixinGuiItemAtlas {
    @Inject(method = "drawToSlot", at = @At("HEAD"))
    private void vkb$iconBegin(int x, int y, boolean clear, net.minecraft.client.renderer.item.ItemStackRenderState state, CallbackInfo ci) {
        IconRtLight.begin();
    }

    @Inject(method = "drawToSlot", at = @At("RETURN"))
    private void vkb$iconEnd(int x, int y, boolean clear, net.minecraft.client.renderer.item.ItemStackRenderState state, CallbackInfo ci) {
        IconRtLight.end();
    }

    /** Safety net: an exception inside drawToSlot skips RETURN. */
    @Inject(method = "endFrame", at = @At("HEAD"))
    private void vkb$iconFrameEnd(CallbackInfo ci) {
        IconRtLight.end();
    }
}
