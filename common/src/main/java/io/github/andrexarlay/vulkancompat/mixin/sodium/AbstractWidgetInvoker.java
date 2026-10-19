package io.github.andrexarlay.vulkancompat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invoker of Sodium's {@code protected void AbstractWidget.playClickSound()} (javap -p libs/sodium-core.jar, Sodium 0.9.2). */
@Mixin(value = AbstractWidget.class, remap = false)
public interface AbstractWidgetInvoker {
    @Invoker("playClickSound")
    void vkb$playClickSound();
}
