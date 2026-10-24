package io.github.andrexarlay.vulkancompat.rtx;

/**
 * Duck interface implemented on {@link net.minecraft.client.renderer.GameRenderer} by
 * {@link io.github.andrexarlay.vulkancompat.mixin.rtx.MixinGameRendererHand}.
 * <p>
 * The first-person hand is drawn inside {@code render3dHud} into the low-resolution world target and is not covered by
 * the world depth copy or the motion vectors. A temporal upscaler therefore reprojects it as if it were background, which
 * produced the stretched, flat-looking arm. The hand pass is deferred and replayed at full resolution after the upscale.
 */
public interface GameRendererHandAccessor {
    /** Redraws the hand deferred during the world phase; no-op when nothing was deferred. */
    void vkb$replayDeferredHand();
}
