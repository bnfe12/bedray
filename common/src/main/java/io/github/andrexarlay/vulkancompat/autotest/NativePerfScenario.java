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
    private NativePerfNetherScene nether;
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
        boolean dlss = "dlss".equals(System.getProperty("vkrtx.autotest.nativeUpscaler", "native"));
        FrameGenSettings.setMode(FrameGenSettings.parseMode(System.getProperty("vkrtx.autotest.nativeFramegen", "off")));
        VulkanRtxConfig.applyUpscaler(dlss ? UpscalerBackend.DLSS : UpscalerBackend.BILINEAR, null,
            dlss ? io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode.BALANCED : null, null, null, "native_perf");
        VulkanRtxManager.applyResolutionScalingNow();
        mc.debugEntries.setOverlayVisible(false);
        mc.gui.setScreen(null);
        if ("nether".equals(System.getProperty("vkrtx.autotest.nativeDimension", "overworld"))) {
            nether = new NativePerfNetherScene();
            nether.prepare(ctx, () -> capture(ctx));
            return;
        }
        ctx.log("scenario", name(), "event", "loading", "seconds", "20");
        ScenarioTime.waitSeconds(ctx, 20, () -> capture(ctx));
    }

    private void capture(AutotestContext ctx) {
        try {
        var mc = ctx.minecraft();
        var settings = new FrameCapture.Config(nether != null && nether.streaming() ? 0L : 10_000_000_000L,
            30_000_000_000L, 30_000, 5_000_000_000L);
        var directory = mc.gameDirectory.toPath().resolve("autotest/native_perf/capture-" + UUID.randomUUID());
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
        var metadata = new java.util.LinkedHashMap<String, String>(Map.of(
            "label", System.getProperty("vkrtx.autotest.captureLabel", "unlabelled"),
            "revision_hint", System.getProperty("vkrtx.autotest.revisionHint", "unverified"),
            "render_distance", String.valueOf(mc.options.renderDistance().get()),
            "vsync", String.valueOf(mc.options.enableVsync().get()),
            "frame_limit", String.valueOf(mc.options.framerateLimit().get()),
            "scenario", name(),
            "gpu", device.name(), "driver", device.driverInfo(), "gpu_backend", device.backendName(),
            "adaptive_budget_ms", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.adaptiveBudgetMs())));
        metadata.put("dimension", mc.player.level().dimension().identifier().toString());
        metadata.put("motion", nether == null ? "caller" : nether.motion());
        metadata.put("path_settings", io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.pathSummary());
        metadata.put("light_budget", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.lightBudget()));
        metadata.put("framegen_requested", FrameGenSettings.mode().name());
        if (nether != null) nether.startMotion(ctx);
        metadata.put("render_distance", String.valueOf(mc.options.renderDistance().get()));
        var future = FrameBenchmark.start(directory, settings, metadata);
        ctx.log("scenario", name(), "event", "capture_armed", "directory", directory.toString());
        ScenarioTime.waitUntil(ctx, future::isDone, 100, () -> saved(ctx, future), () -> {
            FrameBenchmark.abort("scenario_timeout_waiting_for_world_or_capture");
            ScenarioTime.waitUntil(ctx, future::isDone, 10, () -> saved(ctx, future),
                () -> fail(ctx, "native_perf persistence timed out: " + directory, null));
        });
        } catch (RuntimeException failure) { fail(ctx, "native_perf capture setup failed", failure); }
    }

    private void saved(AutotestContext ctx, CompletableFuture<FrameBenchmark.Result> future) {
        if (nether != null && !nether.validateAndStop(ctx)) {
            fail(ctx, "native_perf: obstructed view, dimension change or corrected camera; no FPS acceptance", null);
            return;
        }
        try {
            var saved = future.join(); // waitUntil already proved completion; no render-thread I/O wait.
            ctx.log("scenario", name(), "event", "capture_saved", "directory", saved.directory().toString(),
                "frames", String.valueOf(saved.frames()), "reason", saved.reason(), "fps_acceptance", "offline_required");
            if (!saved.complete()) fail(ctx, "incomplete native_perf capture: " + saved.reason(), null);
            else if (nether != null) {
                var shotSaved = new java.util.concurrent.atomic.AtomicBoolean();
                long deadline = System.nanoTime() + 25_000_000_000L;
                io.github.andrexarlay.vulkanbridge.platform.Platforms.get().registerPostFrameHook(
                    io.github.andrexarlay.vulkanbridge.platform.HookPriority.LOW, () -> {
                        if (shotSaved.get()) return;
                        if (!ctx.minecraft().isRunning()) {
                            if (shotSaved.compareAndSet(false, true)) nether.cleanup(ctx);
                        } else if (System.nanoTime() > deadline && shotSaved.compareAndSet(false, true)) {
                            fail(ctx, "native_perf final screenshot timed out", null);
                        }
                    });
                // Do not call waitFrames here: it would replace the harness's SHOT_WAIT phase.
                ctx.shot("natural_nether_" + nether.motion() + ".png", () -> {
                    if (shotSaved.compareAndSet(false, true)) { nether.cleanup(ctx); ctx.finish(); }
                });
            }
            else ctx.finish();
        } catch (RuntimeException failure) {
            fail(ctx, "native_perf capture write failed", failure);
        }
    }

    private void fail(AutotestContext ctx, String message, Throwable failure) {
        if (nether != null) nether.cleanup(ctx);
        ctx.fail(message, failure);
    }
}
