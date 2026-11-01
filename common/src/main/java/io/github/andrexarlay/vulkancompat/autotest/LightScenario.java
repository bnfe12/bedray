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
 * {@code rt_light}: night shots of the light sources of the RT renderer (glowing blocks, torches, the item in the hand, dropped
 * items). Four closed stone rooms are built next to the spawn with server commands, each lit only by what the step puts in it.
 * <pre>
 *   -Dvkrtx.autotest.lightSteps=R:path,G:rays,H:path+item=glowstone,D:path+drop=glowstone~torch+f300
 * </pre>
 * A step is {@code scene:mode[+modifier...]}; mode {@code rays|path|off}; scenes:
 * <ul>
 *   <li>{@code R}: room with two wall torches, a standing torch and a lantern (the night-room test);</li>
 *   <li>{@code G}: gallery of the light sources: glowstone, sea lantern, shroomlight, froglight, lit lamp, magma, lit copper bulb
 *       (back row) and torch, soul torch, redstone torch, campfire, lantern, soul lantern (front row);</li>
 *   <li>{@code H}: the player stands two blocks from a wall holding {@code item=<id>} (first person, {@code cam=3} third person
 *       from the front);</li>
 *   <li>{@code D}: {@code drop=<id>[~<id>...]} items lying on the floor, camera looking at them.</li>
 * </ul>
 * Modifiers: {@code item=<id>}, {@code drop=<id>~<id>}, {@code cam=3}, {@code close} / {@code near} (camera near the front row of G),
 * {@code nrd} (NRD denoiser in path mode), {@code t<N>} (time, default 18000), {@code b<N>} (path bounces), {@code f<N>}
 * (frames to wait before the shot; default 120 rays / 300 path), {@code aim=<yaw>_<pitch>}.
 * Shots: {@code NN_<step>.png}; a {@code [VKB-AUTOTEST] scenario=rt_light ... event=state} line per step carries the emitter counts.
 */
