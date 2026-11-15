package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSearchTest {
    @Test void matchesAllTermsInAnyOrderAcrossLabelsAndKeys() {
        String index = SettingsSearch.index("DLSS render scale · Масштаб разрешения · dlss_custom_scale");
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("scale DLSS")));
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("custom-scale")));
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("разрешения масштаб")));
        assertFalse(SettingsSearch.matches(index, SettingsSearch.terms("scale FSR")));
    }

    @Test void onlyKnownEditablePrerequisitesAreExposedBySearch() {
        assertEquals("atm_bloom", SettingsDraft.searchPrerequisite("atm_bloom_shape"));
        assertEquals("atm_sunglare", SettingsDraft.searchPrerequisite("atm_sunglare_size"));
        assertEquals("auto_normals", SettingsDraft.searchPrerequisite("auto_normal_strength"));
        assertNull(SettingsDraft.searchPrerequisite("rt_mode"));
        assertNull(SettingsDraft.searchPrerequisite("ui_expert"));
        assertNull(SettingsDraft.searchPrerequisite("dlss_rr_preset"));
    }

    @Test void handlesUnicodeCaseAndSearchesRequireAtLeastOneTerm() {
        String index = SettingsSearch.index("Расчёт чёткости");
        assertTrue(SettingsSearch.matches(index, SettingsSearch.terms("ЧЕТКОСТИ расчет")));
        assertFalse(SettingsSearch.matches(index, SettingsSearch.terms("…")));
    }
}
