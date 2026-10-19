package io.github.andrexarlay.vulkancompat.mixin.sodium;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups;
import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Click on the header of one of our collapsible groups toggles it ({@link CollapsibleGroups}). Target: the package-private
 * {@code OptionListWidget$HeaderWidget} of Sodium 0.9.2 (javap -p libs/sodium-core.jar): {@code final String title},
 * {@code public boolean mouseClicked(MouseButtonEvent, boolean)},
 * {@code public void extractRenderState(GuiGraphicsExtractor, int, int, float)}; {@code isMouseOver(double, double)},
 * {@code getX/getY/getWidth/getHeight} and {@code playClickSound()} from Sodium's AbstractWidget. The primary button is
 * {@code event.button() == 1}, as in Sodium's TickBoxControl and Minecraft's AbstractWidget.isValidClickButton (javap -c).
 * Headers of other groups (Sodium's own, other mods) keep their behaviour: the title is not one of ours.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget$HeaderWidget", remap = false)
public abstract class MixinOptionListHeaderWidget {
    @Shadow
    @Final
    String title;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void vkb$toggleGroup(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        String key = CollapsibleGroups.keyForHeaderTitle(this.title);
        if (key == null || event.button() != 1) {
            return;
        }
        AbstractWidget self = (AbstractWidget) (Object) this;
        if (self.isMouseOver(event.x(), event.y())) {
            ((AbstractWidgetInvoker) self).vkb$playClickSound();
            CollapsibleGroups.toggle(key, "header_click");
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void vkb$hoverCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        String key = CollapsibleGroups.keyForHeaderTitle(this.title);
        if (key == null) {
            return;
        }
        AbstractWidget self = (AbstractWidget) (Object) this;
        CollapsibleGroups.recordHeaderRect(key, self.getX(), self.getY(), self.getWidth(), self.getHeight());
        if (self.isMouseOver(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }
}
