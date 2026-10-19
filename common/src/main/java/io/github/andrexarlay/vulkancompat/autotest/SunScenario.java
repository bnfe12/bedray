package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtSky;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;

import java.util.Locale;

/**
 * {@code sun}: sets the time to {@code -Dvkbridge.autotest.sunTime} (default 3000, sun about 45 degrees up), aims the
 * camera at the sun direction the RT renderer actually uses and takes 01_sun_center, 02_sun_right (yaw+25) and
 * 03_sun_away (looks away, shadows). Run with {@code -Dvkbridge.rt.debug=sky} to see the sun disk at the centre of the
 * first shot. Moved from the earlier prototype harness.
 * <p>
 * The core harness re-asserts its base pose after every frame (applyPose runs before step), so the aim is applied by our
 * own post-frame hook ({@code Platforms.get().registerPostFrameHook(HookPriority.LOW, ...)}), which runs after the harness one (NORMAL).
 */
public final class SunScenario implements AutotestScenario {
    private final int sunTime = Integer.parseInt(System.getProperty("vkbridge.autotest.sunTime", "3000"));
    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private boolean listening;

    @Override
    public String name() {
        return "sun";
    }

    @Override
    public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            ctx.fail("sun: no singleplayer server", null);
            return;
        }
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set " + sunTime);
            } catch (Throwable t) {
                ctx.fail("sun: server command failed: " + t, t);
            }
        });
        if (!listening) {
            listening = true;
            Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> applyAim(ctx));
        }
        ctx.waitFrames(60, () -> startSunAim(ctx));
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

    private void startSunAim(AutotestContext ctx) {
        float angle = RtFrameRenderer.lastSunAngle();
        if (Float.isNaN(angle)) {
            ctx.fail("sun: no sun angle yet (RT renderer has not run; is Hardware RT on?)", null);
            return;
        }
        float[] dir = new float[3];
        RtSky.sunDirection(angle, RtConfig.SUN_FLIP_X, dir);
        float[] yp = RtSky.aimYawPitch(dir);
        aimYaw = yp[0];
        aimPitch = yp[1];
        aimOverride = true;
        ctx.log("event", "sun_aim", "sun_angle_raw", String.format(Locale.ROOT, "%.4f", angle),
            "sun_dir", String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", dir[0], dir[1], dir[2]),
            "aim_yaw", String.format(Locale.ROOT, "%.2f", aimYaw),
            "aim_pitch", String.format(Locale.ROOT, "%.2f", aimPitch), "sunTime", String.valueOf(sunTime));
        ctx.waitFrames(40, () -> ctx.shot("01_sun_center.png", () -> {
            aimYaw += 25f;
            ctx.waitFrames(40, () -> ctx.shot("02_sun_right.png", () -> {
                aimYaw = yp[0] + 180f;
                aimPitch = 20f;
                ctx.waitFrames(40, () -> ctx.shot("03_sun_away.png", ctx::finish));
            }));
        }));
    }
}
