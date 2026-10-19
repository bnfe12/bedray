package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;

/**
 * Autotest scenario {@code glbridge_mod}: {@link ModLikeRenderer} (compute atlas, VAO/VBO/EBO cube, 4x MSAA FBO + glBlitFramebuffer resolve,
 * scaled atlas blit) together with {@link BorderRenderer} registered before it (two renderers in one frame). Screens {@code mod_a.png} and,
 * 40 frames later, {@code mod_b.png} (the cube rotates and the atlas animates between them). PASS needs READY, the readback pixels taken,
 * 4 MSAA samples, and onRemoved after unregister.
 */
public final class GlBridgeModScenario implements AutotestScenario {
    @Override
    public String name() {
        return "glbridge_mod";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge b = VulkanBridge.glBridge();
        if (b.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + b.state() + " " + b.failure(), null);
            return;
        }
        BorderRenderer border = new BorderRenderer();
        ModLikeRenderer mod = new ModLikeRenderer(b);
        b.registerRenderer(border);
        b.registerRenderer(mod);
        ctx.waitFrames(90, () -> ctx.shot("mod_a.png", () -> ctx.waitFrames(40, () -> ctx.shot("mod_b.png", () -> {
            ctx.log("event", "mod_result", "state", b.state().name(), "frames", String.valueOf(mod.frames), "msaaSamples", String.valueOf(mod.samples),
                "panelPixel", mod.panelPixel, "atlasPixel", mod.atlasPixel, "composited", String.valueOf(b.compositedFrames()));
            b.unregisterRenderer(mod);
            b.unregisterRenderer(border);
            ctx.waitFrames(3, () -> {
                // removals are processed on the GL thread at the next FRAME_END (GlBridgeImpl.processRemovals), where onRemoved runs
                ctx.log("event", "mod_removed", "onRemoved", String.valueOf(mod.removed));
                if (b.state() != GlBridge.State.READY || mod.samples != 4 || mod.panelPixel.equals("not_read") || mod.atlasPixel.equals("not_read")
                    || !mod.removed) {
                    ctx.fail("glbridge_mod checks failed (see mod_result / mod_removed)", null);
                } else {
                    ctx.finish();
                }
            });
        }))));
    }
}
