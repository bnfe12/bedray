package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;

/**
 * Autotest scenario {@code glbridge_alpha}: AlphaQuads under alpha_hidden (baseline), PREMULTIPLIED, STRAIGHT and STRAIGHT + opacity 0.5.
 * The pixel values are checked offline against the baseline (expected: 0.5*red + 0.5*game, 0.25*red + 0.75*game for opacity 0.5).
 */
public final class GlBridgeAlphaScenario implements AutotestScenario {
    @Override
    public String name() {
        return "glbridge_alpha";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge b = VulkanBridge.glBridge();
        if (b.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + b.state() + " " + b.failure(), null);
            return;
        }
        b.registerRenderer(new AlphaQuads());
        b.setOverlayVisible(false);
        ctx.waitFrames(10, () -> ctx.shot("alpha_hidden.png", () -> {
            b.setOverlayVisible(true);
            b.setAlphaMode(GlBridge.AlphaMode.PREMULTIPLIED);
            ctx.waitFrames(10, () -> ctx.shot("alpha_premul.png", () -> {
                b.setAlphaMode(GlBridge.AlphaMode.STRAIGHT);
                ctx.waitFrames(10, () -> ctx.shot("alpha_straight.png", () -> {
                    b.setOpacity(0.5f);
                    ctx.waitFrames(10, () -> ctx.shot("alpha_straight_op50.png", () -> {
                        ctx.log("event", "alpha_done", "mode", b.alphaMode().name(), "opacity", String.valueOf(b.opacity()));
                        ctx.finish();
                    }));
                }));
            }));
        }));
    }
}
