package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import io.github.andrexarlay.vulkancompat.sodium.SettingsCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * {@code settings_preview}: takes the real screenshots that the Bedray settings panel shows (PreviewPanel). Builds six fixed scenes
 * in the "RTX Test World" (superflat, fixed positions and cameras, fixed time of day), and for every
 * {@link SettingsCatalog#captureList()} entry switches the setting through the same fields the settings page writes, waits for the
 * image to converge and saves {@code <previewBase>_<valueKey>.png} to {@code <gameDir>/autotest/settings_preview/}. Nothing is saved to
 * bedray.toml. {@code tools/make_preview_assets.py} then scales the shots to 640x360 into assets/bedray/textures/gui/preview.
 * Run: {@code -Dvkbridge.autotest=settings_preview -Dvkbridge.rt.geometry=true} (optional {@code -Dvkrtx.autotest.previewOnly=bloom,water_style},
 * {@code -Dvkrtx.autotest.previewFrames=240}). A value whose backend is not available on the machine is skipped and logged
 * ({@code event=skipped}); the panel then shows a placeholder for it.
 * <p>
 * Scenes: DAY desert avenue with stone floor and far hill, SUNSET colonnade of trees in front of the low sun, WATER pool with a coloured
 * bottom, INTERIOR closed brick room with one lantern, LAVA night with lava, glowstone and sea lanterns.
 */
public final class SettingsPreviewScenario implements AutotestScenario {
    private static final int FRAMES = Integer.getInteger("vkrtx.autotest.previewFrames", 240);
    private static final int SCENE_FRAMES = Integer.getInteger("vkrtx.autotest.previewSceneFrames", 420);

    private record SceneDef(int ox, long time, double cx, double cy, double cz, float yaw, float pitch) {
    }

    private static final Map<SettingsCatalog.Scene, SceneDef> SCENES = new EnumMap<>(SettingsCatalog.Scene.class);

    static {
        // offsets along X from the spawn block; camera relative to (bx + ox, by, bz)
        SCENES.put(SettingsCatalog.Scene.DAY, new SceneDef(0, 3000, 0.5, 0, -4.5, 0f, 14f));
        SCENES.put(SettingsCatalog.Scene.SUNSET, new SceneDef(-60, 12400, 0.5, 0, 0.5, 90f, -1f));
        SCENES.put(SettingsCatalog.Scene.WATER, new SceneDef(60, 4500, 0.5, 1.5, -6.5, 0f, 38f));
        SCENES.put(SettingsCatalog.Scene.INTERIOR, new SceneDef(-120, 6000, 0.5, 0, -2.5, 0f, 6f));
        SCENES.put(SettingsCatalog.Scene.LAVA, new SceneDef(120, 17500, 0.5, 0, -4.5, 0f, 12f));
    }

    private volatile boolean aim;
    private volatile double ax;
    private volatile double ay;
    private volatile double az;
    private volatile float ayaw;
    private volatile float apitch;
    private boolean hooked;
    private int bx;
    private int by;
    private int bz;
    private final Properties manifest = new Properties();

    @Override
    public String name() {
        return "settings_preview";
    }

    private static String cmdFill(int bx, int by, int bz, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + y0, bz + z0, bx + x1, by + y1, bz + z1, block);
    }

    private static String cmdSet(int bx, int by, int bz, int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", bx + x, by + y, bz + z, block);
    }

    /** Commands that build one scene around (bx + ox, by, bz); local coordinates x -14..14, z -6..46. */
    private List<String> buildScene(SettingsCatalog.Scene s, int ox) {
        List<String> c = new ArrayList<>();
        int X = bx + ox;
        switch (s) {
            case DAY -> {
                c.add(cmdFill(X, by, bz, -14, -2, -6, 14, -1, 46, "sand"));
                c.add(cmdFill(X, by, bz, -14, 0, -6, 14, 14, 46, "air"));
                // stone floor in the foreground (surface relief), white wall piece, coloured pillars at 8/18/30 blocks, a far hill
                c.add(cmdFill(X, by, bz, -9, -1, -2, -1, -1, 6, "stone_bricks"));
                c.add(cmdFill(X, by, bz, -6, -1, 0, -4, -1, 3, "cobblestone"));
                c.add(cmdFill(X, by, bz, 2, 0, 5, 9, 4, 5, "white_concrete"));
                for (int[] p : new int[][] {{-8, 10}, {8, 18}, {-6, 30}, {7, 38}}) {
                    c.add(cmdFill(X, by, bz, p[0], 0, p[1], p[0] + 1, 6, p[1] + 1, p[1] % 3 == 0 ? "red_terracotta" : "quartz_pillar"));
                }
                for (int k = 0; k < 8; k++) {
                    c.add(cmdFill(X, by, bz, -14 + 2 * k, 0, 42 - k, 14 - 2 * k, 1 + k, 46, "sand"));
                }
                c.add(cmdSet(X, by, bz, 3, 0, 1, "dead_bush"));
                c.add(cmdSet(X, by, bz, -2, 0, 12, "cactus"));
                c.add(cmdFill(X, by, bz, 4, 0, 8, 4, 0, 8, "gold_block"));
                c.add(cmdFill(X, by, bz, 5, 0, 8, 5, 0, 8, "iron_block"));
                c.add(cmdFill(X, by, bz, 6, 0, 8, 6, 0, 8, "copper_block"));
            }
            case SUNSET -> {
                c.add(cmdFill(X, by, bz, -40, -2, -12, 12, -1, 12, "grass_block"));
                c.add(cmdFill(X, by, bz, -40, 0, -12, 12, 14, 12, "air"));
                // trees in a loose row west of the camera, the sun sets behind them
                for (int[] t : new int[][] {{-8, -4}, {-9, 3}, {-14, -1}, {-15, 6}, {-20, -5}, {-21, 2}, {-26, 5}, {-27, -3}}) {
                    c.add(cmdFill(X, by, bz, t[0], 0, t[1], t[0], 6, t[1], "oak_log"));
                    c.add(cmdFill(X, by, bz, t[0] - 2, 4, t[1] - 2, t[0] + 2, 7, t[1] + 2, "oak_leaves[persistent=true]"));
                }
                c.add(cmdFill(X, by, bz, -2, -1, -3, -1, -1, 3, "stone_bricks"));
            }
            case WATER -> {
                c.add(cmdFill(X, by, bz, -14, -6, -8, 14, -1, 30, "sand"));
                c.add(cmdFill(X, by, bz, -14, 0, -8, 14, 14, 30, "air"));
                c.add(cmdFill(X, by, bz, -8, -5, 0, 8, -2, 14, "water"));
                c.add(cmdFill(X, by, bz, -8, -6, 0, 8, -6, 14, "white_concrete"));
                c.add(cmdFill(X, by, bz, -6, -5, 4, -3, -5, 8, "red_concrete"));
                c.add(cmdFill(X, by, bz, 2, -5, 6, 5, -5, 10, "blue_concrete"));
                c.add(cmdFill(X, by, bz, 0, -5, 2, 1, -4, 3, "gold_block"));
                c.add(cmdFill(X, by, bz, -2, 0, 16, 2, 5, 17, "stone_bricks"));
            }
            case INTERIOR -> {
                c.add(cmdFill(X, by, bz, -6, -2, -6, 6, 6, 12, "stone_bricks"));
                c.add(cmdFill(X, by, bz, -5, -1, -5, 5, 4, 11, "air"));
                c.add(cmdFill(X, by, bz, -5, -1, -5, 5, -1, 11, "polished_andesite"));
                c.add(cmdFill(X, by, bz, 5, 0, -5, 5, 4, 11, "red_wool"));
                c.add(cmdFill(X, by, bz, -5, 0, -5, -5, 4, 11, "blue_wool"));
                c.add(cmdFill(X, by, bz, -1, 0, 6, 1, 1, 8, "white_concrete"));
                c.add(cmdSet(X, by, bz, 4, 2, 11, "lantern[hanging=false]"));
                c.add(cmdSet(X, by, bz, 4, 1, 11, "oak_planks"));
            }
            case LAVA -> {
                c.add(cmdFill(X, by, bz, -14, -3, -6, 14, -1, 30, "netherrack"));
                c.add(cmdFill(X, by, bz, -14, 0, -6, 14, 14, 30, "air"));
                c.add(cmdFill(X, by, bz, -6, -3, 6, 6, -1, 14, "lava"));
                c.add(cmdFill(X, by, bz, -2, 0, 16, 2, 2, 17, "glowstone"));
                c.add(cmdSet(X, by, bz, -9, 0, 10, "sea_lantern"));
                c.add(cmdSet(X, by, bz, 9, 0, 10, "sea_lantern"));
                c.add(cmdFill(X, by, bz, -4, 0, 2, -3, 3, 3, "obsidian"));
                c.add(cmdFill(X, by, bz, 3, 0, 2, 4, 3, 3, "basalt"));
            }
            default -> {
            }
        }
        return c;
    }

    @Override
    public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("settings_preview: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX();
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        if (!hooked) {
            hooked = true;
            Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> holdCamera(ctx));
        }
        String only = System.getProperty("vkrtx.autotest.previewOnly", "");
        List<SettingsCatalog.Info> work = new ArrayList<>();
        for (SettingsCatalog.Info i : SettingsCatalog.captureList()) {
            if (only.isEmpty() || List.of(only.split(",")).contains(i.previewBase())) {
                work.add(i);
            }
        }
        work.sort((a, b) -> a.scene().compareTo(b.scene()));
        // quality-mode screenshots use the first upscaler the machine has
        Path gameDir = XessRuntime.resolveGameDir();
        for (UpscalerBackend b : new UpscalerBackend[] {UpscalerBackend.DLSS, UpscalerBackend.FSR, UpscalerBackend.XESS}) {
            if (UpscalerSelection.status(b, gameDir) == UpscalerSelection.Status.AVAILABLE) {
                SettingsCatalog.previewUpscaler = b;
                break;
            }
        }
        ctx.log("scenario", name(), "event", "start", "base", bx + "," + by + "," + bz, "entries", String.valueOf(work.size()),
            "upscaler_for_modes", String.valueOf(SettingsCatalog.previewUpscaler), "frames", String.valueOf(FRAMES));
        List<String> cmds = new ArrayList<>();
        cmds.add("gamerule doDaylightCycle false");
        cmds.add("gamerule advance_time false");
        cmds.add("weather clear");
        cmds.add("gamemode creative @a");
        for (SettingsCatalog.Scene s : SCENES.keySet()) {
            cmds.addAll(buildScene(s, SCENES.get(s).ox()));
        }
        server.execute(() -> {
            try {
                for (String c : cmds) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                }
            } catch (Throwable t) {
                ctx.fail("settings_preview: server command failed: " + t, t);
            }
        });
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();   // F1: no chat or hotbar on the pictures (Gui.hud.isHidden()/toggle(), javap of the 26.3 client jar)
        }
        ctx.waitFrames(500, () -> scene(ctx, work, 0, null));
    }

    private void holdCamera(AutotestContext ctx) {
        var p = ctx.minecraft().player;
        if (!aim || p == null) {
            return;
        }
        p.getAbilities().flying = true;
        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        p.setPos(ax, ay, az);
        p.xo = ax;
        p.yo = ay;
        p.zo = az;
        p.xOld = ax;
        p.yOld = ay;
        p.zOld = az;
        p.setYRot(ayaw);
        p.yRotO = ayaw;
        p.setXRot(apitch);
        p.xRotO = apitch;
    }

    private void scene(AutotestContext ctx, List<SettingsCatalog.Info> work, int idx, SettingsCatalog.Scene current) {
        if (idx >= work.size()) {
            finish(ctx);
            return;
        }
        SettingsCatalog.Info info = work.get(idx);
        if (info.scene() == current) {
            shots(ctx, work, idx, 0);
            return;
        }
        SceneDef d = SCENES.get(info.scene());
        var server = ctx.minecraft().getSingleplayerServer();
        ax = bx + d.ox() + d.cx();
        ay = by + d.cy();
        az = bz + d.cz();
        ayaw = d.yaw();
        apitch = d.pitch();
        aim = true;
        String tp = String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", ax, ay, az, ayaw, apitch);
        String time = "time set " + d.time();
        server.execute(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), tp);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), time);
        });
        ctx.log("scenario", name(), "event", "scene", "scene", info.scene().name().toLowerCase(Locale.ROOT), "time", String.valueOf(d.time()),
            "camera", String.format(Locale.ROOT, "%.1f,%.1f,%.1f", ax, ay, az));
        // chunks around the new scene arrive, Sodium meshes them, the BLAS are built
        ctx.waitFrames(SCENE_FRAMES, () -> shots(ctx, work, idx, 0));
    }

    private void shots(AutotestContext ctx, List<SettingsCatalog.Info> work, int idx, int pv) {
        SettingsCatalog.Info info = work.get(idx);
        if (pv >= info.previews().size()) {
            SettingsCatalog.previewBaseline();
            scene(ctx, work, idx + 1, info.scene());
            return;
        }
        SettingsCatalog.Preview p = info.previews().get(pv);
        String file = info.previewBase() + "_" + p.valueKey() + ".png";
        String skip = skipReason(info, p);
        if (skip != null) {
            ctx.log("scenario", name(), "event", "skipped", "file", file, "reason", skip);
            shots(ctx, work, idx, pv + 1);
            return;
        }
        try {
            SettingsCatalog.previewBaseline();
            p.apply().run();
        } catch (Throwable t) {
            ctx.log("scenario", name(), "event", "skipped", "file", file, "reason", "apply_failed;" + t);
            shots(ctx, work, idx, pv + 1);
            return;
        }
        ctx.waitFrames(FRAMES, () -> ctx.shot(file, () -> {
            String note = noteFor(info);
            if (!note.isEmpty()) {
                manifest.setProperty(info.previewBase() + "_" + p.valueKey(), note);
            }
            ctx.log("scenario", name(), "event", "captured", "file", file, "scene", info.scene().name().toLowerCase(Locale.ROOT),
                "label", p.label().replace(' ', '_'));
            shots(ctx, work, idx, pv + 1);
        }));
    }

    private static String noteFor(SettingsCatalog.Info info) {
        if (info.previewBase().equals("upscaler_mode")) {
            return "captured with " + SettingsCatalog.previewUpscaler;
        }
        if (info.previewBase().equals("fsr_sharp")) {
            return "captured with FSR Quality";
        }
        return "";
    }

    private static String skipReason(SettingsCatalog.Info info, SettingsCatalog.Preview p) {
        Path gameDir = XessRuntime.resolveGameDir();
        UpscalerBackend need = null;
        if (info.previewBase().equals("upscaler_backend")) {
            need = switch (p.valueKey()) {
                case "dlss" -> UpscalerBackend.DLSS;
                case "fsr" -> UpscalerBackend.FSR;
                case "xess" -> UpscalerBackend.XESS;
                default -> null;
            };
        } else if (info.previewBase().equals("upscaler_mode")) {
            need = SettingsCatalog.previewUpscaler == UpscalerBackend.BILINEAR ? UpscalerBackend.DLSS : SettingsCatalog.previewUpscaler;
        } else if (info.previewBase().equals("fsr_sharp")) {
            need = UpscalerBackend.FSR;
        } else if (info.previewBase().equals("path_denoiser") && p.valueKey().equals("dlss_rr")) {
            need = UpscalerBackend.DLSS;
        }
        if (need != null && UpscalerSelection.status(need, gameDir) != UpscalerSelection.Status.AVAILABLE) {
            return "backend_unavailable;" + need + "=" + UpscalerSelection.status(need, gameDir);
        }
        return null;
    }

    private void finish(AutotestContext ctx) {
        SettingsCatalog.previewBaseline();
        try {
            Path dir = ctx.minecraft().gameDirectory.toPath().resolve("autotest").resolve(name());
            Files.createDirectories(dir);
            try (var w = Files.newBufferedWriter(dir.resolve("manifest.properties"), StandardCharsets.UTF_8)) {
                manifest.store(w, "settings_preview captions");
            }
        } catch (IOException e) {
            ctx.log("scenario", name(), "event", "manifest_failed", "error", e.toString());
        }
        aim = false;
        ctx.log("scenario", name(), "event", "done", "captions", String.valueOf(manifest.size()));
        ctx.finish();
    }
}
