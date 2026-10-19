package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.sodium.VideoSettingsScreenAccess;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor of {@code VideoSettingsScreen.prevScreen} (private final Screen, javap -p of libs/sodium-core.jar). */
@Mixin(value = VideoSettingsScreen.class, remap = false)
public interface MixinVideoSettingsScreenAccessor extends VideoSettingsScreenAccess {

    @Accessor("prevScreen")
    @Override
    Screen vkb$prevScreen();
}
