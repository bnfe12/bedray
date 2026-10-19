package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.List;
import java.util.Locale;

/**
 * Settings of the atmosphere post passes (Bedray): volumetric light shafts ({@link RtVolumetricPass}), bloom and sun glare
 * ({@link RtBloomPass}). Plain static fields so that the settings page, the config file and JVM flags can drive them without this
 * package depending on them; every value is clamped by its setter. Initial values come from JVM flags
 * ({@code -Dvkrtx.rt.godrays=off|post|inline}, {@code -Dvkrtx.rt.godrays.quality=0..3}, {@code ...strength=0..2},
 * {@code ...range=32..256}, {@code -Dvkrtx.rt.bloom=on|off}, {@code ...bloom.strength=0..2}, {@code ...bloom.threshold}, {@code ...bloom.scatter},
 * {@code -Dvkrtx.rt.sunglare=on|off}, {@code ...sunglare.strength}, {@code ...sunglare.star=on|off}).
 *
 * <p>{@link #options()} describes every option for the settings page (key, label, description, Basic/Advanced tier, range); the page
 * reads and writes through {@link #get(String)} / {@link #set(String, double)}.
 */
public final class RtAtmosphereSettings {
    /** Where the light shafts come from. INLINE = the former 6 step estimate inside the primary shader (kept for A/B comparison). */
    public enum GodRays { OFF, POST, INLINE }

    public enum Tier { BASIC, ADVANCED }

    public enum Kind { TOGGLE, CHOICE, SLIDER }

    /** One option for the settings page. For TOGGLE min/max are 0/1, for CHOICE 0..(choices-1); {@code labels} names the choices. */
    public record Option(String key, String label, String description, Tier tier, Kind kind, double min, double max, double def,
                         List<String> labels) {
    }

    private static volatile GodRays godRays = parseGodRays(System.getProperty("vkrtx.rt.godrays"), GodRays.POST);
    private static volatile int godRayQuality = (int) clamp(num("vkrtx.rt.godrays.quality", 2), 0, 3);
    private static volatile float godRayStrength = (float) clamp(num("vkrtx.rt.godrays.strength", 1.0), 0, 2);
    private static volatile int godRayRange = (int) clamp(num("vkrtx.rt.godrays.range", 160), 32, 256);
    private static volatile boolean bloom = flag("vkrtx.rt.bloom", true);
    private static volatile float bloomStrength = (float) clamp(num("vkrtx.rt.bloom.strength", 0.25), 0, 2);
    private static volatile float bloomThreshold = (float) clamp(num("vkrtx.rt.bloom.threshold", 6.0), 0.5, 10);
    private static volatile float bloomScatter = (float) clamp(num("vkrtx.rt.bloom.scatter", 0.62), 0.3, 0.95);
    private static volatile boolean sunGlare = flag("vkrtx.rt.sunglare", true);
    private static volatile float sunGlareStrength = (float) clamp(num("vkrtx.rt.sunglare.strength", 1.0), 0, 2);
    private static volatile boolean sunGlareStar = flag("vkrtx.rt.sunglare.star", false);
    private static volatile float fogVanilla = (float) clamp(num("vkrtx.rt.fog.vanilla", 1.0), 0, 1);
    private static volatile float chunkFade = (float) clamp(num("vkrtx.rt.chunkfade", 1.5), 0, 4);
    private static volatile float fogAerial = (float) clamp(num("vkrtx.rt.fog.aerial", 1.0), 0, 2);

    private RtAtmosphereSettings() {
    }

    // ---- readers (render thread) ----

    public static GodRays godRays() {
        return godRays;
    }

    /** Ray march steps per half resolution pixel for the quality 0..3: 6 / 8 / 12 / 16. */
    public static int godRaySteps() {
        return stepsFor(godRayQuality);
    }

    public static int stepsFor(int quality) {
        return switch ((int) clamp(quality, 0, 3)) {
            case 0 -> 6;
            case 1 -> 8;
            case 2 -> 12;
            default -> 16;
        };
    }

    public static float godRayStrength() {
        return godRayStrength;
    }

