package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A shared palette for navigation, lists and details around native widgets. */
final class SettingsChrome {
    static final int TEXT = 0xFFFFFFFF;
    static final int TITLE = 0xFFCCF5DA;
    static final int ACCENT = 0xFF8AE3AA;
    static final int MUTED = 0xFFB6C7BD;
    static final int PENDING = 0xFFFFD070;
    static final int THUMB = 0xAB8AE3AA;
    private static final int SEPARATOR = 0x586CBF98;
    private static final int HOVER = 0x58375647;
    private static final int SELECTED = 0x90366A52;
    private static final int LIST = 0xB0152B22;
    private static final int MODAL_BACKGROUND = 0xFA13251D;
    private static final int MODAL_OUTLINE = 0xFF527F65;

    private SettingsChrome() {}

    static void background(GuiGraphicsExtractor g, SettingsLayout layout) {
        if (layout.sidebar()) {
            int dividerX = layout.contentX() - 6;
            g.fill(dividerX, SettingsLayout.SEARCH_Y, dividerX + 1, layout.listBottom(), SEPARATOR);
        }
        list(g, layout.contentX(), layout.listTop(), layout.contentWidth(), layout.listBottom());
    }

    static void list(GuiGraphicsExtractor g, int x, int top, int width, int bottom) {
        if (bottom <= top) return;
        g.fill(x, top, x + width, bottom, LIST);
        g.fill(x, top - 1, x + width, top, SEPARATOR);
        g.fill(x, bottom, x + width, bottom + 1, SEPARATOR);
    }

    static void navigation(GuiGraphicsExtractor g, int x, int y, int width, boolean selected, boolean hovered) {
        int bottom = y + SettingsLayout.NAV_HEIGHT;
        if (selected || hovered) g.fill(x, y, x + width, bottom, selected ? SELECTED : HOVER);
        if (selected) g.fill(x + 1, y + 3, x + 2, bottom - 3, ACCENT);
    }

    static void row(GuiGraphicsExtractor g, int x, int y, int width, boolean selected, boolean hovered, boolean changed) {
        int bottom = y + SettingsLayout.CONTROL_HEIGHT;
        if (selected || hovered) g.fill(x + 1, y, x + width - 5, bottom, selected ? SELECTED : HOVER);
        if (changed) g.fill(x + 1, y + 3, x + 2, bottom - 3, PENDING);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        g.fill(x, y, x + width, y + height, MODAL_BACKGROUND);
        g.outline(x, y, width, height, MODAL_OUTLINE);
    }

    static void panelButton(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean hovered, boolean selected) {
        g.fill(x, y, x + width, y + height, hovered ? 0xFF355947 : selected ? 0xFF294636 : 0xFF24362D);
        g.outline(x, y, width, height, hovered ? TITLE : selected ? ACCENT : MODAL_OUTLINE);
    }
}
