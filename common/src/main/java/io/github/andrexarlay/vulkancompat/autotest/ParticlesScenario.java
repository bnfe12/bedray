package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code rt_particles}: shots of the particles of the game inside the ray traced image.
 * <pre>
 *   -Dvkrtx.autotest.particleSteps=flame:rays,flame:rays+off,smoke:rays,explosion:rays,heart:rays,crit:rays,bubble:rays,block:rays,rain:rays
 * </pre>
 * A step is {@code kind:mode[+modifier...]}, mode {@code rays|path|off}. Kinds (all in an open stone yard with a wall; the camera
 * looks at the wall; emitters stand in front of the wall, one behind it (must be hidden) and one beside it (must be visible)):
 * <ul>
 *   <li>{@code flame}, {@code smoke}, {@code explosion}, {@code heart}, {@code crit}, {@code magic} (enchant + witch + end rod):
 *       {@code /particle} emitters repeated every few frames;</li>
 *   <li>{@code bubble}: the camera is inside a block of water, {@code /particle bubble} + {@code bubble_pop};</li>
 *   <li>{@code block}: stone blocks in front of the wall are broken ({@code setblock .. destroy}) every few frames: block-break
 *       particles;</li>
 *   <li>{@code rain}: {@code weather rain} (splashes and the weather particles).</li>
 * </ul>
 * Modifiers: {@code off} (setting "Particles in RT" off: the before shot), {@code night}, {@code f<N>} (frames before the shot,
 * default 150), {@code zoom} (camera 4 blocks from the wall). One {@code [VKB-AUTOTEST] scenario=rt_particles ... event=state} line
 * per step carries {@link RtParticleLayer#telemetry()}.
 */
public final class ParticlesScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.particleSteps";
    public static final String DEFAULT_STEPS = "flame:rays,flame:rays+off,smoke:rays,explosion:rays,heart:rays,crit:rays,magic:rays,bubble:rays,block:rays,rain:rays";
    private static final Set<String> KINDS = Set.of("flame", "smoke", "explosion", "heart", "crit", "magic", "bubble", "block", "rain");

    public record Step(String kind, RtMode mode, List<String> mods, String raw) {
        int frames() {
            for (String m : mods) {
                if (m.matches("f\\d+")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return 150;
        }

        String label() {
            return raw.replace(':', '_').replace('+', '_').toLowerCase(Locale.ROOT);
        }
    }

    private volatile Runnable perFrame;
    private volatile int frameCounter;
    private boolean hooked;
    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_particles";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] km = item.split(":", 2);
            if (km.length != 2 || !KINDS.contains(km[0])) {
                throw new IllegalArgumentException("step '" + item + "': expected <kind>:<mode>, kind in " + KINDS);
            }
            String[] parts = km[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null) {
                throw new IllegalArgumentException("step '" + item + "': unknown mode " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.equals("off") || m.equals("night") || m.equals("zoom") || m.matches("f\\d+"))) {
                    throw new IllegalArgumentException("step '" + item + "': unknown modifier " + m);
                }
                mods.add(m);
            }
            steps.add(new Step(km[0], mode, mods, item));
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
            ctx.fail("rt_particles: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_particles: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX() + 30;
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        if (!hooked) {
            hooked = true;
            Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> postFrame(ctx));
        }
        command(ctx, "gamerule send_command_feedback false");
        command(ctx, "gamerule advance_time false");
        command(ctx, "gamemode creative @a");
        command(ctx, "difficulty peaceful");
        command(ctx, String.format(Locale.ROOT, "forceload add %d %d %d %d", bx - 4, bz - 4, bx + 26, bz + 26));
        ctx.log("scenario", name(), "event", "scene_base", "base", bx + "," + by + "," + bz, "steps", steps.toString().replace(' ', '_'));
        ctx.waitFrames(300, () -> step(ctx, steps, 0));
    }

    private void postFrame(AutotestContext ctx) {
        var player = ctx.minecraft().player;
        if (aimOverride && player != null) {
            player.setYRot(aimYaw);
            player.yRotO = aimYaw;
            player.setXRot(aimPitch);
            player.xRotO = aimPitch;
        }
        Runnable r = perFrame;
        if (r != null && (frameCounter++ % 4) == 0) {
            r.run();
        }
    }

    private void command(AutotestContext ctx, String cmd) {
        var server = ctx.minecraft().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + y0, bz + z0, bx + x1, by + y1, bz + z1, block);
    }

    private String particle(String spec, double x, double y, double z, double dx, double dy, double dz, double speed, int count) {
        return String.format(Locale.ROOT, "particle %s %.2f %.2f %.2f %.2f %.2f %.2f %.3f %d force", spec, bx + x, by + y, bz + z, dx, dy, dz, speed, count);
    }

    /** Yard: stone floor, 12 blocks of air above, a wall of stone bricks at z = 8 (x 4..16). */
    private void buildYard(AutotestContext ctx, Step s) {
        command(ctx, fill(-2, 0, -2, 24, 12, 24, "air"));
        command(ctx, fill(-2, -1, -2, 24, -1, 24, "stone"));
        command(ctx, fill(4, 0, 8, 16, 5, 8, "stone_bricks"));
        command(ctx, "weather clear");
        command(ctx, "time set " + (s.mods().contains("night") ? "18000" : "6000"));
        command(ctx, "kill @e[type=!player]");
    }

    private List<String> emitters(Step s) {
        List<String> c = new ArrayList<>();
        // front (visible), behind the wall (hidden), beside the wall (visible): x 7 / 10 / 19
        double[][] spots = {{7.0, 0.3, 5.0}, {10.0, 0.3, 11.0}, {19.0, 0.3, 11.0}};
        switch (s.kind()) {
            case "flame" -> {
                for (double[] p : spots) {
                    c.add(particle("flame", p[0], p[1], p[2], 0.25, 0.15, 0.25, 0.015, 14));
                    c.add(particle("smoke", p[0], p[1] + 0.6, p[2], 0.2, 0.2, 0.2, 0.01, 6));
                }
            }
            case "smoke" -> {
                for (double[] p : spots) {
                    c.add(particle("large_smoke", p[0], p[1] + 0.5, p[2], 0.3, 0.2, 0.3, 0.02, 10));
                    c.add(particle("campfire_cosy_smoke", p[0], p[1], p[2], 0.1, 0.1, 0.1, 0.03, 2));
                }
            }
            case "explosion" -> {
                for (double[] p : spots) {
                    c.add(particle("explosion", p[0], p[1] + 1.2, p[2], 0.8, 0.4, 0.8, 0.0, 3));
                }
            }
            case "heart" -> {
                for (double[] p : spots) {
                    c.add(particle("heart", p[0], p[1] + 1.2, p[2], 0.5, 0.4, 0.5, 0.0, 4));
                    c.add(particle("angry_villager", p[0] + 0.5, p[1] + 1.2, p[2], 0.3, 0.3, 0.3, 0.0, 1));
                }
            }
            case "crit" -> {
                for (double[] p : spots) {
                    c.add(particle("crit", p[0], p[1] + 1.0, p[2], 0.4, 0.4, 0.4, 0.3, 14));
                    c.add(particle("enchanted_hit", p[0], p[1] + 1.4, p[2], 0.4, 0.4, 0.4, 0.3, 10));
                }
            }
            case "magic" -> {
                for (double[] p : spots) {
                    c.add(particle("enchant", p[0], p[1] + 1.0, p[2], 0.5, 0.4, 0.5, 0.5, 14));
                    c.add(particle("witch", p[0], p[1] + 0.8, p[2], 0.4, 0.4, 0.4, 0.0, 8));
                    c.add(particle("end_rod", p[0], p[1] + 1.2, p[2], 0.3, 0.3, 0.3, 0.02, 6));
                }
            }
            case "bubble" -> {
                double cx = 10.0;
                c.add(particle("bubble", cx, 1.4, 6.0, 0.8, 0.6, 0.8, 0.02, 24));
                c.add(particle("bubble_pop", cx - 1.0, 1.2, 7.0, 0.6, 0.5, 0.6, 0.0, 6));
                c.add(particle("bubble", 7.0, 0.8, 5.0, 0.4, 0.3, 0.4, 0.05, 12));
            }
            case "block" -> {
                // two stone blocks at a time are placed and broken in front of the wall (server side: break particles)
                c.add("setblock " + (bx + 6) + " " + by + " " + (bz + 5) + " stone");
                c.add("setblock " + (bx + 6) + " " + by + " " + (bz + 5) + " air destroy");
                c.add("setblock " + (bx + 9) + " " + by + " " + (bz + 4) + " dirt");
                c.add("setblock " + (bx + 9) + " " + by + " " + (bz + 4) + " air destroy");
                c.add("setblock " + (bx + 12) + " " + by + " " + (bz + 5) + " oak_planks");
                c.add("setblock " + (bx + 12) + " " + by + " " + (bz + 5) + " air destroy");
                c.add(particle("poof", 15.0, 0.5, 5.0, 0.3, 0.2, 0.3, 0.02, 6));
            }
            default -> {
                // rain: the weather itself
            }
        }
        return c;
    }

    private void applySettings(Step s) {
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        RtSettings.raysShadowQuality = 2;
        RtSettings.raysEmissiveDistance = 32;
        RtSettings.pathBounces = 3;
        RtSettings.pathSpp = 1;
        RtSettings.pathAccumulate = true;
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
        RtSettings.autoNormals = true;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        RtSettings.rtParticles = !s.mods().contains("off");
        RtConfig.setDebugView("");
        VulkanRtxConfig.rtMode = s.mode();
        VulkanRtxConfig.enableHardwareRayTracing = s.mode().isActive();
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            perFrame = null;
            aimOverride = false;
            RtSettings.rtParticles = true;
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.size()));
            ctx.finish();
            return;
        }
        Step s = steps.get(index);
        var mc = ctx.minecraft();
        perFrame = null;
        buildYard(ctx, s);
        boolean underwater = s.kind().equals("bubble");
        if (underwater) {
            command(ctx, fill(2, 0, 0, 18, 6, 7, "water"));
        }
        double camZ = s.mods().contains("zoom") ? 3.5 : 0.5;
        aimYaw = 0f;
        aimPitch = underwater ? 6f : 10f;
        aimOverride = true;
        command(ctx, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", bx + 10.5, by + (underwater ? 1.0 : 0.0), bz + camZ, aimYaw, aimPitch));
        if (mc.gui.hud.isHidden() != true) {
            mc.gui.hud.toggle();
        }
        command(ctx, "item replace entity @a weapon.mainhand with minecraft:air");
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        applySettings(s);
        if (s.kind().equals("rain")) {
            command(ctx, "weather rain");
        }
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "mode", s.mode().logName(),
            "particles_setting", String.valueOf(RtSettings.rtParticles), "frame", String.valueOf(ctx.frames()));
        // let the yard build, then emit repeatedly while the frames run
        ctx.waitFrames(60, () -> {
            List<String> cmds = emitters(s);
            perFrame = () -> {
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    for (String c : cmds) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                    }
                });
            };
            RtParticleLayer.resetWindow();
            ctx.waitFrames(s.frames(), () -> {
                RtMode pass = RtFrameRenderer.lastPassMode();
                String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(),
                    "mode", s.mode().logName(), "pass", pass == null ? "none" : pass.logName(),
                    "particles", RtParticleLayer.telemetry().replace(' ', ','),
                    "emitters", RtFrameRenderer.lightTelemetry(), "file", file);
                if (s.mode().isActive() && pass != s.mode()) {
                    ctx.fail("rt_particles step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                    return;
                }
                ctx.shot(file, () -> step(ctx, steps, index + 1));
            });
        });
    }
}
