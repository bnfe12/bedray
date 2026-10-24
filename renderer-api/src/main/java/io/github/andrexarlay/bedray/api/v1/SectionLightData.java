package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Immutable light for every cell, including air: block bits0..3, sky4..7, intrinsic emission8..11.
 * Values describe Minecraft's propagated scalar light, not colored physical light transport.
 */
public final class SectionLightData {
    private final short[] values;
    public SectionLightData(short[] values) {
        if (values.length != SectionSnapshot.CELL_COUNT) throw new IllegalArgumentException("Invalid section light size");
        this.values = values.clone();
        for (short value : this.values) if ((Short.toUnsignedInt(value) & ~4095) != 0)
            throw new IllegalArgumentException("Invalid section light value");
    }
    public int packed(int index) { return Short.toUnsignedInt(values[index]); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof SectionLightData that && Arrays.equals(values, that.values);
    }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}
