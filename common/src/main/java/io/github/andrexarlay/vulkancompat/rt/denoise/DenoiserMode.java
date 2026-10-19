package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.util.Locale;

/**
 * Denoiser of the RT lit view. Default {@link #OFF}. Selected with {@code -Dvkrtx.denoiser=off|nrd|nrd_reblur|nrd_relax}
 * ({@code nrd} is {@code nrd_reblur}). EXPERIMENTAL (level L2 until an in-game run proves it): there is no UI option yet.
 */
public enum DenoiserMode {
    OFF("off", 0),
    NRD_REBLUR("nrd_reblur", 1),
    NRD_RELAX("nrd_relax", 2),
    // [DLSS-RR] NVIDIA DLSS Ray Reconstruction denoiser mode
    DLSS_RR("dlss_rr", 3);

    private final String logName;
    private final int mask;

    DenoiserMode(String logName, int mask) {
        this.logName = logName;
        this.mask = mask;
    }

    public String logName() {
        return logName;
    }

    public boolean isNrd() {
        return this == NRD_REBLUR || this == NRD_RELAX;
    }

    // [DLSS-RR] True if mode is DLSS Ray Reconstruction
    public boolean isDlssRr() {
        return this == DLSS_RR;
    }

    /** {@code VKB_NRD_MODE_*} bit of vkb_nrd.h for the native create/dispatch calls (REBLUR 1, RELAX 2; OFF/DLSS_RR have none). */
    public int nativeModeMask() {
        if (!isNrd()) {
            throw new IllegalStateException(name() + " has no NRD native mode");
        }
        return mask;
    }

    /** Value pushed to rt_pack_nrd.comp: 0 REBLUR, 1 RELAX. */
    public int packShaderCode() {
        if (!isNrd()) {
            throw new IllegalStateException(name() + " has no NRD pack code");
        }
        return this == NRD_REBLUR ? 0 : 1;
    }

    /** Parses the property value; {@code null}/blank means OFF, an unknown non-blank value is an error (no silent fallback). */
    public static DenoiserMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off", "none", "false" -> OFF;
            case "nrd", "nrd_reblur", "reblur" -> NRD_REBLUR;
            case "nrd_relax", "relax" -> NRD_RELAX;
            case "dlss_rr", "dlss-rr", "dlss_d", "rr" -> DLSS_RR;
            default -> throw new IllegalArgumentException("unknown -Dvkrtx.denoiser=" + value + " (use off|nrd|nrd_reblur|nrd_relax|dlss_rr)");
        };
    }
}
