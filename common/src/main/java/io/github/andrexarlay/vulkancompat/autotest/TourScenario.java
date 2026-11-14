package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.Random;

/**
 * {@code rt_tour}: ten screenshots of the traced frame in different biomes, times of day and weather, without the HUD (picture
 * quality review). For every stop the biome is searched with {@code ServerLevel.findClosestBiome3d} around the start, the player
 * is teleported to the surface there and looks in a seeded random direction. The render settings of the instance are kept (judge
 * the picture only with DLSS RR or NRD on). Seed: {@code -Dvkrtx.autotest.tourSeed=<long>} (default 20261114). Shots
 * {@code NN_<biome>_t<time>_<weather>.png}; log lines {@code [VKB-AUTOTEST] scenario=rt_tour ...}.
 */
public final class TourScenario implements AutotestScenario {
    public static final String SEED_PROPERTY = "vkrtx.autotest.tourSeed";
    /** {@code -Dvkrtx.autotest.tourLate=true}: after every shot wait {@link #LATE_SECONDS} and take the same view again ({@code *_late.png}). */
    private static final boolean LATE = Boolean.getBoolean("vkrtx.autotest.tourLate");
    private static final double LATE_SECONDS = 12.0;

    /** biome, time of day (ticks), weather, pitch (degrees, positive looks down), idea of the shot. */
    private record Stop(ResourceKey<Biome> biome, int time, String weather, float pitch, String idea) { }

    private static final Stop[] STOPS = {
        new Stop(Biomes.PLAINS, 1500, "clear", 4f, "morning_horizon"),
        new Stop(Biomes.DESERT, 12400, "clear", 2f, "sunset_dunes"),
        new Stop(Biomes.DARK_FOREST, 13200, "clear", 6f, "dusk_canopy"),
        new Stop(Biomes.JUNGLE, 7000, "rain", 3f, "rain_fog"),
        new Stop(Biomes.SNOWY_PLAINS, 18000, "clear", 5f, "midnight_snow"),
        new Stop(Biomes.SAVANNA, 23200, "clear", 1f, "sunrise_backlight"),
        new Stop(Biomes.JAGGED_PEAKS, 6000, "clear", -6f, "noon_peaks_distance"),
        new Stop(Biomes.SWAMP, 12800, "thunder", 4f, "storm_water"),
        new Stop(Biomes.CHERRY_GROVE, 9500, "clear", 8f, "afternoon_foliage"),
        new Stop(Biomes.BADLANDS, 11000, "clear", 3f, "late_day_terrain"),
    };

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private BlockPos origin;

    @Override
    public String name() {
        return "rt_tour";
    }

    @Override
    public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_tour: no singleplayer server / player", null);
            return;
        }
        origin = mc.player.blockPosition();
        Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> {
            var p = ctx.minecraft().player;
            if (aimOverride && p != null) {
                p.setYRot(aimYaw); p.yRotO = aimYaw; p.setXRot(aimPitch); p.xRotO = aimPitch;
            }
        });
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        Random random = new Random(Long.getLong(SEED_PROPERTY, 20261114L));
        server.execute(() -> {
            for (String c : new String[] {"gamerule send_command_feedback false", "gamerule advance_time false", "gamerule advance_weather false",
                "difficulty peaceful", "gamemode creative @a", "kill @e[type=!player]"}) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            }
        });
        ctx.waitFrames(60, () -> stop(ctx, random, 0, 0));
    }

    private void stop(AutotestContext ctx, Random random, int index, int found) {
        if (index >= STOPS.length) {
            ctx.log("scenario", name(), "event", "done", "stops", String.valueOf(STOPS.length), "shots", String.valueOf(found));
            ctx.finish();
            return;
        }
        Stop s = STOPS[index];
        float yaw = random.nextInt(360) - 180f;
        var server = ctx.minecraft().getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var hit = level.findClosestBiome3d(h -> h.is(s.biome()), origin, 6000, 32, 64);
            if (hit == null) {
                ctx.log("scenario", name(), "stop", String.valueOf(index), "event", "biome_not_found", "biome", s.biome().identifier().getPath());
                ctx.minecraft().execute(() -> stop(ctx, random, index + 1, found));
                return;
            }
            BlockPos at = hit.getFirst();
            // getHeight of a chunk that is not generated yet answers the minimum height: generate it first (blocking, server thread)
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ());
            String actual = level.getBiome(new BlockPos(at.getX(), y, at.getZ())).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
            String[] cmds = {
                "time set " + s.time(), "weather " + s.weather(),
                // spectator, 5 blocks above the ground: a free camera that is not stopped by trees, cacti or terrain
                "gamemode spectator @a",
                String.format(Locale.ROOT, "tp @a %.1f %d %.1f %.1f %.1f", at.getX() + 0.5, y + 5, at.getZ() + 0.5, yaw, s.pitch()),
            };
            for (String c : cmds) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            }
            aimYaw = yaw;
            aimPitch = s.pitch();
            aimOverride = true;
            ctx.minecraft().execute(() -> {
                ctx.log("scenario", name(), "stop", String.valueOf(index), "event", "teleported", "biome", actual, "x", String.valueOf(at.getX()),
                    "y", String.valueOf(y), "z", String.valueOf(at.getZ()), "time", String.valueOf(s.time()), "weather", s.weather(),
                    "idea", s.idea());
                // chunks are generated and the RT scene is built after the teleport: wall time, the frame rate varies
                ScenarioTime.waitSeconds(ctx, 16.0, () -> ctx.waitFrames(90, () -> {
                    String file = "%02d_%s_t%d_%s.png".formatted(index, actual, s.time(), s.weather());
                    ctx.log("scenario", name(), "stop", String.valueOf(index), "event", "shoot", "pass", String.valueOf(RtFrameRenderer.lastPassMode()));
                    ctx.shot(file, () -> {
                        if (!LATE) {
                            stop(ctx, random, index + 1, found + 1);
                            return;
                        }
                        // second shot of the same view after more time: shows what is still being built or faded in
                        ScenarioTime.waitSeconds(ctx, LATE_SECONDS, () -> ctx.shot(file.replace(".png", "_late.png"),
                            () -> stop(ctx, random, index + 1, found + 1)));
                    });
                }));
            });
        });
    }
}
