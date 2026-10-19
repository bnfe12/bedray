package io.github.andrexarlay.vulkanbridge.glbridge;

import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import org.lwjgl.sdl.SDLVideo;

/**
 * Autotest scenario {@code glbridge_perf}: overlay cost at 1280x720 and 1920x1080 (TrianglePainter). Windows of
 * {@link GlbPerf#WINDOW} frames with the overlay hidden (baseline), visible with latency SAME_FRAME and visible with PIPELINED_N_MINUS_1; after every switch one perf window is skipped
 * (it mixes both states) and the next one is logged as {@code event=perf_phase}. Screenshot {@code perf_1080_visible.png}.
 */
public final class GlBridgePerfScenario implements AutotestScenario {
    @Override
    public String name() {
        return "glbridge_perf";
    }

    @Override
    public void run(AutotestContext ctx) {
        GlBridge bridge = VulkanBridge.glBridge();
        if (bridge.state() != GlBridge.State.READY) {
            ctx.fail("GL bridge is not READY: " + bridge.state() + " " + bridge.failure(), null);
            return;
        }
        TrianglePainter painter = new TrianglePainter(bridge);
        GlRendererOptions same = GlRendererOptions.DEFAULTS;
        GlRendererOptions n1 = GlRendererOptions.DEFAULTS.withLatency(GlRendererOptions.Latency.PIPELINED_N_MINUS_1);
        bridge.registerRenderer(painter, same.withVisible(false));
        phase(ctx, "720_hidden", () -> {
            bridge.setOptions(painter, same);
            phase(ctx, "720_visible", () -> {
                bridge.setOptions(painter, n1);
                phase(ctx, "720_visible_n1", () -> {
                    bridge.setOptions(painter, same);
                    if (!SDLVideo.SDL_SetWindowSize(ctx.minecraft().getWindow().handle(), 1920, 1080)) {
                        ctx.fail("SDL_SetWindowSize failed: " + org.lwjgl.sdl.SDLError.SDL_GetError(), null);
                        return;
                    }
                    phase(ctx, "1080_visible", () -> ctx.shot("perf_1080_visible.png", () -> {
                        bridge.setOptions(painter, n1);
                        phase(ctx, "1080_visible_n1", () -> ctx.shot("perf_1080_visible_n1.png", () -> {
                            bridge.setOptions(painter, same.withVisible(false));
                            phase(ctx, "1080_hidden", () -> {
                                ctx.log("event", "perf_done", "bridgeSize", bridge.width() + "x" + bridge.height());
                                if (bridge.width() != 1920 || bridge.height() != 1080) {
                                    ctx.fail("bridge did not reach 1920x1080", null);
                                } else {
                                    ctx.finish();
                                }
                            });
                        }));
                    }));
                });
            });
        });
    }

    /** Skips the current (mixed) perf window, logs the next complete one, then runs {@code then}. */
    private void phase(AutotestContext ctx, String name, Runnable then) {
        String start = GlBridgeImpl.INSTANCE.lastPerfLine();
        waitPerfChange(ctx, start, 0, skipped -> waitPerfChange(ctx, skipped, 0, line -> {
            ctx.log("event", "perf_phase", "name", name, "line", line);
            then.run();
        }));
    }

    private void waitPerfChange(AutotestContext ctx, String from, int waited, java.util.function.Consumer<String> then) {
        String now = GlBridgeImpl.INSTANCE.lastPerfLine();
        if (!now.equals(from)) {
            then.accept(now);
        } else if (waited > 3 * GlbPerf.WINDOW) {
            ctx.fail("no perf line within " + waited + " frames", null);
        } else {
            ctx.waitFrames(10, () -> waitPerfChange(ctx, from, waited + 10, then));
        }
    }
}
