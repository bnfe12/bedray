package io.github.andrexarlay.vulkancompat.rt.hand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L2: the hand light estimate is monotonic, warm for block light and never fully black. */
class HandRtProbeTest {
    private static final float[] DAY_KEY = {1.4f, 1.3f, 1.2f};
    private static final float[] AMB = {0.3f, 0.32f, 0.36f};

    private static int ch(int argb, int shift) { return (argb >> shift) & 0xFF; }

    @Test
    void darkCaveKeepsTheFloor() {
        int c = HandRtProbe.argb(0, 0, DAY_KEY, AMB, 1f);
        assertTrue(ch(c, 16) > 0 && ch(c, 8) > 0 && ch(c, 0) > 0);
        assertEquals(0xFF, c >>> 24);
    }

    @Test
    void moreSkyLightIsBrighter() {
        assertTrue(ch(HandRtProbe.argb(0, 15, DAY_KEY, AMB, 1f), 8) > ch(HandRtProbe.argb(0, 5, DAY_KEY, AMB, 1f), 8));
    }

    @Test
    void blockLightIsWarm() {
        int c = HandRtProbe.argb(15, 0, DAY_KEY, AMB, 1f);
        assertTrue(ch(c, 16) > ch(c, 0));
    }

    @Test
    void neverExceedsWhite() {
        int c = HandRtProbe.argb(15, 15, new float[]{50f, 50f, 50f}, new float[]{9f, 9f, 9f}, 1f);
        assertTrue(ch(c, 16) <= 255 && ch(c, 8) <= 255 && ch(c, 0) <= 255);
    }

    @Test
    void lowerExposureGivesADarkerHand() {
        assertTrue(ch(HandRtProbe.argb(0, 15, DAY_KEY, AMB, 0.2f), 8) < ch(HandRtProbe.argb(0, 15, DAY_KEY, AMB, 1f), 8));
    }
}
