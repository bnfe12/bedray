package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.sodium.PreviewPanel;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes mouse clicks that land on the right-hand panel ({@link PreviewPanel}: compare mode, "compare with" chips, Reset,
 * compact Details button, modal close) to it, and closes the modal on Escape.
 */
@Mixin(value = VideoSettingsScreen.class, remap = false)
public abstract class MixinVideoSettingsScreenInput {
    @org.spongepowered.asm.mixin.Shadow
    private net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget optionList;

    @Inject(method = "updateControls", at = @At("HEAD"), cancellable = true)
    private void vkb$guardOptionList(int mouseX, int mouseY, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.optionList == null) {
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void vkb$panelClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if ((event.button() == 0 || event.button() == 1) && PreviewPanel.click(event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void vkb$modalEsc(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == 256 /* GLFW_KEY_ESCAPE */ && PreviewPanel.isModalOpen()) {
            PreviewPanel.closeModal();
            cir.setReturnValue(true);
        }
    }
}
