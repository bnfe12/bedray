package io.github.andrexarlay.vulkanbridge.api;

/**
 * GL to Vulkan interop service (experiment, since API 0.4.0). A hidden OpenGL 4.6 context on its own thread renders into
 * a VkImage that lives on the engine's VkDevice (exported as an OPAQUE_WIN32 handle, imported into GL with
 * GL_EXT_memory_object_win32, synchronised with exported binary semaphores). The bridge composites that image over the
 * game frame at the end of {@code GameRenderer.render()}.
 * <p>
 * Off by default. A mod switches it on from code with {@link #request(String)} before the VkDevice exists (mod constructor /
 * early init); {@code -Dvkbridge.glbridge=true} forces it on, {@code -Dvkbridge.glbridge=false} forces it off. The device
 * extensions have to be requested before the VkDevice exists, which is why a later switch is impossible. Windows only
 * (OPAQUE_WIN32 handles); tested on NVIDIA. Check {@link #capabilities()} before relying on a feature.
 * <p>
 * Since 0.6.0 the bridge composites through the frame phases of {@link FramePhases} ({@link FramePhase#BEFORE_GUI} and
 * {@link FramePhase#FRAME_END}), and every renderer has its own {@link GlRendererOptions}. Renderers registered without options
 * follow the deprecated global setters ({@link #setAlphaMode}, {@link #setOpacity}, {@link #setOverlayVisible}), which therefore
 * act as the defaults of the 0.5.x API.
 * Obtain via {@link VulkanBridge#glBridge()}.
 */
@Experimental
public interface GlBridge {
    enum State {
        /** The JVM property is not set; the bridge does nothing. */
        DISABLED,
        /** Enabled; the engine device does not exist yet or the first frame has not run. */
        WAITING_FOR_DEVICE,
        /** Shared images are imported into GL; frames can be composited. */
        READY,
        /** Initialisation failed; see {@link #failure()}. The bridge does not retry. */
        FAILED,
        /** The game runs on the OpenGL backend: a mod draws with GL directly, the bridge is not needed and does nothing (since 0.6.0). */
        NOT_NEEDED,
        /** Requested or forced on a platform without OPAQUE_WIN32 sharing (not Windows); nothing is done (since 0.6.0). */
        UNSUPPORTED_PLATFORM
    }

    State state();

    /**
     * Asks for the bridge on behalf of {@code modId} (since 0.6.0). Must run before the VkDevice is created (mod constructor,
     * Fabric preLaunch/client init, or {@code LoaderAccess.registerEarlyHook}): it registers the Win32 external memory/semaphore
     * device extensions. Returns the state after the call: WAITING_FOR_DEVICE (accepted, or already on), READY/FAILED (already
     * running), NOT_NEEDED (OpenGL backend), UNSUPPORTED_PLATFORM, or DISABLED when it came too late (the device requirement
     * registry was already frozen) or {@code -Dvkbridge.glbridge=false} forces the bridge off. Every outcome is logged with the mod id.
     */
    State request(String modId);

    /** What the bridge can do now (since 0.6.0). */
    GlBridgeCapabilities capabilities();

    /**
     * How the colour in the bridge framebuffer is interpreted when it is blended over the game frame. The composite is a Vulkan sampling pass
     * (fullscreen triangle, fixed blend {@code ONE, ONE_MINUS_SRC_ALPHA} for colour and alpha) inside the engine frame, no sRGB conversion
     * (GL image and main target are both UNORM, blending happens on the stored values, like the game's own GUI).
     */
    enum AlphaMode {
        /**
         * Default. The GL output is premultiplied: {@code screen = gl.rgb + game.rgb * (1 - gl.a)}. Get it by drawing opaque pixels, or with
         * {@code glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)} onto the cleared (0,0,0,0) target,
         * or with premultiplied textures and {@code glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)}. Plain {@code glBlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)}
         * squares the alpha channel (a*a): edges come out too transparent.
         */
        PREMULTIPLIED,
        /** The GL output is straight (non-premultiplied, e.g. written without blending): the composite shader multiplies rgb by a first. */
        STRAIGHT
    }

