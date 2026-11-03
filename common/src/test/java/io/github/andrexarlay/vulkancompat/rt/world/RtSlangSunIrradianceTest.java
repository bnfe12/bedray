package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sun lights a floor as strongly as the legacy shading did. The key colour is an irradiance: the legacy direct term
 * was albedo * key * cos. The Slang sampler integrates the disc radiance over its solid angle, so with a radiance of
 * key * 1 a 0.27-degree disc delivered key * 7e-5: the noon floor of the autotest pool scene had no sunlight and no
 * shadows, lit only by the sky (rt_look, 2026-11-01 03:19). The host writes the radiance scale pi / omega in sunColor.w.
 */
class RtSlangSunIrradianceTest {

    /** What a diffuse receiver gets from the disc by NEE: albedo / pi * L * cos * omega, the sampler's estimate. */
    private static double lambertFromDisc(double albedo, double radiance, double cos, double radius) {
        double omega = 2.0 * Math.PI * (1.0 - Math.cos(radius));
        return albedo / Math.PI * radiance * cos * omega;
    }

    @Test
    void aDiffuseFloorReceivesAlbedoTimesKeyTimesCosine() {
        RtFrameParams params = new RtFrameParams();
        params.key.color[0] = 1.45f; params.key.color[1] = 1.40f; params.key.color[2] = 1.28f;
        params.key.dir[0] = 0f; params.key.dir[1] = 1f; params.key.dir[2] = 0f;
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        RtSlangFrameData.fill(frame, params, 16, 9, 0, 0);
        double radius = frame.sunDirection.w;
        double radiance = frame.sunColor.x * frame.sunColor.w;
        assertEquals(0.8 * 1.45 * 0.7, lambertFromDisc(0.8, radiance, 0.7, radius), 1.0e-4,
            "the legacy direct term, albedo * key * cos");
        assertTrue(lambertFromDisc(0.8, frame.sunColor.x, 0.7, radius) < 0.01,
            "with the key written as the radiance itself the sun is a thousandth of that");
    }

    @Test
    void theScaleIsPiOverTheSolidAngle() {
        double radius = Math.toRadians(0.27);
        double omega = 2.0 * Math.PI * (1.0 - Math.cos(radius));
        assertEquals(Math.PI / omega, RtSlangFrameData.discRadianceScale((float) radius), Math.PI / omega * 1e-4);
    }
}
