package io.github.andrexarlay.vulkancompat.rtx.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class F3FormatTest {
    @Test
    void generatedFpsLineShowsRealGeneratedAndShownNextToEachOther() {
        String line = F3Fps.compose("72 fps T: inf (immediate) @165Hz", 72, 144, 2);
        assertEquals("72 real + 72 gen = 144 fps (2x) T: inf (immediate) @165Hz", strip(line));
        assertTrue(line.contains(F3Text.REAL + "72 real"), line);
        assertTrue(line.contains(F3Text.GEN + "72 gen"), line);
    }

    @Test
    void generatedIsShownMinusRealNeverNegative() {
        assertTrue(strip(F3Fps.compose("90 fps T: inf", 90, 85, 2)).startsWith("90 real + 0 gen = 85 fps"));
    }

    @Test
    void fixedFormattingMatchesExpectedDigits() {
        assertEquals("6.90", F3Text.fixed(new StringBuilder(), 6.9, 2).toString());
        assertEquals("0.05", F3Text.fixed(new StringBuilder(), 0.049, 2).toString());
        assertEquals("144", F3Text.fixed(new StringBuilder(), 143.6, 0).toString());
        assertEquals("-", F3Text.fixed(new StringBuilder(), Double.NaN, 1).toString());
        assertEquals("12.3", F3Text.ms(new StringBuilder(), 12.34).toString());
    }

    @Test
    void frameTimeColoursFollowThresholds() {
        assertEquals(F3Text.OK, F3Text.frameColor(8.0));
        assertEquals(F3Text.WARN, F3Text.frameColor(8.1));
        assertEquals(F3Text.WARN, F3Text.frameColor(16.0));
        assertEquals(F3Text.ERR, F3Text.frameColor(16.1));
    }

    @Test
    void lightFieldsAreParsedFromTelemetry() {
        String t = "used=128,in_range=340,total=5400,entity_quads=1/2,capture=x";
        assertEquals(128, F3Sections.field(t, "used="));
        assertEquals(340, F3Sections.field(t, "in_range="));
        assertEquals(5400, F3Sections.field(t, "total="));
        assertEquals(-1, F3Sections.field("none", "used="));
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }
}
