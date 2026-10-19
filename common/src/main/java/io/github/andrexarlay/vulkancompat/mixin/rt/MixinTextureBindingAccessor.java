package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.entity.TextureBindingAccess;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code RenderSetup$TextureBinding.location} (javap -p, 26.3 client jar; the class is package-private, hence targets). */
@Mixin(targets = "net.minecraft.client.renderer.rendertype.RenderSetup$TextureBinding", remap = false)
public interface MixinTextureBindingAccessor extends TextureBindingAccess {
    @Accessor("location")
    @Override
    Identifier vkb$location();
}
