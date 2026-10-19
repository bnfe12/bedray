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
 * {@code rt_modes}: builds two test scenes next to the spawn with server commands and screenshots them in the RT modes,
 * switched at runtime through the same config fields as the settings page (no restart). Needs
 * {@code -Dvkbridge.rt.geometry=true} so that the Sodium geometry capture runs from the start also for the raster steps.
 * <ul>
 *   <li>scene A (outdoor): stone platform, white wall, water and lava pools, iron/gold/copper/diamond blocks, glowstone,
 *       sea lantern, glass, torches, a plank roof on log pillars (shadow + AO);</li>
 *   <li>scene B (closed room, Cornell-box like): red and green side walls, white back wall/floor/ceiling with a 3x3 skylight,
 *       a white pillar and a glowstone block inside (global illumination, colour bleeding, emissive light).</li>
 * </ul>
 * Steps: {@code -Dvkrtx.autotest.rtSteps=A:off,A:rays,A:rays+ao+soft+q2,...}; a step is {@code scene:mode[+modifier...]},
 * mode {@code off|rays|path}, modifiers {@code ao gi soft refl noshadow noemis q<N> b<N> s<N> noaccum noexp nrd dist<N> dbg=<view> p=<preset> f<N>}
 * (f = frames to wait before the shot; default 90, path 300). Every step resets the RT settings to their defaults first.
 * A step whose renderer pass does not match the mode fails the scenario. Shots: {@code NN_<scene>_<step>.png}.
 */
