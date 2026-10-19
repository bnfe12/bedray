package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;

/**
 * Autotest scenario {@code glbridge_fbo0}: red quad via raw framebuffer 0 (must NOT appear), green quad via
 * {@code GlBridge.mapFramebuffer(0)} (must appear). Needs -Dvkbridge.glbridge=true. Screenshot {@code fbo0.png}.
 */
public final class GlBridgeFbo0Scenario implements AutotestScenario {
    @Override
    public String name() {
        return "glbridge_fbo0";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge bridge = VulkanBridge.glBridge();
        if (bridge.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + bridge.state() + " " + bridge.failure() + " (start with -Dvkbridge.glbridge=true)", null);
            return;
        }
        Fbo0Probe probe = new Fbo0Probe(bridge);
        bridge.registerRenderer(probe);
        ctx.waitFrames(60, () -> ctx.shot("fbo0.png", () -> {
            ctx.log("event", "fbo0_result", "rawBinding", String.valueOf(probe.rawBinding), "mappedBinding", String.valueOf(probe.mappedBinding),
                "hiddenWindowPixel", probe.hiddenPixel, "composited", String.valueOf(bridge.compositedFrames()));
            if (probe.rawBinding != 0 || probe.mappedBinding <= 0) {
                ctx.fail("fbo0: rawBinding=" + probe.rawBinding + " mappedBinding=" + probe.mappedBinding, null);
            } else {
                ctx.finish();
            }
        }));
    }
}
