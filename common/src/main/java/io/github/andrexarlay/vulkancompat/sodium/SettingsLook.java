package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.Tonemapper;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/** Visual styles stage display options without owning the user's ray, upscaler or frame generation settings. */
enum SettingsLook {
    NEUTRAL("Neutral", "Neutral color, restrained bloom, and no vignette or grain.",
        Tonemapper.PBR_NEUTRAL, false, 15, 62, false, false),
    VIBRANT("Vibrant", "Stronger colors and glow with the Vibrant Visuals palette.",
        Tonemapper.PEAK, true, 35, 62, false, false),
    CINEMATIC("Cinematic", "AgX tone mapping, gentle bloom, vignette and film grain.",
        Tonemapper.AGX, false, 25, 62, true, true),
    SOFT("Soft Light", "ACES tone mapping and wider, brighter bloom.",
        Tonemapper.ACES, false, 50, 75, false, false);

    private final String title;
    private final String description;
    private final Map<String, Object> values;

    SettingsLook(String title, String description, Tonemapper toneCurve, boolean vibrant, int bloomStrength, int bloomScatter,
                 boolean vignette, boolean filmGrain) {
        this.title = title;
        this.description = description;
        this.values = Map.of(
            "tonemapper", toneCurve, "vibrant_visuals", vibrant, "atm_bloom", true,
            "atm_bloom_strength", bloomStrength, "vignette", vignette, "film_grain", filmGrain,
            "atm_bloom_scatter", bloomScatter);
    }

    Map<String, Object> valuesToStage() { return values; }

    Component title() { return Component.translatableWithFallback(key(), title); }
    Component description() { return Component.translatableWithFallback(key() + ".desc", description); }

    private String key() { return "bedray.settings.look." + name().toLowerCase(Locale.ROOT); }
}
