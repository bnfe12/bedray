package io.github.andrexarlay.vulkancompat.mixin.autotest;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Autotest only (settings_ui scenario): places the game's cursor over a control so that Sodium shows its tooltip.
 * {@code private double xpos / ypos} of MouseHandler (javap -p of libs/minecraft-26.3-client.jar); the screen reads them
 * through {@code MouseHandler.getScaledXPos(Window)} every frame. Not used outside the autotest.
 */
@Mixin(value = MouseHandler.class, remap = false)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void vkb$setXpos(double x);

    @Accessor("ypos")
    void vkb$setYpos(double y);
}
