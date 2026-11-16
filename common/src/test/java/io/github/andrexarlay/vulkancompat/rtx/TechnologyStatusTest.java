package io.github.andrexarlay.vulkancompat.rtx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TechnologyStatusTest {
    @Test
    void readyAdapterReportsTheDriverMultiplier() {
        String text = TechnologyStatus.describe(new TechnologyStatus.Inputs("READY", 6, true, "", false, false));
        assertTrue(text.contains("ready, 2x up to 6x"), text);
        assertEquals(5, TechnologyStatus.lines(new TechnologyStatus.Inputs("READY", 6, true, "", false, false)).size());
    }

    @Test
    void missingFilesAreNamedNotHidden() {
        String text = TechnologyStatus.describe(new TechnologyStatus.Inputs("NO_BINARIES", 0, false, "nvngx_dlss.dll missing", true, true));
        assertTrue(text.contains("Streamline files are missing"), text);
        assertTrue(text.contains("not available: nvngx_dlss.dll missing"), text);
        assertTrue(text.contains("requested but the DLSS files are not usable"), text);
    }

    @Test
    void unimplementedVendorPathsStayVisible() {
        String text = TechnologyStatus.describe(new TechnologyStatus.Inputs("FAILED", 0, true, "", false, false));
        assertTrue(text.contains("XeSS frame generation / XeLL: not implemented"), text);
        assertTrue(text.contains("FSR 3 frame generation (2x): code present"), text);
    }
}
