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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code rt_materials}: night shots of the Bedrock-RTX style materials (emission / MER per pixel, PBR of held items). Five closed
 * stone rooms next to the spawn, built with server commands, each lit only by what is placed in it.
 * <pre>
 *   -Dvkrtx.autotest.materialSteps=D:path,N:path,G:path,T:path,H:path+item=iron_ingot
 * </pre>
 * A step is {@code scene:mode[+modifier...]}; mode {@code rays|path|off}; scenes:
 * <ul>
 *   <li>{@code D}: two redstone dust lines on the floor, one powered by a redstone block (power 15), one dead;</li>
 *   <li>{@code N}: an end rod standing on the floor;</li>
 *   <li>{@code G}: a pad of magma blocks;</li>
 *   <li>{@code T}: an enchanting table;</li>
 *   <li>{@code H}: the player holds {@code item=<id>} two blocks from a wall with a wall torch and a glowstone block
 *       ({@code cam=3}: third person from the front).</li>
 * </ul>
 * Modifiers: {@code item=<id>}, {@code cam=3}, {@code t<N>} (time, default 18000), {@code b<N>} (bounces), {@code f<N>} (frames before the
 * shot, default 400), {@code aim=<yaw>_<pitch>}, {@code cx=<dx>}, {@code cz=<dz>}.
 * Shots: {@code NN_<step>.png}; a {@code [VKB-AUTOTEST] scenario=rt_materials ... event=state} line per step.
 */
