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
 * {@code rt_lighting}: the lighting regression scene of the owner complaint "too bright, unstable, no god rays" (lava column, torches
 * on grass, daylight, a rock with a hole that lets the sun in). One shot list, each step {@code scene[+modifier...]}:
 * <pre>
 *   -Dvkrtx.autotest.lightingShots=A,B,C,B+t4500
 * </pre>
 * Scenes: {@code A} daylight (default time 4000) outside: lava column between two stone walls, wall and standing torches on short grass,
 * camera 15 blocks away; {@code C} the same at night (18000); {@code B} the inside of a closed rock cave (11 x 7 x 13) with a 4 x 3
 * hole in the roof, camera looking at the sun beam (default time 3000, sun 45 degrees).
 * Modifiers: {@code t<N>} time of day, {@code f<N>} frames to wait (default 300), {@code b<N>} bounces, {@code nrd} NRD denoiser,
 * {@code noacc} no accumulation and no denoiser (raw per frame noise), {@code aim=<yaw>_<pitch>}, {@code cx=<d>} / {@code cz=<d>} camera offset.
 * Each step writes {@code NN_<label>.png} and three more shots {@code _b/_c/_d} two frames apart (flicker metric) and a
 * {@code [VKB-AUTOTEST] scenario=rt_lighting event=state} line with the exposure statistics of the last 120 frames
 * (exposure min / max / mean / frame-to-frame max step, average scene luminance).
 */
public final class LightingScenario implements AutotestScenario {
    public static final String SHOTS_PROPERTY = "vkrtx.autotest.lightingShots";
    public static final String DEFAULT_SHOTS = "A,C,B";

    public record Step(String scene, List<String> mods, String raw) {
        int frames() {
            for (String m : mods) {
                if (m.matches("f\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return 300;
        }

        String value(String key) {
            for (String m : mods) {
                if (m.startsWith(key + "=")) {
                    return m.substring(key.length() + 1);
                }
            }
            return null;
        }

        int time() {
            for (String m : mods) {
                if (m.matches("t\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return switch (scene) {
                case "A" -> 4000;
                case "B" -> 5000;
                default -> 18000;
            };
        }

        String label() {
            return raw.replace('+', '_').replace('=', '-').toLowerCase(Locale.ROOT);
        }
    }

    private static final int X0 = 110;
    private static final int OX = -100;
    private static final int X1 = 150;
    private static final int Z1 = 60;

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private volatile boolean sampling;
    private final List<Float> expSamples = new ArrayList<>();
    private final List<Double> lumSamples = new ArrayList<>();
    private boolean listening;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_lighting";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] parts = item.split("\\+");
            if (!(parts[0].equals("A") || parts[0].equals("B") || parts[0].equals("C"))) {
                throw new IllegalArgumentException("step " + item + ": scene must be A, B or C");
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.equals("nrd") || m.equals("noacc") || m.matches("[tbf]\\d+") || m.matches("aim=-?\\d+_-?\\d+")
                    || m.matches("c[xz]=-?\\d+(\\.\\d+)?"))) {
                    throw new IllegalArgumentException("step " + item + ": unknown modifier " + m);
                }
                mods.add(m);
            }
            steps.add(new Step(parts[0], mods, item));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no steps in " + text);
        }
        return steps;
    }

