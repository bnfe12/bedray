package io.github.andrexarlay.vulkanbridge.api;

/**
 * A mod's OpenGL renderer that draws onto the Vulkan frame through the {@link GlBridge}.
 * <p>
 * Threading: every method runs on the dedicated GL bridge thread ("VKB GL Bridge") with the bridge's hidden OpenGL 4.6
 * core context current. Never call these from the render thread yourself. The context is private to the bridge, so
 * GL objects (programs, VAOs, textures) you create in {@link #render} belong to it and survive between frames; GL state you set
 * persists to your next call (and to the next registered renderer: restore what you change if other renderers are active).
 * <p>
 * Before each {@link #render} the bridge guarantees: the bridge framebuffer is bound as GL_FRAMEBUFFER (colour = the shared RGBA8
 * texture cleared to (0,0,0,0), depth/stencil = a plain GL renderbuffer, cleared to 1.0/0, NOT shared with Vulkan), the
 * viewport is (0,0,width,height), the scissor test is off and the colour/depth/stencil write masks are enabled.
 * <p>
 * "Framebuffer 0" limitation (Tier 2): the bridge context has no window the game could present, and its default framebuffer
 * belongs to the hidden SDL window. GL code that calls {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)} to "draw to the screen" would
 * draw to that hidden window, invisibly. The bridge cannot intercept it (LWJGL calls the driver function pointers directly and a
 * foreign mod's classes must not be patched). Use {@link GlBridge#bindTarget()} instead, or route the id through
 * {@link GlBridge#mapFramebuffer(int)} in the mod's own hook (see GlBridge javadoc).
 */
@Experimental
public interface GlRenderer {
    /** Draws one frame into the bridge framebuffer. Exceptions fail the bridge (state FAILED) and are rethrown on the render thread. */
    void render(GlFrame frame);

    /**
     * Called (GL thread) before the first {@link #render} after registration and again whenever the shared target was recreated
     * with a different size (window resize). Recreate size-dependent GL objects (MSAA framebuffers, G-buffers) here.
     */
    default void onResize(int width, int height) {
    }

    /**
     * Called (GL thread) when the renderer is unregistered or the bridge shuts down with the context still current:
     * delete your GL objects here.
     */
    default void onRemoved() {
    }
}
