package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Neutral surfaces around the game's native settings widgets. */
final class SettingsChrome {
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFAAAAAA;
    static final int PENDING = 0xFFFFDA80;
    static final int THUMB = 0xA0FFFFFF;
    private static final int SEPARATOR = 0x38FFFFFF;
    private static final int HOVER = 0x26FFFFFF;
    private static final int SELECTED = 0x3AFFFFFF;
    private static final int LIST = 0x46000000;
    private static final int MODAL_BACKGROUND = 0xFA161616;
    private static final int MODAL_OUTLINE = 0xFF737373;

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
        if (selected) g.fill(x + 1, y + 3, x + 2, bottom - 3, TEXT);
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
        g.fill(x, y, x + width, y + height, hovered ? 0xFF555555 : selected ? 0xFF434343 : 0xFF303030);
        g.outline(x, y, width, height, hovered || selected ? TEXT : MODAL_OUTLINE);
    }
}