    public static int godRayRange() {
        return godRayRange;
    }

    public static boolean bloom() {
        return bloom && bloomStrength > 0f;
    }

    public static float bloomStrength() {
        return bloomStrength;
    }

    public static float bloomThreshold() {
        return bloomThreshold;
    }

    public static float bloomScatter() {
        return bloomScatter;
    }

    public static boolean sunGlare() {
        return sunGlare && sunGlareStrength > 0f;
    }

    public static float sunGlareStrength() {
        return sunGlareStrength;
    }

    public static boolean sunGlareStar() {
        return sunGlareStar;
    }

    /** Weight of the vanilla fog of the game (render distance edge, biome / weather / water fog) in the ray traced image, 0..1. */
    public static float fogVanilla() {
        return fogVanilla;
    }

    /** Seconds a new world section takes to emerge from the haze (0 = pop in). */
    public static float chunkFadeSeconds() {
        return chunkFade;
    }

    /** Multiplier of the aerial perspective (haze by distance and height), 0 = none. */
    public static float fogAerial() {
        return fogAerial;
    }

    // ---- UI access ----

    public static List<Option> options() {
        return List.of(
            new Option("godrays", "Volumetric light", "Light shafts of the sun and moon: rays through leaves, windows and cave openings, "
                + "stronger at sunrise, sunset and in rain. \"Post\" is the ray traced pass (recommended), \"Inline\" the older "
                + "cheaper estimate inside the main shader.", Tier.BASIC, Kind.CHOICE, 0, 2, GodRays.POST.ordinal(),
                List.of("Off", "Post", "Inline")),
            new Option("godrays_strength", "Light shaft strength", "Brightness of the light shafts .",
                Tier.BASIC, Kind.SLIDER, 0, 2, 1.0, List.of()),
            new Option("bloom", "Bloom", "Soft glow around bright light sources and emissive blocks, added before tone mapping so "
                + "colours are kept.", Tier.BASIC, Kind.TOGGLE, 0, 1, 1, List.of()),
            new Option("bloom_strength", "Bloom strength", "How strong the glow is.", Tier.BASIC, Kind.SLIDER, 0, 2, 0.25, List.of()),
            new Option("sunglare", "Sun glare", "Halo around the sun when you look at it; hidden behind blocks and clouds.",
                Tier.BASIC, Kind.TOGGLE, 0, 1, 1, List.of()),
            new Option("fog_aerial", "Distance haze", "Air perspective: distant terrain fades into the horizon colour; denser in rain and "
                + "in valleys, tied to the render distance. Needs Volumetric fog on.", Tier.BASIC, Kind.SLIDER, 0, 2, 1.0, List.of()),
            new Option("fog_vanilla", "Vanilla fog", "How much of the game's own fog is used: hides the edge of the loaded world and "
                + "applies the weather and water fog of the biome.", Tier.ADVANCED, Kind.SLIDER, 0, 1, 1.0, List.of()),
            new Option("chunk_fade", "Chunk fade-in", "New terrain emerges from the distance haze over this many seconds instead of "
                + "popping in (0 = off).", Tier.BASIC, Kind.SLIDER, 0, 4, 1.5, List.of()),
            new Option("godrays_quality", "Light shaft quality", "Ray marching steps per pixel: Low 6, Medium 8, High 12, Ultra 16. "
                + "More steps = smoother shafts, higher GPU cost.", Tier.ADVANCED, Kind.CHOICE, 0, 3, 2,
                List.of("Low", "Medium", "High", "Ultra")),
            new Option("godrays_range", "Light shaft range", "Distance in blocks that the shafts are computed over.",
                Tier.ADVANCED, Kind.SLIDER, 32, 256, 160, List.of()),
            new Option("bloom_threshold", "Bloom threshold", "Brightness (before tone mapping) from which a pixel starts to glow. "
                + "Lower values make more of the image glow.", Tier.ADVANCED, Kind.SLIDER, 0.5, 10, 6.0, List.of()),
            new Option("bloom_scatter", "Bloom spread", "Balance between a tight and a wide glow.", Tier.ADVANCED, Kind.SLIDER,
                0.3, 0.95, 0.62, List.of()),
            new Option("sunglare_strength", "Sun glare strength", "Brightness of the sun halo.", Tier.ADVANCED, Kind.SLIDER, 0, 2, 1.0,
                List.of()),
            new Option("sunglare_star", "Sun glare star", "Adds a six point star to the sun glare.", Tier.ADVANCED, Kind.TOGGLE, 0, 1, 0,
                List.of()));
    }