public final class RtModesScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.rtSteps";
    public static final String DEFAULT_STEPS = "A:off,A:rays,A:rays+ao+soft+q2,A:rays+refl,B:off,B:rays,B:path+b3+f400";
    private static final int TIME_OF_DAY = Integer.getInteger("vkrtx.autotest.rtTime", 2500);

    public record Step(String scene, RtMode mode, List<String> mods, String raw) {
        int frames() {
            for (String m : mods) {
                if (m.startsWith("f")) {
                    return Integer.parseInt(m.substring(1));
                }
            }
            return mode == RtMode.PATH_TRACED ? 300 : 90;
        }

        String label() {
            return raw.replace(':', '_').replace('+', '_').replace('=', '-').toLowerCase(Locale.ROOT);
        }
    }

    private volatile boolean aimOverride;
    /** Scene E floats above the terrain: the player flies (no fall, no gravity jitter of the camera). */
    private volatile boolean flyScene;
    /** Camera motion of the current step (mv=still|walk|turn|strafe), driven once per frame from the post-frame hook. */
    private volatile String motion = "";
    private volatile boolean entMove;
    /** x, y, z, yaw: after a moving capture the camera is held at the pose of the last frame (converged reference of the motion). */
    private volatile double[] holdPose;
    private final java.util.List<double[]> poseLog = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final int[] reqPoseIdx = new int[64];
    private int motionFrame;
    private double motionX;
    private double motionY;
    private double motionZ;
    private float motionYaw;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private boolean listening;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_modes";
    }

    /** Parses the step list; unknown modes/modifiers/scenes throw (the scenario then fails, nothing is skipped). */
    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] sm = item.split(":", 2);
            if (sm.length != 2 || !(sm[0].equals("A") || sm[0].equals("B") || sm[0].equals("C") || sm[0].equals("D") || sm[0].equals("E") || sm[0].equals("F"))) {
                throw new IllegalArgumentException("step '" + item + "': expected A:, B:, C: or D:<mode>");
            }
            String[] parts = sm[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null) {
                throw new IllegalArgumentException("step '" + item + "': unknown mode " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.equals("ao") || m.equals("gi") || m.equals("break") || m.equals("ui") || m.equals("soft") || m.equals("refl") || m.equals("noshadow") || m.equals("noemis")
                    || m.equals("rr") || m.equals("ref") || m.equals("cmp") || m.equals("ent") || m.equals("entabove") || m.equals("noent") || m.equals("label") || m.matches("pack=(on|off)") || m.equals("entmove") || m.matches("mv=(still|walk|turn|strafe|realwalk|realturn)") || m.equals("noaccum") || m.equals("noexp") || m.equals("noautonorm") || m.matches("an\\d+") || m.matches("t\\d+") || m.matches("aim=-?\\d+_-?\\d+") || m.equals("nrd") || m.matches("[qbsf]\\d+") || m.matches("dist\\d+") || m.startsWith("dbg=") || m.matches("up=(off|dlss_[a-z_]+|xess_[a-z_]+|fsr_[a-z_]+|fsr1_[a-z_]+|nis_\\d+|bil_\\d+)") || m.matches("js=(d|-?1_-?1)") || m.matches("shp=[0-9.]+") || m.matches("tm=[a-z0-9_]+") || m.matches("jv=[a-z0-9_]+") || m.matches("p=(off|low|medium|high|ultra|path_tracing|custom)")
                    || m.equals("norefl") || m.matches("water=(vanilla|realistic)") || m.matches("pt=(fast|quality)") || m.matches("den=(off|nrd|rr)"))) {
                    throw new IllegalArgumentException("step '" + item + "': unknown modifier " + m);
                }
                if (m.startsWith("dbg=") && RtConfig.isUnknownMode(m.substring(4))) {
                    throw new IllegalArgumentException("step '" + item + "': unknown debug view " + m.substring(4));
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
            ctx.fail("rt_modes: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_modes: no singleplayer server / player", null);
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
                ctx.fail("rt_modes: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'), "time", String.valueOf(TIME_OF_DAY),
            "geometry_forced", String.valueOf(Boolean.getBoolean("vkbridge.rt.geometry")));
        // F1 view (javap: Gui.hud public final, Hud.isHidden()/toggle()): no chat or hotbar over the comparison shots
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        // the edited sections are rebuilt by Sodium and turned into BLAS before the first step
        ctx.waitFrames(400, () -> step(ctx, steps, 0));
    }

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule doDaylightCycle false");
        c.add("gamerule advance_time false");
        c.add("time set " + TIME_OF_DAY);
        c.add("weather clear");
        c.add("gamemode creative @a");
        // scene A
        c.add(fill(-12, -2, -4, 12, -1, 20, "smooth_stone"));
        c.add(fill(-12, 0, -4, 12, 10, 20, "air"));
        c.add(fill(-12, 0, 20, 12, 6, 20, "white_concrete"));
        c.add(fill(-9, -1, 6, -4, -1, 11, "water"));
        c.add(fill(5, -1, 12, 8, -1, 15, "lava"));
        c.add(set(-3, 0, 8, "diamond_block"));
        c.add(set(-1, 0, 8, "iron_block"));
        c.add(set(1, 0, 8, "gold_block"));
        c.add(set(3, 0, 8, "copper_block"));
        c.add(fill(-1, 0, 17, 0, 1, 18, "glowstone"));
        c.add(set(2, 0, 18, "sea_lantern"));
        c.add(fill(-7, 0, 15, -6, 1, 16, "glass"));
        c.add(set(-10, 0, 18, "torch"));
        c.add(set(10, 0, 18, "torch"));
        c.add(set(6, 0, 18, "lantern"));
        for (int[] p : new int[][] {{4, 4}, {9, 4}, {4, 9}, {9, 9}}) {
            c.add(fill(p[0], 0, p[1], p[0], 3, p[1], "oak_log"));
        }
        c.add(fill(4, 4, 4, 9, 4, 9, "oak_planks"));
        // scene B: closed room, interior x 26..34, y 0..5, z 2..10, skylight in the ceiling
        c.add(fill(23, -2, -1, 37, -1, 13, "white_concrete"));
        c.add(fill(23, 0, -1, 37, 9, 13, "air"));
        c.add(fill(25, 0, 1, 25, 5, 11, "red_wool"));
        c.add(fill(35, 0, 1, 35, 5, 11, "lime_wool"));
        c.add(fill(25, 0, 11, 35, 5, 11, "white_concrete"));
        c.add(fill(25, 0, 1, 35, 5, 1, "white_concrete"));
        c.add(fill(25, 6, 1, 35, 6, 11, "white_concrete"));
        c.add(fill(29, 6, 6, 31, 6, 8, "air"));
        c.add(fill(31, 0, 8, 32, 2, 9, "white_concrete"));
        c.add(set(27, 0, 9, "glowstone"));
        c.add(set(33, 0, 3, "torch"));
        c.add(set(27, 0, 4, "lantern"));
        // scene E (desert, the owner's noise screenshot): sand terraces around a pit, x -20..20, z -10..30, 48 blocks above the base (inside the loaded area), dead bushes
        for (int k = 0; k <= 6; k++) {
            c.add(fill(-20 + 3 * k, -2, 24 + 3 * k, 20 - 3 * k, 6 - k, 64 - 3 * k, "sand"));
            c.add(fill(-20 + 3 * k, 7 - k, 24 + 3 * k, 20 - 3 * k, 16, 64 - 3 * k, "air"));
        }
        for (int[] b : new int[][] {{-17, 43}, {-14, 49}, {-10, 37}, {-5, 28}, {10, 46}, {15, 52}, {5, 59}, {-16, 56}, {0, 44}}) {
            int ring = Math.min(6, Math.min(Math.min(b[0] + 20, 20 - b[0]), Math.min(b[1] - 24, 64 - b[1])) / 3);
            c.add(set(b[0], 7 - ring, b[1], "dead_bush"));
        }
        // scene F (underwater, the owner's "noise under water" report): stone basin x 55..75, z -1..15, water y -9..2, open sky above
        c.add(fill(55, -12, -1, 75, 3, 15, "stone"));
        c.add(fill(56, -11, 0, 74, 2, 14, "water"));
        c.add(fill(55, 4, -1, 75, 12, 15, "air"));
        c.add(fill(56, 3, 0, 74, 3, 14, "air"));
        c.add(fill(62, -11, 9, 66, -9, 12, "sand"));
        c.add(set(60, -10, 7, "sea_lantern"));
        // scene A: more light sources for the night shots
        c.add(set(-4, 0, 2, "torch"));
        c.add(set(4, 0, 2, "torch"));
        c.add(set(0, 0, 13, "lantern"));
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
        if (entMove) {
            // the near husk walks sideways (+X) 0.02 block per frame (server teleport, the client interpolates)
            var server = ctx.minecraft().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                    "execute as @e[type=husk,tag=vkb_mover] at @s run tp @s ~0.02 ~ ~"));
            }
        }
        if (motion.equals("realwalk") || motion.equals("realturn")) {
            // the user's case: real ground walking (W key, gravity, view bobbing option on), no position override
            var o = ctx.minecraft().options;
            o.bobView().set(true);
            player.getAbilities().flying = false;
            o.keyUp.setDown(true);
            if (motion.equals("realturn")) {
                aimYaw += 0.5f;
            }
            player.setYRot(aimYaw);
            player.yRotO = aimYaw - (motion.equals("realturn") ? 0.5f : 0f);
            player.setXRot(aimPitch);
            player.xRotO = aimPitch;
            return;
        }
        if (flyScene) {
            player.getAbilities().flying = true;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
        double[] hold = holdPose;
        if (hold != null) {
            player.getAbilities().flying = true;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.setPos(hold[0], hold[1], hold[2]);
            player.xo = hold[0];
            player.yo = hold[1];
            player.zo = hold[2];
            player.xOld = hold[0];
            player.yOld = hold[1];
            player.zOld = hold[2];
            aimYaw = (float) hold[3];
        } else if (!motion.isEmpty() && !motion.equals("still")) {
            if (motionFrame == 0) {
                motionX = player.getX();
                motionY = player.getY();
                motionZ = player.getZ();
                motionYaw = aimYaw;
            }
            int k = motionFrame++;
            double x = motionX;
            double z = motionZ;
            // yaw 0 looks to +Z: walk = forward (+Z) 0.05 block/frame, strafe = sideways (+X) along the wall, turn = 0.5 deg/frame
            switch (motion) {
                case "walk" -> z += 0.05 * k;
                case "strafe" -> x += 0.05 * k;
                case "turn" -> aimYaw = motionYaw + 0.5f * k;
                default -> { }
            }
            player.getAbilities().flying = true;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.setPos(x, motionY, z);
            player.xo = x;
            player.yo = motionY;
            player.zo = z;
            player.xOld = x;
            player.yOld = motionY;
            player.zOld = z;
            poseLog.add(new double[] {x, motionY, z, aimYaw});
        }
        player.setYRot(aimYaw);
        player.yRotO = aimYaw;
        player.setXRot(aimPitch);
        player.xRotO = aimPitch;
    }

    /** Camera of a scene: A looks south (+Z) over the platform, B stands inside the room near the front wall. */
    private void placeCamera(AutotestContext ctx, String scene) {
        var server = ctx.minecraft().getSingleplayerServer();
        double x;
        double y;
        double z;
        flyScene = false;
        if (scene.equals("A")) {
            x = bx + 0.5;
            y = by;
            z = bz - 3.5;
            aimYaw = 0f;
            aimPitch = 18f;
        } else if (scene.equals("D")) {
            // above the south edge of the water pool of scene A, looking down at the water (reflection of off-screen objects)
            x = bx - 6.5;
            y = by;
            z = bz + 4.0;
            aimYaw = 0f;
            aimPitch = 30f;
        } else if (scene.equals("F")) {
            // inside the water basin, looking along it (+Z) at the sand bank
            x = bx + 65.5;
            y = by - 5;
            z = bz + 2.5;
            aimYaw = 0f;
            aimPitch = 8f;
        } else if (scene.equals("E")) {
            // on the south rim of the sand pit, looking north (-Z) and down at the sunlit terraces
            x = bx + 0.5;
            y = by + 9;
            z = bz + 61.5;
            aimYaw = 180f;
            aimPitch = 28f;
            flyScene = true;
        } else if (scene.equals("F")) {
            // hovering over the near rim of the lava lake, looking south and down at the lava
            x = bx + 65.5;
            y = by + 4;
            z = bz - 4.5;
            aimYaw = 0f;
            aimPitch = 22f;
            flyScene = true;
        } else if (scene.equals("C")) {
            // 300 blocks away (new chunks): high above the ground looking down, the player falls onto the terrain
            x = bx + 300.5;
            y = by + 30;
            z = bz + 0.5;
            aimYaw = 0f;
            aimPitch = 40f;
        } else {
            x = bx + 30.5;
            y = by;
            z = bz + 2.5;
            aimYaw = 0f;
            aimPitch = 12f;
        }
        aimOverride = true;
        String cmd = String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", x, y, z, aimYaw, aimPitch);
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    private static void applySettings(Step s) {
        // defaults of RtSettings (preset Custom), then the modifiers of the step
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        RtSettings.raysShadowQuality = 2;
        RtSettings.raysReflectionDistance = 128;
        RtSettings.raysEmissiveDistance = 32;
        RtSettings.raysGiDistance = 64;
        RtSettings.pathBounces = 3;
        RtSettings.pathSpp = 1;
        RtSettings.pathAccumulate = true;
        RtSettings.pathDistance = 256;
        RtSettings.pathAutoExposure = true;
        RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
        RtSettings.autoNormals = true;
        RtSettings.autoNormalStrength = 100;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        String debug = "";
        for (String m : s.mods()) {
            switch (m) {
                case "ao" -> RtSettings.raysAo = true;
                case "soft" -> RtSettings.raysSoftShadows = true;
                case "refl" -> RtSettings.raysReflections = true;
                case "gi" -> RtSettings.raysGi = true;
                case "noshadow" -> RtSettings.raysShadows = false;
                case "noemis" -> RtSettings.raysEmissive = false;
                case "noaccum" -> {
                    RtSettings.pathAccumulate = false;
                    RtSettings.raysAccumulate = false;
                }
                case "noexp" -> RtSettings.pathAutoExposure = false;
                case "noautonorm" -> RtSettings.autoNormals = false;
                case "break", "ui" -> {
                    // handled in step() / below
                }
                case "nrd" -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;
                // settings-page preview shots (feat/ui-settings): single switches and the PT presets
                case "norefl" -> RtSettings.raysReflections = false;
                case "water=vanilla" -> RtSettings.waterStyle = RtSettings.WATER_STYLE_VANILLA;
                case "water=realistic" -> RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
                case "pt=fast" -> RtSettings.applyPathPreset(RtSettings.PathPreset.FAST);
                case "pt=quality" -> RtSettings.applyPathPreset(RtSettings.PathPreset.QUALITY);
                case "den=off" -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.OFF;
                case "den=nrd" -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;
                case "den=rr" -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.DLSS_RR;
                case "rr" -> RtSettings.pathDenoiser = RtSettings.PathDenoiser.DLSS_RR;
                default -> {
                    if (m.startsWith("an")) {
                        RtSettings.autoNormalStrength = Integer.parseInt(m.substring(2));
                    } else if (m.startsWith("p=")) {
                        RtSettings.applyPreset(RtSettings.Preset.valueOf(m.substring(2).toUpperCase(Locale.ROOT)));
                    } else if (m.startsWith("q")) {
                        int q = Integer.parseInt(m.substring(1));
                        RtSettings.raysShadowQuality = q;
                        RtSettings.raysAoQuality = q;
                        RtSettings.raysEmissiveQuality = q;
                        RtSettings.raysGiQuality = q;
                        RtSettings.raysReflectionQuality = Math.min(4, q);
                    } else if (m.startsWith("b")) {
                        RtSettings.pathBounces = Integer.parseInt(m.substring(1));
                    } else if (m.matches("s\\d+")) {
                        RtSettings.pathSpp = Integer.parseInt(m.substring(1));
                    } else if (m.startsWith("dist")) {
                        int d = Integer.parseInt(m.substring(4));
                        RtSettings.raysReflectionDistance = d;
                        RtSettings.raysGiDistance = Math.min(256, d);
                        RtSettings.pathDistance = d;
                    } else if (m.startsWith("dbg=")) {
                        debug = m.substring(4);
                    }
                }
            }
        }
        RtConfig.setDebugView(debug);
        // jitter sign experiment (js=<sx>_<sy>, d = defaults): handed to DLSS / XeSS / FSR, read by them on every dispatch
        String jsMod = s.mods().stream().filter(m -> m.startsWith("js=")).findFirst().map(m -> m.substring(3)).orElse("d");
        for (String prop : new String[] {"vkbridge.dlss.jitterSign", "vkbridge.xess.jitterSign", "vkrtx.fsr.jitterSign"}) {
            if (jsMod.equals("d")) {
                System.clearProperty(prop + "X");
                System.clearProperty(prop + "Y");
            } else {
                String[] xy = jsMod.split("_");
                System.setProperty(prop + "X", xy[0]);
                System.setProperty(prop + "Y", xy[1]);
            }
        }
        for (String m : s.mods()) {
            if (m.startsWith("shp=")) {
                float sh = Float.parseFloat(m.substring(4));
                VulkanRtxConfig.fsr1Sharpness = sh;
                VulkanRtxConfig.nisSharpness = sh;
                VulkanRtxConfig.fsrSharpness = sh;
            }
        }
        for (String m : s.mods()) {
            if (m.startsWith("up=")) {
                String v = m.substring(3);
                if (v.startsWith("nis_") || v.startsWith("bil_") || v.startsWith("fsr1_")) {
                    // spatial / bilinear variants for the comparison series: nis_<pct>, bil_<pct> (bilinear 3D scale), fsr1_<mode>
                    if (v.startsWith("nis_")) {
                        VulkanRtxConfig.setCustomPercent(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.NIS, Integer.parseInt(v.substring(4)));
                        VulkanRtxConfig.applyUpscalerFull(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.NIS, null, null, null, null,
                            io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode.CUSTOM, "rt_modes");
                    } else if (v.startsWith("fsr1_")) {
                        VulkanRtxConfig.applyUpscalerFull(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.FSR1, null, null, null, null,
                            io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode.valueOf(v.substring(5).toUpperCase(Locale.ROOT)), "rt_modes");
                    } else {
                        int pct = Integer.parseInt(v.substring(4));
                        VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.CUSTOM;
                        VulkanRtxConfig.customScalePercent = pct;
                        VulkanRtxConfig.applyUpscaler(io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.BILINEAR, null, null, "rt_modes");
                    }
                    io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager.applyResolutionScalingNow();
                    continue;
                }
                if (v.equals("off")) {
                    VulkanRtxConfig.qualityPreset = VulkanRtxConfig.QualityPreset.NATIVE;
                }
                io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend b = v.equals("off")
                    ? io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.BILINEAR
                    : io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.valueOf(v.substring(0, v.indexOf('_')).toUpperCase(Locale.ROOT));
                io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode mode = v.equals("off") ? null
                    : io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode.valueOf(v.substring(v.indexOf('_') + 1).toUpperCase(Locale.ROOT));
                VulkanRtxConfig.applyUpscaler(b, b == io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.XESS ? mode : null,
                    b == io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.DLSS ? mode : null,
                    b == io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend.FSR ? mode : null, null, "rt_modes");
                io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager.applyResolutionScalingNow();
            }
        }
        if (s.mods().contains("ui")) {
            // exactly the binding of the settings page ("Tracing" option): mode + engine hooks + Sodium mesh reload
            io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.applyRtModeFromUi(s.mode());
        } else {
            // session only (the runtime fields the settings page writes; nothing is saved by the scenario)
            VulkanRtxConfig.rtMode = s.mode();
            VulkanRtxConfig.enableHardwareRayTracing = s.mode().isActive();
        }
    }

    private volatile int[] stillImg;

    /** Mean absolute luminance gradient (detail energy) of a screen image. */
    private static double sharpness(int[] px, int w, int h) {
        double s = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                float c = ((px[y * w + x] >> 16 & 255) + (px[y * w + x] >> 8 & 255) + (px[y * w + x] & 255)) / 3f;
                float r = ((px[y * w + x + 1] >> 16 & 255) + (px[y * w + x + 1] >> 8 & 255) + (px[y * w + x + 1] & 255)) / 3f;
                float d = ((px[(y + 1) * w + x] >> 16 & 255) + (px[(y + 1) * w + x] >> 8 & 255) + (px[(y + 1) * w + x] & 255)) / 3f;
                s += Math.abs(r - c) + Math.abs(d - c);
                n++;
            }
        }
        return n > 0 ? s / n : 0;
    }

    /** Mean luminance gradient counting only steps above {@code thr} (texture edges; film noise of a few levels is ignored). */
    private static double edgeSharpness(int[] px, int w, int h, float thr) {
        double s = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                float c = luma8(px[y * w + x]);
                float gx = Math.abs(luma8(px[y * w + x + 1]) - c);
                float gy = Math.abs(luma8(px[(y + 1) * w + x]) - c);
                s += (gx > thr ? gx : 0) + (gy > thr ? gy : 0);
                n++;
            }
        }
        return n > 0 ? s / n : 0;
    }

    private static float luma8(int argb) {
        return (((argb >> 16) & 255) + ((argb >> 8) & 255) + (argb & 255)) / 3f;
    }

    /** Immerkaer's fast noise sigma estimate (luminance, 0..255): robust to image structure, overestimates on fine textures. */
    private static double noiseSigma(int[] px, int w, int h) {
        double acc = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                float v = luma8(px[(y - 1) * w + x - 1]) - 2 * luma8(px[(y - 1) * w + x]) + luma8(px[(y - 1) * w + x + 1])
                    - 2 * luma8(px[y * w + x - 1]) + 4 * luma8(px[y * w + x]) - 2 * luma8(px[y * w + x + 1])
                    + luma8(px[(y + 1) * w + x - 1]) - 2 * luma8(px[(y + 1) * w + x]) + luma8(px[(y + 1) * w + x + 1]);
                acc += Math.abs(v);
                n++;
            }
        }
        return n > 0 ? acc / n * Math.sqrt(Math.PI / 2.0) / 6.0 : 0;
    }

    /**
     * Sparkle (firefly) metric between two consecutive frames, in per mille of the pixels: a pixel whose luminance jumps by more
     * than 48/255 while the mean of its 8 neighbours moved by less than 16/255 (isolated flicker; camera motion between two
     * frames is 0.05 block, so real edges shift the neighbourhood too and are not counted). Lava/emissive "sypyat" shows here.
     */
    static double sparkPermille(int[] a, int[] b, int w, int h) {
        long hit = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y += 1) {
            for (int x = 1; x < w - 1; x += 1) {
                int i = y * w + x;
                float ya = luma8(a[i]);
                float yb = luma8(b[i]);
                float na = (luma8(a[i - 1]) + luma8(a[i + 1]) + luma8(a[i - w]) + luma8(a[i + w])
                    + luma8(a[i - w - 1]) + luma8(a[i - w + 1]) + luma8(a[i + w - 1]) + luma8(a[i + w + 1])) / 8f;
                float nb = (luma8(b[i - 1]) + luma8(b[i + 1]) + luma8(b[i - w]) + luma8(b[i + w])
                    + luma8(b[i - w - 1]) + luma8(b[i - w + 1]) + luma8(b[i + w - 1]) + luma8(b[i + w + 1])) / 8f;
                if (Math.abs(yb - ya) > 48f && Math.abs(nb - na) < 16f) {
                    hit++;
                }
                n++;
            }
        }
        return n > 0 ? 1000.0 * hit / n : 0;
    }

    private static double meanAbsDiff(int[] a, int[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += Math.abs(luma8(a[i]) - luma8(b[i]));
        }
        return n > 0 ? s / n : 0;
    }

    private static double rmse(int[] a, int[] b) {
        double s = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            double d = luma8(a[i]) - luma8(b[i]);
            s += d * d;
        }
        return n > 0 ? Math.sqrt(s / n) : 0;
    }

    private final java.util.Map<String, int[]> stillRefs = new java.util.HashMap<>();

    /**
     * Standing-camera noise metrics of the step (modifiers {@code ref} / {@code cmp}): 5 consecutive screenshots. {@code flicker} =
     * mean |frame k - frame k+1| (a still camera: pure temporal noise of the final image), {@code noise_sigma} = Immerkaer estimate,
     * {@code edge} = edge detail energy. {@code ref} stores the last frame as the converged reference of the scene (use a long
     * wait, f400+); {@code cmp} reports mean abs error / RMSE of the last frame against it.
     */
    private void captureStillMetrics(AutotestContext ctx, Step s, int index, Runnable then) {
        final int n = 5;
        final int[][] imgs = new int[n][];
        final int[] size = new int[2];
        var mc = ctx.minecraft();
        final int[] k = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            int idx = k[0]++;
            if (idx >= n) {
                ctx.waitFrames(6, () -> {
                    try {
                        int w = size[0];
                        int h = size[1];
                        double flick = 0;
                        for (int i = 1; i < n; i++) {
                            flick += meanAbsDiff(imgs[i], imgs[i - 1]);
                        }
                        flick /= (n - 1);
                        String sceneKey = s.scene();
                        int[] last = imgs[n - 1];
                        if (s.mods().contains("ref")) {
                            stillRefs.put(sceneKey, last.clone());
                        }
                        int[] ref = stillRefs.get(sceneKey);
                        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "still_metrics", "spec", s.raw(),
                            "flicker", String.format(Locale.ROOT, "%.3f", flick),
                            "noise_sigma", String.format(Locale.ROOT, "%.3f", noiseSigma(last, w, h)),
                            "edge", String.format(Locale.ROOT, "%.3f", edgeSharpness(last, w, h, 12f)),
                            "sharp", String.format(Locale.ROOT, "%.3f", sharpness(last, w, h)),
                            "err_vs_ref", ref == null || ref == last ? "n/a" : String.format(Locale.ROOT, "%.3f", meanAbsDiff(last, ref)),
                            "rmse_vs_ref", ref == null || ref == last ? "n/a" : String.format(Locale.ROOT, "%.3f", rmse(last, ref)),
                            "hist_pct", String.format(Locale.ROOT, "%.1f", RtFrameRenderer.lastHistoryAcceptedPct()),
                            "accum_frames", String.valueOf(RtFrameRenderer.lastAccumulatedFrames()));
                    } catch (Throwable t) {
                        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "still_metrics", "result", "error;" + t);
                    }
                    then.run();
                });
                return;
            }
            net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (com.mojang.blaze3d.platform.NativeImage ni = image) {
                    size[0] = ni.getWidth();
                    size[1] = ni.getHeight();
                    imgs[idx] = ni.getPixels().clone();
                }
            });
            ctx.waitFrames(2, next[0]);
        };
        next[0].run();
    }

    private static final int STAB_FRAMES = 12;
    /** RT reference sequences per scene/motion, so that a step with RT off can score the same camera path. */
    private final java.util.Map<String, java.util.List<io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.Reference>> stabRefs = new java.util.HashMap<>();

    /**
     * Temporal stability while the camera moves: STAB_FRAMES consecutive screenshots of the main target, scored pairwise by
     * {@link io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe} against the per-frame RT references (ray distance +
     * matrices). Logs {@code [VKB-AUTOTEST] event=stability}, writes the last pair and the abs-difference image next to the shot.
     */
    private void captureStability(AutotestContext ctx, Step s, int index, String file, Runnable then) {
        final int[][] imgs = new int[STAB_FRAMES][];
        final int[] size = new int[2];
        final java.util.concurrent.atomic.AtomicInteger got = new java.util.concurrent.atomic.AtomicInteger();
        var mc = ctx.minecraft();
        String key = s.scene() + "_" + motion;
        poseLog.clear();
        io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.startSequence(STAB_FRAMES + 4);
        Runnable[] next = new Runnable[1];
        final int[] k = {0};
        next[0] = () -> {
            final int idx = k[0]++;
            if (idx >= STAB_FRAMES) {
                ctx.waitFrames(10, () -> finishStability(ctx, s, index, file, key, imgs, size, got, then));
                return;
            }
            var pl = mc.player;
            reqPoseIdx[idx] = poseLog.size();
            ctx.log("scenario", name(), "event", "stab_frame", "i", String.valueOf(idx), "frame", String.valueOf(ctx.frames()),
                "pos", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", pl.getX(), pl.getY(), pl.getZ()),
                "rot", String.format(Locale.ROOT, "%.2f,%.2f", pl.getYRot(), pl.getXRot()), "onGround", String.valueOf(pl.onGround()),
                "proj_m11", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelProjectionOr(null) == null ? 0f
                    : io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelProjectionOr(null).m11()));
            net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                try (com.mojang.blaze3d.platform.NativeImage ni = image) {
                    size[0] = ni.getWidth();
                    size[1] = ni.getHeight();
                    imgs[idx] = ni.getPixels().clone();
                    got.incrementAndGet();
                }
            });
            ctx.waitFrames(1, next[0]);
        };
        next[0].run();
    }

    private void finishStability(AutotestContext ctx, Step s, int index, String file, String key, int[][] imgs, int[] size,
                                 java.util.concurrent.atomic.AtomicInteger got, Runnable then) {
        try {
            var refs = io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.sequence();
            if (refs.isEmpty()) {
                refs = stabRefs.getOrDefault(key, java.util.List.of());
            } else {
                stabRefs.put(key, refs);
            }
            if (got.get() < STAB_FRAMES || refs.size() < STAB_FRAMES + 4) {
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "stability", "spec", s.raw(),
                    "result", "unavailable;screens=" + got.get() + ";references=" + refs.size());
                then.run();
                return;
            }
            int sw = size[0];
            int sh = size[1];
            double bestErr = Double.MAX_VALUE;
            double bestNaive = 0;
            double bestCov = 0;
            double bestEdge = 0;
            double bestFlat = 0;
            double bestEdgeShare = 0;
            int bestOff = 0;
            int bestPairs = 0;
            // the screenshot of request i shows RT frame i + off (readback latency is not fixed): take the best alignment
            for (int off = 0; off <= 3; off++) {
                double sErr = 0;
                double sNaive = 0;
                double sCov = 0;
                double sEdge = 0;
                double sFlat = 0;
                double sShare = 0;
                int pairs = 0;
                for (int i = 1; i < STAB_FRAMES; i++) {
                    double[] r = io.github.andrexarlay.vulkancompat.rt.denoise.StabilityProbe.score(refs.get(i + off), imgs[i - 1], imgs[i], sw, sh);
                    if (r[0] >= 0) {
                        sErr += r[0];
                        sNaive += r[1];
                        sCov += r[2];
                        sEdge += r[3];
                        sFlat += r[4];
                        sShare += r[5];
                        pairs++;
                    }
                }
                if (pairs > 0 && sErr / pairs < bestErr) {
                    bestErr = sErr / pairs;
                    bestNaive = sNaive / pairs;
                    bestCov = sCov / pairs;
                    bestEdge = sEdge / pairs;
                    bestFlat = sFlat / pairs;
                    bestEdgeShare = sShare / pairs;
                    bestOff = off;
                    lastAlignOffset = off;
                    bestPairs = pairs;
                }
            }
            double spark = 0;
            for (int i = 1; i < STAB_FRAMES; i++) {
                spark += sparkPermille(imgs[i - 1], imgs[i], sw, sh);
            }
            spark /= (STAB_FRAMES - 1);
            java.nio.file.Path dir = ctx.minecraft().gameDirectory.toPath().resolve("autotest").resolve(name());
            String base = file.replace(".png", "");
            writePng(dir.resolve(base + "_mA.png"), imgs[STAB_FRAMES - 2], sw, sh, null);
            writePng(dir.resolve(base + "_mB.png"), imgs[STAB_FRAMES - 1], sw, sh, null);
            writePng(dir.resolve(base + "_mdiff.png"), imgs[STAB_FRAMES - 1], sw, sh, imgs[STAB_FRAMES - 2]);
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "stability", "spec", s.raw(), "motion", motion,
                "pairs", String.valueOf(bestPairs), "offset", String.valueOf(bestOff),
                "stab_err", String.format(Locale.ROOT, "%.3f", bestErr), "naive_err", String.format(Locale.ROOT, "%.3f", bestNaive),
                "covered", String.format(Locale.ROOT, "%.2f", bestCov),
                "err_edge", String.format(Locale.ROOT, "%.3f", bestEdge), "err_flat", String.format(Locale.ROOT, "%.3f", bestFlat),
                "edge_share", String.format(Locale.ROOT, "%.2f", bestEdgeShare),
                "spark_pm", String.format(Locale.ROOT, "%.3f", spark),
                "hist_pct", String.format(Locale.ROOT, "%.1f", RtFrameRenderer.lastHistoryAcceptedPct()),
                "sharp_still", stillImg == null ? "n/a" : String.format(Locale.ROOT, "%.3f", sharpness(stillImg, sw, sh)),
                "sharp_moving", String.format(Locale.ROOT, "%.3f", sharpness(imgs[STAB_FRAMES - 1], sw, sh)),
                "edge_still", stillImg == null ? "n/a" : String.format(Locale.ROOT, "%.3f", edgeSharpness(stillImg, sw, sh, 12f)),
                "edge_moving", String.format(Locale.ROOT, "%.3f", edgeSharpness(imgs[STAB_FRAMES - 1], sw, sh, 12f)),
                "noise_still", stillImg == null ? "n/a" : String.format(Locale.ROOT, "%.3f", noiseSigma(stillImg, sw, sh)),
                "noise_moving", String.format(Locale.ROOT, "%.3f", noiseSigma(imgs[STAB_FRAMES - 1], sw, sh)),
                "screen", sw + "x" + sh);
        } catch (Throwable t) {
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "stability", "spec", s.raw(), "result", "error;" + t);
        }
        movingReference(ctx, s, index, imgs[STAB_FRAMES - 1], size, then);
    }

    /**
     * Error of the last moving frame against the converged image of the SAME pose: the camera is held at the pose that frame was
     * rendered from (walk/turn paths are deterministic per frame), accumulates for {@code REF_FRAMES}, one screenshot is compared
     * ({@code event=moving_error}: err = mean abs luminance error, rmse). Covers noise, lag and blur of the motion in one number.
     */
    private void movingReference(AutotestContext ctx, Step s, int index, int[] movingImg, int[] size, Runnable then) {
        if (motion.startsWith("real") || movingImg == null || poseLog.isEmpty()) {
            then.run();
            return;
        }
        int off = lastAlignOffset;
        int pi = Math.min(poseLog.size() - 1, reqPoseIdx[STAB_FRAMES - 1] + off);
        double[] pose = poseLog.get(pi);
        holdPose = pose;
        motion = "";
        ctx.waitFrames(REF_FRAMES, () -> net.minecraft.client.Screenshot.takeScreenshot(ctx.minecraft().gameRenderer.mainRenderTarget(), image -> {
            try (com.mojang.blaze3d.platform.NativeImage ni = image) {
                int[] ref = ni.getPixels().clone();
                try {
                    writePng(ctx.minecraft().gameDirectory.toPath().resolve("autotest").resolve(name())
                        .resolve(String.format(Locale.ROOT, "%02d_%s_mref.png", index, s.label())), ref, ni.getWidth(), ni.getHeight(), null);
                } catch (java.io.IOException e) {
                    ctx.log("scenario", name(), "event", "moving_error", "result", "png_error;" + e);
                }
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "moving_error", "spec", s.raw(),
                    "pose_idx", String.valueOf(pi), "err", String.format(Locale.ROOT, "%.3f", meanAbsDiff(movingImg, ref)),
                    "rmse", String.format(Locale.ROOT, "%.3f", rmse(movingImg, ref)),
                    "edge_ref", String.format(Locale.ROOT, "%.3f", edgeSharpness(ref, ni.getWidth(), ni.getHeight(), 12f)),
                    "edge_moving", String.format(Locale.ROOT, "%.3f", edgeSharpness(movingImg, ni.getWidth(), ni.getHeight(), 12f)));
            } catch (Throwable t) {
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "moving_error", "result", "error;" + t);
            }
            holdPose = null;
            ctx.waitFrames(3, then);
        }));
    }

    private static final int REF_FRAMES = Integer.getInteger("vkrtx.autotest.refFrames", 300);
    private volatile int lastAlignOffset;
    private static void writePng(java.nio.file.Path p, int[] px, int w, int h, int[] diffAgainst) throws java.io.IOException {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            if (diffAgainst != null) {
                int o = diffAgainst[i];
                int dr = Math.min(255, 4 * Math.abs(((c >> 16) & 255) - ((o >> 16) & 255)));
                int dg = Math.min(255, 4 * Math.abs(((c >> 8) & 255) - ((o >> 8) & 255)));
                int db = Math.min(255, 4 * Math.abs((c & 255) - (o & 255)));
                c = (dr << 16) | (dg << 8) | db;
            }
            bi.setRGB(i % w, i / w, c & 0xFFFFFF);
        }
        java.nio.file.Files.createDirectories(p.getParent());
        javax.imageio.ImageIO.write(bi, "png", p.toFile());
    }

    private void step(AutotestContext ctx, List<Step> steps, int index) {
        if (index >= steps.size()) {
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.size()));
            ctx.finish();
            return;
        }
        Step s = steps.get(index);
        motion = "";
        ctx.minecraft().options.keyUp.setDown(false);
        motionFrame = 0;
        entMove = false;
        placeCamera(ctx, s.scene());
        for (String m : s.mods()) {
            if (m.equals("break")) {
                // remove blocks of scene A in front of the camera: the RT image must lose them too
                var server = ctx.minecraft().getSingleplayerServer();
                List<String> cmds = List.of(fill(-1, 0, 17, 0, 1, 18, "air"), set(-1, 0, 8, "air"), set(1, 0, 8, "air"));
                server.execute(() -> cmds.forEach(c -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c)));
            }
            if (m.startsWith("pack=")) {
                // resource pack switch at runtime (the user's path: options -> resource packs): RT entity atlas must rebake
                var repo = ctx.minecraft().getResourcePackRepository();
                repo.reload();
                java.util.List<String> sel = new java.util.ArrayList<>(repo.getSelectedIds());
                sel.remove("file/vkb_entity_tex_test");
                if (m.equals("pack=on")) {
                    sel.add("file/vkb_entity_tex_test");
                }
                repo.setSelected(sel);
                ctx.minecraft().reloadResourcePacks();
                ctx.log("scenario", name(), "event", "pack_switch", "selected", String.join("|", repo.getSelectedIds()),
                    "available", String.valueOf(repo.getAvailableIds().contains("file/vkb_entity_tex_test")));
            }
            if (m.equals("label")) {
                // standing sign facing the camera of scene A with three lines of text (sign text in the RT scene)
                var server = ctx.minecraft().getSingleplayerServer();
                String cmd = set(-2, 0, 2, "oak_sign[rotation=8]{front_text:{messages:[\"RTX TEXT\",\"Sign 123\",\"abc xyz\",\"\"]}}");
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
            }
            if (m.equals("noent")) {
                var server = ctx.minecraft().getSingleplayerServer();
                server.execute(() -> { server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=!player]"); server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=item]"); });
            }
            if (m.equals("entabove")) {
                // husk floating ABOVE the view of scene D (not on screen): visible only in a ray-traced water reflection
                var server = ctx.minecraft().getSingleplayerServer();
                List<String> cmds = List.of("difficulty easy", "kill @e[type=!player]", "kill @e[type=item]",
                    String.format(Locale.ROOT, "summon husk %.1f %.1f %.1f {NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Rotation:[180f,0f]}",
                        bx - 6.5, by + 4.7, bz + 19.0),
                    // chest (block entity) floating above the view next to the husk: off-screen block entity in the RT scene
                    set(-9, 5, 19, "chest[facing=north]"),
                    // chest BEHIND the camera, other section: culled with its section, must come from OffscreenBlockEntities
                    set(-6, 0, -14, "chest[facing=north]"));
                server.execute(() -> cmds.forEach(c -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c)));
            }
            if (m.equals("ent")) {
                // entity test set in front of the camera of scene A: zombie (no AI), chest (block entity), dropped item,
                // armor stand, and a zombie behind the water pool (reflection)
                var server = ctx.minecraft().getSingleplayerServer();
                List<String> cmds = List.of(
                    "difficulty easy",
                    "kill @e[type=!player]",
                    String.format(Locale.ROOT, "summon husk %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f],Tags:[\"vkb_mover\"]}", bx + 2.5, by, bz + 3.5),
                    String.format(Locale.ROOT, "summon armor_stand %.1f %d %.1f {Rotation:[180f,0f],ShowArms:1b}", bx - 1.5, by, bz + 4.5),
                    String.format(Locale.ROOT, "summon item %.1f %d %.1f {Item:{id:\"minecraft:diamond_sword\",count:1},PickupDelay:32767,Age:-32768}", bx + 0.5, by, bz + 1.5),
                    String.format(Locale.ROOT, "summon husk %.1f %d %.1f {NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}", bx - 6.5, by, bz + 12.5),
                    set(4, 0, 2, "chest[facing=north]"));
                server.execute(() -> cmds.forEach(c -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c)));
            }
            if (m.startsWith("aim=")) {
                // camera direction override of the step: aim=<yaw>_<pitch> (degrees, pitch < 0 looks up), e.g. sky shots
                String[] yp = m.substring(4).split("_");
                aimYaw = Float.parseFloat(yp[0]);
                aimPitch = Float.parseFloat(yp[1]);
            }
            if (m.matches("t\\d+")) {
                var server = ctx.minecraft().getSingleplayerServer();
                String cmd = "time set " + m.substring(1);
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
            }
        }
        applySettings(s);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "mode", s.mode().logName(),
            "frame", String.valueOf(ctx.frames()));
        ctx.waitFrames(s.frames(), () -> {
            RtMode pass = RtFrameRenderer.lastPassMode();
            String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(),
                "mode", s.mode().logName(), "pass", pass == null ? "none" : pass.logName(),
                "displayed", String.valueOf(RtConfig.displayed()),
                "reg_sections", String.valueOf(io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry.INSTANCE.sectionCount()),
                "reg_added", String.valueOf(io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry.INSTANCE.addedCount()),
                "reg_removed", String.valueOf(io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry.INSTANCE.removedCount()),
                "reg_replaced", String.valueOf(io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry.INSTANCE.replacedCount()),
                "player", String.format(Locale.ROOT, "%.1f,%.1f,%.1f", ctx.minecraft().player.getX(), ctx.minecraft().player.getY(),
                    ctx.minecraft().player.getZ()),
                "upscaler", String.valueOf(io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.active()),
                "upscaler_input", io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.isActive()
                    ? io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.inputWidth() + "x"
                    + io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler.inputHeight() : "-",
                "accum_frames", String.valueOf(RtFrameRenderer.lastAccumulatedFrames()),
                "hist_pct", String.format(Locale.ROOT, "%.1f", RtFrameRenderer.lastHistoryAcceptedPct()),
                "entity_capture", String.format(Locale.ROOT, "hooks=%d,seen=%d,captured=%d,vertices=%d,indices=%d",
                    io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().getHookInvocations(),
                    io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().getEntitiesSeen(),
                    io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().getEntitiesCaptured(),
                    io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().getTotalVertices(),
                    io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().getTotalIndices()),
                "be_offscreen", String.valueOf(io.github.andrexarlay.vulkancompat.rt.entity.OffscreenBlockEntities.lastAdded()),
                "entity_as", io.github.andrexarlay.vulkancompat.rt.world.RtWorld.lastEntityTelemetry.replace(' ', ';'),
                "file", file);
            if (s.mode().isActive() && pass != s.mode()) {
                ctx.fail("rt_modes step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                return;
            }
            String mv = s.mods().stream().filter(m -> m.startsWith("mv=")).findFirst().orElse(null);
            if (mv == null) {
                if (s.mods().contains("ref") || s.mods().contains("cmp")) {
                    captureStillMetrics(ctx, s, index, () -> ctx.shot(file, () -> step(ctx, steps, index + 1)));
                } else {
                    ctx.shot(file, () -> step(ctx, steps, index + 1));
                }
                return;
            }
            // the camera starts moving now (frames before: still, history converged), the probe reads one DLSS-RR frame
            stillImg = null;
            net.minecraft.client.Screenshot.takeScreenshot(ctx.minecraft().gameRenderer.mainRenderTarget(), image -> {
                try (com.mojang.blaze3d.platform.NativeImage ni = image) {
                    stillImg = ni.getPixels().clone();
                }
            });
            motionFrame = 0;
            motion = mv.substring(3);
            entMove = s.mods().contains("entmove");
            ctx.waitFrames(20, () -> {
                java.nio.file.Path dir = ctx.minecraft().gameDirectory.toPath().resolve("autotest").resolve(name());
                io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.arm(dir, String.format(Locale.ROOT, "%02d_%s", index, s.label()));
                ctx.waitFrames(10, () -> {
                    ctx.log("scenario", name(), "step", String.valueOf(index), "event", "motion_probe", "motion", motion,
                        "hist_pct", String.format(Locale.ROOT, "%.1f", RtFrameRenderer.lastHistoryAcceptedPct()),
                        "result", String.valueOf(io.github.andrexarlay.vulkancompat.rt.denoise.MotionProbe.lastResult()).replace(' ', ';'));
                    captureStability(ctx, s, index, file, () -> ctx.shot(file, () -> step(ctx, steps, index + 1)));
                });
            });
        });
    }
}
