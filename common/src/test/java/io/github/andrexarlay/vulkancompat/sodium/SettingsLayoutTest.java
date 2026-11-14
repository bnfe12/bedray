package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsLayoutTest {
    @Test
    void compactLayoutFitsAtCommonGuiScales() {
        for (int[] size : new int[][] {{320, 180}, {480, 270}, {640, 360}, {960, 540}, {1920, 1080}}) {
            var layout = SettingsLayout.measure(size[0], size[1]);
            assertEquals(layout.contentX(), size[0] - layout.contentX() - layout.contentWidth(), 1);
            assertTrue(layout.contentWidth() <= 500);
            assertTrue(layout.listBottom() - layout.listTop() >= SettingsLayout.CONTROL_HEIGHT);
            assertTrue(layout.listBottom() < layout.footerY());
            assertTrue(layout.footerY() + SettingsLayout.FOOTER_HEIGHT <= size[1]);
            int columns = layout.columns();
            assertEquals(layout.contentX(), layout.gridEdge(0, columns));
            assertEquals(layout.contentX() + layout.contentWidth(),
                layout.gridEdge(columns - 1, columns) + layout.gridWidth(columns - 1, columns));
            for (int i = 0; i < columns; i++) {
                assertTrue(layout.gridWidth(i, columns) >= 60);
            }
        }
    }

    @Test
    void visualPresetsOnlyStageDisplayChoices() {
        var displayOptions = java.util.Set.of("tonemapper", "vibrant_visuals", "atm_bloom",
            "atm_bloom_strength", "atm_bloom_scatter", "vignette", "film_grain");
        for (SettingsLook style : SettingsLook.values()) {
            assertEquals(displayOptions, style.valuesToStage().keySet());
        }
        assertEquals(10, SettingsDraft.persistedNumberScale("manual_texture_lod_bias"));
        assertEquals(100, SettingsDraft.persistedNumberScale("atm_bloom_scatter"));
        assertEquals(1, SettingsDraft.persistedNumberScale("atm_godrays_quality"));
    }

    @Test
    void reconstructionAndGenerationAreReachableFromImageAndFps() {
        for (String path : new String[] {"upscaler_backend", "dlss_custom_scale", "nis_custom_scale",
                "path_denoiser", "dlss_rr_preset", "frame_generation_mode", "frame_generation_provider"}) {
            assertEquals(SettingsDraft.Category.UPSCALING, SettingsDraft.category(path), path);
        }
        assertTrue(SettingsDraft.rowOrder("upscaler_backend") < SettingsDraft.rowOrder("dlss_custom_scale"));
        assertTrue(SettingsDraft.rowOrder("path_denoiser") < SettingsDraft.rowOrder("frame_generation_mode"));
        assertTrue(SettingsDraft.rowOrder("frame_generation_mode") < SettingsDraft.rowOrder("dlss_rr_preset"));
        assertEquals(SettingsDraft.Category.WORLD, SettingsDraft.category("rays_indirect_translucent_shadows"));
        assertEquals(SettingsDraft.Category.SYSTEM, SettingsDraft.category("renderer_status"));
        assertEquals(SettingsDraft.Category.EFFECTS, SettingsDraft.category("atm_bloom_threshold"));
        assertEquals(SettingsDraft.Category.EFFECTS, SettingsDraft.category("rt_fog_distance_scale"));
        assertEquals(SettingsDraft.Category.LIGHTING, SettingsDraft.category("sun_tilt_deg"));
        assertEquals(SettingsDraft.Category.UPSCALING, SettingsDraft.category("nis_sharpness"));
        assertTrue(SettingsDraft.rowOrder("atm_bloom") < SettingsDraft.rowOrder("atm_bloom_threshold"));
    }
}
