package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.FrameBenchmark;
import io.github.andrexarlay.vulkancompat.rtx.debug.benchmark.FrameCapture;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Produces evidence; PASS means capture saved, never that the GPU met the FPS target. */
public final class NativePerfScenario implements AutotestScenario {
    @Override public String name() { return "native_perf"; }

    @Override public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        if (mc.getWindow().getWidth() != 1920 || mc.getWindow().getHeight() != 1080) {
            ctx.fail("native_perf requires an actual 1920x1080 framebuffer; got "
                + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight(), null);
            return;
        }
        // Session settings only. Quality, world and camera remain explicit caller choices.
        VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
        VulkanRtxConfig.enableHardwareRayTracing = true;
        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
        FrameGenSettings.setMode(FrameGenSettings.Mode.OFF);
        VulkanRtxConfig.applyUpscaler(UpscalerBackend.BILINEAR, null, null, null, null, "native_perf");
        VulkanRtxManager.applyResolutionScalingNow();
        mc.debugEntries.setOverlayVisible(false);
        mc.gui.setScreen(null);
        ctx.log("scenario", name(), "event", "loading", "seconds", "20");
        ScenarioTime.waitSeconds(ctx, 20, () -> capture(ctx));
    }

    private void capture(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var settings = new FrameCapture.Config(10_000_000_000L, 30_000_000_000L, 30_000, 5_000_000_000L);
        var directory = mc.gameDirectory.toPath().resolve("autotest/native_perf/capture-" + UUID.randomUUID());
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
        var future = FrameBenchmark.start(directory, settings, Map.of(
            "label", System.getProperty("vkrtx.autotest.captureLabel", "unlabelled"),
            "revision_hint", System.getProperty("vkrtx.autotest.revisionHint", "unverified"),
            "render_distance", String.valueOf(mc.options.renderDistance().get()),
            "vsync", String.valueOf(mc.options.enableVsync().get()),
            "frame_limit", String.valueOf(mc.options.framerateLimit().get()),
            "scenario", name(),
            "gpu", device.name(), "driver", device.driverInfo(), "gpu_backend", device.backendName(),
            "adaptive_budget_ms", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.adaptiveBudgetMs())));
        ctx.log("scenario", name(), "event", "capture_armed", "directory", directory.toString());
        ScenarioTime.waitUntil(ctx, future::isDone, 100, () -> saved(ctx, future), () -> {
            FrameBenchmark.abort("scenario_timeout_waiting_for_world_or_capture");
            ScenarioTime.waitUntil(ctx, future::isDone, 10, () -> saved(ctx, future),
                () -> ctx.fail("native_perf persistence timed out: " + directory, null));
        });
    }

    private void saved(AutotestContext ctx, CompletableFuture<FrameBenchmark.Result> future) {
        try {
            var saved = future.join(); // waitUntil already proved completion; no render-thread I/O wait.
            ctx.log("scenario", name(), "event", "capture_saved", "directory", saved.directory().toString(),
                "frames", String.valueOf(saved.frames()), "reason", saved.reason(), "fps_acceptance", "offline_required");
            if (!saved.complete()) ctx.fail("incomplete native_perf capture: " + saved.reason(), null);
            else ctx.finish();
        } catch (RuntimeException failure) {
            ctx.fail("native_perf capture write failed", failure);
        }
    }
}
