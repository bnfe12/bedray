package io.github.andrexarlay.vulkancompat.rt.hand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconRtLightTest {
    private static int ch(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    @Test
    void nightStaysReadable() {
        int c = IconRtLight.iconArgb(0xFF000000);
        // black sky: hue is undefined, every channel is brightness * (1 - STRENGTH) at least
        float floor = IconRtLight.MIN_BRIGHTNESS * (1.0f - IconRtLight.STRENGTH) * 255.0f - 1.0f;
        assertTrue(ch(c, 16) >= floor && ch(c, 8) >= floor && ch(c, 0) >= floor, "night icon too dark: " + Integer.toHexString(c));
        assertEquals(0xFF, c >>> 24);
    }

    @Test
    void daySkyKeepsFullBrightnessAndTintsTowardsTheSky() {
        int c = IconRtLight.iconArgb(0xFF80A0FF); // blue-ish day sky, max channel 1.0
        assertEquals(0xFF, ch(c, 0), "dominant channel keeps full brightness");
        assertTrue(ch(c, 16) < ch(c, 0), "red is below blue for a blue sky");
        assertTrue(ch(c, 16) > 160, "tint stays mild");
    }

    @Test
    void greySkyGivesGreyIcons() {
        int c = IconRtLight.iconArgb(0xFF808080);
        assertEquals(ch(c, 16), ch(c, 8));
        assertEquals(ch(c, 8), ch(c, 0));
    }

    @Test
    void offByDefault() {
        // no property set in the test JVM: begin() must not arm the override
        IconRtLight.begin();
        assertTrue(!IconRtLight.overriding());
    }
}
