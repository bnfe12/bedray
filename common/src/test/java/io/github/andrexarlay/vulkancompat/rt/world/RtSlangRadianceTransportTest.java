package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4 was the cascade merge written as a weighted mean of the near and far intervals. Radiative transfer along a ray
 * is {@code L_front + T_front * L_back}, so a one-block wall that fully absorbs ({@code T = 0}) has to make the far
 * interval irrelevant; an average instead shows the sun through the wall at half strength and floods closed rooms.
 * The typed path composes correctly and the averaging float4 resolve is gone.
 * <p>
 * Ways this silently breaks: someone reintroduces a confidence-weighted mean in {@code rcComposeSegments}, drops the
 * exactly-zero-transmittance early return so an opaque front still admits the back, turns {@code min(confidence)} into
 * a sum so a merged record claims more certainty than either half, or wires a pass to the legacy float4 atlas without
 * noticing that {@code .a} there is a sample count and can never express occlusion.
 */
class RtSlangRadianceTransportTest {

    private static final String RC = "src/main/resources/shaders/slang/radiance_cascades";
    private static final String TRANSPORT = RC + "/interval_transport.slang";
    private static final String ROOT = "src/main/resources/shaders/slang/bedray.slang";

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative + ": " + e.getMessage(), e);
        }
    }

    @Test
    void theMergeIsTransportAndNotAnAverage() {
        String compose = block(read(TRANSPORT), "RCSegment rcComposeSegments(RCSegment front, RCSegment back) {");
        assertTrue(compose.contains("result.radiance = front.radiance + front.transmittance * back.radiance;"),
            "the radiative-transfer form the audit asks for: the back only contributes what the front lets through");
        assertTrue(compose.contains("result.transmittance = front.transmittance * back.transmittance;"),
            "transmittance compounds, so a longer path keeps less of what is behind it");
        String radiance = statement(compose, "result.radiance =");
        assertFalse(radiance.contains("/"),
            "the legacy form divided by the summed confidence of both intervals; a divisor here means the merge became "
                + "a mean again and a wall will average with the sun: " + radiance);
        assertFalse(compose.contains("lerp("), "no blend between the two intervals; blending ignores occlusion");
    }

    @Test
    void anOpaqueFrontBlocksTheBack() {
        String compose = block(read(TRANSPORT), "RCSegment rcComposeSegments(RCSegment front, RCSegment back) {");
        assertTrue(compose.contains("if (all(front.transmittance == float3(0.0))) return front;"),
            "a fully absorbing front ends the ray: this is the line that keeps a wall from showing the sun");
        assertTrue(compose.indexOf("all(front.transmittance == float3(0.0))")
                < compose.indexOf("result.radiance ="),
            "the early return must precede the composition, not sit beside it");
    }

    @Test
    void aMergedRecordClaimsNoMoreConfidenceThanItsWeakestHalf() {
        String compose = block(read(TRANSPORT), "RCSegment rcComposeSegments(RCSegment front, RCSegment back) {");
        assertTrue(compose.contains("result.confidence = min(front.confidence, back.confidence);"),
            "summing the two would let a two-interval probe out-vote a single well-sampled one");
    }

    @Test
    void theFloat4AtlasIsGone() {
        assertFalse(Files.exists(Path.of(RC + "/legacy_float4_store.slang")), "the averaging float4 atlas must not return");
        assertFalse(Files.exists(Path.of(RC + "/radiance_cascades_store.slang")), "nor its facade");
        assertEquals(List.of(), float4AtlasWriters(), "nothing in the shader tree writes a float4 interval");
    }

    /** {@code rcStoreInterval}/{@code rcAccumulateInterval} calls outside the module that defines them. */
    private static List<String> float4AtlasWriters() {
        List<String> found = new ArrayList<>();
        Path root = Path.of("src/main/resources/shaders/slang");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".slang"))
                .forEach(path -> {
                    String source = text(path);
                    for (String call : new String[] {"rcStoreInterval(", "rcAccumulateInterval("}) {
                        if (source.contains(call)) {
                            found.add(root.relativize(path) + " calls " + call);
                        }
                    }
                });
        } catch (IOException e) {
            throw new AssertionError("cannot walk " + root + ": " + e.getMessage(), e);
        }
        return found;
    }

    private static String text(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /** One assignment, from {@code header} to the end of its line. */
    private static String statement(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int end = source.indexOf('\n', start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int depth = 0;
        for (int i = start + header.length() - 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced " + header);
    }
}
