package io.github.andrexarlay.vulkancompat.sodium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every option the settings catalog knows must have a name and a description in both shipped locales, so no row shows
 * English text to a Russian player or no text at all. The catalog is the single source of the English text; the locale
 * files carry the translations the screen actually shows.
 */
class SettingsDescriptionCoverageTest {
    private static final String EN = "src/main/resources/assets/bedray/lang/en_us.json";
    private static final String RU = "src/main/resources/assets/bedray/lang/ru_ru.json";

    @Test
    void everyCatalogOptionHasNameAndDescriptionInBothLocales() throws Exception {
        JsonObject en = load(EN);
        JsonObject ru = load(RU);
        List<String> missing = new ArrayList<>();
        for (String path : SettingsCatalog.all().keySet()) {
            for (String key : new String[] {"bedray.opt." + path, "bedray.opt." + path + ".desc"}) {
                if (!en.has(key)) missing.add("en " + key);
                if (!ru.has(key)) missing.add("ru " + key);
            }
        }
        assertTrue(missing.isEmpty(), "missing locale keys (" + missing.size() + "): " + missing);
    }

    private static JsonObject load(String file) throws Exception {
        try (Reader reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
