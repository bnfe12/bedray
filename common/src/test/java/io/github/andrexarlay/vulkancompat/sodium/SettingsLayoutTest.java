package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class SettingsLayoutTest {
    @Test
    void compactLayoutFitsAtCommonGuiScales() {
        for (int[] size : new int[][] {{320, 180}, {480, 270}, {640, 360}, {960, 540}, {1920, 1080}}) {
            var layout = SettingsLayout.measure(size[0], size[1]);
            assertEquals(layout.frameX(), size[0] - layout.frameX() - layout.frameWidth(), 1);
            assertTrue(layout.frameWidth() <= 536);
            assertEquals(layout.frameX() + layout.frameWidth(), layout.contentX() + layout.contentWidth());
            int reviewWidth = layout.reviewWidth(145);
            assertTrue(reviewWidth <= layout.contentWidth() / 3);
            int searchWidth = layout.searchWidth(reviewWidth);
            assertTrue(searchWidth <= layout.controlWidth());
            assertTrue(searchWidth + SettingsLayout.GAP + reviewWidth <= layout.contentWidth());
            assertTrue(SettingsLayout.SEARCH_HEIGHT < SettingsLayout.CONTROL_HEIGHT);
            assertTrue(layout.listBottom() - layout.listTop() >= SettingsLayout.ROW_HEIGHT + SettingsLayout.CONTROL_HEIGHT);
            assertTrue(layout.listBottom() < layout.footerY());
            assertTrue(layout.footerY() + SettingsLayout.FOOTER_HEIGHT <= size[1]);
            for (int i = 0; i < SettingsDraft.Category.values().length; i++) {
                int x = layout.navX(i);
                int right = x + layout.navWidth(i);
                int bottom = layout.navY(i) + SettingsLayout.NAV_HEIGHT;
                assertTrue(x >= layout.frameX());
                if (layout.sidebar()) {
                    assertTrue(right < layout.contentX());
                    assertTrue(bottom <= layout.listBottom());
                } else {
                    assertTrue(right <= layout.contentX() + layout.contentWidth());
                    assertTrue(bottom < layout.listTop());
                }
            }
        }
    }

    @Test
    void compactTabsReplaceTheSidebarAtLargeGuiScalesAndInShortWindows() {
        var wide = SettingsLayout.measure(640, 360);
        assertTrue(wide.sidebar());
        assertEquals(wide.navX(0), wide.navX(6));
        assertTrue(wide.navY(6) > wide.navY(0));
        var narrow = SettingsLayout.measure(320, 180);
        assertFalse(narrow.sidebar());
        assertEquals(narrow.navY(0), narrow.navY(3));
        assertTrue(narrow.navY(6) > narrow.navY(0));
        assertFalse(SettingsLayout.measure(960, 198).sidebar());
    }

    @Test
    void menuTransparencyChangesBothLayers() {
        assertEquals(255, SettingsChrome.menuTop(0) >>> 24);
        assertEquals(102, SettingsChrome.menuTop(VulkanRtxConfig.MAX_MENU_TRANSPARENCY) >>> 24);
        assertEquals(102, SettingsChrome.menuBottom(VulkanRtxConfig.MAX_MENU_TRANSPARENCY) >>> 24);
        assertNotEquals(SettingsChrome.menuTop(0), SettingsChrome.menuBottom(0));
        assertTrue((SettingsChrome.worldShade(0) >>> 24) > (SettingsChrome.worldShade(VulkanRtxConfig.MAX_MENU_TRANSPARENCY) >>> 24));
        assertEquals(SettingsDraft.Category.SYSTEM, SettingsDraft.category("ui_menu_transparency"));
    }

    @Test
    void selectionFadeStartsClearAndSettlesWithinOneShortTransition() {
        long started = 10L;
        assertEquals(1f, SettingsChrome.selectionOpacity(started, 0L));
        assertEquals(0f, SettingsChrome.selectionOpacity(started, started));
        float halfway = SettingsChrome.selectionOpacity(started + 65_000_000L, started);
        assertTrue(halfway > 0.5f && halfway < 1f);
        assertEquals(1f, SettingsChrome.selectionOpacity(started + 130_000_000L, started));
    }

    @Test
    void menuTransparencySurvivesConfigAndStaysOutOfRenderPresets() throws IOException {
        int oldValue = VulkanRtxConfig.menuTransparency;
        try {
            VulkanRtxConfig.applyKeyValue("ui_menu_transparency", "87");
            assertEquals(VulkanRtxConfig.MAX_MENU_TRANSPARENCY, VulkanRtxConfig.menuTransparency);
            VulkanRtxConfig.applyKeyValue("ui_menu_transparency", "28");
            StringWriter output = new StringWriter();
            VulkanRtxConfig.writeAll(output);
            assertTrue(output.toString().lines().anyMatch("ui_menu_transparency = 28"::equals));
            assertFalse(PresetStore.snapshot().lines().anyMatch(line -> line.startsWith("ui_menu_transparency")));
        } finally {
            VulkanRtxConfig.menuTransparency = oldValue;
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
