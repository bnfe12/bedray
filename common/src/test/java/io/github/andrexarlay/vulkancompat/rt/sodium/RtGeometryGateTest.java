package io.github.andrexarlay.vulkancompat.rt.sodium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 tests for the T8b decoupling: geometry capture ({@link RtGeometryGate#captureEnabled()}) runs for the Iris raster host
 * even with native RT off, while native rays ({@link RtGeometryGate#enabled()}) stay RT-only so the two never mix in a frame.
 */
class RtGeometryGateTest {

    @AfterEach
    void reset() {
        RtGeometryGate.setForTests(false);
        RtGeometryGate.setIrisForTests(false);
    }

    @Test
    void irisEnablesCaptureWithoutNativeRt() {
        RtGeometryGate.setForTests(false);
        RtGeometryGate.setIrisForTests(false);
        assertFalse(RtGeometryGate.irisActive());
        // RT off, iris off: capture and rays both follow only the forced flag (false in the normal test run).
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.captureEnabled());
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.enabled());

        // iris on, RT still off: capture turns on, native rays must NOT.
        RtGeometryGate.setIrisForTests(true);
        assertTrue(RtGeometryGate.irisActive());
        assertTrue(RtGeometryGate.captureEnabled(), "iris must enable geometry capture");
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.enabled(), "iris must NOT enable native rays");
    }

    @Test
    void nativeRtEnablesCaptureIndependentlyOfIris() {
        RtGeometryGate.setIrisForTests(false);
        RtGeometryGate.setForTests(true);
        assertTrue(RtGeometryGate.enabled());
        assertTrue(RtGeometryGate.captureEnabled(), "native RT must enable geometry capture");
        assertFalse(RtGeometryGate.irisActive());

        // Turning RT off while iris is on keeps capture alive for the host.
        RtGeometryGate.setForTests(false);
        RtGeometryGate.setIrisForTests(true);
        assertTrue(RtGeometryGate.captureEnabled());
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.enabled());

        // Both off: capture off (unless forced).
        RtGeometryGate.setIrisForTests(false);
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.captureEnabled());
    }

    @Test
    void irisFlagDoesNotDisturbRtBlockReason() {
        RtGeometryGate.setForTests(false);
        assertNotNull(RtGeometryGate.blockReason(), "RT off has a block reason");
        RtGeometryGate.setIrisForTests(true);
        assertNotNull(RtGeometryGate.blockReason(), "iris capture must not clear the RT block reason");
        assertEquals(RtGeometryGate.forced(), RtGeometryGate.enabled());
    }
}