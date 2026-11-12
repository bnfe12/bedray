package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and panel drawing. No settings, capability queries or input handling. */
final class SettingsChrome {
    static final int TEXT = 0xFFE8EFF4;
    static final int MUTED = 0xFF91A2B2;
    static final int ACCENT = 0xFF72D9BF;
    static final int PENDING = 0xFFFFD38A;

    private SettingsChrome() {}

    static void background(GuiGraphicsExtractor g, SettingsLayout layout, int width, int height) {
        g.fill(0, 0, width, height, 0xF20C1119);
        g.fill(0, 0, width, 37, 0xFF121D29);
        g.fill(12, 12, 15, 26, ACCENT);
        g.fill(0, height - 55, width, height, 0xFF121D29);
        g.fill(12, height - 55, width - 12, height - 54, 0xFF2C3B4A);
        panel(g, layout.contentX() - 4, layout.listTop() - 3,
            layout.contentWidth() + 4, layout.listBottom() - layout.listTop() + 6);
        if (layout.sidebar()) panel(g, 8, 70, 144, layout.listBottom() - 67);
        if (layout.detailsWidth() > 0) panel(g, layout.detailsX(), layout.listTop() - 3,
            layout.detailsWidth(), layout.listBottom() - layout.listTop() + 6);
    }

    static void row(GuiGraphicsExtractor g, int x, int y, int width, boolean selected, boolean changed) {
        g.fill(x - 1, y - 4, x + width - 5, y + 25, selected ? 0xFF203346 : 0xFF172330);
        if (selected || changed) g.fill(x - 1, y - 4, x + 1, y + 25, changed ? PENDING : ACCENT);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        if (height <= 0) return;
        g.fill(x, y, x + width, y + height, 0xFF101923);
        g.outline(x, y, width, height, 0xFF253544);
    }
}
