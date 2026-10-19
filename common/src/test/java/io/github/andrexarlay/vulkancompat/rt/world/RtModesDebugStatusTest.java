package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RtModesDebugStatusTest {
    @Test
    void directOffRequestShowsNoActiveInstance() {
        assertEquals("requested=off selected=off runtime=inactive",
            RtModesDebug.denoiserStatusLine(RtSettings.PathDenoiser.OFF, DenoiserMode.OFF, "inactive", "setting", null));
    }

    @Test
    void fallbackShowsRequestedAndActuallySelectedDenoiserAndReason() {
        String line = RtModesDebug.denoiserStatusLine(RtSettings.PathDenoiser.DLSS_RR, DenoiserMode.NRD_REBLUR,
            "active", "dlss_rr_library_unusable -> fallback_to_nrd", "dlss_rr_init: unsupported adapter");

        assertTrue(line.contains("requested=dlss_rr selected=nrd_reblur runtime=active"), line);
        assertTrue(line.contains("reason=dlss_rr_library_unusable -> fallback_to_nrd"), line);
        assertTrue(line.contains("last_failure=dlss_rr_init: unsupported adapter"), line);
    }

    @Test
    void pendingDropIsNotReportedAsActive() {
        String line = RtModesDebug.denoiserStatusLine(RtSettings.PathDenoiser.NRD, DenoiserMode.NRD_RELAX,
            "pending-drop", "setting", "nrd_record: invalid state");

        assertTrue(line.contains("selected=nrd_relax runtime=pending-drop"), line);
        assertTrue(line.contains("last_failure=nrd_record: invalid state"), line);
        assertFalse(line.contains("runtime=active"), line);
    }

    @Test
    void reflectionSettingsShowConfiguredCostControlsOnlyWhenEnabled() {
        assertEquals(" q3 d256", RtModesDebug.reflectionSettings(true, 3, 256));
        assertEquals("", RtModesDebug.reflectionSettings(false, 3, 256));
    }
}
