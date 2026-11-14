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
            assertTrue(layout.footerY() + SettingsLayout.CONTROL_HEIGHT <= size[1]);
            for (int count : new int[] {layout.columns(), 4}) {
                assertEquals(layout.contentX(), layout.gridEdge(0, count));
                assertEquals(layout.contentX() + layout.contentWidth(),
                    layout.gridEdge(count - 1, count) + layout.gridWidth(count - 1, count));
                for (int i = 0; i < count; i++) {
                    assertTrue(layout.gridWidth(i, count) >= 60);
                }
            }
        }
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
    }
}
