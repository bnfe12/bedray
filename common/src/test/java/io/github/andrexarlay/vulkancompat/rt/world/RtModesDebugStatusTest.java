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

    /** The ray profile's F3 line names every switch and range, so a screenshot shows what the frame traced. */
    @Test
    void rayEffectsLineNamesTheSwitchesTheProfilesRead() {
        boolean sShadows = RtSettings.raysShadows;
        boolean sSoft = RtSettings.raysSoftShadows;
        boolean sRefl = RtSettings.raysReflections;
        boolean sAo = RtSettings.raysAo;
        boolean sGi = RtSettings.raysGi;
        boolean sEmissive = RtSettings.raysEmissive;
        try {
            RtSettings.raysShadows = true;
            RtSettings.raysSoftShadows = true;
            RtSettings.raysReflections = false;
            RtSettings.raysAo = true;
            RtSettings.raysGi = false;
            RtSettings.raysEmissive = true;
            assertEquals("[RT-EFFECTS] shadows=on soft=on reflections=off diffuse=AO_ONLY emissive=on"
                    + " distances=refl" + RtSettings.reflectionDistance() + " gi" + RtSettings.giDistance()
                    + " ao" + RtSettings.aoRadius() + " emis" + RtSettings.emissiveDistance(),
                RtModesDebug.rayEffectsLine());

            RtSettings.raysGi = true;
            assertTrue(RtModesDebug.rayEffectsLine().contains("diffuse=GI"), "GI supersedes the ambient-only bounce");

            RtSettings.raysGi = false;
            RtSettings.raysAo = false;
            assertTrue(RtModesDebug.rayEffectsLine().contains("diffuse=OFF"),
                "no GI and no AO means no diffuse continuation at all");
        } finally {
            RtSettings.raysShadows = sShadows;
            RtSettings.raysSoftShadows = sSoft;
            RtSettings.raysReflections = sRefl;
            RtSettings.raysAo = sAo;
            RtSettings.raysGi = sGi;
            RtSettings.raysEmissive = sEmissive;
        }
    }
}