    /**
     * Sets the alpha interpretation used by renderers registered without options (default PREMULTIPLIED); takes effect on the next frame.
     * @deprecated since 0.6.0: per renderer with {@link GlRendererOptions#alphaMode()}
     */
    @Deprecated
    void setAlphaMode(AlphaMode mode);

    /** Alpha mode of renderers registered without options. */
    AlphaMode alphaMode();

    /**
     * Opacity in [0,1] (default 1) of renderers registered without options, applied to the premultiplied colour.
     * @deprecated since 0.6.0: per renderer with {@link GlRendererOptions#opacity()}
     */
    @Deprecated
    void setOpacity(float opacity);

    /** Opacity of renderers registered without options. */
    float opacity();

    /** Reason of the failure when {@link #state()} is FAILED, otherwise {@code null}. */
    String failure();

    default boolean isReady() {
        return state() == State.READY;
    }

    /** Size of the shared images in pixels (0 before READY). */
    int width();

    int height();

    /**
     * Registers a GL renderer; renderers run in registration order every frame. Without any renderer nothing is rendered or
     * composited. May be called from any thread; takes effect on the next frame. {@link GlRenderer#onResize} is called before its
     * first frame. Registering the same instance twice is a no-op.
     */
    void registerRenderer(GlRenderer renderer);

    /**
     * Registers a renderer with its own options (since 0.6.0). If it is already registered, its options are replaced. Throws
     * IllegalArgumentException for a phase the bridge cannot composite in (see {@link GlBridgeCapabilities#phases()}).
     */
    void registerRenderer(GlRenderer renderer, GlRendererOptions options);

    /**
     * Replaces the options of a registered renderer; takes effect on the next frame. Returns false when it is not registered.
     * Same phase check as {@link #registerRenderer(GlRenderer, GlRendererOptions)}.
     */
    boolean setOptions(GlRenderer renderer, GlRendererOptions options);

    /** Options in effect for a registered renderer (for one registered without options: the global defaults), or null. */
    GlRendererOptions options(GlRenderer renderer);

    /** Removes a renderer; its {@link GlRenderer#onRemoved} runs on the GL thread before the next frame. Returns false if it was not registered. */
    boolean unregisterRenderer(GlRenderer renderer);

    /**
     * Legacy single painter (0.4.0): replaces the previous painter, null removes it. Implemented as a renderer.
     * @deprecated use {@link #registerRenderer(GlRenderer)}
     */
    @Deprecated
    void setPainter(GlOverlayPainter painter);

    /** True when the calling thread is the GL bridge thread (the only thread allowed to call GL on the bridge context). */
    boolean onGlThread();

    /**
     * GL thread only (throws IllegalStateException elsewhere, or outside a frame): binds the bridge framebuffer of the current frame as
     * GL_FRAMEBUFFER. This is the replacement for {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)} of GL code that believes it draws
     * to the window.
     */
    void bindTarget();

    /** GL thread only, inside a frame: the bridge framebuffer id of the current frame (changes every frame). */
    int targetFramebuffer();

    /**
     * Maps a framebuffer id the consumer wants to bind: 0 (the window) becomes the bridge framebuffer of the current frame, any other id is
     * returned unchanged. Intended for a hook in the consuming mod (for example a Mixin or a wrapper around the mod's own
     * {@code glBindFramebuffer} call): {@code glBindFramebuffer(target, bridge.mapFramebuffer(id))}. Outside a frame returns {@code requested}.
     */
    int mapFramebuffer(int requested);

    /**
     * When false, renderers registered without options are neither rendered nor composited (default true).
     * @deprecated since 0.6.0: per renderer with {@link GlRendererOptions#visible()}
     */
    @Deprecated
    void setOverlayVisible(boolean visible);

    /** Frames that were rendered by GL and composited into the game frame since READY. */
    long compositedFrames();
}
