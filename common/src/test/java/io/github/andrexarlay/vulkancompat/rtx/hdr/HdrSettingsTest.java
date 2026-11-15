package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: HDR settings parsing, persistence and the swapchain decision (no GPU). */
public class HdrSettingsTest {
    @Test
    public void parsesOutput() {
        assertEquals(HdrSettings.Output.OFF, HdrSettings.parseOutput("off"));
        assertEquals(HdrSettings.Output.OFF, HdrSettings.parseOutput("\"SDR\""));
        assertEquals(HdrSettings.Output.HDR10, HdrSettings.parseOutput("HDR10"));
        assertEquals(HdrSettings.Output.HDR10, HdrSettings.parseOutput("on"));
        assertNull(HdrSettings.parseOutput(null));
        assertNull(HdrSettings.parseOutput("dolby"));
    }

    @Test
    public void tomlRoundTripAndClamps() throws Exception {
        HdrSettings.applyKeyValue("hdr_output", "HDR10");
        HdrSettings.applyKeyValue("hdr_paper_white", "250");
        HdrSettings.applyKeyValue("hdr_peak", "2000");
        StringWriter w = new StringWriter();
        HdrSettings.write(w);
        String t = w.toString();
        assertTrue(t.contains("[hdr]"));
        assertTrue(t.contains("hdr_output = \"HDR10\""));
        assertTrue(t.contains("hdr_paper_white = 250"));
        assertTrue(t.contains("hdr_peak = 2000"));
        HdrSettings.applyKeyValue("hdr_paper_white", "9999");
        assertEquals(HdrSettings.PAPER_WHITE_MAX, HdrSettings.paperWhiteNits());
        HdrSettings.setPaperWhiteNits(200);
        HdrSettings.applyKeyValue("hdr_peak", "1");
        assertEquals(HdrSettings.PEAK_MIN, HdrSettings.peakNits());
        HdrSettings.applyKeyValue("hdr_peak", "nonsense"); // keeps the value, no exception
        assertEquals(HdrSettings.PEAK_MIN, HdrSettings.peakNits());
        HdrSettings.applyKeyValue("hdr_output", "OFF");
        HdrSettings.setPaperWhiteNits(200);
        HdrSettings.setPeakNits(1000);
    }

    @Test
    public void peakNeverBelowPaperWhite() {
        HdrSettings.setPaperWhiteNits(500);
        HdrSettings.setPeakNits(400);
        assertEquals(500, HdrSettings.peakNits());
        HdrSettings.setPaperWhiteNits(200);
        HdrSettings.setPeakNits(1000);
    }

    @Test
    public void findsHdr10PairOnly() {
        int[] fmt = {44, 50, 97, 64, 64};
        int[] cs = {0, 0, 1000104002, 1000104008, 0};
        assertEquals(3, HdrOutput.findHdr10(fmt, cs));
        assertEquals(-1, HdrOutput.findHdr10(new int[] {64}, new int[] {0}));
        assertEquals(-1, HdrOutput.findHdr10(new int[0], new int[0]));
    }

    @Test
    public void decisionHonoursFrameGenerationAndDriver() {
        assertTrue(HdrOutput.decide(true, false, 3, new ArrayList<>()));
        assertFalse(HdrOutput.decide(true, true, 3, new ArrayList<>()));
        assertFalse(HdrOutput.decide(true, false, -1, new ArrayList<>()));
        assertFalse(HdrOutput.decide(false, false, 3, new ArrayList<>()));
    }

    @Test
    public void swapchainColorSpaceFollowsActiveState() {
        HdrOutput.setActive(false);
        assertEquals(0, HdrOutput.swapchainColorSpace(0));
        HdrOutput.setActive(true);
        assertEquals(HdrOutput.COLOR_SPACE_HDR10, HdrOutput.swapchainColorSpace(0));
        HdrOutput.setActive(false);
    }
}
