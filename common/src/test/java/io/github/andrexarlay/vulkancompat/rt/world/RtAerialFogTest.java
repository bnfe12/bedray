package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtAerialFogTest {
    @Test
    void defaultLeavesTheConfiguredShareAtTheRenderDistance() {
        float sigma = RtAerialFog.sigma(1f, 256f, 0f, 0.9f, false); // clear air, sun high
        double tau = sigma * 256.0 * (256.0 / RtAerialFog.DISTANCE_REF);
        assertEquals(RtAerialFog.TRANSMITTANCE_AT_RENDER_DISTANCE, (float) Math.exp(-tau), 1.0e-3f);
        // the foreground stays clear: at a fifth of the render distance far less than a fifth of the haze is there
        double near = sigma * 51.2 * (51.2 / RtAerialFog.DISTANCE_REF);
        assertTrue(near < 0.05 * tau, "near haze " + near + " vs far " + tau);
    }

    @Test
    void strongerSettingGivesMoreHazeAndOffGivesNone() {
        float one = RtAerialFog.sigma(1f, 256f, 0f, 0.9f, false);
        float two = RtAerialFog.sigma(2f, 256f, 0f, 0.9f, false);
        assertEquals(2f * one, two, 1.0e-6f);
        assertEquals(0f, RtAerialFog.sigma(0f, 256f, 0f, 0.9f, false));
        assertEquals(0f, RtAerialFog.sigma(1f, 256f, 0f, 0.9f, true), "under water the water fog is used");
    }

    @Test
    void rainAndLowSunThickenTheAirAndSmallRenderDistanceToo() {
        float clear = RtAerialFog.sigma(1f, 256f, 0f, 0.9f, false);
        assertTrue(RtAerialFog.sigma(1f, 256f, 1f, 0.9f, false) > 2.3f * clear);
        assertTrue(RtAerialFog.sigma(1f, 256f, 0f, 0.0f, false) > 2.1f * clear);
        assertTrue(RtAerialFog.sigma(1f, 128f, 0f, 0.9f, false) > 1.9f * clear);
        // a silly render distance is clamped, never zero or infinite
        float tiny = RtAerialFog.sigma(1f, 1f, 0f, 0.9f, false);
        float huge = RtAerialFog.sigma(1f, 1.0e9f, 0f, 0.9f, false);
        assertTrue(tiny > 0f && Float.isFinite(tiny) && huge > 0f && Float.isFinite(huge));
    }

    @Test
    void colourIsPalerThanTheSkyAndTintedByALowSun() {
        float[] sky = {0.47f, 0.65f, 1.0f};
        int high = RtAerialFog.packedColor(sky, new float[] {1.45f, 1.39f, 1.23f}, 0.9f);
        int r = high & 63, g = (high >> 6) & 63, b = (high >> 12) & 63;
        assertTrue(r > Math.round(0.47f * 63f) && g > Math.round(0.65f * 63f), "paler than the zenith sky colour");
        assertEquals(63, b);
        assertEquals(0, high >>> 18, "the colour uses 18 bits only");
        int low = RtAerialFog.packedColor(sky, new float[] {0.86f, 0.40f, 0.12f}, 0.02f); // sunset key light
        assertTrue((low & 63) > r && ((low >> 12) & 63) < b, "a low orange sun warms the haze");
    }

    @Test
    void cameraHeightSurvivesThePackingWithoutDisturbingTheColour() {
        int colour = RtAerialFog.packedColor(new float[] {0.47f, 0.65f, 1.0f}, new float[] {1f, 1f, 1f}, 0.9f);
        for (double y : new double[] {-64, 0, 63.5, 175.25, 320, 575}) {
            int packed = RtAerialFog.pack(colour, y);
            assertEquals(colour, packed & 0x3FFFF);
            assertEquals((float) y, RtAerialFog.unpackHeight(packed), 0.05f, "height " + y);
        }
        // out of range is clamped, never wraps
        assertEquals(-64f, RtAerialFog.unpackHeight(RtAerialFog.pack(colour, -500)), 0.05f);
        assertEquals(576f, RtAerialFog.unpackHeight(RtAerialFog.pack(colour, 5000)), 0.05f);
    }

    @Test
    void blackSkyGivesBlackHazeNotNaN() {
        assertEquals(0, RtAerialFog.packedColor(new float[] {0f, 0f, 0f}, new float[] {0f, 0f, 0f}, -1f));
    }
}
