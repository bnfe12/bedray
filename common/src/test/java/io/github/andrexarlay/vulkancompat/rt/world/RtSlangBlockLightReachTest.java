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
 * Reach of block light in the typed emitter module. One emitter follows {@code d^-1.5} up to two blocks and the physical
 * {@code d^-2} beyond, windowed at {@code EMITTER_RADIUS}.
 *
 * <p>History: the physical tail read as a four-to-five-block puddle around every torch (owner report 2026-10-23), so a
 * gentler {@code d^-1.25} tail was tried; with it one torch or one lava column lit a whole room or canyon evenly and
 * painted it orange, where Bedrock RTX keeps a pool of light around the source (owner comparison 2026-11-11, autotest
 * A/B light-final2 / light-lv2). The puddle of October also came from the saturated orange flame colour, which is now
 * the flame's own yellow. A change of the exponent, the seam or the radius has to be made on purpose: the shader has to
 * contain the curve verbatim and the model below mirrors it.
 */
class RtSlangBlockLightReachTest {

    private static final String EMITTERS = "src/main/resources/shaders/slang/light/block_emitters.slang";
    private static final double EMITTER_RADIUS = 24.0;

    /** The shader's {@code emitterAttenuation}. */
    private static double tail(double d) {
        return 1.8 / (d * d + 1.0);
    }

    /** The shader's {@code emitterWindow}, squared as the caller applies it. */
    private static double window(double d, double radius) {
        double window = Math.min(Math.max(1.0 - d * d / (radius * radius), 0.0), 1.0);
        return window * window;
    }

    private static double attenuation(double d, double lightRadius) {
        return tail(d) * window(d, lightRadius);
    }

    @Test
    void theLightIsAPoolAroundItsSource() {
        double at8 = attenuation(8.0, EMITTER_RADIUS) / attenuation(2.0, EMITTER_RADIUS);
        double at15 = attenuation(15.0, EMITTER_RADIUS) / attenuation(2.0, EMITTER_RADIUS);
        assertEquals(5.0 / 65.0, tail(8.0) / tail(2.0), 1.0e-9, "far out the falloff tends to the inverse square");
        assertTrue(at8 < 0.07, "eight blocks out a torch is a glow, not the light of the room: " + at8);
        assertTrue(at15 < 0.02, "fifteen blocks out it has faded: " + at15);
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
        // Every entry point (sample, evaluate, evaluateTarget) hoists the reach once, from the declared radius and the
        // camera list's own range.
        assertEquals(3, count(source, "float lightRadius = min(maxDistance, EMITTER_RADIUS);"),
            "each batch hoists the reach once, never inside its candidate loop");
        assertTrue(source.contains("min(maxDistance, EMITTER_RADIUS)"),
            "the host's collection range may shrink the reach but never widen it past the radius");
    }

    @Test
    void theShaderKeepsThatCurve() throws IOException {
        String source = read(EMITTERS);
        assertTrue(source.contains("return 1.8 / (d2 + 1.0);"),
            "one soft inverse square: physical tail, no hot core under the source");

        assertTrue(source.contains("float emitterAttenuation(float d2)"));
        assertFalse(source.contains("pow("),
            "the tail must not go through pow: eight calls per NEE call, half a million lanes of log2/exp2");
        assertFalse(source.contains("pow(ddist, 1.25)"));
    }

    @Test
    void theCoreIsSoftAndTheMidRangeIsKept() {
        assertEquals(0.36, tail(2.0), 1.0e-9, "equal to the former curve at two blocks");
        assertTrue(tail(0.5) < 1.5, "no hot spot under the source (the former core read 2.8 at half a block)");
        assertTrue(tail(0.0) <= 1.8 + 1.0e-9, "bounded at the source");
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
        int batch = loop.indexOf("blockEmitterCandidate(records, atlas, selected.index, quad, receiver,");
        assertTrue(hoisted > 0 && hoisted < loopStart, "the reach is hoisted out of the candidate loop");
        assertTrue(batch > loopStart && loop.indexOf("blockEmitterCandidate(", batch + 1) == -1,
            "the loop evaluates each candidate through the one gated entry point");
    }

    @Test
    void theIndexProbabilityKeepsItsShape() throws IOException {
        String source = read("src/main/resources/shaders/slang/light/emitter_selection.slang");
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
