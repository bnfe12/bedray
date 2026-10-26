package io.github.andrexarlay.vulkancompat.rt.particles;

/**
 * Which weather columns of this frame the layer must replay, and how many times the game was asked to draw them.
 * <p>
 * The game draws weather once in the classic transparency path ({@code LevelRenderer.executeClassicTransparency}) and once per OIT
 * stage in the improved-transparency path ({@code LevelRenderer.executeOit}), both through the same private
 * {@code WeatherEffectRenderer.render(state, pass, pipeline)}. The layer must issue the draw set once per frame, otherwise rain is
 * blended on top of itself and gets darker the more transparency stages are enabled. Unlike particles there is no group index to
 * key on: every visit carries the whole frame's columns, so the first visit wins and the rest are dropped.
 * <p>
 * Index arithmetic follows the vanilla renderer (javap -p -c on {@code WeatherEffectRenderer}, mc 26.3 client jar):
 * {@code prepare} builds {@code DefaultVertexFormat.PARTICLE} with 4 vertices per column and {@code renderWeather} draws
 * {@code count * 6} indices from {@code firstColumn * 6} (rain: 0, snow: {@code rainColumns * 6}).
 */
final class WeatherVisitLedger {
    /** Indices of one weather column in the sequential index buffer of the game (one quad, 4 vertices). */
    static final int INDICES_PER_COLUMN = 6;

    private boolean visited;
    private int rainColumns;
    private int snowColumns;

    /**
     * Offers one visit of the vanilla weather renderer to this frame.
     *
     * @return true when the counts were taken (the first visit with columns)
     */
    boolean accept(int rainColumns, int snowColumns) {
        if (visited || rainColumns < 0 || snowColumns < 0) {
            return false;
        }
        if (rainColumns + snowColumns == 0) {
            // fair-weather frame: vanilla returns before drawing, nothing to remember
            return false;
        }
        visited = true;
        this.rainColumns = rainColumns;
        this.snowColumns = snowColumns;
        return true;
    }

    boolean visited() {
        return visited;
    }

    int rainColumns() {
        return rainColumns;
    }

    int snowColumns() {
        return snowColumns;
    }

    /** Columns of the accepted visit: rain first, then snow, exactly as vanilla uploaded them. */
    int columns() {
        return rainColumns + snowColumns;
    }

    /** Indices of the rain part of the draw set. */
    int rainIndexCount() {
        return rainColumns * INDICES_PER_COLUMN;
    }

    /** Indices of the snow part of the draw set. */
    int snowIndexCount() {
        return snowColumns * INDICES_PER_COLUMN;
    }

    /** Where the snow part starts in the sequential index buffer: behind every rain column. */
    int snowFirstIndex() {
        return rainColumns * INDICES_PER_COLUMN;
    }

    /** Indices the sequential index buffer must hold for the whole draw set. */
    int requiredIndices() {
        return columns() * INDICES_PER_COLUMN;
    }

    /** Head of {@code GameRenderer.render()}: the next frame starts without weather. */
    void clear() {
        visited = false;
        rainColumns = 0;
        snowColumns = 0;
    }
}
