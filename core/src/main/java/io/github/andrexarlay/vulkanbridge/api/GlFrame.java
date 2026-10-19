package io.github.andrexarlay.vulkanbridge.api;

/**
 * Per-frame parameters handed to {@link GlRenderer#render}.
 *
 * @param fbo         the bridge framebuffer (already bound as GL_FRAMEBUFFER); the id changes between frames (ring slots)
 * @param width       framebuffer width in pixels (= the game's main render target size at ring creation)
 * @param height      framebuffer height in pixels
 * @param frame       bridge frame counter (0, 1, 2, ... since READY)
 * @param partialTick fraction of the current game tick (Minecraft DeltaTracker.getGameTimeDeltaPartialTick(false)), 0..1
 * @param camera      the game camera at the end of the frame, or {@code null} when the game has no initialised camera (main menu)
 */
@Experimental
public record GlFrame(int fbo, int width, int height, long frame, float partialTick, GlCamera camera) {
}
