package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.world.RtAtmosphereSettings;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rt.world.RtSky;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code rt_atmo}: A/B pictures of the atmosphere effects (light shafts, bloom, sun glare) in the same session, so the pair differs
 * only by the settings. Scenes are built next to the spawn with server commands:
 * <ul>
 *   <li>{@code F}: forest: a grid of oak trunks with a leaf canopy that has gaps, grass floor (shafts through leaves);</li>
 *   <li>{@code C}: cave tunnel in stone with two skylight shafts and an opening at the far end (shafts through openings);</li>
 *   <li>{@code D}: open sandstone plaza with a lamp, glowstone and sea lantern row (bloom of emissive blocks, sun glare).</li>
 * </ul>
 * Steps: {@code -Dvkrtx.autotest.atmoSteps=F:path+t3000+gr=post,F:path+t3000+gr=off,...}; a step is {@code scene:mode[+modifier...]}, mode
 * {@code rays|path}; modifiers: {@code t<N>} time of day (default 3000), {@code f<N>} frames to wait (default 300), {@code gr=off|post|inline},
 * {@code bloom=on|off}, {@code glare=on|off}, {@code star}, {@code rain} (weather rain), {@code sun=<dyaw>_<dpitch>} aim relative to the sun,
 * {@code aim=<yaw>_<pitch>}, {@code cx=<d> cy=<d> cz=<d>} camera offset from the scene default, {@code dn=nrd|rr} denoiser, {@code fogv=<0..1>} vanilla fog weight, {@code fogaer=<0..2>} aerial perspective, {@code perf}
 * (GPU span times are logged by {@code -Dvkrtx.rt.gpuTimers=true}). Shots: {@code NN_<step>.png}.
 */
