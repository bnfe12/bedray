package io.github.andrexarlay.vulkancompat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;

/**
 * Long values of enum rows ("Path Tracing (recommended)") overlapped the row label: Sodium 0.9.2
 * {@code CyclingControl$CyclingControlElement} reserves a fixed {@code Control.getMaxWidth()} = 70 for the value
 * (ControlElement.getContentWidth, used by truncateLabelToFit and StatefulControlElement), but draws the value right-aligned
 * with its full width (extractRenderState: {@code drawString(name, getLimitX() - strWidth - 6, ...)}).
 * Sources: decompiled sodium092 CyclingControl / ControlElement, javap -p -c on libs/sodium-core.jar (getElementName(Enum) call in
 * extractRenderState). Fix: the reserved content width follows the real value width (at least the old 70, at most 60% of the row),
 * and a value that still does not fit is cut with an ellipsis, so the label and the value never overlap.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl$CyclingControlElement", remap = false)
public abstract class MixinCyclingControlElement {
    @Shadow
    private EnumOption option;

    private int vkb$maxValueWidth() {
        AbstractWidget self = (AbstractWidget) (Object) this;
        return Math.max(40, self.getWidth() * 3 / 5);
    }

    @SuppressWarnings("unchecked")
    private Component vkb$rawName(Enum<?> value) {
        return this.option.getElementName(value);
    }

    /** Overrides ControlElement.getContentWidth (public, javap), which the label truncation and reset overlay use. */
    public int getContentWidth() {
        Font font = Minecraft.getInstance().font;
        Object v = this.option.getValidatedValue();
        int w = v instanceof Enum<?> e ? font.width(vkb$rawName(e)) + 6 : 70;
        return Math.min(Math.max(70, w), vkb$maxValueWidth());
    }

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/config/structure/EnumOption;getElementName(Ljava/lang/Enum;)Lnet/minecraft/network/chat/Component;"))
    private Component vkb$fitValue(EnumOption opt, Enum value) {
        Component name = vkb$rawName(value);
        Font font = Minecraft.getInstance().font;
        int allowed = vkb$maxValueWidth() - 6;
        if (font.width(name) <= allowed) {
            return name;
        }
        String cut = font.plainSubstrByWidth(name.getString(), Math.max(0, allowed - font.width("...")));
        return Component.literal(cut + "...").withStyle(name.getStyle());
    }
}