public final class MaterialsScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.materialSteps";
    public static final String DEFAULT_STEPS = "D:path,N:path,G:path,T:path,H:path+item=iron_ingot";
    private static final String SCENES = "DNGTHSEBKRW";

    public record Step(String scene, RtMode mode, List<String> mods, String raw) {
        int frames() {
            for (String m : mods) {
                if (m.matches("f\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return 400;
        }

        String value(String key) {
            for (String m : mods) {
                if (m.startsWith(key + "=")) {
                    return m.substring(key.length() + 1);
                }
            }
            return null;
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
        return "rt_materials";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] sm = item.split(":", 2);
            if (sm.length != 2 || sm[0].length() != 1 || SCENES.indexOf(sm[0].charAt(0)) < 0) {
                throw new IllegalArgumentException("step '" + item + "': expected D:, N:, G:, T:, H:, S:, E:, B:, K:, R: or W:<mode>");
            }
            String[] parts = sm[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null) {
                throw new IllegalArgumentException("step '" + item + "': unknown mode " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.startsWith("item=") || m.equals("cam=3") || m.matches("c[xz]=-?\\d+(\\.\\d+)?") || m.matches("[tbf]\\d+")
                    || m.matches("aim=-?\\d+_-?\\d+") || m.equals("water=vanilla") || m.equals("water=realistic"))) {
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
            ctx.fail("rt_materials: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_materials: no singleplayer server / player", null);
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
                ctx.fail("rt_materials: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'));
        ctx.waitFrames(400, () -> step(ctx, steps, 0));
    }

    /** West corner (x) of the room of a scene; rooms are 11 x 11 blocks, interior 9 x 4 x 9, 14 blocks apart. */
    private static int roomX(String scene) {
        return 40 + 14 * (SCENES.indexOf(scene.charAt(0)) % 5);
    }

    /** Rooms 5..9 (S, E, B, K, R) are in a second row, 14 blocks to the south. */
    private static int roomZ(String scene) {
        return ROOM_Z + 14 * (SCENES.indexOf(scene.charAt(0)) / 5);
    }

    private static final int ROOM_Z = 0;

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        for (int i = 0; i < SCENES.length(); i++) {
            int x0 = roomX(SCENES.substring(i, i + 1));
            int z0 = roomZ(SCENES.substring(i, i + 1));
            c.add(fill(x0, -2, z0, x0 + 10, 5, z0 + 10, "stone_bricks"));
            c.add(fill(x0 + 1, 0, z0 + 1, x0 + 9, 4, z0 + 9, "air"));
        }
        // D: powered line (redstone block at the west end) at z+5, a dead line at z+8
        int d = roomX("D");
        c.add(set(d + 1, 0, ROOM_Z + 5, "redstone_block"));
        for (int i = 2; i <= 8; i++) {
            c.add(set(d + i, 0, ROOM_Z + 5, "redstone_wire[east=side,west=side,north=none,south=none,power=15]"));
            c.add(set(d + i, 0, ROOM_Z + 8, "redstone_wire[east=side,west=side,north=none,south=none,power=0]"));
        }
        // N: end rod on the floor
        c.add(set(roomX("N") + 5, 0, ROOM_Z + 6, "end_rod[facing=up]"));
        // G: pad of magma blocks 3 x 3
        int g = roomX("G");
        c.add(fill(g + 4, 0, ROOM_Z + 6, g + 6, 0, ROOM_Z + 8, "magma_block"));
        // T: enchanting table
        c.add(set(roomX("T") + 5, 0, ROOM_Z + 7, "enchanting_table"));
        // H: a wall torch and a glowstone block in the wall in front of the player, a lantern on the floor to the side
        int h = roomX("H");
        c.add(set(h + 3, 2, ROOM_Z + 9, "wall_torch[facing=north]"));
        c.add(set(h + 7, 2, ROOM_Z + 10, "glowstone"));
        c.add(set(h + 8, 0, ROOM_Z + 8, "lantern"));
        addMatfixScenes(c);
        return c;
    }

    private static final String[][] STAND = {
        {"shroomlight", "glowstone", "sea_lantern", "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "magma_block",
            "jack_o_lantern[facing=south]", "redstone_lamp[lit=true]"},
        {"crying_obsidian", "respawn_anchor[charges=4]", "copper_lantern", "exposed_copper_lantern", "weathered_copper_lantern",
            "oxidized_copper_lantern", "lantern", "soul_lantern", "copper_bulb[lit=true]"},
        {"candle[candles=3,lit=true]", "red_candle[candles=4,lit=true]", "end_rod[facing=up]", "campfire[lit=true]",
            "soul_campfire[lit=true]", "torch", "soul_torch", "redstone_torch", "copper_torch"}
    };

    /** Dropped item ids of scene E (item entities with the same name as the blocks behind them). */
    static final String[] DROPS = {"glass", "packed_ice", "blue_ice", "ice", "copper_lantern", "shroomlight",
        "red_stained_glass", "torch", "lantern"};

    private String setAt(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", bx + x, by + y, bz + z, block);
    }

    /** S, E, B, K, R scenes of the material / lighting audit (positions relative to the player's base). */
    private void addMatfixScenes(List<String> c) {
        int s0 = roomX("S");
        int sz = roomZ("S");
        int[] rowZ = {9, 7, 5};
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < 9; i++) {
                c.add(setAt(s0 + 1 + i, 0, sz + rowZ[r], STAND[r][i]));
            }
        }
        int e0 = roomX("E");
        int ez = roomZ("E");
        for (int i = 0; i < 9; i++) {
            String blk = DROPS[i].equals("torch") ? "glowstone" : DROPS[i];
            c.add(setAt(e0 + 1 + i, 0, ez + 8, blk));
        }
        c.add(setAt(e0 + 1, 1, ez + 3, "oak_wall_sign[facing=east]{front_text:{has_glowing_text:true,color:\"orange\","
            + "messages:['\"GLOW INK\"','\"sign text\"','\"\"','\"\"']}}"));
        c.add(setAt(e0 + 1, 1, ez + 6, "oak_wall_sign[facing=east]{front_text:{color:\"orange\","
            + "messages:['\"PLAIN INK\"','\"sign text\"','\"\"','\"\"']}}"));
        int b0 = roomX("B");
        int bz0 = roomZ("B");
        for (int x : new int[] {2, 5, 8}) {
            c.add(setAt(b0 + x, 0, bz0 + 9, "glowstone"));
        }
        for (int y = 0; y <= 1; y++) {
            for (int x = 1; x <= 3; x++) {
                c.add(setAt(b0 + x, y, bz0 + 7, "iron_bars"));
            }
            for (int x = 4; x <= 6; x++) {
                c.add(setAt(b0 + x, y, bz0 + 7, "oak_leaves[persistent=true]"));
            }
            c.add(setAt(b0 + 7, y, bz0 + 7, "oak_fence"));
            c.add(setAt(b0 + 9, y, bz0 + 7, "oak_fence"));
            c.add(setAt(b0 + 8, y, bz0 + 7, "chain[axis=y]"));
        }
        int k0 = roomX("K");
        int kz = roomZ("K");
        c.add(setAt(k0 + 2, 0, kz + 6, "torch"));
        c.add(setAt(k0 + 4, 0, kz + 6, "redstone_torch"));
        c.add(setAt(k0 + 6, 0, kz + 6, "soul_torch"));
        c.add(setAt(k0 + 8, 0, kz + 6, "copper_torch"));
        int w0 = roomX("W");
        int wz = roomZ("W");
        c.add(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", bx + w0, by + 5, bz + wz, bx + w0 + 10, by + 5, bz + wz + 10));
        c.add(String.format(Locale.ROOT, "fill %d %d %d %d %d %d water", bx + w0 + 2, by - 1, bz + wz + 3, bx + w0 + 8, by - 1, bz + wz + 8));
        c.add(setAt(w0 + 5, -2, wz + 6, "glowstone"));
        int r0 = roomX("R");
        int rz = roomZ("R");
        List<String> wires = new ArrayList<>();
        c.add(setAt(r0 + 1, 0, rz + 3, "redstone_block"));
        for (int x = 2; x <= 9; x++) {
            wires.add(r0 + x + " 0 " + (rz + 3));
        }
        for (int z = 2; z <= 9; z++) {
            if (z != 3) {
                wires.add((r0 + 5) + " 0 " + (rz + z));
            }
        }
        for (int x = 6; x <= 9; x++) {
            wires.add((r0 + x) + " 0 " + (rz + 9));
        }
        c.add(setAt(r0 + 2, 0, rz + 6, "stone"));
        wires.add((r0 + 2) + " 1 " + (rz + 6));
        wires.add((r0 + 3) + " 0 " + (rz + 6));
        wires.add((r0 + 8) + " 0 " + (rz + 6));
        c.add(setAt(r0 + 8, 0, rz + 7, "redstone_block"));
        c.add(setAt(r0 + 3, 0, rz + 7, "redstone_block"));
        for (String w : wires) {
            String[] t = w.split(" ");
            c.add(setAt(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]), "redstone_wire"));
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

    private void placeCamera(AutotestContext ctx, Step s) {
        int x0 = roomX(s.scene());
        double x = bx + x0 + 5.5;
        double z;
        int z0 = roomZ(s.scene());
        aimYaw = 0f;
        switch (s.scene()) {
            case "D" -> {
                z = bz + ROOM_Z + 2.5;
                aimPitch = 38f;
            }
            case "S" -> {
                z = bz + z0 + 1.5;
                aimPitch = 52f;
            }
            case "E" -> {
                z = bz + z0 + 2.5;
                aimPitch = 28f;
            }
            case "B" -> {
                z = bz + z0 + 2.5;
                aimPitch = 12f;
            }
            case "K" -> {
                z = bz + z0 + 3.5;
                aimPitch = 20f;
            }
            case "W" -> {
                z = bz + z0 + 1.5;
                aimPitch = 35f;
            }
            case "R" -> {
                z = bz + z0 + 1.0;
                aimPitch = 55f;
            }
            case "N" -> {
                z = bz + ROOM_Z + 3.5;
                aimPitch = 14f;
            }
            case "G" -> {
                z = bz + ROOM_Z + 2.5;
                aimPitch = 30f;
            }
            case "T" -> {
                z = bz + ROOM_Z + 4.0;
                aimPitch = 22f;
            }
            default -> {
                z = bz + ROOM_Z + 6.5;
                aimPitch = 10f;
            }
        }
        String cx = s.value("cx");
        if (cx != null) {
            x += Double.parseDouble(cx);
        }
        String cz = s.value("cz");
        if (cz != null) {
            z += Double.parseDouble(cz);
        }
        String aim = s.value("aim");
        if (aim != null) {
            String[] yp = aim.split("_");
            aimYaw = Float.parseFloat(yp[0]);
            aimPitch = Float.parseFloat(yp[1]);
        }
        aimOverride = true;
        command(ctx, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, (double) by, z, aimYaw, aimPitch));
    }

    private void applySettings(Step s) {
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        RtSettings.raysShadowQuality = 2;
        RtSettings.raysEmissiveDistance = 32;
        RtSettings.pathBounces = 3;
        RtSettings.pathSpp = 1;
        RtSettings.pathAccumulate = true;
        RtSettings.pathDistance = 256;
        RtSettings.pathAutoExposure = true;
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
        RtSettings.autoNormals = true;
        RtSettings.autoNormalStrength = 100;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        for (String m : s.mods()) {
            if (m.matches("b\\d+")) {
                RtSettings.pathBounces = Integer.parseInt(m.substring(1));
            }
        }
        for (String m : s.mods()) {
            if (m.equals("water=vanilla")) {
                RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA;
            } else if (m.equals("water=realistic")) {
                RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
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
        // F1 hides the hand too: only the H scene (item in the hand) keeps the HUD on
        if (mc.gui.hud.isHidden() != !s.scene().equals("H")) {
            mc.gui.hud.toggle();
        }
        String time = "18000";
        for (String m : s.mods()) {
            if (m.matches("t\\d+")) {
                time = m.substring(1);
            }
        }
        command(ctx, "time set " + time);
        command(ctx, "kill @e[type=item]");
        if (s.scene().equals("E")) {
            int ex = bx + roomX("E");
            int ez = bz + roomZ("E");
            for (int i = 0; i < DROPS.length; i++) {
                command(ctx, String.format(Locale.ROOT, "summon item %d.5 %d.3 %d.5 {Item:{id:\"minecraft:%s\",count:1},PickupDelay:32767s,Age:-32768s,"
                    + "Motion:[0.0d,0.0d,0.0d]}", ex + 1 + i, by, ez + 6, DROPS[i]));
            }
        }
        String item = s.value("item");
        command(ctx, item != null ? "item replace entity @a weapon.mainhand with minecraft:" + item
            : "item replace entity @a weapon.mainhand with minecraft:air");
        mc.options.setCameraType("3".equals(s.value("cam")) || s.mods().contains("cam=3")
            ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT : net.minecraft.client.CameraType.FIRST_PERSON);
        applySettings(s);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "mode", s.mode().logName(),
            "frame", String.valueOf(ctx.frames()));
        ctx.waitFrames(s.frames(), () -> {
            RtMode pass = RtFrameRenderer.lastPassMode();
            String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(),
                "mode", s.mode().logName(), "pass", pass == null ? "none" : pass.logName(),
                "player", String.format(Locale.ROOT, "%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                "hand", String.valueOf(mc.player.getMainHandItem().getItem()),
                "emitters", RtFrameRenderer.lightTelemetry(),
                "file", file);
            if (s.mode().isActive() && pass != s.mode()) {
                ctx.fail("rt_materials step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                return;
            }
            ctx.shot(file, () -> step(ctx, steps, index + 1));
        });
    }
}
