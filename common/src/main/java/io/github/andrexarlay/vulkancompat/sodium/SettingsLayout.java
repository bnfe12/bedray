package io.github.andrexarlay.vulkancompat.sodium;

/** All responsive geometry of the settings screen, in Minecraft GUI pixels. */
record SettingsLayout(boolean sidebar, int contentX, int contentWidth, int listTop, int listBottom,
                      int detailsX, int detailsWidth) {
    static final int MARGIN = 12;
    static final int SEARCH_Y = 46;
    static final int CATEGORY_Y = 73;
    static final int ROW_HEIGHT = 36;

    static SettingsLayout measure(int width, int height) {
        boolean sidebar = width >= 500 && height >= 300;
        int detailsWidth = width >= 800 ? Math.min(300, width / 3) : 0;
        int detailsX = width - MARGIN - detailsWidth;
        int contentX = sidebar ? 164 : MARGIN;
        int contentWidth = Math.max(120, width - contentX - MARGIN - (detailsWidth > 0 ? detailsWidth + 12 : 0));
        int listTop = sidebar ? 101 : 126;
        return new SettingsLayout(sidebar, contentX, contentWidth, listTop, Math.max(listTop, height - 62),
            detailsX, detailsWidth);
    }
}
