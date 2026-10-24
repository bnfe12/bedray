package io.github.andrexarlay.bedray.api.v1;

/** Immutable current-frame environment, six vec4 values (96 bytes), linear RGB colors.
 * 0: key direction xyz/enabled; 1: key RGB/skyFactor; 2: sky RGB/ambientFactor;
 * 3: block RGB/blockFactor; 4: dimension ambient RGB/brightness;
 * 5: darkness/nightVision/bossDarkening/reserved zero.
 * Brightness controls presentation; this contract does not promise exact vanilla lightmap matching.
 */
public final class LightingFrame {
    public static final int FLOAT_COUNT = 24;
    public static final int BYTES = FLOAT_COUNT * Float.BYTES;
    private final float[] values;
    public LightingFrame(float[] values) {
        if (values.length != FLOAT_COUNT) throw new IllegalArgumentException("Invalid lighting frame size");
        this.values = values.clone();
        for (float value : this.values) if (!Float.isFinite(value))
            throw new IllegalArgumentException("Nonfinite lighting frame");
        for (int i = 3; i < FLOAT_COUNT; i++) if (this.values[i] < 0)
            throw new IllegalArgumentException("Negative lighting value");
        if (this.values[23] != 0) throw new IllegalArgumentException("Reserved lighting value must be zero");
    }
    public float component(int index) { return values[index]; }
}
