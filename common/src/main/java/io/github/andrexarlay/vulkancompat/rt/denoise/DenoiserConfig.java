package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.util.Locale;

/**
 * JVM-property configuration of the NRD denoiser path (read once).
 * <ul>
 *   <li>{@code -Dvkrtx.denoiser=off|nrd|nrd_reblur|nrd_relax} (default off);</li>
 *   <li>{@code -Dvkrtx.denoiser.view=denoised|split|noisy}: {@code split} keeps the left half raw (noisy) for the before/after
 *       screenshot; {@code noisy} runs NRD but shows the raw image (default {@code denoised});</li>
 *   <li>{@code -Dvkrtx.denoiser.range=4096} CommonSettings.denoisingRange in blocks.</li>
 * </ul>
 * An invalid value is recorded in {@link #configError()} and treated as OFF; the frame renderer logs it as
 * {@code [VKB-NRD] status=disabled reason=...} (rule 4: not silent).
 */
public final class DenoiserConfig {
    public enum View { DENOISED, SPLIT, NOISY }

    private static final DenoiserMode MODE;
    private static final String ERROR;
    public static final View VIEW;
    public static final float DENOISING_RANGE;
    /** Negate the clip-space Y of the projection handed to NRD (see NrdCameraMath); {@code -Dvkrtx.nrd.projFlipY=false} disables it. */
    public static final boolean PROJ_FLIP_Y = !"false".equalsIgnoreCase(System.getProperty("vkrtx.nrd.projFlipY", "true").trim());

    private static String readDenoiserConfigFile() {
        try {
            for (String name : new String[] {"bedray_denoiser.txt", "vulkan_rtx_denoiser.txt"}) {
                java.nio.file.Path p = java.nio.file.Path.of("config", name);
                if (java.nio.file.Files.isRegularFile(p)) {
                    return java.nio.file.Files.readString(p).trim();
                }
                p = java.nio.file.Path.of(name);
                if (java.nio.file.Files.isRegularFile(p)) {
                    return java.nio.file.Files.readString(p).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static {
        DenoiserMode m = DenoiserMode.OFF;
        String err = null;
        try {
            String prop = System.getProperty("vkrtx.denoiser");
            boolean rrFlag = "true".equalsIgnoreCase(System.getProperty("vkrtx.dlss.rr"));
            if (prop == null || prop.isBlank()) {
                prop = readDenoiserConfigFile();
            }
            if (prop != null && !prop.isBlank()) {
                m = DenoiserMode.parse(prop);
            } else if (rrFlag) {
                // [DLSS-RR] -Dvkrtx.dlss.rr=true enables DLSS Ray Reconstruction
                m = DenoiserMode.DLSS_RR;
            } else {
                m = DenoiserMode.OFF;
            }
        } catch (IllegalArgumentException e) {
            err = e.getMessage();
        }
        MODE = m;
        View v = View.DENOISED;
        String view = System.getProperty("vkrtx.denoiser.view", "denoised").trim().toLowerCase(Locale.ROOT);
        switch (view) {
            case "denoised" -> v = View.DENOISED;
            case "split" -> v = View.SPLIT;
            case "noisy" -> v = View.NOISY;
            default -> err = (err == null ? "" : err + "; ") + "unknown -Dvkrtx.denoiser.view=" + view + " (use denoised|split|noisy)";
        }
        VIEW = v;
        float range = 4096f;
        String rangeText = System.getProperty("vkrtx.denoiser.range", "4096");
        try {
            range = Math.max(16f, Float.parseFloat(rangeText.trim()));
        } catch (NumberFormatException e) {
            err = (err == null ? "" : err + "; ") + "invalid -Dvkrtx.denoiser.range=" + rangeText;
        }
        DENOISING_RANGE = range;
        ERROR = err;
    }

    private DenoiserConfig() {
    }

    /** True when no {@code -Dvkrtx.denoiser}, {@code -Dvkrtx.dlss.rr} or config file was given. */
    public static boolean automatic() {
        return System.getProperty("vkrtx.denoiser") == null && System.getProperty("vkrtx.dlss.rr") == null && readDenoiserConfigFile() == null;
    }

    public static DenoiserMode requested() {
        return ERROR != null ? DenoiserMode.OFF : MODE;
    }

    /** Non-null when a property had an invalid value. */
    public static String configError() {
        return ERROR;
    }
}
