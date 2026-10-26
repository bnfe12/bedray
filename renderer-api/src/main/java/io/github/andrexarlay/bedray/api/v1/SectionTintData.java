package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Optional resolved game tint per cell (API minor 5), index x+16*(y+16*z).
 * A value is zero (no cell tint) or an opaque sRGB ARGB color. It is applied only to cube faces
 * marked {@link CubeFaceMaterial#cellTint()} and model quads flagged {@link ModelQuad#CELL_TINT}.
 * Moving the position-dependent biome color out of the appearance key keeps one palette entry per
 * geometry instead of one per distinct biome blend value.
 */
public final class SectionTintData {
    private final int[] values;
    public SectionTintData(int[] values) {
        if (values.length != SectionSnapshot.CELL_COUNT) throw new IllegalArgumentException("Invalid section tint size");
        this.values = values.clone();
        for (int value : this.values)
            if (value != 0 && (value >>> 24) != 255) throw new IllegalArgumentException("Cell tint must be zero or opaque ARGB");
    }
    public int argb(int index) { return values[index]; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof SectionTintData that && Arrays.equals(values, that.values);
    }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}
