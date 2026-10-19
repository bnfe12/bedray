package io.github.andrexarlay.vulkanbridge.autotest;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code build_test_world}: creates a SUPERFLAT creative world (default name "RTX Test World"), copies the datapack
 * directory {@code -Dvkbridge.autotest.datapack=<dir>} into {@code <world>/datapacks/rtxtest} BEFORE the world is created
 * (a fresh world enables file/ packs; /reload is not used because it also enables experimental feature packs), then
 * {@code function <ns>:build}, and takes one shot per zone by
 * running {@code function <ns>:tp_<zone>} as the player. The world is saved by the normal game stop.
 * <p>
 * An existing world with the same name is deleted only with {@code -Dvkbridge.autotest.overwrite=true}; otherwise FAIL.
 * Properties: {@code .worldName}, {@code .datapack} (required), {@code .namespace} (default rtxtest),
 * {@code .zones} (csv of tp function suffixes, default room,night,water,mirror,shadows,corridor,overview).
 * <p>
 * API (javap of libs/minecraft-26.3-client.jar, 2026-10-12): WorldPresets.FLAT, Registries.WORLD_PRESET,
 * HolderLookup.Provider.lookupOrThrow, HolderGetter.getOrThrow(ResourceKey) -> Holder.Reference, WorldPreset.createWorldDimensions(),
 * WorldOpenFlows.createFreshLevel(String, LevelSettings, WorldOptions, Function&lt;HolderLookup.Provider, WorldDimensions&gt;, Screen),
 * MinecraftServer.getWorldPath(LevelResource), LevelResource.DATAPACK_DIR. Command feedback of the server source goes to latest.log.
 */
public final class TestWorldScenario implements AutotestScenario {
    private final String worldName = System.getProperty("vkbridge.autotest.worldName", "RTX Test World");
    private final String datapack = System.getProperty("vkbridge.autotest.datapack", "");
    private final String ns = System.getProperty("vkbridge.autotest.namespace", "rtxtest");
    private final boolean overwrite = Boolean.getBoolean("vkbridge.autotest.overwrite");
    private final List<String> zones = List.of(System.getProperty("vkbridge.autotest.zones",
            "room,night,water,mirror,shadows,corridor,overview").split(","));

    private boolean waitingForWorld;

    @Override
    public String name() {
        return "build_test_world";
    }

    @Override
    public boolean needsWorld() {
        return false;
    }

    @Override
    public void run(AutotestContext ctx) {
        Path src = datapack.isBlank() ? null : Paths.get(datapack);
        if (src == null || !Files.isRegularFile(src.resolve("pack.mcmeta"))) {
            ctx.fail("build_test_world: -Dvkbridge.autotest.datapack must point at a directory with pack.mcmeta, got '" + datapack + "'", null);
            return;
        }
        Minecraft mc = ctx.minecraft();
        mc.schedule(() -> {
            try {
                LevelStorageSource source = mc.getLevelSource();
                if (source.levelExists(worldName)) {
                    if (!overwrite) {
                        ctx.fail("build_test_world: world '" + worldName + "' exists (set -Dvkbridge.autotest.overwrite=true)", null);
                        return;
                    }
                    try (var access = source.createAccess(worldName)) {
                        access.deleteLevel();
                    }
                    ctx.log("event", "old_world_deleted", "world", worldName);
                }
                // The pack goes into the world folder BEFORE creation: a fresh world enables world (file/) packs
                // automatically. A later /reload would also enable every experimental feature pack (seen in a run:
                // minecart_improvements, redstone_experiments, trade_rebalance), so /reload is not used.
                Path dst = source.getBaseDir().resolve(worldName).resolve(LevelResource.DATAPACK_DIR.id()).resolve(ns);
                copyTree(src, dst);
                ctx.log("event", "datapack_copied", "to", dst.toString());
                LevelSettings settings = new LevelSettings(worldName, GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
                WorldOptions options = new WorldOptions(20261012L, false, false);
                ctx.log("event", "creating_world", "world", worldName, "preset", "flat");
                waitingForWorld = true;
                mc.createWorldOpenFlows().createFreshLevel(worldName, settings, options,
                        p -> p.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        mc.gui.screen());
            } catch (Throwable t) {
                ctx.fail("build_test_world: world creation failed: " + t, t);
            }
        });
    }

    @Override
    public void onFrame(AutotestContext ctx) {
        Minecraft mc = ctx.minecraft();
        if (!waitingForWorld || mc.level == null || mc.player == null || mc.gui.screen() != null) {
            return;
        }
        waitingForWorld = false;
        var server = mc.getSingleplayerServer();
        if (server == null) {
            ctx.fail("build_test_world: no singleplayer server", null);
            return;
        }
        ctx.waitFrames(80, () -> {
            server.execute(() -> {
                cmd(server, "datapack list enabled");
                cmd(server, "datapack list available");
                cmd(server, "function " + ns + ":build");
            });
            ctx.waitFrames(200, () -> shootZone(ctx, server, 0));
        });
    }

    private void shootZone(AutotestContext ctx, net.minecraft.server.MinecraftServer server, int i) {
        if (i >= zones.size()) {
            server.execute(() -> {
                cmd(server, "function " + ns + ":day");
                cmd(server, "execute as @a at @s run function " + ns + ":tp_overview");
            });
            ctx.waitFrames(60, ctx::finish);
            return;
        }
        String zone = zones.get(i).trim();
        server.execute(() -> cmd(server, "execute as @a at @s run function " + ns + ":tp_" + zone));
        // chunks around the new position must be meshed before the shot
        ctx.waitFrames(180, () -> {
            ctx.log("event", "zone_shot", "zone", zone, "terrainComplete",
                    String.valueOf(ctx.minecraft().levelRenderer.hasRenderedAllSections()));
            ctx.shot(String.format("%02d_%s.png", i + 1, zone), () -> shootZone(ctx, server, i + 1));
        });
    }

    private static void cmd(net.minecraft.server.MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
