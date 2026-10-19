package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;

import java.util.Locale;

/**
 * DLSS quality modes offered by the mod and their {@code VkbDlssQualityMode} numbers.
 * <p>
 * Numbers: {@code native/dlss_bridge/include/vkb_dlss.h} (our own wrapper enum); the wrapper maps them onto
 * {@code NVSDK_NGX_PerfQuality_Value} (nvsdk_ngx_defs.h of the DLSS SDK) inside {@code toPerfQuality()}.
 * <p>
 * The render scale of a mode is NOT stored here: it is queried from NGX ({@code NGX_DLSS_GET_OPTIMAL_SETTINGS},
 * DLSS Programming Guide, performance quality modes), see {@link DlssRuntime}.
 * Ultra Quality is deliberately absent: NGX reports a 0x0 render size for it on DLSS 310.9.0 (see vkb_dlss.cpp).
 */
public enum DlssQuality {
    ULTRA_PERFORMANCE(0, UpscalerMode.ULTRA_PERFORMANCE),
    PERFORMANCE(1, UpscalerMode.PERFORMANCE),
    BALANCED(2, UpscalerMode.BALANCED),
    QUALITY(3, UpscalerMode.QUALITY),
    DLAA(5, UpscalerMode.DLAA);

    private final int nativeValue;
    private final UpscalerMode mode;

    DlssQuality(int nativeValue, UpscalerMode mode) {
        this.nativeValue = nativeValue;
        this.mode = mode;
    }

    /** {@code VkbDlssQualityMode} value for {@code vkb_dlss_query_optimal_settings} / create info. */
    public int nativeValue() {
        return nativeValue;
    }

    public UpscalerMode mode() {
        return mode;
    }

    /** @throws IllegalArgumentException when DLSS does not offer the UI mode (never mapped to a default) */
    public static DlssQuality fromMode(UpscalerMode mode) {
        for (DlssQuality q : values()) {
            if (q.mode == mode) {
                return q;
            }
        }
        throw new IllegalArgumentException("DLSS has no quality mode " + mode);
    }

    /** Parses a mode name (case-insensitive, {@code -} and space accepted as {@code _}); unknown names throw. */
    public static DlssQuality parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("DLSS quality mode is null");
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (DlssQuality q : values()) {
            if (q.name().equals(normalized)) {
                return q;
            }
        }
        throw new IllegalArgumentException("Unknown DLSS quality mode '" + text + "', expected one of "
            + java.util.Arrays.toString(values()));
    }
}
