package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.RtxBootstrap;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

/**
 * {@code settings}: opens Sodium's Video Settings from the title screen, shoots it, jumps to the page registered by
 * {@code VulkanSodiumConfig} and shoots again. Moved from the earlier prototype AutotestHarness; the Sodium signatures were
 * taken from there (javap of the Sodium jar in libs/): {@code VideoSettingsScreen.createScreen(Screen)} public static,
 * {@code jumpToPage(OptionPage)}, {@code ConfigManager.CONFIG.getModOptions()}, {@code ModOptions.configId()/pages()}.
 */
public final class SettingsScenario implements AutotestScenario {
    @Override
    public String name() {
        return "settings";
    }

    @Override
    public boolean needsWorld() {
        return false;
    }

    @Override
    public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        // -Dvkrtx.autotest.rtLevels=true: show the advanced and expert RT controls (scrolled shots 03/04)
        if (Boolean.getBoolean("vkrtx.autotest.rtLevels")) {
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.showAdvanced = true;
            io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.showExpert = true;
        }
        mc.gui.setScreen(VideoSettingsScreen.createScreen(mc.gui.screen()));
        ctx.waitFrames(15, () -> {
            if (!(mc.gui.screen() instanceof VideoSettingsScreen)) {
                ctx.fail("expected VideoSettingsScreen, got "
                    + (mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getName()), null);
                return;
            }
            ctx.shot("01_settings.png", () -> selectVulkanPage(ctx));
        });
    }

    /** Scrolls the option list (Screen.mouseScrolled, javap of VideoSettingsScreen) and shoots 03.. until n shots. */
    private void scrollShots(AutotestContext ctx, VideoSettingsScreen screen, int index) {
        if (index > 6) {
            String reset = System.getProperty("vkrtx.autotest.rtReset");
            if (reset == null) {
                ctx.finish();
                return;
            }
            // the "Reset" button action (same method as the ExternalButtonOption), then the reopened page
            io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig.resetAndReopen(
                io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.Level.valueOf(reset.toUpperCase(java.util.Locale.ROOT)), screen);
            ctx.waitFrames(10, () -> {
                if (ctx.minecraft().gui.screen() instanceof VideoSettingsScreen reopened) {
                    selectPageOnly(ctx, reopened);
                    ctx.waitFrames(10, () -> {
                        reopened.mouseScrolled(ctx.minecraft().getWindow().getGuiScaledWidth() * 0.55,
                            ctx.minecraft().getWindow().getGuiScaledHeight() * 0.5, 0.0, -12.0);
                        ctx.waitFrames(10, () -> ctx.shot("07_after_reset.png", ctx::finish));
                    });
                } else {
                    ctx.fail("settings screen not reopened after reset", null);
                }
            });
            return;
        }
        var w = ctx.minecraft().getWindow();
        double x = w.getGuiScaledWidth() * 0.55;
        double y = w.getGuiScaledHeight() * 0.5;
        screen.mouseScrolled(x, y, 0.0, -6.0);
        ctx.waitFrames(10, () -> ctx.shot(String.format(java.util.Locale.ROOT, "%02d_vulkan_page_scrolled.png", index),
            () -> scrollShots(ctx, screen, index + 1)));
    }

    private static void selectPageOnly(AutotestContext ctx, VideoSettingsScreen screen) {
        for (ModOptions mo : ConfigManager.CONFIG.getModOptions()) {
            if (RtxBootstrap.MOD_ID.equals(mo.configId()) && mo.pages().get(0) instanceof OptionPage page) {
                screen.jumpToPage(page);
            }
        }
    }

    private void selectVulkanPage(AutotestContext ctx) {
        var mc = ctx.minecraft();
        StringBuilder ids = new StringBuilder();
        ModOptions ours = null;
        for (ModOptions mo : ConfigManager.CONFIG.getModOptions()) {
            ids.append(mo.configId()).append(',');
            if (RtxBootstrap.MOD_ID.equals(mo.configId())) {
                ours = mo;
            }
        }
        boolean visible = ours != null && !ours.pages().isEmpty();
        ctx.log("scenario", name(), "page_visible", String.valueOf(visible), "modOptionsIds", ids.toString());
        if (!visible) {
            ctx.fail("Vulkan page not registered in Sodium ConfigManager.CONFIG (ids: " + ids + ")", null);
            return;
        }
        if (ours.pages().get(0) instanceof OptionPage page && mc.gui.screen() instanceof VideoSettingsScreen screen) {
            screen.jumpToPage(page);
            ctx.waitFrames(10, () -> ctx.shot("02_vulkan_page.png", () -> scrollShots(ctx, screen, 3)));
        } else {
            ctx.fail("cannot jump to page: " + ours.pages().get(0).getClass().getName(), null);
        }
    }
}
