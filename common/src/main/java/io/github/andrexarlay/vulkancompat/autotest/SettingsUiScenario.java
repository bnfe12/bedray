package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.RtxBootstrap;
import io.github.andrexarlay.vulkancompat.mixin.autotest.MouseHandlerAccessor;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups;
import io.github.andrexarlay.vulkancompat.sodium.PreviewPanel;
import io.github.andrexarlay.vulkancompat.sodium.PresetStore;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code settings_ui}: the Bedray settings pages driven through the real UI paths, at several window sizes and GUI scales.
 * Sizes: {@code -Dvkrtx.autotest.uiSizes=1280x720@3,1920x1080@3,...} (default below). Per size: opens the Sodium Video Settings
 * screen, hovers an essential option and shoots the page with the right-hand panel (the log gets the panel rectangle, Sodium's reserved
 * area and the Done button position: the panel must end above the buttons), then uses the panel (compare chip, flip), expands
 * an advanced group. Once (first size): option reset from the panel, group reset, page reset, user preset save / change / load / delete,
 * row hiding when the backend changes. Hover places the game cursor (MouseHandler xpos/ypos accessor); clicks go through
 * {@code VideoSettingsScreen.mouseClicked(MouseButtonEvent(x, y, MouseButtonInfo(1, 0)))} (button 1 = primary, javap + the existing
 * header-click code). Window resize: SDL_SetWindowSize (the 26.3 client uses SDL, javap Window.handle / handleEvent(SDL_Event)).
 */
public final class SettingsUiScenario implements AutotestScenario {
    private static final String DEFAULT_SIZES = "1280x720@3,1280x720@2,1920x1080@3,1920x1080@2,2560x1440@4,854x480@2,640x360@1";
    private static final Identifier RT_MODE = id("rt_mode");
    private static final Identifier BOUNCES = id("path_bounces");
    private static final Identifier BACKEND = id("upscaler_backend");

    private static Identifier id(String p) {
        return Identifier.fromNamespaceAndPath("bedray", p);
    }

    @Override
    public String name() {
        return "settings_ui";
    }

    @Override
    public boolean needsWorld() {
        return false;
    }

    private String[] sizes;

    @Override
    public void run(AutotestContext ctx) {
        sizes = System.getProperty("vkrtx.autotest.uiSizes", DEFAULT_SIZES).split(",");
        RtSettings.showAdvanced = false;
        RtSettings.showExpert = false;
        VulkanRtxConfig.setRtMode(RtMode.PATH_TRACED);
        RtSettings.applyPathPreset(RtSettings.PathPreset.FAST);
        ctx.log("scenario", name(), "event", "prepared", "mode", VulkanRtxConfig.effectiveRtMode().logName(), "sizes", String.valueOf(sizes.length));
        size(ctx, 0);
    }

    private void size(AutotestContext ctx, int i) {
        if (i >= sizes.length) {
            logic(ctx);
            return;
        }
        String[] a = sizes[i].trim().split("[x@]");
        int w = Integer.parseInt(a[0]);
        int h = Integer.parseInt(a[1]);
        int gs = Integer.parseInt(a[2]);
        var mc = ctx.minecraft();
        mc.options.guiScale().set(gs);
        try {
            Class.forName("org.lwjgl.sdl.SDLVideo").getMethod("SDL_SetWindowSize", long.class, int.class, int.class)
                .invoke(null, mc.getWindow().handle(), w, h);
        } catch (Throwable t) {
            ctx.log("scenario", name(), "event", "resize_failed", "error", t.toString());
        }
        ctx.waitFrames(30, () -> {
            var win = mc.getWindow();
            String tag = win.getWidth() + "x" + win.getHeight() + "_g" + win.getGuiScale();
            ctx.log("scenario", name(), "event", "size", "wanted", sizes[i], "window", win.getWidth() + "x" + win.getHeight(),
                "gui_scale", String.valueOf(win.getGuiScale()), "gui_size", win.getGuiScaledWidth() + "x" + win.getGuiScaledHeight());
            mc.gui.setScreen(VideoSettingsScreen.createScreen(null));
            ctx.waitFrames(25, () -> {
                VideoSettingsScreen s = screen(ctx);
                if (s == null) {
                    return;
                }
                s.jumpToPage(page(0));
                ctx.waitFrames(8, () -> {
                    hover(ctx, RT_MODE);
                    ctx.waitFrames(12, () -> ctx.shot("s" + i + "_main_" + tag + ".png", () -> {
                        logPanel(ctx, "main_rt_mode");
                        clickChip(ctx, () -> ctx.waitFrames(6, () -> ctx.shot("s" + i + "_main_compare_" + tag + ".png", () -> {
                            hover(ctx, id("water_style"));
                            ctx.waitFrames(8, () -> ctx.shot("s" + i + "_main_water_" + tag + ".png", () -> {
                                logPanel(ctx, "main_water");
                                advanced(ctx, i, tag);
                            }));
                        })));
                    }));
                });
            });
        });
    }

