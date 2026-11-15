package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared palette and panel drawing. No settings, capability queries or input handling. */
final class SettingsChrome {
    static final int TEXT = 0xFFE8EFF4;
    static final int MUTED = 0xFF91A2B2;
    static final int ACCENT = 0xFF72D9BF;
    static final int PENDING = 0xFFFFD38A;

    private SettingsChrome() {}

    static void background(GuiGraphicsExtractor g, SettingsLayout layout) {
        int x = layout.contentX();
        int right = x + layout.contentWidth();
        g.fill(x, layout.listTop() - 3, right, layout.listTop() - 2, 0xFF39434C);
        g.fill(x, layout.listBottom() + 2, right, layout.listBottom() + 3, 0xFF39434C);
    }

    static void row(GuiGraphicsExtractor g, int x, int y, int width, boolean selected, boolean changed) {
        if (selected) g.fill(x, y, x + width - 5, y + SettingsLayout.CONTROL_HEIGHT, 0xB02F3943);
        if (selected || changed) g.fill(x, y, x + 2, y + SettingsLayout.CONTROL_HEIGHT, changed ? PENDING : ACCENT);
    }

}
