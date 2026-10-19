package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import java.util.Locale;

/**
 * Intel XeSS quality settings ({@code xess_quality_settings_t}).
 * Values taken from {@code xess.h} of XeSS SDK 3.0.2 ({@code local reference}).
 */
public enum XessQuality {
    ULTRA_PERFORMANCE(100),
    PERFORMANCE(101),
    BALANCED(102),
    QUALITY(103),
    ULTRA_QUALITY(104),
    ULTRA_QUALITY_PLUS(105),
    AA(106);

    private final int nativeValue;

    XessQuality(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    /** Value of {@code xess_quality_settings_t}. */
    public int nativeValue() {
        return nativeValue;
    }

    /**
     * Parses a mode name (case-insensitive, {@code -} and space accepted as {@code _}).
     *
     * @throws IllegalArgumentException for an unknown name (never silently mapped to a default)
     */
    public static XessQuality parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("XeSS quality mode is null");
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (XessQuality q : values()) {
            if (q.name().equals(normalized)) {
                return q;
            }
        }
        throw new IllegalArgumentException("Unknown XeSS quality mode '" + text + "', expected one of "
            + java.util.Arrays.toString(values()));
    }
}
