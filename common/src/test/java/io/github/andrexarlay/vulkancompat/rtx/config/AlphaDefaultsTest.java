package io.github.andrexarlay.vulkancompat.rtx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No quality presets in the alpha: defaults are the owner's working values, saved FAST / QUALITY presets are ignored. */
class AlphaDefaultsTest {
    @Test
    void alphaDefaultsAreTheWorkingCustomValues() {
        RtSettings.writeAlphaPathDefaults();
        assertEquals(2, RtSettings.pathSpp);
        assertEquals(4, RtSettings.pathBounces);
        assertEquals(384, RtSettings.pathDistance);
        assertTrue(RtSettings.caustics);
        assertTrue(RtSettings.pathAccumulate);
        assertFalse(RtSettings.pathAutoExposure);
        assertEquals(RtSettings.PathDenoiser.DLSS_RR, RtSettings.pathDenoiser);
    }

    @Test
    void savedPathPresetAndItsValuesAreKept() {
        RtSettings.writeAlphaPathDefaults();
        try {
            RtSettings.applyKeyValue("pt_preset", "FAST");
            assertEquals(RtSettings.PathPreset.FAST, RtSettings.pathPreset);
            RtSettings.applyKeyValue("path_spp", "1");
            RtSettings.applyKeyValue("path_denoiser", "nrd");
            assertEquals(1, RtSettings.pathSpp);
            assertEquals(RtSettings.PathDenoiser.NRD, RtSettings.pathDenoiser);
            RtSettings.applyKeyValue("path_bounces", "3");
            assertEquals(3, RtSettings.pathBounces);
            RtSettings.applyKeyValue("rt_fog_density", "120");
            assertEquals(120, RtSettings.rtFogDensity);
        } finally {
            RtSettings.rtFogDensity = 100;
            RtSettings.writeAlphaPathDefaults();
        }
    }

    @Test
    void customPresetKeepsSavedValues() {
        try {
            RtSettings.applyKeyValue("pt_preset", "CUSTOM");
            RtSettings.applyKeyValue("path_spp", "3");
            assertEquals(3, RtSettings.pathSpp);
        } finally {
            RtSettings.writeAlphaPathDefaults();
        }
    }
}
