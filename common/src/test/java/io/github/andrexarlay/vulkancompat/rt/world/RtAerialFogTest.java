package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtAerialFogTest {
    @Test
    void defaultLeavesTheConfiguredShareAtTheRenderDistance() {
        float sigma = RtAerialFog.sigma(1f, 256f, 0f, 0.9f, false); // clear air, sun high
        assertEquals(RtAerialFog.TRANSMITTANCE_AT_RENDER_DISTANCE, (float) Math.exp(-sigma * 256f), 1.0e-3f);
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
        int r = high & 255, g = (high >> 8) & 255, b = (high >> 16) & 255;
        assertTrue(r > Math.round(0.47f * 255f) && g > Math.round(0.65f * 255f), "paler than the zenith sky colour");
        assertEquals(255, b);
        int low = RtAerialFog.packedColor(sky, new float[] {0.86f, 0.40f, 0.12f}, 0.02f); // sunset key light
        assertTrue((low & 255) > r && ((low >> 16) & 255) < b, "a low orange sun warms the haze");
    }

    @Test
    void blackSkyGivesBlackHazeNotNaN() {
        assertEquals(0, RtAerialFog.packedColor(new float[] {0f, 0f, 0f}, new float[] {0f, 0f, 0f}, -1f));
    }
}
