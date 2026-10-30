package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code rt_look}: picture quality and GPU cost of water, glass, metals and lighting (look comparison before / after a shader
 * change). Scenes are built next to the spawn with server commands:
 * <ul>
 *   <li>{@code W}: open ocean (60 x 70 blocks, 7 deep, sand floor with a reef of coloured blocks) seen from a sand beach;</li>
 *   <li>{@code P}: a 8 x 8 pool, 3 deep, tiled floor with lamps under water, in a white courtyard;</li>
 *   <li>{@code G}: glass and metal: a coloured concrete backdrop, a row of metal / lamp blocks, on the left a chain of 5 glass
 *       layers (clear, blue, clear, red, clear), on the right one clear pane layer and a green stained one;</li>
 *   <li>{@code I}: closed oak room with wall torches, lanterns, campfire, glowstone, a window to the outside and a metal shelf.</li>
 * </ul>
 * Steps: {@code -Dvkrtx.autotest.lookSteps=W:path+t6000,G:rays+t6000+perf,...}; a step is {@code scene:mode[+modifier...]}, mode
 * {@code off|rays|path}; modifiers: {@code t<N>} time of day (default 6000), {@code f<N>} frames to wait (default 300 path / 120 rays),
 * {@code b<N>} path bounces, {@code cx=<d> cy=<d> cz=<d>} camera offset in blocks, {@code aim=<yaw>_<pitch>}, {@code v<N>} named view,
 * {@code refl gi ao} (rays mode options), {@code water=vanilla|realistic}, {@code perf} (four 0.5 s PerfMonitor windows are logged as
 * {@code perf=} before the shot). Shots: {@code NN_<step>.png}. Log lines: {@code [VKB-AUTOTEST] scenario=rt_look ...}.
 */