public final class LightScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.lightSteps";
    public static final String DEFAULT_STEPS = "R:rays,R:path,G:rays,H:path+item=glowstone,D:path+drop=glowstone";

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

        String label() {
            return raw.replace(':', '_').replace('+', '_').replace('=', '-').replace('~', '-').toLowerCase(Locale.ROOT);
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
        return "rt_light";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] sm = item.split(":", 2);
            if (sm.length != 2 || !(sm[0].equals("R") || sm[0].equals("G") || sm[0].equals("H") || sm[0].equals("D"))) {
                throw new IllegalArgumentException("step '" + item + "': expected R:, G:, H: or D:<mode>");
            }
            String[] parts = sm[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null) {
                throw new IllegalArgumentException("step '" + item + "': unknown mode " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.startsWith("item=") || m.startsWith("drop=") || m.equals("cam=3") || m.equals("close") || m.equals("near") || m.equals("nrd")
                    || m.matches("cx=-?\\d+(\\.\\d+)?") || m.matches("[tbf]\\d+") || m.matches("aim=-?\\d+_-?\\d+"))) {
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
            ctx.fail("rt_light: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_light: no singleplayer server / player", null);
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
                ctx.fail("rt_light: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'));
        ctx.waitFrames(400, () -> step(ctx, steps, 0));
    }

    /** West corner (x) of the room of a scene; rooms are 11 x 11 blocks, interior 9 x 4 x 9, 14 blocks apart. */
    private static int roomX(String scene) {
        return switch (scene) {
            case "R" -> 40;
            case "G" -> 54;
            case "H" -> 68;
            default -> 82;
        };
    }

    private static final int ROOM_Z = 0;

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        for (String scene : new String[] {"R", "G", "H", "D"}) {
            int x0 = roomX(scene);
            c.add(fill(x0, -2, ROOM_Z, x0 + 10, 5, ROOM_Z + 10, "stone_bricks"));
            c.add(fill(x0 + 1, 0, ROOM_Z + 1, x0 + 9, 4, ROOM_Z + 9, "air"));
        }
        // R: two wall torches on the west wall, a standing torch and a lantern on the floor
        int r = roomX("R");
        c.add(set(r + 1, 2, ROOM_Z + 4, "wall_torch[facing=east]"));
        c.add(set(r + 1, 2, ROOM_Z + 8, "wall_torch[facing=east]"));
        c.add(set(r + 8, 0, ROOM_Z + 6, "torch"));
        c.add(set(r + 5, 0, ROOM_Z + 9, "lantern"));
        // G: back row on the floor of the far wall (z+8), front row (z+5)
        int g = roomX("G");
        String[] back = {"glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "redstone_lamp[lit=true]", "magma_block", "copper_bulb[lit=true]"};
        for (int i = 0; i < back.length; i++) {
            c.add(set(g + 2 + i, 0, ROOM_Z + 8, back[i]));
        }
        String[] front = {"torch", "soul_torch", "redstone_torch", "campfire[lit=true]", "lantern", "soul_lantern"};
        for (int i = 0; i < front.length; i++) {
            c.add(set(g + 2 + i, 0, ROOM_Z + 5, front[i]));
        }
        // the floor under the campfire needs no support; H: a plain wall in front of the player (the shell wall at z+10)
        return c;
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
        double y = by;
        double z;
        aimYaw = 0f;
        aimPitch = 12f;
        switch (s.scene()) {
            case "R" -> z = bz + ROOM_Z + 1.5;
            case "G" -> {
                z = s.mods().contains("near") ? bz + ROOM_Z + 3.9 : s.mods().contains("close") ? bz + ROOM_Z + 3.0 : bz + ROOM_Z + 1.5;
                aimPitch = s.mods().contains("near") ? 32f : s.mods().contains("close") ? 25f : 12f;
            }
            case "H" -> {
                z = bz + ROOM_Z + 6.5;   // the wall is 3.5 blocks in front, the player holds the item
                aimPitch = 10f;
            }
            default -> {
                z = bz + ROOM_Z + 1.5;
                aimPitch = 28f;
            }
        }
        String cx = s.value("cx");
        if (cx != null) {
            x += Double.parseDouble(cx);   // camera x shifted by cx blocks (G: -2 is in front of the torches)
        }
        String aim = s.value("aim");
        if (aim != null) {
            String[] yp = aim.split("_");
            aimYaw = Float.parseFloat(yp[0]);
            aimPitch = Float.parseFloat(yp[1]);
        }
        aimOverride = true;
        command(ctx, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, aimYaw, aimPitch));
    }

    private void applySettings(Step s) {
        RtSettings.applyPreset(RtSettings.Preset.LOW);
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
            if (m.equals("nrd")) {
                RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;
            } else if (m.matches("b\\d+")) {
                RtSettings.pathBounces = Integer.parseInt(m.substring(1));
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
        // F1 hides the hand too: only the H scene (item in the hand) keeps the HUD (hotbar) on
        if (mc.gui.hud.isHidden() != !s.scene().equals("H")) {
            mc.gui.hud.toggle();
        }
        int x0 = roomX(s.scene());
        String time = "18000";
        for (String m : s.mods()) {
            if (m.matches("t\\d+")) {
                time = m.substring(1);
            }
        }
        command(ctx, "time set " + time);
        command(ctx, "kill @e[type=item]");
        String item = s.value("item");
        command(ctx, item != null ? "item replace entity @a weapon.mainhand with minecraft:" + item
            : "item replace entity @a weapon.mainhand with minecraft:air");
        String drop = s.value("drop");
        if (drop != null) {
            String[] ids = drop.split("~");
            for (int i = 0; i < ids.length; i++) {
                double dx = bx + x0 + 5.5 + (i - (ids.length - 1) / 2.0) * 1.6;
                command(ctx, String.format(Locale.ROOT,
                    "summon item %.2f %.2f %.2f {Item:{id:\"minecraft:%s\",count:1},PickupDelay:32767,Age:-32768}",
                    dx, by + 0.0, bz + ROOM_Z + 5.0, ids[i]));
            }
        }
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
                ctx.fail("rt_light step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                return;
            }
            ctx.shot(file, () -> step(ctx, steps, index + 1));
        });
    }
}
