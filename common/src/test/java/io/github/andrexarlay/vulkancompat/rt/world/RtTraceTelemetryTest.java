package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtTraceTelemetryTest {
    @Test void performanceReconstructionDoesNotCountOutputPixelsAsPrimarySamples() {
        var measured = new RtTraceTelemetry(1280, 720, 2560, 1440, 1, 2);
        assertEquals(921_600, measured.tracePixels());
        assertEquals(3_686_400, measured.outputPixels());
        assertEquals(921_600, measured.primarySamples());
        assertEquals("1280x720", measured.traceExtent());
        assertEquals("2560x1440", measured.outputExtent());
    }

    @Test void effectiveSamplingIsCapturedIndependentlyOfTheNextResolution() {
        var beforeResize = new RtTraceTelemetry(1920, 1080, 1920, 1080, 2, 3);
        var afterResize = new RtTraceTelemetry(960, 540, 1920, 1080, 1, 1);
        assertEquals(4_147_200, beforeResize.primarySamples());
        assertEquals(518_400, afterResize.primarySamples());
        assertEquals(2, beforeResize.spp());
        assertEquals(3, beforeResize.bounces());
        assertEquals("1920x1080", beforeResize.traceExtent());
    }

    @Test void emptyDimensionsAndInvalidSamplingCannotBecomePlausibleMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> new RtTraceTelemetry(0, 720, 2560, 1440, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new RtTraceTelemetry(1280, 720, -1, 1440, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new RtTraceTelemetry(1280, 720, 2560, 1440, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new RtTraceTelemetry(1280, 720, 2560, 1440, 1, -1));
    }
}
