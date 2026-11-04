package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DlssFeatureSettingsTest {
    @Test void matchesBothInputAndOutputAxes() {
        var settings = new DlssFeatureSettings(960, 540, 1920, 1080, DlssQuality.PERFORMANCE, 1);
        assertTrue(settings.matches(960, 540, 1920, 1080));
        assertFalse(settings.matches(961, 540, 1920, 1080));
        assertFalse(settings.matches(960, 541, 1920, 1080));
        assertFalse(settings.matches(960, 540, 1921, 1080));
        assertFalse(settings.matches(960, 540, 1920, 1081));
    }

    @Test void refusesInvalidActiveSettings() {
        assertThrows(IllegalArgumentException.class, () -> new DlssFeatureSettings(0, 540, 1920, 1080, DlssQuality.PERFORMANCE, 1));
        assertThrows(IllegalArgumentException.class, () -> new DlssFeatureSettings(960, 540, 1920, 1080, DlssQuality.PERFORMANCE, -1));
        assertThrows(NullPointerException.class, () -> new DlssFeatureSettings(960, 540, 1920, 1080, null, 1));
    }
}
