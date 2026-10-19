package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.screens.Screen;

/**
 * Read access to Sodium's private {@code VideoSettingsScreen.prevScreen} (javap -p of libs/sodium-core.jar), implemented by
 * the accessor mixin {@code mixin.sodium.MixinVideoSettingsScreenAccessor}. Used by the RT "Reset" buttons to reopen the
 * settings page with the reset values (the screen caches option values when it is built).
 */
public interface VideoSettingsScreenAccess {
    Screen vkb$prevScreen();
}
