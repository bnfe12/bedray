package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reach of block light in the typed emitter module. The deleted {@code RtBlockLightReachTest} guarded the same kernel in
 * {@code rt_common_emitters.glsl}; {@code light/block_emitters.slang} carries it unchanged, so the numbers and the
 * source are pinned again.
 *
 * <p>One emitter follows {@code d^-1.5} up to two blocks and {@code d^-1.25} beyond, windowed at {@code EMITTER_RADIUS}.
 * The physical {@code d^-2} tail kept 5.6 % of the two-block value at eight blocks and 1.2 % at fifteen, which read as a
 * four-to-five-block puddle around every torch (owner report 2026-10-23). The gentler tail is what makes a torch cross
 * a room. A change of the exponent, of the handover constant or of the radius has to be made on purpose: the shader has
 * to contain the curve verbatim, and the model below mirrors it.
 */
class RtSlangBlockLightReachTest {

    private static final String EMITTERS = "src/main/resources/shaders/slang/light/block_emitters.slang";

    /** {@code 2^1.25 / (2 * sqrt(2))}: the value of {@code d^-1.5} at the two-block seam. */
    private static final double HANDOVER = 0.84090;
    private static final double EMITTER_RADIUS = 24.0;

    /** The shader's {@code emitterAttenuation}. */
    private static double tail(double d) {
        double dd = Math.max(d * d, 0.25);
        return dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd))
                         : HANDOVER / Math.sqrt(dd * Math.sqrt(Math.sqrt(dd)));
    }

    /** The shader's {@code emitterWindow}, squared as the caller applies it. */
    private static double window(double d, double radius) {
        double window = Math.min(Math.max(1.0 - d * d / (radius * radius), 0.0), 1.0);
        return window * window;
    }

    private static double attenuation(double d, double lightRadius) {
        return tail(d) * window(d, lightRadius);
    }

    /** What the renderer did before: the same window over the physical {@code d^-2} tail. */
    private static double physical(double d, double lightRadius) {
        double dd = Math.max(d * d, 0.25);
        double value = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd)) : 1.41421356 / dd;
        return value * window(d, lightRadius);
    }

    @Test
    void theSeamIsContinuousAndTheNearFieldUnchanged() {
        assertEquals(1.0 / (2.0 * Math.sqrt(2.0)), HANDOVER / Math.pow(2.0, 1.25), 1.0e-4,
            "the handover constant must equal d^-1.5 at the two-block seam, or a torch has a visible ring");
        assertEquals(tail(2.0 - 1.0e-9), tail(2.0 + 1.0e-9), 1.0e-5,
            "the two branches meet at two blocks, so no distance is lit twice or left dark");
        assertEquals(physical(1.0, EMITTER_RADIUS), attenuation(1.0, EMITTER_RADIUS), 1.0e-12,
            "inside two blocks the kernel is the one that was already accepted");
        assertEquals(physical(2.0, EMITTER_RADIUS), attenuation(2.0, EMITTER_RADIUS), 1.0e-6,
            "the seam itself is unchanged: only what lies beyond it was retuned");
    }

    @Test
    void aTorchCrossesTheRoom() {
        double at8 = attenuation(8.0, EMITTER_RADIUS) / attenuation(2.0, EMITTER_RADIUS);
        double at15 = attenuation(15.0, EMITTER_RADIUS) / attenuation(2.0, EMITTER_RADIUS);
        assertTrue(at8 > 2.5 * (physical(8.0, EMITTER_RADIUS) / physical(2.0, EMITTER_RADIUS)),
            "eight blocks out the light must be several times brighter than the physical tail, got " + at8);
        assertTrue(at15 > 3.0 * (physical(15.0, EMITTER_RADIUS) / physical(2.0, EMITTER_RADIUS)),
            "fifteen blocks is the reach of the game's own block light, got " + at15);
        assertTrue(at15 < 0.10, "the far field must stay a glow, not an evenly lit room: " + at15);
        for (double d = 0.5; d < EMITTER_RADIUS; d += 0.25) {
            assertTrue(attenuation(d, EMITTER_RADIUS) > attenuation(d + 0.25, EMITTER_RADIUS),
                "the falloff must stay monotonic at " + d);
        }
        assertEquals(0.0, attenuation(EMITTER_RADIUS, EMITTER_RADIUS), 0.0,
            "the window still ends every source at EMITTER_RADIUS");
    }

    @Test
    void theWindowEndsAtTheSameRadiusTheShaderDeclares() throws IOException {
        String source = read(EMITTERS);
        assertTrue(source.contains("static const float EMITTER_RADIUS = 24.0;"),
            "the window still ends at the light radius of the comment above it");
        assertTrue(source.contains(
            "float window = clamp(1.0 - d2 / (lightRadius * lightRadius), 0.0, 1.0);"));
        assertTrue(source.contains("return window * window;"));
        // Both batch entry points hoist the reach once, from the declared radius and the camera list's own range.
        assertEquals(2, count(source, "float lightRadius = min(maxDistance, EMITTER_RADIUS);"),
            "each batch hoists the reach once, never inside its candidate loop");
        assertTrue(source.contains("min(maxDistance, EMITTER_RADIUS)"),
            "the host's collection range may shrink the reach but never widen it past the radius");
    }

    @Test
    void theShaderKeepsThatCurve() throws IOException {
        String source = read(EMITTERS);
        assertTrue(source.contains("0.84090 * rsqrt(dd * sqrt(sqrt(dd)))"),
            "the tail must stay the gentler d^-1.25 curve");
        assertTrue(source.contains("rsqrt(dd * sqrt(dd))"), "the near field is written as dd^-0.75 == d^-1.5");
        assertFalse(source.contains("1.41421356 / dd"),
            "the physical d^-2 tail is what made every torch a four-block puddle");
        assertTrue(source.contains("float emitterAttenuation(float d2)"));
        assertFalse(source.contains("pow("),
            "the tail must not go through pow: eight calls per NEE call, half a million lanes of log2/exp2");
        assertFalse(source.contains("pow(ddist, 1.25)"));
    }

    @Test
    void theRsqrtFormMatchesTheCurve() {
        for (double d = 0.5; d <= 20.0; d += 0.05) {
            double dd = d * d;
            double shader = dd <= 4.0 ? 1.0 / Math.sqrt(dd * Math.sqrt(dd))
                                      : HANDOVER / Math.sqrt(dd * Math.sqrt(Math.sqrt(dd)));
            double reference = dd <= 4.0 ? Math.pow(dd, -0.75) : HANDOVER * Math.pow(dd, -0.625);
            assertEquals(reference, shader, 1.0e-12 * reference, "falloff at " + d + " blocks");
        }
    }

    @Test
    void aCandidateIsRejectedBeforeItsAtlasFetch() throws IOException {
        String source = read(EMITTERS);
        String candidate = block(source, source.indexOf("BlockEmitterCandidate blockEmitterCandidate("));
        int gate = candidate.indexOf("distance2 > lightRadius * lightRadius");
        int self = candidate.indexOf("distance2 < (receiver.emission > 0.5 ? 0.25 : 1.0e-6)");
        int atlas = candidate.indexOf("atlas.material.SampleLevel");
        int entity = candidate.indexOf("entityTexel(atlas.entities");
        assertTrue(gate > 0 && self > gate, "both zero tests of the target density are in the gate");
        assertTrue(atlas > gate, "the atlas fetch must come after the distance gate");
        assertTrue(entity > gate, "the entity atlas fetch must come after the distance gate");
        assertTrue(candidate.contains("candidate.distance2 = distance2;"),
            "the measured squared distance is handed to the density instead of being rebuilt");
        assertTrue(candidate.contains("candidate.phat = blockEmitterPhat(point, receiver, distance2, lightRadius);"),
            "the candidate passes the distance and the hoisted reach, so neither is recomputed per candidate");
        assertTrue(source.contains("float blockEmitterPhat(BlockEmitterPoint p, LightReceiver receiver, "
            + "float distance2, float lightRadius)"), "the density takes the squared distance");
        assertFalse(source.contains("dot(y - s.pos, y - s.pos)"), "no caller rebuilds the distance from the position");

        String loop = block(source, source.indexOf("BlockEmitterWinner sampleBlockEmitterBatch("));
        int hoisted = loop.indexOf("float lightRadius = min(maxDistance, EMITTER_RADIUS);");
        int loopStart = loop.indexOf("for (uint k = 0u; k < candidates; ++k) {");
        int batch = loop.indexOf("blockEmitterCandidate(records, atlas, index, quad, receiver,");
        assertTrue(hoisted > 0 && hoisted < loopStart, "the reach is hoisted out of the candidate loop");
        assertTrue(batch > loopStart && loop.indexOf("blockEmitterCandidate(", batch + 1) == -1,
            "the loop evaluates each candidate through the one gated entry point");
    }

    @Test
    void theIndexProbabilityKeepsItsShape() throws IOException {
        String source = read(EMITTERS);
        assertTrue(source.contains(
            "return (sqrt(float(index + 1u)) - sqrt(float(index))) * rsqrt(float(count));"),
            "P(i) is the sqrt ladder difference, written with one square root pair instead of two quotients");
        for (int n : new int[] {1, 2, 7, 64, 512}) {
            for (int i = 0; i < n; i++) {
                double before = Math.sqrt((i + 1.0) / n) - Math.sqrt((double) i / n);
                double after = (Math.sqrt(i + 1.0) - Math.sqrt(i)) * (1.0 / Math.sqrt(n));
                assertEquals(before, after, 1.0e-12, "n=" + n + " i=" + i);
            }
        }
    }

    // ---- helpers ----

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String block(String source, int at) {
        assertTrue(at >= 0, "anchor not found");
        int depth = 0;
        for (int i = source.indexOf('{', at); i >= 0 && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }

    private static int count(String haystack, String needle) {
        int hits = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
    }
}