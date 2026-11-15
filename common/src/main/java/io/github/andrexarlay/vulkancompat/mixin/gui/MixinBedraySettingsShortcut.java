package io.github.andrexarlay.vulkancompat.mixin.gui;

import io.github.andrexarlay.vulkancompat.sodium.BedrayConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The same settings shortcut on the title screen and the interactive pause menu. */
@Mixin(value = {TitleScreen.class, PauseScreen.class}, remap = false)
public abstract class MixinBedraySettingsShortcut extends Screen {
    protected MixinBedraySettingsShortcut(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void bedray$addSettingsShortcut(CallbackInfo ci) {
        Screen parent = this;
        if (parent instanceof PauseScreen pause && !pause.showsPauseMenu()) return;

        Component label = Component.translatable("bedray.settings.title");
        int buttonWidth = Math.max(1, Math.min(width - 16, font.width(label) + 16));
        addRenderableWidget(Button.builder(label, button -> BedrayConfigScreen.open(parent))
            .bounds(8, 8, buttonWidth, 20).build());
    }
}
