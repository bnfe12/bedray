package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Locale;

/**
 * JVM-property configuration of the world ray tracing path (R1). All values are read once.
 * <ul>
 *   <li>{@code -Dvkbridge.rt.radiusSections=6} horizontal radius in sections (Y radius: {@code vkbridge.rt.radiusY}, 4);</li>
 *   <li>{@code -Dvkbridge.rt.blasPerFrame=24} maximum BLAS builds recorded per frame;</li>
 *   <li>{@code -Dvkbridge.rt.vramBudgetMb=1024} stop creating BLAS above this much reserved GPU memory;</li>
 *   <li>{@code -Dvkbridge.rt.debug=normals|depth|uv|section|albedo|lit|shadow|ao|sky|gi|emission|bounces|material|reflection} view that REPLACES the world image;
 *       unset: shading mode lit, shown only when the Hardware RT option is on (with only {@code -Dvkbridge.rt.geometry=true} it is traced, not shown);
 *   <li>{@code -Dvkbridge.rt.sunFlipX=true} mirrors the sun/moon X direction (the sign is an estimate);</li>
 *   <li></li>
 *   <li>{@code -Dvkbridge.rt.flipY=true} mirrors the vertical ray direction (if the debug image comes out upside down).</li>
 * </ul>
 */
public final class RtConfig {
    public static final int MODE_NONE = -1;
    public static final int MODE_NORMALS = 0;
    public static final int MODE_DEPTH = 1;
    public static final int MODE_UV = 2;
    public static final int MODE_SECTION = 3;
    public static final int MODE_ALBEDO = 4;
    public static final int MODE_LIT = 5;
    public static final int MODE_SHADOW = 6;
    public static final int MODE_AO = 7;
    public static final int MODE_SKY = 8;
    public static final int MODE_GI = 9;
    public static final int MODE_EMISSION = 10;
    public static final int MODE_BOUNCES = 11;
    public static final int MODE_MATERIAL = 12;
    public static final int MODE_REFLECTION = 13;

    /** Session override of the horizontal RT radius in sections ({@code -Dvkbridge.rt.radiusSections}), 0 = from the settings. */
    public static final int RADIUS_XZ_OVERRIDE = Math.max(0, Integer.getInteger("vkbridge.rt.radiusSections", 0));
    /** Session override of the vertical RT radius in sections ({@code -Dvkbridge.rt.radiusY}), 0 = from the settings. */
    public static final int RADIUS_Y_OVERRIDE = Math.max(0, Integer.getInteger("vkbridge.rt.radiusY", 0));
    /** Vertical radius with "RT Distance: As in game": +-8 sections = 256 blocks of height around the camera. */
    public static final int AUTO_RADIUS_Y = 8;
    /** Legacy constants (radius before the settings existed), used by the unit tests only. */
    public static final int RADIUS_XZ = 6;
    public static final int RADIUS_Y = 4;
    /** Sections farther than this (radius + margin) release their GPU data. */
    public static final int EVICT_MARGIN = 2;
    public static final int BLAS_PER_FRAME = Math.max(1, Integer.getInteger("vkbridge.rt.blasPerFrame", 24));
    /** Session override of the BLAS memory budget ({@code -Dvkbridge.rt.vramBudgetMb}), 0 = from the settings. */
    public static final long VRAM_BUDGET_OVERRIDE_MB = Math.max(0L, Long.getLong("vkbridge.rt.vramBudgetMb", 0L));
    public static final int MAX_INSTANCES = Math.max(256, Integer.getInteger("vkbridge.rt.maxInstances", 16384));

    /** Horizontal RT radius in sections: override, else the game render distance ("As in game"), else the manual value. */
    public static int radiusXz(int gameRenderDistanceChunks) {
        if (RADIUS_XZ_OVERRIDE > 0) {
            return RADIUS_XZ_OVERRIDE;
        }

        return io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.rtDistanceAuto()
            ? Math.max(2, gameRenderDistanceChunks)
            : io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.rtDistanceChunks();
    }

    /** Vertical RT radius in sections. */
    public static int radiusY(int radiusXz) {
        if (RADIUS_Y_OVERRIDE > 0) {
            return RADIUS_Y_OVERRIDE;
        }
        return Math.min(AUTO_RADIUS_Y, Math.max(2, radiusXz));
    }

