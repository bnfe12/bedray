package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSearchTest {
    @Test void matchesAllTermsInAnyOrderAcrossLabelsAndKeys() {
        String index = SettingsSearch.index("DLSS render scale · Масштаб разрешения · dlss_custom_scale");
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("scale DLSS")));
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("custom-scale")));
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("разрешения масштаб")));
        assertFalse(SettingsSearch.matches(index, SettingsSearch.terms("scale FSR")));
    }

    @Test void handlesUnicodeCaseAndSearchesRequireAtLeastOneTerm() {
        String index = SettingsSearch.index("Расчёт чёткости");
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("ЧЕТКОСТИ расчет")));
        assertFalse(SettingsSearch.matches(index, SettingsSearch.terms("…")));
    }
}
