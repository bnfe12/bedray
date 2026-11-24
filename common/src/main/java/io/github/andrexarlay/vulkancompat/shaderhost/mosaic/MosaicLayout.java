package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

/**
 * Screen regions of the Mosaic editor. Every region is exclusive: the top bar, palette, canvas, inspector and status
 * bar tile the screen without overlapping, so a click or a scroll always belongs to exactly one of them. Narrow
 * screens shrink the side panels to their minimum first, then hide the palette (quick-add still works), then the
 * inspector, so the canvas keeps a usable width. Pure arithmetic, unit-tested.
 */
public record MosaicLayout(Rect top, Rect palette, Rect canvas, Rect inspector, Rect status) {

    public static final int TOP_H = 22, STATUS_H = 14, MIN_CANVAS = 160;
    public static final int PALETTE_MIN = 100, PALETTE_MAX = 150, INSPECTOR_MIN = 132, INSPECTOR_MAX = 190;

    /** An axis-aligned screen rectangle; zero width or height means hidden. */
    public record Rect(int x, int y, int w, int h) {
        public static final Rect NONE = new Rect(0, 0, 0, 0);

        public int right() { return x + w; }
        public int bottom() { return y + h; }
        public boolean visible() { return w > 0 && h > 0; }

        public boolean contains(double px, double py) {
            return visible() && px >= x && px < x + w && py >= y && py < y + h;
        }

        public boolean overlaps(Rect o) {
            return visible() && o.visible() && x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
        }
    }

    public boolean paletteShown() { return palette.visible(); }
    public boolean inspectorShown() { return inspector.visible(); }

    public static MosaicLayout of(int width, int height, boolean wantPalette, boolean wantInspector) {
        int top = Math.min(TOP_H, height / 4), status = Math.min(STATUS_H, height / 6);
        int bodyY = top, bodyH = Math.max(0, height - top - status);
        int pw = wantPalette ? Math.clamp(width / 6, PALETTE_MIN, PALETTE_MAX) : 0;
        int iw = wantInspector ? Math.clamp(width / 5, INSPECTOR_MIN, INSPECTOR_MAX) : 0;
        if (width - pw - iw < MIN_CANVAS) pw = 0;          // the palette has a keyboard/quick-add substitute
        if (width - pw - iw < MIN_CANVAS) iw = 0;          // the inspector last
        int cw = Math.max(0, width - pw - iw);
        return new MosaicLayout(
            new Rect(0, 0, width, top),
            pw > 0 ? new Rect(0, bodyY, pw, bodyH) : Rect.NONE,
            new Rect(pw, bodyY, cw, bodyH),
            iw > 0 ? new Rect(pw + cw, bodyY, iw, bodyH) : Rect.NONE,
            new Rect(0, height - status, width, status));
    }
}