public final class AtmosphereScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.atmoSteps";
    public static final String DEFAULT_STEPS = "F:path+t3000+gr=post,F:path+t3000+gr=off,C:path+t3000+gr=post,D:path+t3000+sun=0_0";

    private static final int FX = 0;      // forest centre (offset from the spawn)
    private static final int FZ = 60;
    private static final int CX = 90;     // cave tunnel start
    private static final int DX = -90;    // plaza centre
    private static final int SX = 170;    // slit wall stage: the camera stands east of a 24 high wall with 1 wide slits, the low sun behind it

    public record Step(String scene, RtMode mode, List<String> mods, String raw) {
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
        return "rt_atmo";
    }

    public static List<Step> parseSteps(String text) {
        List<Step> steps = new ArrayList<>();
        for (String raw : text.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            String[] sm = item.split(":", 2);
            if (sm.length != 2 || !(sm[0].equals("F") || sm[0].equals("C") || sm[0].equals("D") || sm[0].equals("S"))) {
                throw new IllegalArgumentException("step '" + item + "': expected F:, C:, D: or S:<mode>");
            }
            String[] parts = sm[1].split("\\+");
            RtMode mode = RtMode.parse(parts[0]);
            if (mode == null || !mode.isActive()) {
                throw new IllegalArgumentException("step '" + item + "': mode must be rays or path, got " + parts[0]);
            }
            List<String> mods = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String m = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!(m.matches("[tf]\\d+") || m.matches("c[xyz]=-?\\d+(\\.\\d+)?") || m.matches("(aim|sun)=-?\\d+(\\.\\d+)?_-?\\d+(\\.\\d+)?")
                    || m.matches("gr=(off|post|inline)") || m.matches("(bloom|glare)=(on|off)") || m.equals("star") || m.equals("rain") || m.equals("seq") || m.matches("(fade|gs|bs)=[0-4]([.][0-9]+)?")
                    || m.equals("perf") || m.matches("dn=(nrd|rr)") || m.matches("gq=[0-3]") || m.matches("fogv=[01](\\.[0-9]+)?") || m.matches("fogaer=[0-2](\\.[0-9]+)?"))) {
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
            ctx.fail("rt_atmo: " + e.getMessage(), e);
            return;
        }
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_atmo: no singleplayer server / player", null);
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
                ctx.fail("rt_atmo: server command failed: " + t, t);
            }
        });
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "commands", String.valueOf(cmds.size()),
            "steps", steps.toString().replace(' ', '_'));
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        ctx.waitFrames(900, () -> step(ctx, steps, 0));
    }

    // ---- scenes (offsets from the spawn block, y relative: 0 = the floor level the player stands at) ---------------------------

    private List<String> buildCommands() {
        List<String> c = new ArrayList<>();
        c.add("gamerule send_command_feedback false");
        c.add("gamerule advance_time false");
        c.add("weather clear");
        c.add("gamemode creative @a");
        c.add("difficulty peaceful");
        // ---- F: forest 41 x 41, trees every 5 blocks; canopy blobs 5 x 5 leave 1 block gaps ---------------------------------
        fillBig(c, FX - 22, -3, FZ - 22, FX + 22, 14, FZ + 22, "air");
        c.add(fill(FX - 22, -2, FZ - 22, FX + 22, -1, FZ + 22, "dirt"));
        c.add(fill(FX - 22, -1, FZ - 22, FX + 22, -1, FZ + 22, "grass_block"));
        for (int ix = -3; ix <= 3; ix++) {
            for (int iz = -3; iz <= 3; iz++) {
                int tx = FX + ix * 6;
                int tz = FZ + iz * 6;
                int h = 6 + ((ix * 7 + iz * 13) & 3);
                c.add(fill(tx, 0, tz, tx, h, tz, "oak_log"));
                c.add(fill(tx - 2, h - 1, tz - 2, tx + 2, h, tz + 2, "oak_leaves[persistent=true]"));
                c.add(fill(tx - 1, h + 1, tz - 1, tx + 1, h + 2, tz + 1, "oak_leaves[persistent=true]"));
            }
        }
        // ---- C: stone block 17 wide, 40 long, 11 high; tunnel 3 x 4; two skylight shafts and an opening at the far end -----------
        fillBig(c, CX - 10, -3, -12, CX + 8, 14, 32, "air");
        c.add(fill(CX - 9, -2, -10, CX + 7, 10, 30, "stone"));
        c.add(fill(CX - 1, 0, -10, CX + 1, 3, 30, "air"));              // tunnel along z
        c.add(fill(CX, 4, 6, CX, 10, 6, "air"));                         // skylight shaft 1x1
        c.add(fill(CX - 1, 4, 16, CX + 1, 10, 17, "air"));               // skylight shaft 3x2
        c.add(fill(CX - 1, 0, 30, CX + 1, 3, 30, "air"));
        c.add(fill(CX - 9, -1, -10, CX + 7, -1, 30, "stone_bricks"));
        // ---- D: plaza 30 x 30: lamps and emissive blocks, pillars -------------------------------------------------------------
        fillBig(c, DX - 15, -3, -15, DX + 15, 12, 15, "air");
        c.add(fill(DX - 15, -2, -15, DX + 15, -1, 15, "sandstone"));
        String[] glow = {"glowstone", "sea_lantern", "shroomlight", "redstone_lamp[lit=true]", "ochre_froglight", "verdant_froglight"};
        for (int i = 0; i < glow.length; i++) {
            c.add(fill(DX - 10 + i * 4, 0, 6, DX - 9 + i * 4, 1, 6, glow[i]));
        }
        for (int i = 0; i < 3; i++) {
            c.add(fill(DX - 8 + i * 8, 0, 12, DX - 8 + i * 8, 6, 12, "quartz_pillar"));
            c.add(set(DX - 8 + i * 8, 7, 12, "lantern"));
        }
        c.add(fill(DX - 6, 0, -8, DX + 6, 0, -8, "white_concrete"));
        // ---- S: slit wall: stone wall 24 high at x = SX - 14, z -20..20, slits every 4 blocks; sandstone floor around the camera ------
        fillBig(c, SX - 20, -3, -24, SX + 12, 26, 24, "air");
        c.add(fill(SX - 20, -2, -24, SX + 12, -1, 24, "sandstone"));
        c.add(fill(SX - 14, 0, -20, SX - 14, 24, 20, "stone_bricks"));
        for (int z = -18; z <= 18; z += 4) {
            c.add(fill(SX - 14, 0, z, SX - 14, 24, z, "air"));
        }
        return c;
    }

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

    private static double num(String v) {
        return v == null ? 0.0 : Double.parseDouble(v);
    }

    /** Feet position and default aim per scene; the sun= / aim= modifiers override the aim (sun= is applied one frame batch later). */
    private void placeCamera(AutotestContext ctx, Step s) {
        double x;
        double z;
        double y = by;
        aimYaw = 0f;
        aimPitch = 4f;
        switch (s.scene()) {
            case "F" -> {
                x = bx + FX + 3.0;
                z = bz + FZ - 20.5;
                aimPitch = -6f;
            }
            case "C" -> {
                x = bx + CX + 0.5;
                z = bz - 8.5;
                aimPitch = 3f;
            }
            case "S" -> {
                x = bx + SX;
                z = bz + 0.5;
                aimYaw = 90f;
                aimPitch = -3f;
            }
            default -> {
                x = bx + DX;
                z = bz - 12.0;
                aimPitch = 8f;
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
        RtSettings.waterStyle = RtSettings.WATER_STYLE_REALISTIC;
        RtSettings.volumetricFogEnabled = true;
        RtSettings.rtFog = true;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        String dn = s.value("dn");
        if ("nrd".equals(dn)) {
            RtSettings.pathDenoiser = RtSettings.PathDenoiser.NRD;
        } else if ("rr".equals(dn)) {
            RtSettings.pathDenoiser = RtSettings.PathDenoiser.DLSS_RR;
        }
        RtConfig.setDebugView("");
        VulkanRtxConfig.rtMode = s.mode();
        VulkanRtxConfig.enableHardwareRayTracing = s.mode().isActive();
        // atmosphere options of this step (everything not named stays at its default, so consecutive steps do not leak)
        RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.POST.ordinal());
        RtAtmosphereSettings.set("godrays_quality", s.value("gq") != null ? Integer.parseInt(s.value("gq")) : 1);
        RtAtmosphereSettings.set("bloom", 1);
        RtAtmosphereSettings.set("sunglare", 1);
        String gr = s.value("gr");
        if (gr != null) {
            RtAtmosphereSettings.set("godrays", RtAtmosphereSettings.GodRays.valueOf(gr.toUpperCase(Locale.ROOT)).ordinal());
        }
        RtAtmosphereSettings.set("fog_vanilla", s.value("fogv") != null ? Double.parseDouble(s.value("fogv")) : 1.0);
        RtAtmosphereSettings.set("fog_aerial", s.value("fogaer") != null ? Double.parseDouble(s.value("fogaer")) : 1.0);
        RtAtmosphereSettings.set("chunk_fade", s.value("fade") != null ? Double.parseDouble(s.value("fade")) : 1.5);
        RtAtmosphereSettings.set("godrays_strength", s.value("gs") != null ? Double.parseDouble(s.value("gs")) : 1.0);
        RtAtmosphereSettings.set("bloom_strength", s.value("bs") != null ? Double.parseDouble(s.value("bs")) : 1.0);
        if ("off".equals(s.value("bloom"))) {
            RtAtmosphereSettings.set("bloom", 0);
        }
        if ("off".equals(s.value("glare"))) {
            RtAtmosphereSettings.set("sunglare", 0);
        }
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
        String time = "3000";
        for (String m : s.mods()) {
            if (m.matches("t\\d+")) {
                time = m.substring(1);
            }
        }
        command(ctx, "time set " + time);
        command(ctx, s.mods().contains("rain") ? "weather rain" : "weather clear");
        applySettings(s);
        ctx.log("scenario", name(), "step", String.valueOf(index), "event", "apply", "spec", s.raw(), "mode", s.mode().logName(),
            "atmo", RtAtmosphereSettings.describe().replace(' ', '_'), "frame", String.valueOf(ctx.frames()));
        // the sun aim needs the sun angle of the renderer for the new time of day: wait for the sky to follow the time first
        ctx.waitFrames(60, () -> {
            String sunMod = s.value("sun");
            if (sunMod != null) {
                float angle = RtFrameRenderer.lastSunAngle();
                if (!Float.isNaN(angle)) {
                    float[] dir = new float[3];
                    RtSky.sunDirection(angle, RtConfig.SUN_FLIP_X, dir);
                    float[] yp = RtSky.aimYawPitch(dir);
                    String[] off = sunMod.split("_");
                    aimYaw = yp[0] + Float.parseFloat(off[0]);
                    aimPitch = yp[1] + Float.parseFloat(off[1]);
                    ctx.log("scenario", name(), "step", String.valueOf(index), "event", "sun_aim", "yaw", String.valueOf(aimYaw),
                        "pitch", String.valueOf(aimPitch));
                }
            }
            ctx.waitFrames(s.frames(), () -> {
                RtMode pass = RtFrameRenderer.lastPassMode();
                String file = String.format(Locale.ROOT, "%02d_%s.png", index, s.label());
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "spec", s.raw(), "mode", s.mode().logName(),
                    "pass", pass == null ? "none" : pass.logName(),
                    "player", String.format(Locale.ROOT, "%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                    "yaw_pitch", String.format(Locale.ROOT, "%.1f,%.1f", aimYaw, aimPitch), "file", file);
                if (pass != s.mode()) {
                    ctx.fail("rt_atmo step " + index + " " + s.raw() + ": renderer pass is " + pass + ", expected " + s.mode(), null);
                    return;
                }
                if (s.mods().contains("seq")) {
                    // emergence sequence: shots at growing delays (new terrain loading after a teleport)
                    ctx.shot(file, () -> ctx.waitFrames(40, () -> ctx.shot(file.replace(".png", "_b.png"),
                        () -> ctx.waitFrames(80, () -> ctx.shot(file.replace(".png", "_c.png"),
                            () -> ctx.waitFrames(160, () -> ctx.shot(file.replace(".png", "_d.png"), () -> step(ctx, steps, index + 1))))))));
                    return;
                }
                ctx.shot(file, () -> step(ctx, steps, index + 1));
            });
        });
    }
}
