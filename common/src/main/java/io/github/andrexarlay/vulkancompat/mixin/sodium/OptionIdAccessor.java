package io.github.andrexarlay.vulkancompat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor of the package-private {@code final Identifier id} of Sodium's Option (javap -p libs/sodium-core.jar, 0.9.2). */
@Mixin(value = Option.class, remap = false)
public interface OptionIdAccessor {
    @Accessor("id")
    Identifier vkb$id();
}
