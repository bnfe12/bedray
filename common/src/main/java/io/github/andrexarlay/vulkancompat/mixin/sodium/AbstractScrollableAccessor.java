package io.github.andrexarlay.vulkancompat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractScrollable;
import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollbarWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor of {@code AbstractScrollable.scrollbar} (protected ScrollbarWidget, javap -p of libs/sodium-core.jar, Sodium
 * 0.9.2). Used to keep the scroll position when a collapsible group is toggled ({@link MixinOptionListWidget}).
 */
@Mixin(value = AbstractScrollable.class, remap = false)
public interface AbstractScrollableAccessor {
    @Accessor("scrollbar")
    ScrollbarWidget vkb$scrollbar();
}
