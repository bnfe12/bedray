package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;

/**
 * Autotest scenario {@code glbridge} (registered by {@code BridgeBootstrap.clientInit}). In the fixed test world:
 * without -Dvkbridge.glbridge=true it takes {@code before.png} (the baseline picture, bridge off) and ends. With the flag it takes
 * {@code before_hidden.png} (bridge ready, overlay hidden), shows the overlay (GL triangle), renders 120 frames and takes
 * {@code after.png}; PASS needs state READY and at least 120 composited frames.
 * Screenshots read the main render target (the harness says so itself): they prove the composite into the target, not the swapchain.
 */
public final class GlBridgeScenario implements AutotestScenario {
    static final int OVERLAY_FRAMES = 120;

    @Override
    public String name() {
        return "glbridge";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge bridge = VulkanBridge.glBridge();
        ctx.log("event", "glbridge_state", "state", bridge.state().name(), "size", bridge.width() + "x" + bridge.height());
        if (bridge.state() == GlBridge.State.DISABLED) {
            ctx.shot("before.png", () -> {
                ctx.log("event", "glbridge_baseline", "bridge", "DISABLED");
                ctx.finish();
            });
            return;
        }
        if (bridge.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY at scenario start: " + bridge.state() + " " + bridge.failure(), null);
            return;
        }
        TrianglePainter painter = new TrianglePainter(bridge);
        bridge.registerRenderer(painter);
        bridge.setOverlayVisible(false);
        ctx.waitFrames(5, () -> ctx.shot("before_hidden.png", () -> {
            long compositedHidden = bridge.compositedFrames();
            bridge.setOverlayVisible(true);
            ctx.waitFrames(OVERLAY_FRAMES, () -> ctx.shot("after.png", () -> {
                long composited = bridge.compositedFrames();
                var f = painter.last;
                if (f != null) {
                    var c = f.camera();
                    ctx.log("event", "glframe", "fbo", String.valueOf(f.fbo()), "size", f.width() + "x" + f.height(), "frame", String.valueOf(f.frame()),
                        "partialTick", String.valueOf(f.partialTick()),
                        "camera", c == null ? "null" : String.format("pos=%.2f,%.2f,%.2f rot=%.1f,%.1f fov=%.1f", c.x(), c.y(), c.z(), c.xRot(), c.yRot(), c.fov()));
                }
                ctx.log("event", "glbridge_done", "state", bridge.state().name(), "compositedHidden", String.valueOf(compositedHidden),
                    "composited", String.valueOf(composited));
                if (bridge.state() != GlBridge.State.READY || compositedHidden != 0 || composited < OVERLAY_FRAMES) {
                    ctx.fail("glbridge: state=" + bridge.state() + " compositedHidden=" + compositedHidden + " composited=" + composited, null);
                } else {
                    ctx.finish();
                }
            }));
        }));
    }
}
