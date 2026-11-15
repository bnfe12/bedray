package io.github.andrexarlay.vulkancompat.sodium;

/** Geometry at the game's current GUI scale. The menu stays centred as the window grows. */
record SettingsLayout(int contentX, int contentWidth, int listTop, int listBottom, int footerY, int columns) {
    static final int CONTROL_HEIGHT = 20;
    static final int ROW_HEIGHT = 24;
    static final int FOOTER_HEIGHT = 16;
    static final int GAP = 4;
    static final int SEARCH_Y = 24;
    static final int TABS_Y = 49;
    static final int TAB_COUNT = SettingsDraft.Category.values().length;
    private static final int MAX_WIDTH = 500;
    private static final int MARGIN = 8;

    static SettingsLayout measure(int width, int height) {
        int contentWidth = Math.max(1, Math.min(MAX_WIDTH, width - 2 * MARGIN));
        int columns = contentWidth >= 450 ? TAB_COUNT : 4;
        int listTop = TABS_Y + Math.ceilDiv(TAB_COUNT, columns) * (CONTROL_HEIGHT + GAP) + GAP;
        int footerY = height - FOOTER_HEIGHT - MARGIN;
        return new SettingsLayout((width - contentWidth) / 2, contentWidth, listTop,
            Math.max(listTop, footerY - 17), footerY, columns);
    }

    int controlWidth() {
        return Math.min(150, contentWidth * 45 / 100);
    }

    int gridEdge(int index, int count) {
        return contentX + ((contentWidth + GAP) * index) / count;
    }

    int gridWidth(int index, int count) {
        return gridEdge(index + 1, count) - gridEdge(index, count) - GAP;
    }
}
