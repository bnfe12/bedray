package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the built-in factory looks: the registry, that the Vibrant preset's keys load through the same config parser a
 * saved preset uses, and that the presets screen lists built-ins read-only through the identical import path.
 */
class BuiltinPresetsTest {
    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }

    @Test void registryShipsTheVibrantLook() {
        assertFalse(BuiltinPresets.all().isEmpty(), "at least one factory look must ship");
        BuiltinPresets.Preset vibrant = BuiltinPresets.find("Vibrant");
        assertNotNull(vibrant, "the Vibrant preset must exist");
        assertEquals("Vibrant", vibrant.name());
        assertNotNull(BuiltinPresets.find("vibrant"), "lookup must be case-insensitive");
        assertNotNull(BuiltinPresets.find("  Vibrant  "), "lookup must trim the name");
        assertTrue(BuiltinPresets.isBuiltin("Vibrant"));
        assertFalse(BuiltinPresets.isBuiltin("My own look"));
        assertFalse(BuiltinPresets.isBuiltin(null));
        assertNull(BuiltinPresets.find(null));
    }

    @Test void vibrantPresetBundlesTheGradeAndTheAcesCurve() {
        String text = BuiltinPresets.find("Vibrant").text();
        assertTrue(text.contains("vibrant_visuals = true"), "the preset must turn the grade on");
        assertTrue(text.contains("tonemapper = \"ACES\""), "the preset must pair it with the ACES tone curve");
    }

    @Test void vibrantPresetKeysLoadThroughTheConfigParser() {
        boolean savedV = RtSettings.vibrantVisuals;
        RtSettings.Tonemapper savedT = RtSettings.tonemapper;
        try {
            int applied = 0;
            for (String line : BuiltinPresets.find("Vibrant").text().split("\\R")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) {
                    continue;
                }
                int eq = s.indexOf('=');
                assertTrue(eq > 0, "preset line must be key = value: " + s);
                String key = s.substring(0, eq).trim();
                String value = s.substring(eq + 1).trim().replace("\"", "");
                assertTrue(RtSettings.applyKeyValue(key, value), "the config parser must accept preset key " + key);
                applied++;
            }
            assertEquals(2, applied, "the Vibrant preset stages exactly the grade and the tone curve");
            assertTrue(RtSettings.vibrantVisuals(), "loading the preset must enable the grade");
            assertEquals(RtSettings.Tonemapper.ACES, RtSettings.tonemapper, "loading the preset must select ACES");
        } finally {
            RtSettings.vibrantVisuals = savedV;
            RtSettings.tonemapper = savedT;
        }
    }

    @Test void presetsScreenListsBuiltinsReadOnlyThroughTheSameImportPath() throws Exception {
        String screen = java("sodium/PresetsScreen.java");
        assertTrue(screen.contains("BuiltinPresets.all()"), "the presets screen must list the built-in looks");
        assertTrue(screen.contains("row.builtin() ? BuiltinPresets.find(n).text() : PresetStore.read(n)"),
            "a built-in row must load its factory text through the same draft.importText path as a player preset");
        assertTrue(screen.contains("if (!row.builtin())"), "built-in presets must not offer Delete");
    }
}