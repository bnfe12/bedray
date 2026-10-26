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

    private static final String EMITTERS = "/shaders/glsl/rt_common_emitters.glsl";
    private static final String SURFACE = "/shaders/glsl/rt_common_surface.glsl";

    /** The shader's handover constant: 2^1.25 / (2 * sqrt(2)), the value of d^-1.5 at d = 2. */
    private static final double HANDOVER = 0.84090;
    private static final double EMITTER_RADIUS = 24.0;

    /** Attenuation of one emitter at {@code d} blocks, as emitterPhat computes it. */
    private static double attenuation(double d) {
        double dd = Math.max(d * d, 0.25);
        double tail = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd))
                                : HANDOVER / Math.sqrt(dd * Math.sqrt(Math.sqrt(dd)));
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
        assertTrue(src.contains("0.84090 * inversesqrt(dd * sqrt(sqrt(dd)))"),
            "emitterPhat must use the gentler d^-1.25 tail");
        assertFalse(src.contains("pow(ddist, 1.25)"),
            "the tail must not go through pow: eight calls per NEE call, half a million lanes of log2/exp2");
        assertFalse(src.contains("1.41421356 / dd"),
            "the physical d^-2 tail is what made every torch a 4-5 block puddle");
        assertTrue(src.contains("inversesqrt(dd * sqrt(dd))"), "the near field is written as dd^-0.75 == d^-1.5");
        String surface = RtShaderCompiler.loadSource(SURFACE);
        assertTrue(surface.contains("const float EMITTER_RADIUS = 24.0;"),
            "the window still ends at the light radius of the comment above it");
    }

    /** inversesqrt/sqrt form of the tail must stay numerically identical to the d^-1.5 / d^-1.25 curve the numbers above use. */
    @Test
    public void sqrtFormMatchesTheCurve() {
        for (double d = 0.5; d <= 20.0; d += 0.05) {
            double dd = d * d;
            double shader = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd))
                                      : HANDOVER / Math.sqrt(dd * Math.sqrt(Math.sqrt(dd)));
            double reference = dd <= 4.0 ? Math.pow(dd, -0.75) : HANDOVER * Math.pow(dd, -0.625);
            assertEquals(reference, shader, 1.0e-12 * reference, "falloff at " + d + " blocks");
        }
    }

    /**
     * The candidate loop already has the squared distance of every candidate (its own radius test), so it hands it to
     * emitterPhat instead of the function rebuilding it from {@code y - s.pos}: one subtraction and one dot product per
     * candidate of every NEE call, and the light radius is hoisted out of the loop.
     */
    @Test
    public void candidateLoopReusesTheDistanceAndTheRadius() {
        String src = RtShaderCompiler.loadSource(EMITTERS);
        assertTrue(src.contains("float emitterPhat(vec3 y, float d2, float texEm"), "emitterPhat takes the squared distance");
        assertTrue(src.contains("emitterPhat(y, candD2, texEm, en, twoSided, s, maxDist)"),
            "the candidate call passes the distance it already measured");
        assertTrue(src.contains("emitterPhat(yNow, dot(yNow - s.pos, yNow - s.pos)"), "the history call passes its own");
        String decl = "float lightR = min(maxDist, EMITTER_RADIUS);";
        assertEquals(2, count(src, decl), "one declaration inside emitterPhat, one hoisted for the candidate loop");
        int loop = src.indexOf("for (uint k = 0u; k < candidates; k++)");
        assertTrue(loop > 0, "the candidate loop is still where this test expects it");
        assertTrue(src.lastIndexOf(decl) < loop, "and no candidate recomputes it: the last declaration is before the loop");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
