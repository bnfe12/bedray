package io.github.andrexarlay.vulkanbridge.api;

import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

/** Backend-neutral "draw to the screen" for GL code that runs both on the GL backend and through the {@link GlBridge} (ADR-0006). */
@Experimental
public final class GlTargets {
    private GlTargets() {}

    /**
     * Binds the framebuffer that stands for "the screen" and returns its id:
     * <ul>
     *   <li>on the GL bridge thread inside {@link GlRenderer#render}: the bridge framebuffer of the current renderer
     *       ({@link GlBridge#bindTarget()});</li>
     *   <li>when the game itself runs on the OpenGL backend (bridge state NOT_NEEDED): framebuffer 0 of the game's context, on the
     *       thread that owns that context;</li>
     *   <li>anywhere else (Vulkan backend outside the bridge thread): {@link IllegalStateException}, because no GL context of the
     *       game exists there.</li>
     * </ul>
     */
    public static int bindDefault() {
        GlBridge bridge = VulkanBridge.glBridge();
        if (bridge.onGlThread()) {
            bridge.bindTarget();
            return bridge.targetFramebuffer();
        }
        if (VulkanBridge.backend() == BackendType.OPENGL) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return 0;
        }
        throw new IllegalStateException("GlTargets.bindDefault: not on the GL bridge thread and the game backend is " + VulkanBridge.backend()
            + "; GL code on the Vulkan backend must run inside GlRenderer.render");
    }
}
