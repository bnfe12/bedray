package io.github.andrexarlay.vulkancompat.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Accessor duck interface implemented on {@link net.minecraft.client.renderer.GameRenderer}
 * via {@link io.github.andrexarlay.vulkancompat.mixin.rtx.MixinGameRenderer} to allow swapping
 * render targets during the 3D world phase without reflection.
 */
public interface GameRendererTargetAccessor {
    RenderTarget vkb$getMainRenderTarget();
    void vkb$setMainRenderTarget(RenderTarget target);
    RenderTarget vkb$getHud3DTarget();
    void vkb$setHud3DTarget(RenderTarget target);
}
