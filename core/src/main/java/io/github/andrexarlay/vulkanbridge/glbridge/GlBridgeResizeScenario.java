package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import org.lwjgl.sdl.SDLVideo;

/**
 * Autotest scenario {@code glbridge_resize}: window 1280x720 -> 1920x1080 -> 1000x600 -> 1280x720 with {@code SDL_SetWindowSize} on the game window.
 * After each change it waits until the main target has the new size, then until the bridge reports the same size (the ring is recreated after
 * {@link GlBridgeImpl#RESIZE_STABLE_FRAMES} stable frames), renders 30 more frames and takes a screenshot (BorderRenderer: crisp 4 px frame).
 * PASS needs every step to reach its size within 600 frames and the renderer to see each size in onResize.
 */
public final class GlBridgeResizeScenario implements AutotestScenario {
    private static final int[][] SIZES = {{1280, 720}, {1920, 1080}, {1000, 600}, {1280, 720}};

    @Override
    public String name() {
        return "glbridge_resize";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge bridge = VulkanBridge.glBridge();
        if (bridge.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + bridge.state() + " " + bridge.failure(), null);
            return;
        }
        BorderRenderer border = new BorderRenderer();
        bridge.registerRenderer(border);
        ctx.waitFrames(30, () -> shotAndNext(ctx, bridge, border, 0));
    }

    private void shotAndNext(AutotestContext ctx, GlBridge bridge, BorderRenderer border, int step) {
        int[] size = SIZES[step];
        var target = ctx.minecraft().gameRenderer.mainRenderTarget();
        var f = border.last;
        ctx.log("event", "resize_step", "step", String.valueOf(step), "wanted", size[0] + "x" + size[1], "target", target.width + "x" + target.height,
            "bridge", bridge.width() + "x" + bridge.height(), "rendererFrame", f == null ? "none" : f.width() + "x" + f.height(),
            "onResizeCalls", String.valueOf(border.resizeCalls), "lastOnResize", border.lastResizeW + "x" + border.lastResizeH);
        if (target.width != size[0] || target.height != size[1] || bridge.width() != size[0] || bridge.height() != size[1]
            || f == null || f.width() != size[0] || f.height() != size[1] || border.lastResizeW != size[0] || border.lastResizeH != size[1]) {
            ctx.fail("resize step " + step + ": sizes disagree (see resize_step line)", null);
            return;
        }
        ctx.shot("size" + step + "_" + size[0] + "x" + size[1] + ".png", () -> {
            if (step + 1 == SIZES.length) {
                ctx.log("event", "resize_done", "onResizeCalls", String.valueOf(border.resizeCalls), "composited", String.valueOf(bridge.compositedFrames()));
                bridge.unregisterRenderer(border);
                ctx.waitFrames(3, () -> {
                    if (!border.removedCalled) {
                        ctx.fail("onRemoved was not called after unregisterRenderer", null);
                    } else {
                        ctx.finish();
                    }
                });
                return;
            }
            int[] next = SIZES[step + 1];
            long window = ctx.minecraft().getWindow().handle();
            if (!SDLVideo.SDL_SetWindowSize(window, next[0], next[1])) {
                ctx.fail("SDL_SetWindowSize failed: " + org.lwjgl.sdl.SDLError.SDL_GetError(), null);
                return;
            }
            waitForSize(ctx, bridge, border, step + 1, 0);
        });
    }

    private void waitForSize(AutotestContext ctx, GlBridge bridge, BorderRenderer border, int step, int waited) {
        int[] size = SIZES[step];
        var target = ctx.minecraft().gameRenderer.mainRenderTarget();
        if (target.width == size[0] && target.height == size[1] && bridge.width() == size[0] && bridge.height() == size[1]) {
            ctx.waitFrames(30, () -> shotAndNext(ctx, bridge, border, step));
        } else if (waited > 600) {
            ctx.log("event", "resize_timeout", "step", String.valueOf(step), "target", target.width + "x" + target.height, "bridge", bridge.width() + "x" + bridge.height());
            ctx.fail("resize step " + step + " timed out", null);
        } else {
            ctx.waitFrames(5, () -> waitForSize(ctx, bridge, border, step, waited + 5));
        }
    }
}
