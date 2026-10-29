package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-9: a leaf, a fence or a pane of glass is cut out by alpha, so the ray walks through the hole without spending a
 * scattering event. Each hole moves the origin, and three numbers have to move with it or the reported depth stops
 * describing the surface the denoiser is being fed: the origin, the remaining interval and the accumulated primary
 * displacement.
 *
 * <p>Ways this silently breaks: {@code primaryAdvance} accumulated on a bounce instead of only on the eye ray, so a
 * hole behind a mirror adds metres to a depth that never crossed one; {@code ray.tMax} left alone after an advance, so
 * the walk reaches one bias past the distance the caller bounded; {@code tMin} set to another bias after an origin
 * that is already one bias past the candidate, which is the double-offset defect at a hole instead of at a surface;
 * {@code mediumStart} reset to zero, which measures the water segment from the new origin while the surface it just
 * left sits at {@code -rayBias} behind it.
 */
class RtSlangCutoutAdvanceTest {

    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";

    /** The whole hole branch, up to and including the {@code continue} that keeps it a free step. */
    private static String holeBranch() {
        String tracer = read(TRACER);
        int at = tracer.indexOf("if (surfaceIsHole(surface, options.alphaCutoff)) {");
        assertTrue(at >= 0, "the tracer no longer treats a cutout hole as a step");
        String body = block(tracer, at);
        assertTrue(body.contains("continue;"), "a hole must not consume a scattering event");
        return body;
    }

    @Test
    void theAdvanceMovesTheOriginAndShortensTheIntervalByTheSameAmount() {
        String hole = holeBranch();
        assertTrue(hole.contains("float advance = hit.t + options.rayBias;"),
            "one bias, on the distance the origin actually moved");
        assertTrue(hole.contains("float3 advancedOrigin = ray.origin + ray.direction * advance;"),
            "the new origin is the old one plus the advance along the same ray");
        assertTrue(hole.contains("ray.origin = advancedOrigin;"), "the origin is really moved");
        assertTrue(hole.contains("ray.tMax = max(ray.tMax - advance, 0.0);"),
            "the interval loses exactly what the origin gained, or the walk escapes the caller's maxDistance "
                + "one bias per hole");
    }

    /**
     * The point the audit questioned: does {@code N * rayBias} creep into the depth the denoiser sees? It is not an
     * error, because each bias is a real displacement of the origin, but it is only honest while the accumulated value
     * is the primary ray's displacement and the reported depth adds the final segment to it.
     */
    @Test
    void thePrimaryDistanceIsMeasuredFromTheCameraAndOnlyFromTheCameraRay() {
        String tracer = read(TRACER);
        String hole = holeBranch();
        assertEquals(1, count(hole, "primaryAdvance += advance;"), "one accumulation site");
        assertTrue(hole.contains("if (!firstSurfaceAccepted) primaryAdvance += advance;"),
            "guarded by the primary ray: a hole past a bounce is not the eye ray's displacement");
        int accepted = tracer.indexOf("if (!firstSurfaceAccepted) {");
        assertTrue(accepted >= 0);
        String firstSurface = block(tracer, accepted);
        assertTrue(firstSurface.contains("result.firstHitDistance = primaryAdvance + hit.t;"),
            "the surface distance is the camera-to-origin displacement plus the segment still to travel");
        assertTrue(firstSurface.indexOf("result.firstHitDistance") > firstSurface.indexOf(
            "if (!isFinite(primaryAdvance + hit.t))"),
            "the sum is proven finite before it is published as depth");
    }

    /** An advance already puts the origin past the candidate, so no second offset may be added on top of it. */
    @Test
    void theAdvancedRayAddsNoSecondOffsetAndKeepsTheWaterSegmentExact() {
        String hole = holeBranch();
        assertTrue(hole.contains("ray.tMin = 0.0;"),
            "the origin is one bias off the surface it just left; a tMin of options.rayBias would test twice "
                + "as far and re-open the hole the step just closed");
        assertTrue(hole.contains("mediumStart = -options.rayBias;"),
            "the candidate sits a bias behind the new origin, so the next segment starts there and not at zero");
        assertEquals(-1, hole.indexOf("ray.tMin = options.rayBias"),
            "no bias may reappear as an interval start inside the hole step");
    }

    private static String block(String source, int at) {
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
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

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