    private void advanced(AutotestContext ctx, int i, String tag) {
        screen(ctx).jumpToPage(page(1));
        ctx.waitFrames(8, () -> {
            hoverAway(ctx);
            ctx.waitFrames(5, () -> clickHeader(ctx, CollapsibleGroups.RT_ADVANCED, () -> {
                hover(ctx, id("path_denoiser"));
                ctx.waitFrames(10, () -> ctx.shot("s" + i + "_advanced_" + tag + ".png", () -> {
                    logPanel(ctx, "advanced_denoiser");
                    // collapse again so the next size starts clean
                    CollapsibleGroups.toggle(CollapsibleGroups.RT_ADVANCED, "autotest");
                    ctx.waitFrames(5, () -> size(ctx, i + 1));
                }));
            }));
        });
    }

    /** Non-visual checks: option reset from the panel, group reset, page reset, presets, row hiding. */
    private void logic(AutotestContext ctx) {
        var mc = ctx.minecraft();
        mc.options.guiScale().set(3);
        mc.gui.setScreen(VideoSettingsScreen.createScreen(null));
        ctx.waitFrames(25, () -> {
            VideoSettingsScreen s = screen(ctx);
            if (s == null) {
                return;
            }
            s.jumpToPage(page(0));
            ctx.waitFrames(8, () -> {
                // 1) option reset from the panel button
                setValue(ctx, BOUNCES, 6);
                hover(ctx, BOUNCES);
                ctx.waitFrames(12, () -> {
                    boolean clicked = clickAction(ctx, "reset");
                    ctx.waitFrames(3, () -> {
                        int now = ((Number) valueOf(BOUNCES)).intValue();
                        ctx.log("scenario", name(), "event", "option_reset", "clicked", String.valueOf(clicked), "value_after", String.valueOf(now));
                        if (!clicked || now != 4) {
                            ctx.fail("option reset from the panel did not restore the default (clicked=" + clicked + " value=" + now + ")", null);
                            return;
                        }
                        // 2) group reset and page reset (Sodium defaults)
                        setValue(ctx, BOUNCES, 5);
                        VulkanSodiumConfig.resetOptions(VulkanSodiumConfig.groupIds("main_rt"), "autotest_group");
                        int g = ((Number) valueOf(BOUNCES)).intValue();
                        setValue(ctx, BOUNCES, 7);
                        VulkanSodiumConfig.resetOptions(VulkanSodiumConfig.pageIds(false), "autotest_page");
                        int p = ((Number) valueOf(BOUNCES)).intValue();
                        ctx.log("scenario", name(), "event", "group_page_reset", "group_value", String.valueOf(g), "page_value", String.valueOf(p),
                            "page_options", String.valueOf(VulkanSodiumConfig.pageIds(false).size()));
                        if (g != 4 || p != 4) {
                            ctx.fail("group/page reset failed: group=" + g + " page=" + p, null);
                            return;
                        }
                        presets(ctx);
                    });
                });
            });
        });
    }

    private void presets(AutotestContext ctx) {
        RtSettings.pathBounces = 5;
        RtSettings.markPathCustom();
        String name = "autotest preset";
        boolean saved = PresetStore.saveCurrent(name);
        RtSettings.pathBounces = 2;
        String text = PresetStore.read(name);
        int applied = text == null ? -1 : PresetStore.apply(text);
        int restored = RtSettings.pathBounces;
        boolean listed = PresetStore.list().contains(name);
        boolean fileHasPath = text != null && (text.contains(":\\") || text.contains("/home/") || text.toLowerCase(Locale.ROOT).contains("c:/"));
        boolean deleted = PresetStore.delete(name);
        ctx.log("scenario", name(), "event", "preset", "saved", String.valueOf(saved), "listed", String.valueOf(listed), "applied_settings",
            String.valueOf(applied), "bounces_after_load", String.valueOf(restored), "has_path", String.valueOf(fileHasPath),
            "deleted", String.valueOf(deleted), "bytes", String.valueOf(text == null ? -1 : text.length()));
        if (!saved || !listed || restored != 5 || fileHasPath || !deleted) {
            ctx.fail("preset save/load/delete failed", null);
            return;
        }
        // row hiding: the DLSS / FSR quality row exists only for its backend
        String sig = VulkanSodiumConfig.hiddenRowSignature();
        ctx.log("scenario", name(), "event", "hidden_rows", "signature", sig);
        RtSettings.applyPathPreset(RtSettings.PathPreset.FAST);
        ctx.finish();
    }

    // ---- helpers ----

    private Object valueOf(Identifier id) {
        Option o = ConfigManager.CONFIG.getOption(id);
        return o instanceof StatefulOption<?> so ? so.getValidatedValue() : null;
    }

    private void logPanel(AutotestContext ctx, String what) {
        int[] r = PreviewPanel.lastPanelRect();
        var win = ctx.minecraft().getWindow();
        ctx.log("scenario", name(), "event", "panel", "what", what, "gui", win.getGuiScaledWidth() + "x" + win.getGuiScaledHeight(),
            "rect", r[0] + "," + r[1] + "," + r[2] + "x" + r[3], "bottom", String.valueOf(r[1] + r[3]), "hits", String.join("|", PreviewPanel.hitNames()));
    }