    /** Current value of option {@code key} (toggles 0/1, choices the index); NaN for an unknown key. */
    public static double get(String key) {
        return switch (key) {
            case "godrays" -> godRays.ordinal();
            case "godrays_strength" -> godRayStrength;
            case "godrays_quality" -> godRayQuality;
            case "godrays_range" -> godRayRange;
            case "bloom" -> bloom ? 1 : 0;
            case "bloom_strength" -> bloomStrength;
            case "bloom_threshold" -> bloomThreshold;
            case "bloom_scatter" -> bloomScatter;
            case "sunglare" -> sunGlare ? 1 : 0;
            case "sunglare_strength" -> sunGlareStrength;
            case "sunglare_star" -> sunGlareStar ? 1 : 0;
            case "fog_vanilla" -> fogVanilla;
            case "fog_aerial" -> fogAerial;
            case "chunk_fade" -> chunkFade;
            default -> Double.NaN;
        };
    }

    /** Sets option {@code key} (clamped to its range); returns false for an unknown key or a non-finite value. */
    public static boolean set(String key, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return false;
        }
        switch (key) {
            case "godrays" -> godRays = GodRays.values()[(int) clamp(Math.round(v), 0, GodRays.values().length - 1)];
            case "godrays_strength" -> godRayStrength = (float) clamp(v, 0, 2);
            case "godrays_quality" -> godRayQuality = (int) clamp(Math.round(v), 0, 3);
            case "godrays_range" -> godRayRange = (int) clamp(Math.round(v), 32, 256);
            case "bloom" -> bloom = v >= 0.5;
            case "bloom_strength" -> bloomStrength = (float) clamp(v, 0, 2);
            case "bloom_threshold" -> bloomThreshold = (float) clamp(v, 0.5, 10);
            case "bloom_scatter" -> bloomScatter = (float) clamp(v, 0.3, 0.95);
            case "sunglare" -> sunGlare = v >= 0.5;
            case "sunglare_strength" -> sunGlareStrength = (float) clamp(v, 0, 2);
            case "sunglare_star" -> sunGlareStar = v >= 0.5;
            case "fog_vanilla" -> fogVanilla = (float) clamp(v, 0, 1);
            case "fog_aerial" -> fogAerial = (float) clamp(v, 0, 2);
            case "chunk_fade" -> chunkFade = (float) clamp(v, 0, 4);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** One line for the log. */
    public static String describe() {
        return String.format(Locale.ROOT, "godrays=%s steps=%d strength=%.2f range=%d bloom=%s strength=%.2f threshold=%.2f scatter=%.2f "
                + "sunglare=%s strength=%.2f star=%s fog_vanilla=%.2f fog_aerial=%.2f chunk_fade=%.2f", godRays, godRaySteps(), godRayStrength, godRayRange,
            bloom(), bloomStrength, bloomThreshold, bloomScatter, sunGlare(), sunGlareStrength, sunGlareStar, fogVanilla, fogAerial, chunkFade);
    }

    static GodRays parseGodRays(String s, GodRays def) {
        if (s == null) {
            return def;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "0", "none" -> GodRays.OFF;
            case "post", "on", "true", "1" -> GodRays.POST;
            case "inline", "legacy" -> GodRays.INLINE;
            default -> def;
        };
    }

    private static boolean flag(String prop, boolean def) {
        String s = System.getProperty(prop);
        if (s == null) {
            return def;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "0", "no" -> false;
            case "on", "true", "1", "yes" -> true;
            default -> def;
        };
    }

    private static double num(String prop, double def) {
        String s = System.getProperty(prop);
        if (s == null) {
            return def;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isNaN(v) || Double.isInfinite(v) ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
