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
 * {@code rt_shadow}: shadows and sun/moon light colour across the day. Builds an open-air "shadow garden" (a sand platform with a
 * row of cutout / glass / entity casters, a grass strip, a leaf tree, a stone pillar) next to the spawn with server commands and
 * shoots it at several times of day from several views.
 * <pre>
 *   -Dvkrtx.autotest.shadowSteps=3000:wide,3000:close,12000:wide,18000:wide
 *   -Dvkrtx.autotest.shadowMode=path|rays|off   (default path; off = vanilla frame for before/after comparisons)
 *   -Dvkrtx.autotest.shadowDebug=shadow     (optional RT debug view, e.g. shadow / ao)
 *   -Dvkrtx.autotest.shadowFrames=300       (frames to accumulate before each shot)
 * </pre>
 * A step is {@code <time>:<view>[:<debug view>]} (debug view = RT debug view name: shadow, ao, normals, lit...); time = game time of day (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight);
 * views: {@code wide} (whole row from the south), {@code close} (bush, flowers, grass), {@code glass} (stained glass), {@code high}
 * (tree and pillar from the east), {@code desert} (natural spawn terrain, diamond sword in hand), {@code sun} (looks at the sun/moon), {@code ground} (low angle across the sand).
 */
public final class ShadowScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.shadowSteps";
    public static final String DEFAULT_STEPS = "3000:wide,3000:close,6000:wide,11000:wide,12000:wide,13000:wide,18000:wide";

    private record Step(int time, String view, String debug) {
        String label() {
            return time + "_" + view + (debug.isEmpty() ? "" : "_" + debug);
        }
    }

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private boolean listening;
    private int bx;
    private int by;
    private int bz;

    // the garden centre relative to the player
    private static final int CX = 16;
    private static final int CZ = 16;
    /** The garden floats this high above the player so that the (unflat) terrain never reaches it. */
    private static final int GY = 45;

    @Override
    public String name() {
        return "rt_shadow";
    }

    private static List<Step> parse(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] tv = item.split(":");
            if (tv.length < 2 || tv.length > 3 || !tv[0].matches("[0-9]+") || !tv[1].matches("wide|close|glass|high|sun|ground|desert|redstone|items|hand|fly")
                || (tv.length == 3 && !tv[2].matches("[a-z_]+"))) {
                throw new IllegalArgumentException("step '" + item + "': expected <time>:<wide|close|glass|high|sun|ground|desert|redstone|items|hand|fly>[:<debug view>]");
            }
            steps.add(new Step(Integer.parseInt(tv[0]), tv[1], tv.length == 3 ? tv[2] : ""));
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
            steps = parse(System.getProperty(STEPS_PROPERTY, DEFAULT_STEPS));
        } catch (IllegalArgumentException e) {
            ctx.fail("rt_shadow: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_shadow: no singleplayer server / player", null);
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
                ctx.fail("rt_shadow: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()));
        ctx.waitFrames(400, () -> step(ctx, steps, 0));
    }

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("gamerule advance_weather false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        // clear volume and platform: sand 31 x 31, 12 blocks of air above
        c.add(fill(CX - 15, -1, CZ - 15, CX + 15, 14, CZ + 15, "air"));
        c.add(fill(CX - 15, -4, CZ - 15, CX + 15, -2, CZ + 15, "stone"));   // sand falls: it needs a support
        c.add(fill(CX - 15, -1, CZ - 15, CX + 15, -1, CZ + 15, "sand"));
        // grass strip (z+3..z+5) under the plants
        c.add(fill(CX - 8, -1, CZ + 3, CX + 3, -1, CZ + 5, "grass_block"));
        // row z = CZ: casters
        c.add(set(CX - 8, 0, CZ, "dead_bush"));
        c.add(set(CX - 7, 0, CZ, "cactus"));
        c.add(set(CX - 6, 0, CZ, "cactus"));
        c.add(set(CX - 6, 1, CZ, "cactus"));
        c.add(set(CX - 8, 0, CZ + 4, "oak_sapling"));
        c.add(set(CX - 6, 0, CZ + 4, "poppy"));
        c.add(set(CX - 5, 0, CZ + 4, "dandelion"));
        c.add(set(CX - 4, 0, CZ + 4, "short_grass"));
        c.add(set(CX - 3, 0, CZ + 4, "tall_grass[half=lower]"));
        c.add(set(CX - 3, 1, CZ + 4, "tall_grass[half=upper]"));
        c.add(set(CX - 2, 0, CZ + 4, "fern"));
        c.add(set(CX - 1, 0, CZ + 4, "oak_leaves[persistent=true]"));
        // oak tree: trunk + canopy at x = CX + 1
        c.add(fill(CX + 1, 0, CZ, CX + 1, 3, CZ, "oak_log"));
        c.add(fill(CX - 1, 3, CZ - 2, CX + 3, 4, CZ + 2, "oak_leaves[persistent=true]"));
        c.add(fill(CX, 5, CZ - 1, CX + 2, 5, CZ + 1, "oak_leaves[persistent=true]"));
        // fence + vine on a stone block
        c.add(fill(CX + 5, 0, CZ, CX + 5, 1, CZ, "oak_fence"));
        c.add(fill(CX + 6, 0, CZ, CX + 6, 1, CZ, "oak_fence"));
        c.add(fill(CX + 5, 1, CZ, CX + 6, 1, CZ, "oak_fence"));
        // glass wall and stained glass wall (2 x 2)
        c.add(fill(CX + 8, 0, CZ, CX + 9, 1, CZ, "glass"));
        c.add(fill(CX + 11, 0, CZ, CX + 12, 1, CZ, "red_stained_glass"));
        c.add(fill(CX + 11, 0, CZ + 3, CX + 12, 1, CZ + 3, "blue_stained_glass"));
        c.add(fill(CX + 8, 0, CZ + 3, CX + 9, 1, CZ + 3, "lime_stained_glass"));
        // stone pillar + stone block with vines
        c.add(fill(CX + 3, 0, CZ - 6, CX + 3, 5, CZ - 6, "stone_bricks"));
        c.add(set(CX - 10, 0, CZ + 1, "stone"));
        c.add(set(CX - 10, 1, CZ + 1, "stone"));
        c.add(set(CX - 11, 0, CZ + 1, "vine[east=true]"));
        c.add(set(CX - 11, 1, CZ + 1, "vine[east=true]"));
        // redstone signal line: a redstone block at CX-9 powers 16 dust blocks, signal 15 .. 0 (z = CZ + 10)
        c.add(fill(CX - 10, 0, CZ + 10, CX + 7, 0, CZ + 10, "air"));
        c.add(set(CX - 9, 0, CZ + 10, "redstone_block"));
        for (int i = 0; i < 16; i++) {
            c.add(set(CX - 8 + i, 0, CZ + 10, "redstone_wire[east=side,west=side,north=none,south=none]"));
        }
        // dropped items (z = CZ + 8): glass, iron block, glowstone, gold block, red stained glass, diamond sword
        String[] drops = {"glass", "iron_block", "glowstone", "gold_block", "red_stained_glass", "diamond_sword"};
        for (int i = 0; i < drops.length; i++) {
            c.add(String.format(Locale.ROOT,
                "summon item %.2f %d %.2f {Item:{id:\"minecraft:%s\",count:1},PickupDelay:32767,Age:-32768}",
                bx + CX + 2.5 + i * 1.6, by + GY, bz + CZ + 8.5, drops[i]));
        }
        // entities: armor stand and a dropped item
        c.add(String.format(Locale.ROOT, "summon armor_stand %.1f %d %.1f {NoGravity:1b,Rotation:[90f,0f]}", bx + CX + 14.5, by + GY, bz + CZ - 2.5));
        c.add(String.format(Locale.ROOT, "summon armor_stand %.1f %d %.1f {NoGravity:1b,Rotation:[20f,0f]}", bx + CX - 3.5, by + GY, bz + CZ - 3.5));
        return c;
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + GY + y0, bz + z0, bx + x1, by + GY + y1, bz + z1, block);
    }

    private String set(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", bx + x, by + GY + y, bz + z, block);
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
        double x = bx + CX + 0.5;
        double z = bz + CZ - 12.5;
        aimYaw = 0f;
        aimPitch = 14f;
        switch (s.view()) {
            case "close" -> {
                x = bx + CX - 4.5;
                z = bz + CZ - 3.0;
                aimYaw = -8f;
                aimPitch = 22f;
            }
            case "glass" -> {
                x = bx + CX + 10.5;
                z = bz + CZ - 6.0;
                aimYaw = 0f;
                aimPitch = 20f;
            }
            case "high" -> {
                x = bx + CX + 12.5;
                z = bz + CZ + 0.5;
                aimYaw = 90f;
                aimPitch = 12f;
            }
            case "redstone" -> {
                x = bx + CX - 11.5;
                z = bz + CZ + 9.0;
                aimYaw = -75f;
                aimPitch = 28f;
            }
            case "items", "hand" -> {
                x = bx + CX + 6.0;
                z = bz + CZ + 5.0;
                aimYaw = 0f;
                aimPitch = 24f;
            }
            case "fly" -> {
                // in the air above the garden, looking down at the sand platform (vignette / sky light by distance check)
                x = bx + CX + 0.5;
                z = bz + CZ - 4.5;
                aimYaw = 0f;
                aimPitch = 38f;
            }
            case "desert" -> {
                // the natural terrain at the spawn (stepped sand hill, dead bushes), a diamond sword in the hand
                x = bx + 0.5;
                z = bz + 0.5;
                aimYaw = 135f;
                aimPitch = 12f;
            }
            case "ground" -> {
                x = bx + CX - 12.5;
                z = bz + CZ - 9.0;
                aimYaw = -25f;
                aimPitch = 3f;
            }
            case "sun" -> {
                aimPitch = -15f;
                aimYaw = s.time() < 6000 ? -90f : 90f;   // morning: the sun is in the east (-90 looks east)
            }
            default -> {
            }
        }
        aimOverride = true;
        double y = s.view().equals("desert") ? by : s.view().equals("fly") ? by + GY + 14 : by + GY;
        command(ctx, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, aimYaw, aimPitch));
    }

    private static String modeProp() {
        return System.getProperty("vkrtx.autotest.shadowMode", "path").toLowerCase(Locale.ROOT);
    }

    private static RtMode wantedMode() {
        return switch (modeProp()) {
            case "rays" -> RtMode.RAY_TRACED;
            case "off" -> RtMode.OFF;
            default -> RtMode.PATH_TRACED;
        };
    }

    private void applySettings(Step s) {
        boolean rays = wantedMode() == RtMode.RAY_TRACED;
        RtSettings.applyPreset(rays ? RtSettings.Preset.HIGH : RtSettings.Preset.PATH_TRACING);
        RtSettings.pathAccumulate = true;
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        String dbg = s.debug().isEmpty() ? System.getProperty("vkrtx.autotest.shadowDebug", "") : s.debug();
        RtConfig.setDebugView(dbg);
        RtMode mode = wantedMode();
        VulkanRtxConfig.rtMode = mode;
        VulkanRtxConfig.enableHardwareRayTracing = mode.isActive();
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
        // F1 hides the hand too: only the desert view (sword in the hand) keeps the HUD on
        boolean keepHud = s.view().equals("desert") || s.view().equals("hand");
        if (mc.gui.hud.isHidden() != !keepHud) {
            mc.gui.hud.toggle();
        }
        command(ctx, "item replace entity @a weapon.mainhand with minecraft:" + (s.view().equals("desert") ? "diamond_sword"
            : s.view().equals("hand") ? "glowstone" : "air"));
        command(ctx, "time set " + s.time());
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        applySettings(s);
        int frames = Integer.getInteger("vkrtx.autotest.shadowFrames", 300);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "time", String.valueOf(s.time()), "view", s.view(),
            "frame", String.valueOf(ctx.frames()));
        ctx.waitFrames(frames, () -> {
            RtMode pass = RtFrameRenderer.lastPassMode();
            String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "time", String.valueOf(s.time()), "view", s.view(), "debug", s.debug().isEmpty() ? "-" : s.debug(), "mode", wantedMode().logName(),
                "pass", pass == null ? "none" : pass.logName(), "file", file,
                "player", String.format(Locale.ROOT, "%.1f,%.1f,%.1f yaw=%.1f pitch=%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot()));
            RtMode want = wantedMode();
            if (want.isActive() && pass != want) {
                ctx.fail("rt_shadow step " + index + ": renderer pass is " + pass + ", expected " + want, null);
                return;
            }
            ctx.shot(file, () -> step(ctx, steps, index + 1));
        });
    }
}
