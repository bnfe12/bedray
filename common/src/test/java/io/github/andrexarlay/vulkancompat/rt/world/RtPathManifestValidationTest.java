package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The host layout-contract checks moved out of RtPathTraceSlangPass into RtPathManifestValidation: the device-capability
 * gate, the manifest/element-size validation and the ReSTIR PT reservoir budget. The frozen layout constants and records
 * stay on the pass (its public API); this guards the extracted pure logic. Dropping the two-history factor from the
 * reservoir budget, or accepting a null runtime, fails here.
 */
public class RtPathManifestValidationTest {

    @Test
    public void reservoirBudgetIsTwoHistoryFramesOfPtReservoirs() {
        assertEquals(597_196_800L, RtPathManifestValidation.ptReservoirBytes(1920, 1080),
            "2 * 1920 * 1080 * 144 B (two PT reservoir history frames)");
        assertEquals(2L * 2560 * 1440 * 144, RtPathManifestValidation.ptReservoirBytes(2560, 1440));
    }

    @Test
    public void nullRuntimeIsNotSupported() {
        assertFalse(RtPathManifestValidation.isSupported(null), "a null runtime cannot register the pass");
    }
}