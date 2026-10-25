package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog is the single description of the settings panel: every entry is complete and the shipped screenshots fit the budget. */
class SettingsCatalogTest {
    private static final Path PREVIEW_DIR = Path.of("src/main/resources/assets/bedray/textures/gui/preview");

    @Test
    void everyEntryHasTextAndEitherScreenshotsOrAReason() {
        for (SettingsCatalog.Info i : SettingsCatalog.all().values()) {
            assertFalse(i.name().isBlank(), i.path() + ": name");
            assertFalse(i.desc().isBlank(), i.path() + ": description");
            assertTrue(i.hasPreviews() || !i.noImage().isBlank(), i.path() + ": needs previews or a reason why there is none");
            Set<String> keys = new HashSet<>();
            for (SettingsCatalog.Preview p : i.previews()) {
                assertTrue(keys.add(p.valueKey()), i.path() + ": duplicate preview key " + p.valueKey());
                assertTrue(p.valueKey().matches("[a-z0-9_]+"), i.path() + ": bad key " + p.valueKey());
                assertTrue(p.apply() != null, i.path() + ": preview " + p.valueKey() + " has no action");
            }
        }
    }

    @Test
    void shippedScreenshotsMatchTheCatalogAndTheBudget() throws IOException {
        if (!Files.isDirectory(PREVIEW_DIR)) {
            return;   // no screenshots captured yet: the panel shows placeholders
        }
        Set<String> expected = new HashSet<>();
        for (SettingsCatalog.Info i : SettingsCatalog.all().values()) {
            for (SettingsCatalog.Preview p : i.previews()) {
                expected.add(i.previewBase() + "_" + p.valueKey() + ".png");
            }
        }
        long total = 0;
        try (Stream<Path> s = Files.list(PREVIEW_DIR)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String n = f.getFileName().toString();
                if (n.endsWith(".png")) {
                    assertTrue(expected.contains(n), "screenshot without a catalog entry: " + n);
                    long size = Files.size(f);
                    assertTrue(size <= 400 * 1024, n + " is " + size + " bytes (limit 400 KB)");
                    total += size;
                }
            }
        }
        assertTrue(total <= 12L * 1024 * 1024, "screenshots total " + total + " bytes (limit 12 MB)");
    }

    @Test
    void presetNamesAreSanitized() {
        assertEquals("My look 1", PresetStore.sanitize("  My look 1 "));
        assertEquals("abc", PresetStore.sanitize("../abc"));
        assertEquals(null, PresetStore.sanitize("///"));
        assertTrue(PresetStore.sanitize("x".repeat(80)).length() <= 32);
    }

    @Test
    void presetTextHasNoPathsOrUiState() {
        String text = PresetStore.snapshot();
        assertFalse(text.contains("ui_expert"));
        assertFalse(text.contains("rt_show_"));
        assertFalse(text.contains(":\\") || text.contains(":/"), "preset must not contain a path");
        assertTrue(text.contains("path_bounces"));
    }
}
