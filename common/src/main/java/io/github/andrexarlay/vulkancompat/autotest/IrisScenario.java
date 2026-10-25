package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge;

/**
 * {@code iris}: live evidence for the iris shader host. Logs the host selection, the post runtime status and every
 * shader-host line of the F3 diagnostics, then takes one screenshot per time of day (morning, noon, sunset, night) with
 * the selected pack. Run with {@code -Dvkrtx.shader_host=iris -Dbedray.iris.post=true -Dvkrtx.shaderhost.pack=<pack>};
 * the screenshots show what the pack actually produced, and the log says why when the host stayed native.
 * Times are overridable with {@code -Dvkbridge.autotest.irisTimes=1000,6000,12500,18000}.
 */
public final class IrisScenario implements AutotestScenario {
    private static final String[] DEFAULT_TIMES = {"1000", "6000", "12500", "18000"};
    private static final String[] LABELS = {"morning", "noon", "sunset", "night"};

    @Override
    public String name() {
        return "iris";
    }

    @Override
    public void run(AutotestContext ctx) {
        String configured = System.getProperty("vkbridge.autotest.irisTimes");
        String[] times = configured == null || configured.isBlank() ? DEFAULT_TIMES : configured.split(",");
        logState(ctx, "start");
        step(ctx, times, 0);
    }

    private void step(AutotestContext ctx, String[] times, int index) {
        if (index >= times.length) {
            logState(ctx, "end");
            ctx.finish();
            return;
        }
        String time = times[index].trim();
        String label = index < LABELS.length ? LABELS[index] : "t" + time;
        var server = ctx.minecraft().getSingleplayerServer();
        if (server == null) {
            ctx.fail("iris: no singleplayer server", null);
            return;
        }
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set " + time);
            } catch (Throwable t) {
                ctx.fail("iris: time command failed: " + t, t);
            }
        });
        // Several frames so post history, exposure and denoiser accumulation settle on the new lighting.
        ctx.waitFrames(90, () -> {
            logState(ctx, label);
            String shot = String.format(java.util.Locale.ROOT, "%02d_%s.png", index + 1, label);
            ctx.shot(shot, () -> step(ctx, times, index + 1));
        });
    }

    private void logState(AutotestContext ctx, String phase) {
        var selection = ShaderHosts.selection();
        if (selection == null) {
            ctx.log("scenario", name(), "phase", phase, "selection", "none");
        } else {
            ctx.log("scenario", name(), "phase", phase,
                "requested", String.valueOf(selection.requested()),
                "active", String.valueOf(selection.active()),
                "requested_state", String.valueOf(selection.requestedStatus()));
        }
        ctx.log("scenario", name(), "phase", phase, "post_runtime", IrisRenderBridge.runtimeStatus());
        for (String line : DebugInfo.lines()) {
            String plain = DebugText.plain(line);
            if (plain.startsWith("[SHADER-HOST]") || plain.startsWith("[IRIS") || plain.startsWith("[DIRTRT")
                || plain.startsWith("[CUSTOM-RT")) {
                ctx.log("scenario", name(), "phase", phase, "f3", plain);
            }
        }
    }
}
