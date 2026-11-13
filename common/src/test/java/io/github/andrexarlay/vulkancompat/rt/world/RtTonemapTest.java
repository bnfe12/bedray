package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CPU mirror of the player-selectable tone curves ({@code tonemap_curve.slang} {@code tonemapColorMode}). Mode 0 must stay
 * byte-for-byte the peak-channel curve of {@link RtTonemap#apply}; modes 1..4 are ACES, Khronos PBR Neutral, Gran Turismo
 * and AgX. The assertions pin known anchors, monotonicity, range and that the curves are genuinely different looks.
 */
class RtTonemapTest {
    private static final int MODES = 5;

    private static float luma(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    @Test void mode0IsTheExistingPeakChannelCurve() {
        float[][] samples = {{0, 0, 0}, {0.18f, 0.18f, 0.18f}, {1, 0.5f, 0.25f}, {4, 2, 1}, {64, 64, 64}};
        for (float[] s : samples) {
            assertArrayEquals(RtTonemap.apply(s[0], s[1], s[2]), RtTonemap.applyMode(s[0], s[1], s[2], 0), 1e-6f,
                "mode 0 must remain the shipped peak-channel curve");
        }
    }

    @Test void everyModeMapsBlackToBlackAndStaysInRange() {
        for (int m = 0; m < MODES; m++) {
            float[] black = RtTonemap.applyMode(0, 0, 0, m);
            for (float c : black) {
                assertTrue(c >= 0f && c <= 1f, "mode " + m + " out of range: " + c);
                assertEquals(0f, c, 1e-3f, "mode " + m + " must map black to black");
            }
        }
    }

    @Test void everyModeIsMonotonicOnAGrayRamp() {
        float[] ramp = {0f, 0.05f, 0.18f, 0.5f, 1f, 2f, 4f, 8f, 16f, 64f};
        for (int m = 0; m < MODES; m++) {
            float prev = -1f;
            for (float v : ramp) {
                float l = luma(RtTonemap.applyMode(v, v, v, m));
                assertTrue(l >= prev - 1e-4f, "mode " + m + " not monotonic at " + v + ": " + l + " < " + prev);
                assertTrue(l >= 0f && l <= 1f, "mode " + m + " luminance out of range at " + v);
                prev = l;
            }
        }
    }

    @Test void everyModeApproachesWhiteForABrightInput() {
        for (int m = 0; m < MODES; m++) {
            float l = luma(RtTonemap.applyMode(64, 64, 64, m));
            assertTrue(l > 0.9f, "mode " + m + " should be near white at 64, got " + l);
        }
    }

    @Test void acesMatchesTheNarkowiczAnchor() {
        // (1*(2.51+0.03)) / (1*(2.43+0.59)+0.14) = 2.54 / 3.16
        float[] c = RtTonemap.applyMode(1, 1, 1, 1);
        assertEquals(2.54f / 3.16f, decodeSrgb(c[0]), 1e-4f);
        assertEquals(c[0], c[1], 1e-6f);
    }

    @Test void pbrNeutralPreservesLowColoursMinusTheOffset() {
        // min 0.5 >= 0.08 -> offset 0.04; peak 0.46 < startCompression 0.76 -> colour - offset, no compression
        float[] c = RtTonemap.applyMode(0.5f, 0.5f, 0.5f, 2);
        assertEquals(0.46f, decodeSrgb(c[0]), 1e-4f);
    }

    private static float decodeSrgb(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    @Test void standardCurvesEncodeDisplayLinearOutputExactlyOnce() {
        // Khronos PBR Neutral specifies linear output: mid gray minus the 0.04 Fresnel offset is 0.14 linear.
        assertEquals(0.14f, decodeSrgb(RtTonemap.applyMode(0.18f, 0.18f, 0.18f, 2)[0]), 1e-5f);
        // Uchimura's linear section passes its middle-gray knot through unchanged before display encoding.
        assertEquals(0.22f, decodeSrgb(RtTonemap.applyMode(0.22f, 0.22f, 0.22f, 3)[0]), 1e-5f);
        // The minimal AgX reference maps 18% gray near a 0.5 display signal, not a second encoded 0.74 signal.
        assertEquals(0.5f, RtTonemap.applyMode(0.18f, 0.18f, 0.18f, 4)[0], 0.025f);
    }

    @Test void theModesAreGenuinelyDifferentLooks() {
        float r = 1f, g = 0.5f, b = 0.25f;
        float[][] out = new float[MODES][];
        for (int m = 0; m < MODES; m++) {
            out[m] = RtTonemap.applyMode(r, g, b, m);
        }
        for (int a = 0; a < MODES; a++) {
            for (int b2 = a + 1; b2 < MODES; b2++) {
                float diff = Math.abs(out[a][0] - out[b2][0]) + Math.abs(out[a][1] - out[b2][1])
                    + Math.abs(out[a][2] - out[b2][2]);
                assertTrue(diff > 1e-3f, "modes " + a + " and " + b2 + " are the same curve");
            }
        }
    }

    @Test void nonFiniteInputIsSanitised() {
        for (int m = 0; m < MODES; m++) {
            float[] c = RtTonemap.applyMode(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, m);
            for (float v : c) {
                assertTrue(Float.isFinite(v) && v >= 0f && v <= 1f, "mode " + m + " leaked a non-finite value: " + v);
            }
        }
    }
}