public final class LookScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.lookSteps";
    public static final String DEFAULT_STEPS = "W:path+t6000,P:path+t6000,G:path+t6000,I:path+t18000";
    private static final int PERF_WINDOWS = Integer.getInteger("vkrtx.autotest.lookPerfWindows", 6);

    public record Step(String scene, RtMode mode, List<String> mods, String raw) {
        int frames() {
            for (String m : mods) {
                if (m.matches("f\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return mode == RtMode.PATH_TRACED ? 300 : 120;
        }

        String value(String key) {
            for (String m : mods) {
                if (m.startsWith(key + "=")) {
                    return m.substring(key.length() + 1);
                }
            }
            return null;
        }

        int view() {
            for (String m : mods) {
                if (m.matches("v\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return 1;
        }

        String label() {
            return raw.replace(':', '_').replace('+', '_').replace('=', '-').toLowerCase(Locale.ROOT);
        }
    }

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private boolean listening;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_look";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] sm = item.split(":", 2);
            if (sm.length != 2 || !(sm[0].equals("W") || sm[0].equals("P") || sm[0].equals("G") || sm[0].equals("I") || sm[0].equals("R") || sm[0].equals("Q"))) {
                throw new IllegalArgumentException("step '" + item + "': expected W:, P:, G:, I: or R:<mode>");
            }
            String[] parts = sm[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null) {
                throw new IllegalArgumentException("step '" + item + "': unknown mode " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.matches("[tbfvsd]\\d+") || m.matches("c[xyz]=-?\\d+(\\.\\d+)?") || m.matches("aim=-?\\d+_-?\\d+") || m.equals("refl")
                    || m.equals("gi") || m.equals("ao") || m.equals("perf") || m.matches("water=(vanilla|realistic)") || m.equals("noaccum") || m.equals("noglass") || m.equals("glass"))) {
                    throw new IllegalArgumentException("step '" + item + "': unknown modifier " + m);
                }
                mods.add(m);
            }
            steps.add(new Step(sm[0], mode, mods, item));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no steps in '" + text + "'");
        }
        return steps;
    }

    @Override
    public void run(AutotestContext ctx) {
        List<Step> steps;
        try {
            steps = parseSteps(System.getProperty(STEPS_PROPERTY, DEFAULT_STEPS));
        } catch (IllegalArgumentException e) {
            ctx.fail("rt_look: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_look: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX();
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        if (!listening) {
            listening = true;
            Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> applyAim(ctx));
        }
        List<String> cmds = buildCommands();
        server.execute(() -> {
            try {
                for (String c : cmds) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                }
            } catch (Throwable t) {
                ctx.fail("rt_look: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'));
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        ctx.waitFrames(600, () -> step(ctx, steps, 0));
    }

    // ---- scene geometry (offsets from the spawn block; y relative: 0 = the floor level the player stands at) -------------

    private static final int WX = -140;   // ocean centre x
    private static final int WZ = 0;
    private static final int PX = -90;    // pool courtyard centre
    private static final int GX = 40;     // glass / metal stage centre
    private static final int IX = 100;    // interior room west corner
    private static final int QX = 240;   // glass interior: cell of glass cubes / panes and stained glass around lamps
    private static final int RX = 170;    // reflection stage centre (red glass mirror wall, pillars, lamps)

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        // ---- W: ocean --------------------------------------------------------------------------------------------------
        fillBig(c, WX - 32, -10, WZ - 14, WX + 32, 14, WZ + 74, "air");
        c.add(fill(WX - 30, -2, WZ - 14, WX + 30, -1, WZ - 1, "sandstone"));            // beach (top at 0)
        fillBig(c, WX - 31, -9, WZ, WX + 31, -8, WZ + 73, "sandstone");
        fillBig(c, WX - 31, -7, WZ, WX - 31, -1, WZ + 73, "sandstone");
        fillBig(c, WX + 31, -7, WZ, WX + 31, -1, WZ + 73, "sandstone");
        fillBig(c, WX - 31, -7, WZ + 73, WX + 31, -1, WZ + 73, "sandstone");
        fillBig(c, WX - 30, -7, WZ, WX + 30, -1, WZ + 72, "water");
        c.add(fill(WX - 30, -8, WZ, WX + 30, -2, WZ, "sandstone"));                     // beach slope wall
        c.add(fill(WX - 30, -1, WZ, WX + 30, -1, WZ + 6, "water"));
        for (int i = 0; i < 40; i++) {                                              // reef: coloured blocks on the sea floor
            String[] reef = {"prismarine", "sea_lantern", "gold_block", "red_concrete", "dark_prismarine", "magma_block", "lapis_block", "brain_coral_block"};
            int rx = WX - 16 + (i * 7) % 33;
            int rz = WZ + 6 + (i * 11) % 40;
            c.add(set(rx, -7, rz, reef[i % reef.length]));
        }
        c.add(fill(WX - 3, -7, WZ + 12, WX + 3, -6, WZ + 14, "sea_lantern"));
        c.add(fill(WX + 6, -7, WZ + 10, WX + 9, -5, WZ + 13, "gold_block"));
        c.add(fill(WX - 10, -7, WZ + 8, WX - 7, -5, WZ + 11, "red_concrete"));
        c.add(fill(WX + 12, -1, WZ + 20, WX + 16, -1, WZ + 24, "ice"));                  // ice floe on the surface
        c.add(fill(WX - 16, -7, WZ + 22, WX - 13, -5, WZ + 25, "blue_ice"));            // blue ice on the sea floor
        // ---- P: pool courtyard -----------------------------------------------------------------------------------------
        fillBig(c, PX - 14, -8, -14, PX + 14, 12, 14, "air");
        c.add(fill(PX - 14, -6, -14, PX + 14, -1, 14, "quartz_block"));
        c.add(fill(PX - 4, -5, -4, PX + 4, -1, 4, "air"));                           // pool pit 9 x 9, 4 deep
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                c.add(set(PX + x, -5, z, ((x + z) & 1) == 0 ? "light_blue_concrete" : "white_concrete"));
            }
        }
        c.add(fill(PX - 4, -4, -4, PX + 4, -2, 4, "water"));
        c.add(set(PX - 3, -5, -3, "sea_lantern"));
        c.add(set(PX + 3, -5, 3, "sea_lantern"));
        c.add(set(PX + 3, -5, -3, "gold_block"));
        c.add(set(PX - 3, -5, 3, "red_concrete"));
        c.add(set(PX, -5, 0, "diamond_block"));
        c.add(fill(PX - 14, 0, 14, PX + 14, 6, 14, "white_concrete"));              // wall behind
        c.add(set(PX - 7, 0, 10, "torch"));
        c.add(set(PX + 7, 0, 10, "torch"));
        c.add(fill(PX - 9, 0, -9, PX - 9, 3, -9, "oak_log"));
        c.add(fill(PX + 9, 0, -9, PX + 9, 3, -9, "oak_log"));
        // ---- G: glass chain + metal gallery ---------------------------------------------------------------------------
        fillBig(c, GX - 20, -3, -4, GX + 20, 10, 30, "air");
        c.add(fill(GX - 20, -2, -4, GX + 20, -1, 30, "smooth_stone"));
        String[] back = {"red_concrete", "orange_concrete", "yellow_concrete", "lime_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "white_concrete"};
        for (int i = 0; i < back.length; i++) {
            c.add(fill(GX - 16 + i * 4, 0, 22, GX - 13 + i * 4, 4, 22, back[i]));
        }
        String[] metals = {"iron_block", "gold_block", "copper_block", "diamond_block", "emerald_block", "netherite_block", "lapis_block",
            "quartz_block", "glowstone", "sea_lantern", "redstone_lamp[lit=true]", "shroomlight"};
        for (int i = 0; i < metals.length; i++) {
            c.add(set(GX - 11 + i * 2, 0, 20, metals[i]));
        }
        // left: chain of 5 glass layers over x -12..-2 at z 8..12 (full height 4)
        String[] chain = {"glass", "blue_stained_glass", "glass", "red_stained_glass", "glass"};
        for (int i = 0; i < chain.length; i++) {
            c.add(fill(GX - 12, 0, 8 + i, GX - 2, 3, 8 + i, chain[i]));
        }
        // right: one clear layer + one green layer
        c.add(fill(GX + 2, 0, 8, GX + 12, 3, 8, "glass"));
        c.add(fill(GX + 2, 0, 10, GX + 12, 3, 10, "lime_stained_glass"));
        c.add(fill(GX + 2, 0, 12, GX + 12, 3, 12, "yellow_stained_glass"));
        c.add(set(GX, 0, 15, "torch"));
        // ---- R: reflection stage: red stained glass wall in front of a black backdrop, stone pillars with lamps on the viewer side
        fillBig(c, RX - 24, -3, -4, RX + 24, 10, 30, "air");
        c.add(fill(RX - 24, -2, -4, RX + 24, -1, 30, "smooth_stone"));
        c.add(fill(RX - 14, 0, 14, RX + 14, 5, 14, "black_concrete"));
        c.add(fill(RX - 14, 0, 10, RX + 14, 3, 10, "red_stained_glass"));
        for (int i = 0; i < 4; i++) {
            int px = RX - 9 + i * 6;
            c.add(fill(px, 0, 5, px, 4, 5, "stone_bricks"));
            c.add(set(px, 5, 5, "sea_lantern"));
            c.add(set(px + 1, 0, 4, "lantern"));
            c.add(set(px - 1, 0, 6, "torch"));
        }
        c.add(fill(RX - 14, 0, 3, RX + 14, 0, 3, "birch_planks"));
        // ---- Q: glass interior: stone shell, a row of coloured glass cubes and single layers, lamps behind them ----------------
        fillBig(c, QX - 8, -3, -2, QX + 8, 9, 16, "air");
        c.add(fill(QX - 7, -2, -1, QX + 7, -1, 15, "stone_bricks"));
        c.add(fill(QX - 7, 0, 15, QX + 7, 6, 15, "stone_bricks"));
        c.add(fill(QX - 7, 0, -1, QX - 7, 6, 15, "stone_bricks"));
        c.add(fill(QX + 7, 0, -1, QX + 7, 6, 15, "stone_bricks"));
        c.add(fill(QX - 7, 6, -1, QX + 7, 6, 15, "glass"));
        String[] qglass = {"blue_stained_glass", "yellow_stained_glass", "glass", "red_stained_glass", "lime_stained_glass", "purple_stained_glass"};
        for (int i = 0; i < qglass.length; i++) {
            c.add(fill(QX - 6 + i * 2, 0, 6, QX - 5 + i * 2, 3, 7, qglass[i]));
            c.add(fill(QX - 6 + i * 2, 0, 10, QX - 6 + i * 2, 3, 10, qglass[(i + 2) % 6]));
        }
        c.add(set(QX - 5, 0, 13, "lantern"));
        c.add(set(QX, 0, 13, "sea_lantern"));
        c.add(set(QX + 5, 0, 13, "glowstone"));
        c.add(set(QX - 2, 0, 3, "torch"));
        c.add(fill(QX - 6, 0, 13, QX + 6, 0, 14, "gold_block"));
        // ---- I: closed oak room, 11 x 11, interior 9 x 4 x 9 ---------------------------------------------------------------
        fillBig(c, IX - 2, -4, -2, IX + 14, 8, 14, "air");
        c.add(fill(IX, -2, 0, IX + 10, 5, 10, "oak_planks"));
        c.add(fill(IX + 1, -1, 1, IX + 9, 4, 9, "air"));
        c.add(fill(IX + 1, -1, 1, IX + 9, -1, 9, "dark_oak_planks"));
        c.add(fill(IX + 4, 1, 0, IX + 6, 3, 0, "glass"));                           // window in the south wall (z = 0) towards the sky
        c.add(set(IX + 1 + 0, 2, 4, "wall_torch[facing=east]"));
        c.add(set(IX + 1 + 0, 2, 8, "wall_torch[facing=east]"));
        c.add(set(IX + 8, 0, 6, "torch"));
        c.add(set(IX + 5, 0, 9, "lantern"));
        c.add(set(IX + 3, 0, 8, "campfire[lit=true]"));
        c.add(fill(IX + 8, 0, 8, IX + 9, 0, 9, "glowstone"));
        c.add(fill(IX + 2, 0, 2, IX + 3, 0, 2, "iron_block"));
        c.add(set(IX + 4, 0, 2, "gold_block"));
        c.add(set(IX + 5, 0, 2, "copper_block"));
        c.add(set(IX + 6, 0, 2, "diamond_block"));
        c.add(fill(IX + 1, 0, 6, IX + 1, 1, 6, "bookshelf"));
        return c;
    }

    /** Fill split into z slabs of at most 30000 blocks (the fill command limit is 32768). */
    private void fillBig(List<String> c, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        int area = (x1 - x0 + 1) * (y1 - y0 + 1);
        int step = Math.max(1, 30000 / area);
        for (int z = z0; z <= z1; z += step) {
            c.add(fill(x0, y0, z, x1, y1, Math.min(z1, z + step - 1), block));
        }
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + y0, bz + z0, bx + x1, by + y1, bz + z1, block);
    }

    private String set(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", bx + x, by + y, bz + z, block);
    }

    private void applyAim(AutotestContext ctx) {
        var player = ctx.minecraft().player;
        if (!aimOverride || player == null) {
            return;
        }
        player.setYRot(aimYaw);
        player.yRotO = aimYaw;
        player.setXRot(aimPitch);
        player.xRotO = aimPitch;
    }

    private void command(AutotestContext ctx, String cmd) {
        var server = ctx.minecraft().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    /** Feet position and aim of the camera, per scene and view; cx/cy/cz and aim= override. */
    private void placeCamera(AutotestContext ctx, Step s) {
        double x;
        double y = by;
        double z;
        switch (s.scene()) {
            case "W" -> {
                x = bx + WX;
                z = bz + WZ - 3.0;
                aimYaw = 0f;
                aimPitch = s.view() == 2 ? 25f : 6f;
                if (s.view() == 3) {   // low sun glitter angle: looking along the water at the horizon
                    aimPitch = 1.5f;
                    aimYaw = 70f;
                }
            }
            case "P" -> {
                x = bx + PX;
                z = bz - 9.5;
                aimYaw = 0f;
                aimPitch = s.view() == 2 ? 55f : 30f;
                y = by + (s.view() == 2 ? 3.0 : 0.0);
                if (s.view() >= 3) {   // under water (surface at y = -1, eye 1.62 above the feet): looking up at the surface
                    x = bx + PX - 3.0;
                    z = bz - 2.5;
                    y = by - 3.6;
                    aimYaw = -30f;
                    aimPitch = s.view() == 3 ? -60f : -35f;
                }
            }
            case "G" -> {
                x = bx + GX;
                z = bz + 1.5;
                aimYaw = 0f;
                aimPitch = 4f;
                if (s.view() == 2) {   // look through the 5-glass chain from the left
                    x = bx + GX - 7;
                    aimPitch = 0f;
                } else if (s.view() == 3) {   // oblique angle, Fresnel on the glass
                    x = bx + GX - 6;
                    z = bz + 3.0;
                    aimYaw = -50f;
                    aimPitch = 3f;
                }
            }
            case "Q" -> {
                x = bx + QX;
                z = bz + 1.0;
                aimYaw = 0f;
                aimPitch = 8f;
                if (s.view() == 2) {
                    x = bx + QX - 5;
                    z = bz + 3.0;
                    aimYaw = -25f;
                } else if (s.view() == 3) {
                    x = bx + QX + 5;
                    z = bz + 2.0;
                    aimYaw = 30f;
                }
            }
            case "R" -> {
                x = bx + RX - 12;
                z = bz + 8.0;
                aimYaw = -75f;
                aimPitch = 3f;
                if (s.view() == 2) {   // from the front, mirror at near normal incidence
                    x = bx + RX - 3;
                    z = bz - 1.0;
                    aimYaw = -20f;
                    aimPitch = 4f;
                }
            }
            default -> {
                x = bx + IX + 5.5;
                z = bz + 8.6;
                aimYaw = 180f;
                aimPitch = 10f;
                if (s.view() == 2) {
                    x = bx + IX + 2.0;
                    z = bz + 1.6;
                    aimYaw = -35f;
                    aimPitch = 8f;
                    aimYaw = 215f;
                }
            }
        }
        x += num(s.value("cx"));
        y += num(s.value("cy"));
        z += num(s.value("cz"));
        String aim = s.value("aim");
        if (aim != null) {
            String[] yp = aim.split("_");
            aimYaw = Float.parseFloat(yp[0]);
            aimPitch = Float.parseFloat(yp[1]);
        }
        aimOverride = true;
        command(ctx, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, aimYaw, aimPitch));
    }

    private static double num(String v) {
        return v == null ? 0.0 : Double.parseDouble(v);
    }

    private void applySettings(Step s) {
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        RtSettings.raysEmissiveDistance = 32;
        RtSettings.pathBounces = 3;
        RtSettings.pathSpp = 1;
        for (String m : s.mods()) {
            if (m.matches("s\\d+")) {
                RtSettings.pathSpp = Integer.parseInt(m.substring(1));
            }
        }
        RtSettings.pathAccumulate = !s.mods().contains("noaccum");
        RtSettings.pathDistance = 256;
        for (String m : s.mods()) {
            if (m.matches("d\\d+")) {
                RtSettings.pathDistance = Integer.parseInt(m.substring(1));
            }
        }
        RtSettings.pathAutoExposure = true;
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
        RtSettings.autoNormals = true;
        RtSettings.autoNormalStrength = 100;
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        for (String m : s.mods()) {
            if (m.matches("b\\d+")) {
                RtSettings.pathBounces = Integer.parseInt(m.substring(1));
            } else if (m.equals("refl")) {
                RtSettings.raysReflections = true;
            } else if (m.equals("gi")) {
                RtSettings.raysGi = true;
            } else if (m.equals("ao")) {
                RtSettings.raysAo = true;
            } else if (m.equals("water=vanilla")) {
                RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA;
            }
        }
        RtConfig.setDebugView("");
        VulkanRtxConfig.rtMode = s.mode();
        VulkanRtxConfig.enableHardwareRayTracing = s.mode().isActive();
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.size()));
            ctx.finish();
            return;
        }
        Step s = steps.get(index);
        var mc = ctx.minecraft();
        placeCamera(ctx, s);
        String time = "6000";
        for (String m : s.mods()) {
            if (m.matches("t\\d+")) {
                time = m.substring(1);
            }
        }
        command(ctx, "time set " + time);
        if (s.scene().equals("G") && (s.mods().contains("noglass") || s.mods().contains("glass"))) {
            // glass layers: removed (cost baseline) or put back
            boolean on = s.mods().contains("glass");
            String[] chain = {"glass", "blue_stained_glass", "glass", "red_stained_glass", "glass"};
            for (int i = 0; i < chain.length; i++) {
                command(ctx, fill(GX - 12, 0, 8 + i, GX - 2, 3, 8 + i, on ? chain[i] : "air"));
            }
            command(ctx, fill(GX + 2, 0, 8, GX + 12, 3, 8, on ? "glass" : "air"));
            command(ctx, fill(GX + 2, 0, 10, GX + 12, 3, 10, on ? "lime_stained_glass" : "air"));
            command(ctx, fill(GX + 2, 0, 12, GX + 12, 3, 12, on ? "yellow_stained_glass" : "air"));
        }
        applySettings(s);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "mode", s.mode().logName(),
            "frame", String.valueOf(ctx.frames()));
        ctx.waitFrames(s.frames(), () -> {
            RtMode pass = RtFrameRenderer.lastPassMode();
            String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(),
                "mode", s.mode().logName(), "pass", pass == null ? "none" : pass.logName(),
                "player", String.format(Locale.ROOT, "%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                "file", file);
            if (s.mode().isActive() && pass != s.mode()) {
                ctx.fail("rt_look step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                return;
            }
            if (s.mods().contains("perf")) {
                perf(ctx, s, index, 0, PerfMonitor.publishedVersion(), () -> ctx.shot(file, () -> step(ctx, steps, index + 1)));
            } else {
                ctx.shot(file, () -> step(ctx, steps, index + 1));
            }
        });
    }

    private void perf(AutotestContext ctx, Step s, int index, int k, long seen, Runnable then) {
        if (k >= PERF_WINDOWS) {
            then.run();
            return;
        }
        ScenarioTime.waitUntil(ctx, () -> PerfMonitor.publishedVersion() != seen, 5.0, () -> {
            ctx.log("scenario", name(), "step", String.valueOf(index), "spec", s.raw(), "sample", String.valueOf(k),
                "perf", DebugInfo.lastPerfLine());
            perf(ctx, s, index, k + 1, PerfMonitor.publishedVersion(), then);
        }, () -> ctx.fail("rt_look: no PerfMonitor window published within 5 s", null));
    }
}