    @Override
    public void run(AutotestContext ctx) {
        List<Step> steps;
        try {
            steps = parseSteps(System.getProperty(SHOTS_PROPERTY, DEFAULT_SHOTS));
        } catch (IllegalArgumentException e) {
            ctx.fail("rt_lighting: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_lighting: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX();
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        if (!listening) {
            listening = true;
            Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> {
                applyAim(ctx);
                if (sampling) {
                    expSamples.add(RtFrameRenderer.lastExposure());
                    lumSamples.add(RtFrameRenderer.lastAverageLuminance());
                }
            });
        }
        List<String> cmds = buildCommands();
        server.execute(() -> {
            try {
                for (String c : cmds) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                }
            } catch (Throwable t) {
                ctx.fail("rt_lighting: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'));
        ctx.waitFrames(700, () -> step(ctx, steps, 0));
    }

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        // flat pad: dirt, grass on top, air above
        c.add(fill(X0, -5, 0, X1, -2, Z1, "dirt"));
        c.add(fill(X0, -1, 0, X1, -1, Z1, "grass_block"));
        c.add(fill(X0, 0, 0, X1, 14, Z1, "air"));
        c.add(fill(X0, 15, 0, X1, 28, Z1, "air"));
        // --- scene A / C: lava column, walls, torches, grass ---
        c.add(fill(127, -1, 22, 133, -1, 26, "stone_bricks"));
        c.add(fill(128, 0, 25, 132, 8, 25, "stone_bricks"));
        c.add(fill(128, 0, 23, 128, 8, 24, "stone_bricks"));
        c.add(fill(132, 0, 23, 132, 8, 24, "stone_bricks"));
        c.add(fill(130, 0, 24, 130, 8, 24, "lava"));
        c.add(fill(118, 0, 21, 122, 6, 27, "stone_bricks"));       // wall 10 blocks west of the lava
        c.add(fill(138, 0, 21, 142, 6, 27, "stone_bricks"));       // wall 10 blocks east of the lava
        c.add(fill(120, 0, 8, 134, 0, 20, "short_grass"));
        c.add(set(126, 0, 19, "stone_bricks"));
        c.add(set(126, 1, 19, "stone_bricks"));
        c.add(set(126, 2, 19, "stone_bricks"));
        c.add(set(125, 2, 19, "wall_torch[facing=west]"));
        c.add(set(127, 2, 19, "wall_torch[facing=east]"));
        c.add(set(123, 0, 16, "torch"));
        c.add(set(129, 0, 16, "torch"));
        c.add(set(135, 0, 16, "torch"));
        // --- scene B: closed rock with a roof hole (sun enters from the east at 45 degrees at time 3000) ---
        c.add(fill(112, -1, 34, 124, 9, 48, "stone"));
        c.add(fill(113, 0, 35, 123, 7, 47, "air"));
        c.add(fill(116, 8, 39, 119, 9, 41, "air"));
        return c;
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0 + OX, by + y0, bz + z0, bx + x1 + OX, by + y1, bz + z1, block);
    }

    private String set(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d %s", bx + x + OX, by + y, bz + z, block);
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
        double x;
        double z;
        if (s.scene().equals("B")) {
            x = bx + OX + 120.5;
            z = bz + 46.5;
            aimYaw = 140f;
            aimPitch = 12f;
        } else {
            x = bx + OX + 130.5;
            z = bz + 7.5;
            aimYaw = 0f;
            aimPitch = 4f;
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
            if (m.equals("nrd")) {
                RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;
            } else if (m.equals("noacc")) {
                RtSettings.pathAccumulate = false;
            } else if (m.matches("b\\d+")) {
                RtSettings.pathBounces = Integer.parseInt(m.substring(1));
            }
        }
        RtConfig.setDebugView("");
        VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
        VulkanRtxConfig.enableHardwareRayTracing = true;
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.size()));
            ctx.finish();
            return;
        }
        Step s = steps.get(index);
        var mc = ctx.minecraft();
        sampling = false;
        placeCamera(ctx, s);
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        command(ctx, "time set " + s.time());
        command(ctx, "item replace entity @a weapon.mainhand with minecraft:air");
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        applySettings(s);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "frame", String.valueOf(ctx.frames()));
        int wait = s.frames();
        ctx.waitFrames(Math.max(1, wait - 120), () -> {
            expSamples.clear();
            lumSamples.clear();
            sampling = true;
            ctx.waitFrames(120, () -> {
                sampling = false;
                String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(), "pass",
                    String.valueOf(RtFrameRenderer.lastPassMode()), "exposure", exposureStats(), "emitters", RtFrameRenderer.lightTelemetry(),
                    "file", file);
                ctx.shot(file, () -> burst(ctx, steps, index, s, 1));
            });
        });
    }

    /** Three more shots two frames apart: the frame-to-frame difference of the images is the flicker metric. */
    private void burst(AutotestContext ctx, List<Step> steps, int index, Step s, int n) {
        if (n > 3) {
            step(ctx, steps, index + 1);
            return;
        }
        String file = String.format(Locale.ROOT, "%02d_%s_%c.png", index, s.label(), (char) ('a' + n));
        ctx.waitFrames(2, () -> ctx.shot(file, () -> burst(ctx, steps, index, s, n + 1)));
    }

    private String exposureStats() {
        if (expSamples.isEmpty()) {
            return "none";
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double sum = 0;
        double maxStep = 0;
        for (int i = 0; i < expSamples.size(); i++) {
            double v = expSamples.get(i);
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            if (i > 0) {
                maxStep = Math.max(maxStep, Math.abs(Math.log(v / expSamples.get(i - 1)) / Math.log(2.0)));
            }
        }
        double lum = lumSamples.isEmpty() ? 0 : lumSamples.get(lumSamples.size() - 1);
        return String.format(Locale.ROOT, "min=%.3f,max=%.3f,mean=%.3f,maxStepEV=%.4f,avgLum=%.4f,n=%d", min, max, sum / expSamples.size(),
            maxStep, lum, expSamples.size());
    }
}
