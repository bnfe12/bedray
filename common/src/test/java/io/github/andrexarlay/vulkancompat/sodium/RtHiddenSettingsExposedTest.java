package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards that three renderer-wired settings which used to be reachable only through bedray.toml or a JVM override now
 * have a menu row bound to the right field, a catalog description, and a live reader in the renderer. A row that changes
 * nothing is the failure this prevents. The string assertions are mutation-proved.
 */
class RtHiddenSettingsExposedTest {
    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }

    @Test void menuBindsThePreviouslyHiddenRows() throws Exception {
        String menu = java("sodium/VulkanSodiumConfig.java");
        assertTrue(menu.contains("boolOpt(builder, \"path_accumulate\", true, v -> RtSettings.pathAccumulate = v, () -> RtSettings.pathAccumulate,"),
            "path_accumulate must be a row bound to RtSettings.pathAccumulate");
        assertTrue(menu.contains("intOpt(builder, \"sun_tilt_deg\", 24, 0, 45, 1, unit(\"°\"), v -> RtSettings.sunTiltDeg = v,"),
            "sun_tilt_deg must be a 0..45 row bound to RtSettings.sunTiltDeg");
        assertTrue(menu.contains("intOpt(builder, \"baked_light_fill_pct\", 0, 0, 100, 5, percent(), v -> RtSettings.bakedLightFillPct = v,"),
            "baked_light_fill_pct must be a 0..100 row bound to RtSettings.bakedLightFillPct");
    }

    @Test void theRowsAreGatedToTheModeThatReadsThem() throws Exception {
        String menu = java("sodium/VulkanSodiumConfig.java");
        // path_accumulate lives in the Path Tracing group; the baked fill is rays-only and must hide in path mode.
        assertTrue(menu.contains("() -> RtSettings.pathAccumulate,\n            pathOn, dMode, Mark.PATH"),
            "path_accumulate must be gated to path mode");
        assertTrue(menu.contains("() -> RtSettings.bakedLightFillPct, raysOn, dMode, Mark.RT, OptionImpact.LOW, true, st"),
            "baked_light_fill_pct must be rays-only and hide its row when disabled");
    }

    @Test void catalogDescribesTheExposedSettings() throws Exception {
        String catalog = java("sodium/SettingsCatalog.java");
        assertTrue(catalog.contains("putNoImage(\"path_accumulate\", Tier.ADVANCED, \"Temporal Accumulation\","));
        assertTrue(catalog.contains("putNoImage(\"sun_tilt_deg\", Tier.EXPERT, \"Sun Arc Tilt\","));
        assertTrue(catalog.contains("putNoImage(\"baked_light_fill_pct\", Tier.EXPERT, \"Baked Light Fill\","));
    }

    @Test void theExposedSettingsReachTheRenderer() throws Exception {
        // The getters the rows write must be read by the renderer, or the row is a switch that changes nothing.
        assertTrue(java("rt/world/RtSkyState.java").contains("RtSettings.sunTiltRad()"),
            "sun_tilt_deg must drive the celestial arc");
        assertTrue(java("rt/world/RtFrameRenderer.java").contains("RtSettings.bakedLightFill()"),
            "baked_light_fill_pct must drive the baked fill");
        assertTrue(java("rt/world/RtSlangPathPass.java").contains("RtSettings.accumulate()"),
            "path_accumulate must drive the accumulation flag");
    }
}