    private boolean clickAction(AutotestContext ctx, String action) {
        for (String h : PreviewPanel.hitNames()) {
            if (h.startsWith(action)) {
                String[] xy = h.substring(h.indexOf('@') + 1).split(",");
                return screen(ctx).mouseClicked(new MouseButtonEvent(Double.parseDouble(xy[0]), Double.parseDouble(xy[1]), new MouseButtonInfo(1, 0)), false);
            }
        }
        return false;
    }

    /** Clicks the first "compare with" chip, then the Flip mode and the picture. Logs what was handled. */
    private void clickChip(AutotestContext ctx, Runnable then) {
        boolean chip = clickAction(ctx, "cmp:");
        boolean flip = clickAction(ctx, "mode_flip");
        ctx.waitFrames(3, () -> {
            boolean pic = clickAction(ctx, "flip");
            ctx.log("scenario", name(), "event", "panel_clicks", "chip", String.valueOf(chip), "mode_flip", String.valueOf(flip), "picture", String.valueOf(pic));
            then.run();
        });
    }

    private VideoSettingsScreen screen(AutotestContext ctx) {
        if (ctx.minecraft().gui.screen() instanceof VideoSettingsScreen s) {
            return s;
        }
        var cur = ctx.minecraft().gui.screen();
        ctx.fail("expected VideoSettingsScreen, got " + (cur == null ? "null" : cur.getClass().getName()), null);
        return null;
    }

    private static OptionPage page(int index) {
        for (ModOptions mo : ConfigManager.CONFIG.getModOptions()) {
            if (RtxBootstrap.MOD_ID.equals(mo.configId()) && mo.pages().get(index) instanceof OptionPage p) {
                return p;
            }
        }
        throw new IllegalStateException("page " + index + " of " + RtxBootstrap.MOD_ID + " not found");
    }

    private static OptionListWidget list(AutotestContext ctx) {
        if (ctx.minecraft().gui.screen() instanceof VideoSettingsScreen s) {
            for (var child : s.children()) {
                if (child instanceof OptionListWidget l) {
                    return l;
                }
            }
        }
        return null;
    }

    /** Clicks a section header through the screen's own mouseClicked (header must have been rendered). */
    private void clickHeader(AutotestContext ctx, String key, Runnable then) {
        int[] r = CollapsibleGroups.headerRect(key);
        if (r == null) {
            ctx.fail("header " + key + " not rendered (not on screen)", null);
            return;
        }
        int before = list(ctx) == null ? -1 : list(ctx).getControls().size();
        double x = r[0] + r[2] / 2.0;
        double y = r[1] + r[3] / 2.0;
        boolean handled = screen(ctx).mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false);
        ctx.waitFrames(10, () -> {
            int after = list(ctx) == null ? -1 : list(ctx).getControls().size();
            ctx.log("scenario", name(), "event", "header_click", "key", key, "handled", String.valueOf(handled),
                "controls_before", String.valueOf(before), "controls_after", String.valueOf(after));
            if (after <= before) {
                ctx.fail("header click on " + key + " did not add rows (" + before + " -> " + after + ")", null);
                return;
            }
            then.run();
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setValue(AutotestContext ctx, Identifier id, Object value) {
        Option o = ConfigManager.CONFIG.getOption(id);
        if (!(o instanceof StatefulOption so)) {
            ctx.fail("option " + id + " not found", null);
            return;
        }
        so.modifyValue(value);
        ConfigManager.CONFIG.applyOption(id);
    }

    /** Places the game cursor over the control of an option (GUI coordinates -> MouseHandler xpos/ypos). */
    private void hover(AutotestContext ctx, Identifier id) {
        OptionListWidget l = list(ctx);
        Option target = ConfigManager.CONFIG.getOption(id);
        List<String> seen = new ArrayList<>();
        if (l != null) {
            for (ControlElement el : l.getControls()) {
                if (el.getOption() == target) {
                    moveCursor(ctx, el.getX() + el.getWidth() * 0.5, el.getY() + el.getHeight() * 0.5);
                    return;
                }
                seen.add(el.getOption().getName().getString());
            }
        }
        ctx.fail("control of " + id + " not in the option list (" + seen.size() + " controls)", null);
    }

    private void hoverAway(AutotestContext ctx) {
        moveCursor(ctx, 2, 2);
    }

    private static void moveCursor(AutotestContext ctx, double guiX, double guiY) {
        var w = ctx.minecraft().getWindow();
        double sx = w.getScreenWidth() / (double) w.getGuiScaledWidth();
        double sy = w.getScreenHeight() / (double) w.getGuiScaledHeight();
        MouseHandlerAccessor m = (MouseHandlerAccessor) ctx.minecraft().mouseHandler;
        m.vkb$setXpos(guiX * sx);
        m.vkb$setYpos(guiY * sy);
    }
}
