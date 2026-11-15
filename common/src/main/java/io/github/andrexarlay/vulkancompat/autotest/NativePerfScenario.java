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
        // Chunk streaming and Sodium re-meshing keep running for a minute or more after the world appears (the section registry's
        // replaced counter was still rising at the end of a 30 s capture), so the default 20 s measures a moving scene.
        int loadSeconds = Integer.getInteger("vkrtx.autotest.loadSeconds", 20);
        String dimension = System.getProperty("vkrtx.autotest.perfDimension", "overworld").toLowerCase(java.util.Locale.ROOT);
        perfDimension = dimension;
        if (dimension.equals("overworld")) {
            ctx.log("scenario", name(), "event", "loading", "seconds", String.valueOf(loadSeconds), "dimension", dimension);
            ScenarioTime.waitSeconds(ctx, loadSeconds, () -> capture(ctx));
            return;
        }
        if (!dimension.equals("nether") && !dimension.equals("end")) {
            ctx.fail("native_perf: unknown vkrtx.autotest.perfDimension " + dimension + " (overworld|nether|end)", null);
            return;
        }
        enterDimension(ctx, dimension, loadSeconds);
    }

    private String perfDimension = "overworld";
    private String perfPose = "";

    /**
     * Moves the player into another dimension for the capture: spectator mode (no gravity, the camera holds still), a first
     * teleport so the server generates the chunks around the origin, then {@code findOpenPose} picks an open-air camera pose
     * from the loaded blocks (a natural Nether or End has no known clear spot at a fixed coordinate). The pose is logged and
     * recorded in the manifest, so the dimension runs are comparable between builds on the same world seed.
     */
    private void enterDimension(AutotestContext ctx, String dimension, int loadSeconds) {
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("native_perf: dimension " + dimension + " needs a singleplayer server and a player", null);
            return;
        }
        String id = dimension.equals("nether") ? "minecraft:the_nether" : "minecraft:the_end";
        int startY = dimension.equals("nether") ? 70 : 90;
        server.execute(() -> {
            var source = server.createCommandSourceStack();
            for (String c : new String[] {"gamerule send_command_feedback false", "gamerule advance_time false",
                "gamerule random_tick_speed 0", "weather clear", "gamemode spectator @a",
                "execute in " + id + " run tp @a 0 " + startY + " 0 0 5"}) {
                server.getCommands().performPrefixedCommand(source, c);
            }
        });
        ctx.log("scenario", name(), "event", "dimension_entered", "dimension", dimension, "start_y", String.valueOf(startY));
        // Chunks (and for the End the dragon island) generate around the player; the camera pose is chosen from loaded blocks.
        ScenarioTime.waitSeconds(ctx, 25, () -> server.execute(() -> {
            try {
                var level = java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .filter(l -> l.dimension().identifier().toString().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("dimension unavailable: " + id));
                int[] pose = findOpenPose(level, dimension.equals("nether") ? 36 : 60, dimension.equals("nether") ? 100 : 110);
                String cmd = String.format(java.util.Locale.ROOT, "execute in %s run tp @a %d %d %d 0 5", id, pose[0], pose[1], pose[2]);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                mc.execute(() -> {
                    ctx.log("scenario", name(), "event", "camera_pose", "dimension", dimension,
                        "x", String.valueOf(pose[0]), "y", String.valueOf(pose[1]), "z", String.valueOf(pose[2]),
                        "open_air_pct", String.valueOf(pose[3]));
                    perfPose = pose[0] + "," + pose[1] + "," + pose[2];
                    ctx.log("scenario", name(), "event", "loading", "seconds", String.valueOf(loadSeconds), "dimension", dimension);
                    ScenarioTime.waitSeconds(ctx, loadSeconds, () -> capture(ctx));
                });
            } catch (Throwable t) {
                mc.execute(() -> ctx.fail("native_perf: could not place the camera in " + dimension + ": " + t, t));
            }
        }));
    }

    /**
     * Scans a spiral around the origin for the loaded column whose 13 x 7 x 13 box around a candidate eye position holds the most
     * air (>= 80 % required) with solid ground below, y from {@code maxY} down to {@code minY} in steps of 4.
     * Returns {x, y, z, open_air_percent}.
     */
    private static int[] findOpenPose(net.minecraft.server.level.ServerLevel level, int minY, int maxY) {
        int[] best = null;
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int ring = 0; ring <= 6; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = dx * 8, z = dz * 8;
                    if (!level.hasChunkAt(pos.set(x, minY, z))) continue;
                    for (int y = maxY; y >= minY; y -= 4) {
                        int air = 0, total = 0;
                        for (int ox = -6; ox <= 6; ox += 2) {
                            for (int oy = -3; oy <= 3; oy++) {
                                for (int oz = -6; oz <= 6; oz += 2) {
                                    pos.set(x + ox, y + oy, z + oz);
                                    total++;
                                    if (level.hasChunkAt(pos) && level.getBlockState(pos).isAir()) air++;
                                }
                            }
                        }
                        int pct = air * 100 / Math.max(total, 1);
                        boolean ground = false;
                        for (int oy = 4; oy <= 40 && !ground; oy++) {
                            pos.set(x, y - oy, z);
                            ground = level.hasChunkAt(pos) && !level.getBlockState(pos).isAir();
                        }
                        if (pct >= 80 && ground && (best == null || pct > best[3])) {
                            best = new int[] {x, y, z, pct};
                        }
                    }
                }
            }
            if (best != null) break;
        }
        if (best == null) throw new IllegalStateException("no open-air pose found near the origin");
        return best;
    }

    private void capture(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var settings = new FrameCapture.Config(10_000_000_000L, 30_000_000_000L, 30_000, 5_000_000_000L);
        var directory = mc.gameDirectory.toPath().resolve("autotest/native_perf/capture-" + UUID.randomUUID());
        var device = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
        var limiter = mc.getFramerateLimitTracker();
        var future = FrameBenchmark.start(directory, settings, Map.ofEntries(
            Map.entry("label", System.getProperty("vkrtx.autotest.captureLabel", "unlabelled")),
            Map.entry("revision_hint", System.getProperty("vkrtx.autotest.revisionHint", "unverified")),
            Map.entry("render_distance", String.valueOf(mc.options.renderDistance().get())),
            Map.entry("vsync", String.valueOf(mc.options.enableVsync().get())),
            Map.entry("frame_limit", String.valueOf(mc.options.framerateLimit().get())),
            // What the vanilla limiter applies right now (30 would mean the AFK throttle caps the capture).
            Map.entry("effective_frame_limit", String.valueOf(limiter.getFramerateLimit())),
            Map.entry("throttle_reason", String.valueOf(limiter.getThrottleReason())),
            Map.entry("inactivity_fps_limit", String.valueOf(mc.options.inactivityFpsLimit().get())),
            // true: a background bench waived the FOCUSED requirement (window kept behind the user's windows).
            Map.entry("focus_waived", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.debug.FrameBenchmark.FOCUS_WAIVED)),
            Map.entry("dimension", perfDimension),
            Map.entry("camera_pose", perfPose.isEmpty() ? "default" : perfPose),
            Map.entry("scenario", name()),
            Map.entry("gpu", device.name()), Map.entry("driver", device.driverInfo()),
            Map.entry("gpu_backend", device.backendName()),
            Map.entry("adaptive_budget_ms", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.adaptiveBudgetMs()))));
        ctx.log("scenario", name(), "event", "capture_armed", "directory", directory.toString(),
            "effective_frame_limit", String.valueOf(mc.getFramerateLimitTracker().getFramerateLimit()),
            "throttle_reason", String.valueOf(mc.getFramerateLimitTracker().getThrottleReason()),
            "focus_waived", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.debug.FrameBenchmark.FOCUS_WAIVED));
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
