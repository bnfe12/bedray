package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.sodium.SettingsCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A texture style is a one-click group over the two texture-sampling rows (point sampling + mipmap LOD bias offset). The
 * mapping is a pure enum method so it is checked here without a running game; the screen only iterates it and calls Apply.
 * Every staged row must exist in the catalog, and the offset is the raw slider value (tenths, see texture_lod_bias_offset).
 */
class TexturePresetTest {

    @Test
    void pixelArtStagesPointSamplingAndASharperBias() {
        Map<String, Object> values = TexturePreset.PIXEL_ART.stagedValues();
        assertEquals(true, values.get("point_sampling_sharpness"));
        assertEquals(-3, values.get("texture_lod_bias_offset"));
    }

    @Test
    void balancedStagesNeutralSampling() {
        Map<String, Object> values = TexturePreset.BALANCED.stagedValues();
        assertEquals(false, values.get("point_sampling_sharpness"));
        assertEquals(0, values.get("texture_lod_bias_offset"));
    }

    @Test
    void softStagesASmootherBias() {
        Map<String, Object> values = TexturePreset.SOFT.stagedValues();
        assertEquals(false, values.get("point_sampling_sharpness"));
        assertEquals(3, values.get("texture_lod_bias_offset"));
    }

    @Test
    void everyPresetStagesExactlyTheTwoTextureRows() {
        for (TexturePreset preset : TexturePreset.values()) {
            assertEquals(Set.of("point_sampling_sharpness", "texture_lod_bias_offset"), preset.stagedValues().keySet(),
                preset + " must stage only the two texture-sampling rows");
        }
    }

    @Test
    void everyStagedRowExistsInTheCatalog() {
        for (TexturePreset preset : TexturePreset.values()) {
            for (String path : preset.stagedValues().keySet()) {
                assertNotNull(SettingsCatalog.get(path), path + " is staged by " + preset + " but missing from the catalog");
            }
        }
    }

    @Test
    void theOffsetStaysWithinTheSliderRange() {
        for (TexturePreset preset : TexturePreset.values()) {
            int offset = (Integer) preset.stagedValues().get("texture_lod_bias_offset");
            assertTrue(offset >= -20 && offset <= 10, preset + " offset " + offset + " is outside the texture_lod_bias_offset slider");
        }
    }

    @Test
    void theStagedValuesAreOptionCompatibleTypes() {
        for (TexturePreset preset : TexturePreset.values()) {
            Map<String, Object> values = preset.stagedValues();
            assertTrue(values.get("point_sampling_sharpness") instanceof Boolean, preset + " point sampling must be a Boolean");
            assertTrue(values.get("texture_lod_bias_offset") instanceof Integer, preset + " offset must be an Integer");
        }
    }

    @Test
    void theStylesOrderFromSharpestToSoftest() {
        int pixel = (Integer) TexturePreset.PIXEL_ART.stagedValues().get("texture_lod_bias_offset");
        int balanced = (Integer) TexturePreset.BALANCED.stagedValues().get("texture_lod_bias_offset");
        int soft = (Integer) TexturePreset.SOFT.stagedValues().get("texture_lod_bias_offset");
        assertTrue(pixel < balanced && balanced < soft, "pixel art must be sharper than balanced, balanced sharper than soft");
        assertEquals(true, TexturePreset.PIXEL_ART.stagedValues().get("point_sampling_sharpness"));
        assertEquals(false, TexturePreset.BALANCED.stagedValues().get("point_sampling_sharpness"));
        assertEquals(false, TexturePreset.SOFT.stagedValues().get("point_sampling_sharpness"));
    }
}