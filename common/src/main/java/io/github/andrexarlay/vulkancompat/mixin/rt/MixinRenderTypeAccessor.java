package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.entity.RenderTypeAccess;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Texture of an entity RenderType for the RT entity atlas (field verified with javap -p, 26.3 client jar). */
@Mixin(value = RenderType.class, remap = false)
public interface MixinRenderTypeAccessor extends RenderTypeAccess {
    @Accessor("state")
    @Override
    RenderSetup vkb$state();
}
