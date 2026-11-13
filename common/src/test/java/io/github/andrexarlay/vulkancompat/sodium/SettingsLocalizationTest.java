package io.github.andrexarlay.vulkancompat.sodium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The rebuilt settings screen shows the image-grade rows (tone curve, vibrant visuals, vignette, film grain) and the
 * Bedrock RTX pack actions through translatable keys. A key missing from a shipped locale silently falls back to English,
 * so a Russian player sees an English row. This pins every one of those keys into both en_us and ru_ru, the same contract
 * RtRestirSettingsTest keeps for the ReSTIR switches. The catalog clears cost globally, so only name/desc/req are shown.
 */
class SettingsLocalizationTest {
    private static final String EN = "src/main/resources/assets/bedray/lang/en_us.json";
    private static final String RU = "src/main/resources/assets/bedray/lang/ru_ru.json";

    private static final String[] IMAGE_ROWS = {"tonemapper", "vibrant_visuals", "vignette", "film_grain"};
    private static final String[] TONEMAPPER_ELEMENTS = {"peak", "aces", "pbr_neutral", "gt", "agx"};
    private static final String[] BEDROCK_ACTIONS = {"bedrock_open", "bedrock_import"};

    @Test
    void imageGradeRowsHaveNameDescriptionAndRequirementInBothLocales() {
        for (JsonObject locale : new JsonObject[] {readJson(EN), readJson(RU)}) {
            for (String row : IMAGE_ROWS) {
                assertNotNull(locale.get("bedray.opt." + row), row + " name in both locales");
                assertNotNull(locale.get("bedray.opt." + row + ".desc"), row + " description in both locales");
                assertNotNull(locale.get("bedray.opt." + row + ".req"), row + " requirement in both locales");
            }
        }
    }

    @Test
    void everyToneCurveChoiceIsNamedInBothLocales() {
        for (JsonObject locale : new JsonObject[] {readJson(EN), readJson(RU)}) {
            for (String element : TONEMAPPER_ELEMENTS) {
                assertNotNull(locale.get("bedray.tonemapper." + element), "tone curve " + element + " in both locales");
            }
        }
    }

    @Test
    void bedrockPackActionsAreLabelledInBothLocales() {
        for (JsonObject locale : new JsonObject[] {readJson(EN), readJson(RU)}) {
            for (String action : BEDROCK_ACTIONS) {
                assertNotNull(locale.get("bedray.settings." + action), "Bedrock action " + action + " in both locales");
            }
        }
    }

    private static JsonObject readJson(String relative) {
        try (Reader reader = Files.newBufferedReader(Path.of(relative), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}