    /** BLAS memory budget in bytes. */
    public static long vramBudgetBytes() {
        long mb = VRAM_BUDGET_OVERRIDE_MB > 0 ? VRAM_BUDGET_OVERRIDE_MB
            : io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.vramBudgetMb();
        return Math.max(64L, mb) << 20;
    }
    /** Ambient occlusion rays per pixel, 0 = off (default). The ao debug view uses at least 1. */
    public static final int AO_SAMPLES = Math.max(0, Math.min(16, Integer.getInteger("vkbridge.rt.ao", 0)));
    /** Tone mapping of the lit view: aces (default) or none. */
    public static final boolean TONEMAP_ACES = !System.getProperty("vkbridge.rt.tonemap", "aces").trim().equalsIgnoreCase("none");
    public static final boolean SUN_FLIP_X = Boolean.getBoolean("vkbridge.rt.sunFlipX");
    public static final boolean FLIP_Y = Boolean.getBoolean("vkbridge.rt.flipY");
    public static final String DEBUG_NAME = System.getProperty("vkbridge.rt.debug", "").trim().toLowerCase(Locale.ROOT);
    public static final int DEBUG_MODE = parseMode(DEBUG_NAME);

    /** Debug view set at runtime by an autotest scenario (null = the -Dvkbridge.rt.debug value). */
    private static volatile String debugOverride;

    /** Autotest hook: switches the debug view at runtime ("" = none, i.e. the lit image). Unknown names throw. */
    public static void setDebugView(String name) {
        String n = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        if (n != null && isUnknownMode(n)) {
            throw new IllegalArgumentException("unknown RT debug view '" + name + "'");
        }
        debugOverride = n;
    }

    public static String currentDebugView() {
        return debugName();
    }

    private static String debugName() {
        String o = debugOverride;
        return o != null ? o : DEBUG_NAME;
    }

    private static int debugMode() {
        return parseMode(debugName());
    }

    /** AO samples actually sent to the shader (legacy -Dvkbridge.rt.ao; the ao view uses at least 1). */
    public static int effectiveAoSamples() {
        return debugMode() == MODE_AO ? Math.max(1, AO_SAMPLES) : AO_SAMPLES;
    }

    /** Shading mode sent to the shader: the debug view, or AO/DIRECT, or lit when none is set. */
    public static int effectiveMode() {
        int m = debugMode();
        if (m != MODE_NONE) {
            return m;
        }
        RtMode rm = mode();
        if (rm == RtMode.AO) {
            return MODE_AO;
        }
        if (rm == RtMode.DIRECT) {
            return MODE_SHADOW;
        }
        return MODE_LIT;
    }

    /** The ray tracing mode of this session (settings page / toml / {@code -Dvkrtx.rt.mode}). */
    public static RtMode mode() {
        return io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.effectiveRtMode();
    }

    /** Whether the RT image replaces the raster image: a debug view was requested, or a ray tracing mode is selected. */
    public static boolean displayed() {
        return debugMode() != MODE_NONE || mode().isActive();
    }

    public static String effectiveModeName() {
        if (debugMode() != MODE_NONE) {
            return debugName();
        }
        RtMode rm = mode();
        if (rm == RtMode.AO) {
            return "ao";
        }
        if (rm == RtMode.DIRECT) {
            return "shadow";
        }
        return "lit";
    }

    private RtConfig() {
    }

    /** Unknown non-empty names are reported by the caller through {@link #isUnknownMode}. */
    public static int parseMode(String name) {
        return switch (name) {
            case "normals" -> MODE_NORMALS;
            case "depth" -> MODE_DEPTH;
            case "uv" -> MODE_UV;
            case "section" -> MODE_SECTION;
            case "albedo" -> MODE_ALBEDO;
            case "lit" -> MODE_LIT;
            case "shadow" -> MODE_SHADOW;
            case "ao" -> MODE_AO;
            case "sky" -> MODE_SKY;
            case "gi" -> MODE_GI;
            case "emission" -> MODE_EMISSION;
            case "bounces" -> MODE_BOUNCES;
            case "material" -> MODE_MATERIAL;
            case "reflection" -> MODE_REFLECTION;
            default -> MODE_NONE;
        };
    }

    public static boolean isUnknownMode(String name) {
        return !name.isEmpty() && parseMode(name) == MODE_NONE;
    }
}
