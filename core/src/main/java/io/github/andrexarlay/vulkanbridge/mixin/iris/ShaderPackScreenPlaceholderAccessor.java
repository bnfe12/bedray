package io.github.andrexarlay.vulkanbridge.mixin.iris;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code private Screen parent} of {@code ShaderPackScreenPlaceholder} (javap -p, iris 1.11.7+mc26.3). */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder", remap = false)
public interface ShaderPackScreenPlaceholderAccessor {
    @Accessor(value = "parent", remap = false)
    Screen vkb$getParent();
}
