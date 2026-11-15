package io.github.andrexarlay.vulkancompat.sodium;

/** Geometry in Minecraft GUI pixels, with a sidebar when the window has room for it. */
record SettingsLayout(int frameX, int frameWidth, boolean sidebar, int listTop, int listBottom, int footerY) {
    static final int CONTROL_HEIGHT = 20;
    static final int ROW_HEIGHT = 24;
    static final int NAV_HEIGHT = 16;
    static final int FOOTER_HEIGHT = 20;
    static final int GAP = 4;
    static final int SEARCH_Y = 24;
    private static final int TAB_TOP = SEARCH_Y + CONTROL_HEIGHT + 5;
    private static final int TAB_COLUMNS = 4;
    private static final int TAB_COUNT = SettingsDraft.Category.values().length;
    private static final int MAX_WIDTH = 536;
    private static final int MARGIN = 8;
    private static final int SIDEBAR_WIDTH = 108;
    private static final int SIDEBAR_GAP = 12;

    static SettingsLayout measure(int width, int height) {
        int frameWidth = Math.max(1, Math.min(MAX_WIDTH, width - 2 * MARGIN));
        boolean sidebar = frameWidth >= 486 && height >= 210;
        int listTop = sidebar ? TAB_TOP : TAB_TOP + Math.ceilDiv(TAB_COUNT, TAB_COLUMNS) * (NAV_HEIGHT + GAP) + 2;
        int footerY = height - FOOTER_HEIGHT - MARGIN;
        return new SettingsLayout((width - frameWidth) / 2, frameWidth, sidebar, listTop, Math.max(listTop, footerY - 15), footerY);
    }

    int contentX() { return sidebar ? frameX + SIDEBAR_WIDTH + SIDEBAR_GAP : frameX; }
    int contentWidth() { return sidebar ? frameWidth - SIDEBAR_WIDTH - SIDEBAR_GAP : frameWidth; }

    int controlWidth() {
        return Math.min(150, contentWidth() * 45 / 100);
    }

    int reviewWidth(int textWidth) {
        return Math.min(contentWidth() / 3, textWidth + 10);
    }

    int searchWidth(int reviewWidth) {
        return Math.max(1, contentWidth() - reviewWidth - GAP);
    }

    int navX(int index) { return sidebar ? frameX : tabEdge(index % TAB_COLUMNS); }

    int navY(int index) {
        int row = sidebar ? index : index / TAB_COLUMNS;
        return (sidebar ? SEARCH_Y : TAB_TOP) + row * (NAV_HEIGHT + GAP);
    }

    int navWidth(int index) {
        if (sidebar) return SIDEBAR_WIDTH;
        int column = index % TAB_COLUMNS;
        return tabEdge(column + 1) - tabEdge(column) - GAP;
    }

    private int tabEdge(int column) {
        return frameX + (frameWidth + GAP) * column / TAB_COLUMNS;
    }
}
