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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

/**
 * {@code rt_overlay}: the two vanilla overlays that the ray traced frame has to carry: the red hurt flash of a living entity
 * (LivingEntityRenderState.hasRedOverlay -> OverlayTexture red row) and the block crumbling animation (destroy stages).
 * Steps: {@code -Dvkrtx.autotest.overlaySteps=hurt:path,break:rays}; mode {@code rays|path}. Shots: {@code NN_<step>.png}:
 * hurt: {@code _before} (no hit yet) and {@code _hit} (a few frames after the attack); break: {@code _s<stage>} at the destroy
 * stages 2 and 6. Log lines: {@code [VKB-AUTOTEST] scenario=rt_overlay ...}.
 */
public final class OverlayScenario implements AutotestScenario {
    public static final String STEPS_PROPERTY = "vkrtx.autotest.overlaySteps";
    private static final int OX = 230;   // stage x offset from the spawn block

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_overlay";
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + y0, bz + z0, bx + x1, by + y1, bz + z1, block);
    }

    @Override
    public void run(AutotestContext ctx) {
        String[] steps = System.getProperty(STEPS_PROPERTY, "hurt:rays,break:rays").split(",");
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_overlay: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX();
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> applyAim(ctx));
        String[] cmds = {
            "gamerule send_command_feedback false", "gamerule advance_time false", "weather clear", "difficulty peaceful",
            "time set 6000", "gamemode survival @a", "kill @e[type=!player]",
            fill(OX - 8, -3, -4, OX + 8, 8, 12, "air"),
            fill(OX - 8, -2, -4, OX + 8, -1, 12, "smooth_stone"),
            fill(OX - 1, 0, 3, OX + 1, 2, 3, "stone"),
            fill(OX - 8, 0, 9, OX + 8, 6, 9, "white_concrete"),
        };
        server.execute(() -> {
            for (String c : cmds) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            }
        });
        if (!mc.gui.hud.isHidden()) {
            mc.gui.hud.toggle();
        }
        ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "steps", String.join("_", steps));
        ctx.waitFrames(400, () -> step(ctx, steps, 0));
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

    private void applyMode(String modeName) {
        RtMode mode = RtMode.parse(modeName);
        RtSettings.applyPreset(RtSettings.Preset.LOW);
        RtSettings.pathAccumulate = true;
        RtSettings.pathBounces = 3;
        RtSettings.raysReflections = true;
        RtSettings.autoNormals = true;
        RtSettings.preset = RtSettings.Preset.CUSTOM;
        RtConfig.setDebugView("");
        VulkanRtxConfig.rtMode = mode;
        VulkanRtxConfig.enableHardwareRayTracing = mode.isActive();
    }

    private void step(AutotestContext ctx, String[] steps, int index) {
        if (index >= steps.length) {
            ctx.log("scenario", name(), "event", "done", "steps", String.valueOf(steps.length));
            ctx.finish();
            return;
        }
        String[] kv = steps[index].trim().split(":", 2);
        applyMode(kv.length > 1 ? kv[1] : "rays");
        if (kv[0].equals("hurt")) {
            hurt(ctx, steps, index);
        } else if (kv[0].equals("break")) {
            breakBlock(ctx, steps, index);
        } else {
            ctx.fail("rt_overlay: unknown step " + steps[index], null);
        }
    }

    private void hurt(AutotestContext ctx, String[] steps, int index) {
        var mc = ctx.minecraft();
        aimYaw = 0f;
        aimPitch = 8f;
        aimOverride = true;
        command(ctx, "kill @e[type=!player]");
        command(ctx, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 8", bx + OX + 0.5, by, bz + 0.5));
        command(ctx, String.format(Locale.ROOT, "summon iron_golem %.1f %d %.1f {NoAI:1b,Silent:1b}", bx + OX + 0.5, by, bz + 2.2));
        ctx.waitFrames(300, () -> {
            String tag = "%02d_hurt_%s".formatted(index, steps[index].split(":")[1]);
            ctx.shot(tag + "_before.png", () -> {
                Entity target = findTarget(mc);
                if (target == null) {
                    ctx.fail("rt_overlay: no living target near the player", null);
                    return;
                }
                mc.gameMode.attack(mc.player, target);
                ctx.log("scenario", name(), "step", String.valueOf(index), "event", "attack", "target", target.getType().toString());
                ctx.waitFrames(6, () -> {
                    ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "pass",
                        String.valueOf(RtFrameRenderer.lastPassMode()), "hurtTime", String.valueOf(((LivingEntity) target).hurtTime));
                    ctx.shot(tag + "_hit.png", () -> step(ctx, steps, index + 1));
                });
            });
        });
    }

    private Entity findTarget(net.minecraft.client.Minecraft mc) {
        AABB box = mc.player.getBoundingBox().inflate(6.0);
        List<Entity> list = mc.level.getEntities(mc.player, box);
        for (Entity e : list) {
            if (e instanceof LivingEntity) {
                return e;
            }
        }
        return null;
    }

    private void breakBlock(AutotestContext ctx, String[] steps, int index) {
        var mc = ctx.minecraft();
        aimYaw = 0f;
        aimPitch = 12f;
        aimOverride = true;
        command(ctx, "kill @e[type=!player]");
        command(ctx, String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 12", bx + OX + 0.5, by, bz + 0.5));
        BlockPos pos = new BlockPos(bx + OX, by + 1, bz + 3);
        String tag = "%02d_break_%s".formatted(index, steps[index].split(":")[1]);
        ctx.waitFrames(300, () -> {
            mc.gameMode.startDestroyBlock(pos, Direction.NORTH);
            breakUntil(ctx, steps, index, pos, tag, new int[] {0}, new int[] {2, 6});
        });
    }

    private void breakUntil(AutotestContext ctx, String[] steps, int index, BlockPos pos, String tag, int[] shots, int[] want) {
        var mc = ctx.minecraft();
        ScenarioTime.waitUntil(ctx, () -> {
            mc.gameMode.continueDestroyBlock(pos, Direction.NORTH);
            return mc.gameMode.getDestroyStage() >= want[shots[0]];
        }, 30.0, () -> {
            int stage = mc.gameMode.getDestroyStage();
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "state", "pass",
                String.valueOf(RtFrameRenderer.lastPassMode()), "destroyStage", String.valueOf(stage));
            ctx.shot(tag + "_s" + stage + ".png", () -> {
                shots[0]++;
                if (shots[0] >= want.length) {
                    mc.gameMode.stopDestroyBlock();
                    step(ctx, steps, index + 1);
                } else {
                    breakUntil(ctx, steps, index, pos, tag, shots, want);
                }
            });
        }, () -> ctx.fail("rt_overlay: destroy stage not reached", null));
    }
}
