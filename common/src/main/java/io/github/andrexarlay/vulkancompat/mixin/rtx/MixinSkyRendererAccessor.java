package io.github.andrexarlay.vulkancompat.mixin.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.SkyRenderTargetAccessor;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin exposing {@link SkyRenderer#renderTarget} for identity verification and diagnostics.
 */
@Mixin(value = SkyRenderer.class, remap = false)
public interface MixinSkyRendererAccessor extends SkyRenderTargetAccessor {

    @Accessor("renderTarget")
    @Override
    RenderTarget vkb$getRenderTarget();
}
