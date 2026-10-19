package io.github.andrexarlay.vulkancompat.rtx.config;

import java.util.Locale;

/**
 * Quality mode names shown to the player, shared by all temporal upscaler technologies
 * (which modes exist per technology and their ratios: {@link UpscalerModes}).
 */
public enum UpscalerMode {
    /** Picks the mode by target FPS. STUB: not implemented, never offered in the UI. */
    AUTO("Auto"),
    /** DLSS only: full-resolution anti-aliasing. */
    DLAA("DLAA"),
    /** FSR only: Native AA. */
    NATIVE_AA("Native AA"),
    ULTRA_QUALITY_PLUS("Ultra Quality Plus"),
    ULTRA_QUALITY("Ultra Quality"),
    QUALITY("Quality"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    ULTRA_PERFORMANCE("Ultra Performance"),
    /**
     * User render scale in percent ({@code VulkanRtxConfig.customPercent(backend)}) instead of a preset. Never reaches a vendor
     * library as a mode: {@code UpscalerRequest.build} maps it to the vendor preset whose dynamic range is used (see there).
     */
    CUSTOM("Custom");

    private final String englishName;

    UpscalerMode(String englishName) {
        this.englishName = englishName;
    }

    public String englishName() {
        return englishName;
    }

    /** Translation key {@code bedray.mode.<name>}. */
    public String translationKey() {
        return "bedray.mode." + name().toLowerCase(Locale.ROOT);
    }
}
