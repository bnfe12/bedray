package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkanbridge.platform.HookPriority;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.Locale;

/**
 * {@code rt_hand}: first-person hand and inventory icons in the ray traced frame. Builds a lit wall with a glass block next to it,
 * puts an item into the main hand and takes shots: hand with HUD, then the inventory screen (icons).
 * Items: {@code -Dvkrtx.autotest.handItems=glass,torch,lantern} (default {@code glass,torch}). The render settings of the instance are
 * kept (use the denoiser the instance has, the hand is judged only with RR or NRD on). Shots {@code NN_hand_<item>.png},
 * {@code NN_inv_<item>.png}; log lines {@code [VKB-AUTOTEST] scenario=rt_hand ...}.
 */
public final class HandScenario implements AutotestScenario {
    public static final String ITEMS_PROPERTY = "vkrtx.autotest.handItems";
    public static final String TIME_PROPERTY = "vkrtx.autotest.handTime";
    private static final int OX = 260;

    private volatile boolean aimOverride;
    private volatile float aimYaw;
    private volatile float aimPitch;
    private int bx;
    private int by;
    private int bz;

    @Override
    public String name() {
        return "rt_hand";
    }

    private String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", bx + x0, by + y0, bz + z0, bx + x1, by + y1, bz + z1, block);
    }

    private void command(AutotestContext ctx, String cmd) {
        var server = ctx.minecraft().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    @Override
    public void run(AutotestContext ctx) {
        String[] items = System.getProperty(ITEMS_PROPERTY, "glass,torch").split(",");
        var mc = ctx.minecraft();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            ctx.fail("rt_hand: no singleplayer server / player", null);
            return;
        }
        bx = mc.player.getBlockX();
        by = mc.player.getBlockY();
        bz = mc.player.getBlockZ();
        Platforms.get().registerPostFrameHook(HookPriority.LOW, () -> {
            var p = ctx.minecraft().player;
            if (aimOverride && p != null) {
                p.setYRot(aimYaw); p.yRotO = aimYaw; p.setXRot(aimPitch); p.xRotO = aimPitch;
            }
        });
        // Teleport first: /fill only works in loaded chunks, and the stage is far from the spawn.
        String[] prep = {
            "gamerule send_command_feedback false", "gamerule advance_time false", "weather clear", "difficulty peaceful",
            "time set " + System.getProperty(TIME_PROPERTY, "6000"), "gamemode creative @a", "kill @e[type=!player]",
            String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 5", bx + OX + 0.5, by, bz + 0.5),
        };
        String[] build = {
            fill(OX - 8, -3, -4, OX + 8, 8, 12, "air"),
            fill(OX - 8, -2, -4, OX + 8, -1, 12, "smooth_stone"),
            fill(OX - 8, 0, 4, OX + 8, 6, 4, "white_concrete"),
            fill(OX - 1, 0, 3, OX + 1, 1, 3, "glass"),
            String.format(Locale.ROOT, "tp @a %.1f %d %.1f 0 5", bx + OX + 0.5, by, bz + 0.5),
        };
        server.execute(() -> {
            for (String c : prep) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            }
        });
        ctx.waitFrames(300, () -> {
            server.execute(() -> {
                for (String c : build) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                }
            });
            aimYaw = 0f;
            aimPitch = 5f;
            aimOverride = true;
            ctx.log("scenario", name(), "event", "scene_built", "base", bx + "," + by + "," + bz, "items", String.join("_", items));
            ctx.waitFrames(400, () -> step(ctx, items, 0));
        });
    }

    private void step(AutotestContext ctx, String[] items, int index) {
        if (index >= items.length) {
            ctx.log("scenario", name(), "event", "done", "items", String.valueOf(items.length));
            ctx.finish();
            return;
        }
        String item = items[index].trim();
        var mc = ctx.minecraft();
        command(ctx, "item replace entity @a weapon.mainhand with " + item);
        ctx.waitFrames(120, () -> {
            ctx.log("scenario", name(), "step", String.valueOf(index), "event", "hand", "item", item,
                "pass", String.valueOf(RtFrameRenderer.lastPassMode()));
            ctx.shot("%02d_hand_%s.png".formatted(index, item), () -> {
                mc.gui.setScreen(new InventoryScreen(mc.player));
                ctx.waitFrames(60, () -> ctx.shot("%02d_inv_%s.png".formatted(index, item), () -> {
                    mc.gui.setScreen(null);
                    ctx.waitFrames(30, () -> step(ctx, items, index + 1));
                }));
            });
        });
    }
}
