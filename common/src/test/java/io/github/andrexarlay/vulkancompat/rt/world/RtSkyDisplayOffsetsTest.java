package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every field the shader reads from the display-sky packet sits where the host writes it. SKY_DISPLAY_EXPOSURE_BYTE was
 * 36, the high word of the emitter device address, while the host writes the exposure at RtStatsLayout.EXPOSURE (44).
 * With emitters in the scene that word reads as a denormal float, the visible sky was divided by it and clamped to an
 * even grey, and every mirror and glossy surface reflected that grey (autotest rt_look 2026-11-02, sky (99,97,93) vs
 * (122,181,251) in v0.1.0-alpha.1).
 */
class RtSkyDisplayOffsetsTest {
    private static final Path CONTRACT = Path.of("src/main/resources/shaders/slang/contracts/sky_display.slang");

    @Test
    void theShaderReadsEachFieldWhereTheHostWritesIt() throws IOException {
        String contract = Files.readString(CONTRACT);
        Map<String, Integer> host = Map.of(
            "SKY_DISPLAY_EXPOSURE_BYTE", RtStatsLayout.EXPOSURE,
            "SKY_DISPLAY_SUN_BYTE", RtStatsLayout.SKY_SUN_DIR,
            "SKY_DISPLAY_MOON_BYTE", RtStatsLayout.SKY_MOON_DIR,
            "SKY_DISPLAY_FOG_BYTE", RtStatsLayout.SKY_FOG_COLOR,
            "SKY_DISPLAY_SUNSET_BYTE", RtStatsLayout.SKY_SUNSET,
            "SKY_DISPLAY_CLOUD_COLOR_BYTE", RtStatsLayout.SKY_CLOUD_COLOR,
            "SKY_DISPLAY_CLOUD_POS_BYTE", RtStatsLayout.SKY_CLOUD_POS,
            "SKY_DISPLAY_MISC_BYTE", RtStatsLayout.SKY_MISC,
            "SKY_DISPLAY_ATLAS_ADDRESS_BYTE", RtStatsLayout.SKY_TEX_ADDR,
            "SKY_DISPLAY_FLAGS_BYTE", RtStatsLayout.SKY_TEX_FLAGS);
        for (Map.Entry<String, Integer> field : host.entrySet()) {
            Matcher m = Pattern.compile("static const uint " + field.getKey() + " = (\\d+)u;").matcher(contract);
            assertTrue(m.find(), field.getKey());
            assertEquals(field.getValue().intValue(), Integer.parseInt(m.group(1)), field.getKey());
        }
        Matcher tilt = Pattern.compile("static const uint SKY_DISPLAY_TILT_BYTE = (\\d+)u;").matcher(contract);
        assertTrue(tilt.find());
        assertEquals(RtStatsLayout.SKY_TILT, Integer.parseInt(tilt.group(1)));
        Matcher color = Pattern.compile("static const uint SKY_DISPLAY_COLOR_DAY_BYTE = (\\d+)u;").matcher(contract);
        assertTrue(color.find());
        assertEquals(RtSkyDisplayLayout.COLOR_DAY, Integer.parseInt(color.group(1)));
    }
}
