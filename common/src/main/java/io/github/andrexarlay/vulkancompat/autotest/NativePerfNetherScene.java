package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

/** Natural terrain benchmark: no fill commands, lamps, reference rooms or edits to the terrain. */
final class NativePerfNetherScene {
    private record Camera(double x, double y, double z, float yaw, int score) { }
    private final String motion = System.getProperty("vkrtx.autotest.nativeMotion", "still");
    private Camera camera;
    private long motionStart;
    private boolean moving;
    private int originalDistance = -1;
    private long frames, invalidFrames, corrections;

    void prepare(AutotestContext ctx, Runnable ready) {
        if (!motion.equals("still") && !motion.equals("turn") && !motion.equals("stream")) {
            ctx.fail("native_perf: nativeMotion must be still, turn or stream", null);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null) { ctx.fail("native_perf: Nether requires a singleplayer server", null); return; }
        originalDistance = mc.options.renderDistance().get();
        if (streaming()) {
            mc.options.renderDistance().set(4);
            mc.options.broadcastOptions();
        }
        server.execute(() -> {
            var commands = server.getCommands();
            var source = server.createCommandSourceStack();
            commands.performPrefixedCommand(source, "gamemode spectator @a");
            commands.performPrefixedCommand(source, "difficulty peaceful");
            commands.performPrefixedCommand(source, "execute in minecraft:the_nether run tp @a 0 64 0");
            mc.execute(() -> ScenarioTime.waitSeconds(ctx, 20, () -> locate(ctx, ready)));
        });
    }

    private void locate(AutotestContext ctx, Runnable ready) {
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(), false)
                    .filter(candidate -> candidate.dimension().identifier().toString().equals("minecraft:the_nether"))
                    .findFirst().orElseThrow();
                Camera best = null;
                // Only inspect terrain already loaded around the spectator. Unloaded air cannot win the search.
                for (int x = -32; x <= 32; x += 16) for (int z = -32; z <= 32; z += 16) {
                    for (int y = 48; y <= 80; y += 16) {
                        boolean clear = true;
                        for (int dy = 0; dy <= 2; dy++) {
                            var p = new BlockPos(x, y + dy, z);
                            clear &= level.hasChunkAt(p) && level.getBlockState(p).isAir();
                        }
                        if (!clear) continue;
                        for (int a = 0; a < 8; a++) {
                            float yaw = a * 45f;
                            double dx = -Math.sin(Math.toRadians(yaw)), dz = Math.cos(Math.toRadians(yaw));
                            int score = 0;
                            boolean lava = false;
                            for (int d = 4; d <= 64; d += 4) {
                                var p = BlockPos.containing(x + 0.5 + dx * d, y + 1.6 - d * 0.30, z + 0.5 + dz * d);
                                if (!level.hasChunkAt(p)) break;
                                var state = level.getBlockState(p);
                                if (state.getFluidState().is(FluidTags.LAVA)) { lava = true; break; }
                                if (!state.isAir()) break;
                                score++;
                            }
                            if (lava && score >= 3 && (best == null || score > best.score))
                                best = new Camera(x + 0.5, y, z + 0.5, yaw, score);
                        }
                    }
                }
                if (best == null) throw new IllegalStateException("no loaded natural lava vista; no benchmark accepted");
                camera = best;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    String.format(java.util.Locale.ROOT, "execute in minecraft:the_nether run tp @a %.2f %.2f %.2f %.1f 17",
                        camera.x, camera.y, camera.z, camera.yaw));
                mc.execute(() -> {
                    ctx.log("scenario", "native_perf", "event", "natural_nether_camera", "position",
                        camera.x + "," + camera.y + "," + camera.z, "yaw", String.valueOf(camera.yaw),
                        "open_ray_samples", String.valueOf(camera.score), "motion", motion);
                    ScenarioTime.waitSeconds(ctx, 10, ready);
                });
            } catch (RuntimeException error) {
                mc.execute(() -> { cleanup(ctx); ctx.fail("native_perf: natural Nether setup failed", error); });
            }
        });
    }

    void startMotion(AutotestContext ctx) {
        if (streaming()) {
            // Increase demand at a validated fixed camera instead of flying a spectator through unknown walls.
            ctx.minecraft().options.renderDistance().set(16);
            ctx.minecraft().options.broadcastOptions();
            ctx.log("scenario", "native_perf", "event", "stream_distance_change", "from", "4", "to", "16");
        }
        motionStart = System.nanoTime();
        moving = true;
        Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> {
            if (!moving) return;
            frames++;
            var player = ctx.minecraft().player;
            if (player == null || camera == null) { invalidFrames++; return; }
            double seconds = (System.nanoTime() - motionStart) / 1.0e9;
            float yaw = camera.yaw + (motion.equals("turn") ? (float) (30 * Math.sin(seconds * 0.5)) : 0f);
            double x = camera.x, z = camera.z;
            if (Math.abs(player.getX() - x) + Math.abs(player.getY() - camera.y) + Math.abs(player.getZ() - z) > 2)
                corrections++;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.setPos(x, camera.y, z);
            player.xo = player.xOld = x;
            player.yo = player.yOld = camera.y;
            player.zo = player.zOld = z;
            player.setYRot(yaw); player.yRotO = yaw;
            player.setXRot(17f); player.xRotO = 17f;
            var level = ctx.minecraft().level;
            var eye = BlockPos.containing(player.getEyePosition());
            if (level == null || !level.dimension().identifier().toString().equals("minecraft:the_nether")
                || !level.hasChunkAt(eye) || !level.getBlockState(eye).isAir()) invalidFrames++;
        });
    }

    boolean validateAndStop(AutotestContext ctx) {
        moving = false;
        ctx.log("scenario", "native_perf", "event", "camera_validation", "motion_frames", String.valueOf(frames),
            "invalid_view_frames", String.valueOf(invalidFrames), "camera_corrections", String.valueOf(corrections));
        return frames > 0 && invalidFrames == 0 && corrections == 0;
    }
    void cleanup(AutotestContext ctx) {
        moving = false;
        if (originalDistance < 0) return;
        int restore = originalDistance;
        originalDistance = -1;
        ctx.minecraft().options.renderDistance().set(restore);
        ctx.minecraft().options.broadcastOptions();
    }
    String motion() { return motion; }
    boolean streaming() { return motion.equals("stream"); }
}
