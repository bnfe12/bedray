package io.github.andrexarlay.vulkancompat.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Accessor interface for reading the target captured by {@code SkyRenderer}.
 */
public interface SkyRenderTargetAccessor {
    RenderTarget vkb$getRenderTarget();
}
