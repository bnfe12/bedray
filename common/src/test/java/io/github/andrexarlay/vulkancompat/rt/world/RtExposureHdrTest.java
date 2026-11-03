package io.github.andrexarlay.vulkancompat.rt.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtExposureHdrTest {
    private static void observe(RtExposure meter, double luminance, float dt, int frames) {
        int samples = 1024;
        long sum = Math.round((Math.log(luminance) / Math.log(2) + 16) * 64 * samples);
        for (int i = 0; i < frames; ++i) meter.observe(sum, samples, dt);
    }
    @Test void multipleBrightEmittersCanDarkenBelowTheFixedRasterExposure() {
        var meter = new RtExposure();
        observe(meter, 4, 1f / 60, 600);
        assertTrue(meter.value() < .2f);
        assertTrue(meter.value() >= RtExposure.MIN);
        assertEquals(RtExposure.FIXED, meter.applied(false, 0));
    }
    @Test void extremeHdrRetainsAFiniteBoundAndResetRestoresTheFixedStart() {
        var meter = new RtExposure();
        observe(meter, 1024, 1f / 60, 900);
        assertEquals(RtExposure.MIN, meter.value(), .002f);
        meter.reset();
        assertEquals(RtExposure.FIXED, meter.value());
    }
    @Test void enteringABrightRoomRespondsInSecondsAtBoth15And120Fps() {
        var low = new RtExposure(); var high = new RtExposure();
        observe(low, .004, 1f / 15, 150); observe(high, .004, 1f / 120, 1200);
        observe(low, 16, 1f / 15, 60); observe(high, 16, 1f / 120, 480);
        assertTrue(low.value() < .25f && high.value() < .25f);
        assertEquals(low.value(), high.value(), .025f);
    }
    @Test void missingMeasurementsKeepThePreviousExposure() {
        var meter = new RtExposure();
        observe(meter, 16, 1f / 60, 600);
        float value = meter.value();
        for (int i = 0; i < 600; ++i) meter.observe(0, 0, 1f / 60);
        assertEquals(value, meter.value());
    }
}
