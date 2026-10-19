package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.entity.RenderSetupAccess;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** {@code RenderSetup.textures} (javap -p, 26.3 client jar). */
@Mixin(value = RenderSetup.class, remap = false)
public interface MixinRenderSetupAccessor extends RenderSetupAccess {
    @SuppressWarnings("rawtypes")
    @Accessor("textures")
    @Override
    Map vkb$textures();
}
