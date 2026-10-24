package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source check for the reach of block light (torches, lanterns, glowstone) in {@code emitterPhat}.
 *
 * <p>The light of one emitter used to follow the physical {@code d^-2} beyond 2 blocks. At 8 blocks that keeps 5.6% of the
 * value it has at 2 blocks and at 15 blocks 1.2%, so on the testbed a torch lit a 4-5 block puddle and the rest of the room
 * stayed black (owner report 2026-10-23: "the distance the torch light reaches is too small"), while the block light of the
 * game itself is a level 15 that reaches 15 blocks. The tail is now {@code d^-1.25}: about 2.8x brighter at 8 blocks and 4x
 * at 15, near the source unchanged, and the {@code distFactor} window still ends every source at {@link #EMITTER_RADIUS}.
 *
 * <p>The numbers below mirror the shader so a change of the exponent or of the handover constant has to be made on purpose:
 * the test also requires the shader to contain them verbatim.
 */
public class RtBlockLightReachTest {

    private static final String EMITTERS = "/shaders/rt_common_emitters.glsl";
    private static final String SURFACE = "/shaders/rt_common_surface.glsl";

    /** The shader's handover constant: 2^1.25 / (2 * sqrt(2)), the value of d^-1.5 at d = 2. */
    private static final double HANDOVER = 0.84090;
    private static final double EMITTER_RADIUS = 24.0;

    /** Attenuation of one emitter at {@code d} blocks, as emitterPhat computes it. */
    private static double attenuation(double d) {
        double dd = Math.max(d * d, 0.25);
        double tail = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd)) : HANDOVER / Math.pow(Math.sqrt(dd), 1.25);
        double window = Math.clamp(1.0 - (dd / (EMITTER_RADIUS * EMITTER_RADIUS)), 0.0, 1.0);
        return tail * window * window;
    }

    /** What the renderer did before: the same curve with the physical d^-2 tail. */
    private static double physical(double d) {
        double dd = Math.max(d * d, 0.25);
        double tail = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd)) : 1.41421356 / dd;
        double window = Math.clamp(1.0 - (dd / (EMITTER_RADIUS * EMITTER_RADIUS)), 0.0, 1.0);
        return tail * window * window;
    }

    @Test
    public void nearFieldIsUnchangedAndContinuous() {
        assertEquals(1.0 / (2.0 * Math.sqrt(2.0)), HANDOVER / Math.pow(2.0, 1.25), 1.0e-4,
            "the handover constant must equal d^-1.5 at the 2-block seam, otherwise a torch has a visible ring");
        assertEquals(physical(2.0), attenuation(2.0), 1.0e-4, "the near field was already accepted: keep it");
        assertEquals(physical(1.0), attenuation(1.0), 1.0e-4, "inside 2 blocks nothing changes");
    }

    @Test
    public void torchLightCrossesTheRoom() {
        double at8 = attenuation(8.0) / attenuation(2.0);
        double at15 = attenuation(15.0) / attenuation(2.0);
        assertTrue(at8 > 2.5 * (physical(8.0) / physical(2.0)),
            "8 blocks out the light must be several times brighter than the physical d^-2 tail, got " + at8);
        assertTrue(at15 > 3.0 * (physical(15.0) / physical(2.0)),
            "15 blocks is the reach of the game's own block light, got " + at15);
        assertTrue(at15 < 0.10, "the far field must stay a glow, not an evenly lit room: " + at15);
        for (double d = 0.5; d < EMITTER_RADIUS; d += 0.25) {
            assertTrue(attenuation(d) > attenuation(d + 0.25), "the falloff must stay monotonic at " + d);
        }
        assertEquals(0.0, attenuation(EMITTER_RADIUS), 0.0, "the window still ends every source at EMITTER_RADIUS");
    }

    @Test
    public void shaderKeepsThatCurve() {
        String src = RtShaderCompiler.loadSource(EMITTERS);
        assertTrue(src.contains("0.84090 / pow(ddist, 1.25)"),
            "emitterPhat must use the gentler d^-1.25 tail");
        assertFalse(src.contains("1.41421356 / dd"),
            "the physical d^-2 tail is what made every torch a 4-5 block puddle");
        assertTrue(src.contains("float ddist = sqrt(dd);"), "the tail is written in terms of the distance");
        String surface = RtShaderCompiler.loadSource(SURFACE);
        assertTrue(surface.contains("const float EMITTER_RADIUS = 24.0;"),
            "the window still ends at the light radius of the comment above it");
    }
}
