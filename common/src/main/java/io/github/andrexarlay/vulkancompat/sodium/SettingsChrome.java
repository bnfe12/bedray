package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette, gradients and selection easing for Bedray's settings screens. */
final class SettingsChrome {
    static final int TEXT = 0xFFFFFFFF;
    static final int TITLE = 0xFFFFF0D9;
    static final int ACCENT = 0xFFFFE0AC;
    static final int MUTED = 0xFFBDB8BA;
    static final int PENDING = 0xFFFFBF62;
    static final int THUMB = 0xABBFC1C8;

    private static final int SEPARATOR = 0x50B7AC9E;
    private static final int HOVER = 0x59363036;
    private static final int SELECTED_TOP = 0xA058483E;
    private static final int SELECTED_BOTTOM = 0xA0222025;
    private static final int MENU_TOP = 0x302B2B;
    private static final int MENU_BOTTOM = 0x08090C;
    private static final int ADDON_LIST = 0xB00C0B0D;
    private static final int MODAL_TOP = 0xFA211E20;
    private static final int MODAL_BOTTOM = 0xFA0B0B0E;
    private static final int MODAL_OUTLINE = 0xFF6B645E;
    private static final long SELECTION_FADE_NS = 130_000_000L;

    private SettingsChrome() {}

    static int worldShade(int transparency) {
        // Keep labels readable even over the daytime sky at the most transparent setting.
        return withOpacity(0x050507, 72 - transparency / 6);
    }

    static int menuTop(int transparency) {
        return withOpacity(MENU_TOP, 100 - transparency);
    }

    static int menuBottom(int transparency) {
        return withOpacity(MENU_BOTTOM, 100 - transparency);
    }

    private static int withOpacity(int rgb, int percent) {
        return withAlpha(rgb, percent / 100f);
    }

    private static int withAlpha(int color, float opacity) {
        return (Math.round(opacity * 255) << 24) | (color & 0xFFFFFF);
    }

    private static int fade(int color, float opacity) {
        return withAlpha(color, opacity * (color >>> 24) / 255f);
    }

    /** Ease out without changing geometry or hit targets. A zero timestamp means the selection is already settled. */
    static float selectionOpacity(long now, long started) {
        if (started == 0L) return 1f;
        float remaining = 1f - Math.clamp((now - started) / (float) SELECTION_FADE_NS, 0f, 1f);
        return 1f - remaining * remaining;
    }

    static void background(GuiGraphicsExtractor g, SettingsLayout layout, int transparency) {
        g.fillGradient(layout.frameX() - 4, 4, layout.frameX() + layout.frameWidth() + 4,
            layout.footerY() + SettingsLayout.FOOTER_HEIGHT + 4, menuTop(transparency), menuBottom(transparency));
        if (layout.sidebar()) {
            int dividerX = layout.contentX() - 6;
            g.fill(dividerX, SettingsLayout.SEARCH_Y, dividerX + 1, layout.listBottom(), SEPARATOR);
        }
        list(g, layout.contentX(), layout.listTop(), layout.contentWidth(), layout.listBottom(), 0x24000000);
    }

    static void list(GuiGraphicsExtractor g, int x, int top, int width, int bottom) {
        list(g, x, top, width, bottom, ADDON_LIST);
    }

    private static void list(GuiGraphicsExtractor g, int x, int top, int width, int bottom, int color) {
        if (bottom <= top) return;
        g.fill(x, top, x + width, bottom, color);
        g.fill(x, top - 1, x + width, top, SEPARATOR);
        g.fill(x, bottom, x + width, bottom + 1, SEPARATOR);
    }

    static void navigation(GuiGraphicsExtractor g, int x, int y, int width, float selection, boolean hovered) {
        int bottom = y + SettingsLayout.NAV_HEIGHT;
        highlight(g, x, y, x + width, bottom, selection, hovered);
        if (selection > 0f) g.fill(x + 1, y + 3, x + 2, bottom - 3, fade(ACCENT, selection));
    }

    static void row(GuiGraphicsExtractor g, int x, int y, int width, float selection, boolean hovered, boolean changed) {
        int bottom = y + SettingsLayout.CONTROL_HEIGHT;
        highlight(g, x + 1, y, x + width - 5, bottom, selection, hovered);
        if (changed) g.fill(x + 1, y + 3, x + 2, bottom - 3, PENDING);
    }

    private static void highlight(GuiGraphicsExtractor g, int x, int y, int right, int bottom, float selection, boolean hovered) {
        if (hovered) g.fill(x, y, right, bottom, HOVER);
        if (selection > 0f) g.fillGradient(x, y, right, bottom, fade(SELECTED_TOP, selection), fade(SELECTED_BOTTOM, selection));
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.fillGradient(x, y, x + width, y + height, MODAL_TOP, MODAL_BOTTOM);
        g.outline(x, y, width, height, MODAL_OUTLINE);
    }

    static void panelButton(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean hovered, boolean selected) {
        int top = hovered ? 0xFF453D36 : selected ? 0xFF3C3028 : 0xFF28262A;
        g.fillGradient(x, y, x + width, y + height, top, 0xFF18171A);
        g.outline(x, y, width, height, hovered ? TITLE : selected ? ACCENT : MODAL_OUTLINE);
    }
}
