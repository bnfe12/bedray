package io.github.andrexarlay.vulkanbridge.api;

/**
 * Draws one overlay frame with OpenGL. Called on the dedicated GL bridge thread, with the bridge's hidden GL 4.6 core
 * context current, once per game frame. The bridge has already bound {@code fbo} (the shared image, RGBA8, cleared to
 * transparent black (0,0,0,0)) as the draw framebuffer and set the viewport. Output is composited over the game frame
 * with premultiplied alpha: {@code out = gl.rgb + game.rgb * (1 - gl.a)}.
 * <p>
 * Normal GL orientation (row 0 = bottom of the picture); it lands on screen the right way up, because the engine's main
 * render target is stored bottom-up as well (the engine flips it when it blits to the swapchain). Leave the draw
 * framebuffer binding alone (or restore it); GL state set here persists to the next call because the context is private.
 */
@Experimental
@FunctionalInterface
public interface GlOverlayPainter {
    void paint(int fbo, int width, int height, long frame);
}
