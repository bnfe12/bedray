package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;

import java.util.Locale;

/**
 * {@code FfxApiUpscaleQualityMode} (ffx_upscale.h l.31-38, FidelityFX SDK v1.1.4) with the per-dimension ratio written in the
 * header comments and in docs/techniques/super-resolution-upscaler.md "Scaling modes" (FSR 3.1.4): NativeAA 1.0x,
 * Quality 1.5x, Balanced 1.7x, Performance 2.0x, Ultra Performance 3.0x. There is no "Ultra Quality" mode in FSR 2/3.1
 * (it existed only in FSR 1.0), so it is not offered. At runtime the render size comes from
 * {@code ffxQueryDescUpscaleGetRenderResolutionFromQualityMode}; the ratio here is for the UI and tests.
 */
public enum FsrQuality {
    NATIVE_AA(0, 1.0, UpscalerMode.NATIVE_AA),
    QUALITY(1, 1.5, UpscalerMode.QUALITY),
    BALANCED(2, 1.7, UpscalerMode.BALANCED),
    PERFORMANCE(3, 2.0, UpscalerMode.PERFORMANCE),
    ULTRA_PERFORMANCE(4, 3.0, UpscalerMode.ULTRA_PERFORMANCE);

    private final int nativeValue;
    private final double ratio;
    private final UpscalerMode mode;

    FsrQuality(int nativeValue, double ratio, UpscalerMode mode) {
        this.nativeValue = nativeValue;
        this.ratio = ratio;
        this.mode = mode;
    }

    public int nativeValue() {
        return nativeValue;
    }

    /** Per-dimension upscale ratio (output / render) from the SDK header. */
    public double ratio() {
        return ratio;
    }

    public UpscalerMode mode() {
        return mode;
    }

    public static FsrQuality fromMode(UpscalerMode mode) {
        for (FsrQuality q : values()) {
            if (q.mode == mode) {
                return q;
            }
        }
        throw new IllegalArgumentException("FSR has no mode " + mode + " (FSR 3.1 modes: NATIVE_AA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE)");
    }

    /** Accepts the enum name, case-insensitive, '-' or ' ' as '_' (e.g. {@code -Dvkrtx.fsr=quality}). */
    public static FsrQuality parse(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown FSR mode '" + raw + "' (NATIVE_AA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE)");
        }
    }
}
