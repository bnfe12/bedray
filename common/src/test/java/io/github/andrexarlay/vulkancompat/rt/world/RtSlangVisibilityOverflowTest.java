package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filtered visibility walk stops after sixteen translucent layers and reports what it collected as a visible sight
 * line. That is the one place in the shading chain where a truncation is not an observation: reading it as "visible"
 * lights a receiver through an opaque blocker hidden behind the glass. The decision is now stated once, in
 * {@code surfaceVisibilityProven}, and every consumer is required to ask for proof instead of the raw bit - which is
 * what these guards hold shut.
 */
class RtSlangVisibilityOverflowTest {

    private static final String VISIBILITY = "src/main/resources/shaders/slang/surface/visibility.slang";
    private static final String SHADERS = "src/main/resources/shaders/slang";

    /** The budget, the flag it raises and the single predicate that turns it into a verdict. */
    @Test
    void agaveUpWalkIsNotAProvenSightLine() {
        String visibility = read(VISIBILITY);
        assertTrue(visibility.contains("static const uint SURFACE_VISIBILITY_MAX_LAYERS = 16u;"),
            "the budget is the number that decides when a walk stops being an observation");
        assertTrue(visibility.contains("visibility.flags |= SURFACE_VISIBILITY_FLAG_OVERFLOW;"),
            "the walk reports the truncation instead of hiding it");
        String predicate = block(visibility, "bool surfaceVisibilityProven(SurfaceVisibility visibility) {");
        assertTrue(predicate.contains("visibility.result.visible")
                && predicate.contains("(visibility.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) == 0u"),
            "proof means visible and not truncated");
        assertEquals(1, count(predicate, "result.visible"), "one read, in the predicate");
        int before = visibility.indexOf("bool surfaceVisibilityProven(");
        int use = visibility.indexOf("return !surfaceVisibilityProven(visibility);");
        assertTrue(before >= 0 && use > before,
            "the predicate is declared before the boolean helper uses it: Slang needs the definition first");
    }

    /**
     * The sweep that keeps the decision from rotting: any shader that reads the raw visibility bit either goes
     * through the predicate or has to state the overflow itself.
     */
    @Test
    void everyShadowConsumerAsksForProof() throws IOException {
        try (var files = Files.walk(Path.of(SHADERS))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".slang")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
                int reads = count(source, "result.visible") - count(source, "result.visible =");
                if (reads == 0) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (name.equals("visibility.slang")) {
                    assertEquals(1, reads, "only the predicate reads the bit in the file that owns it");
                    continue;
                }
                assertTrue(source.contains("SURFACE_VISIBILITY_FLAG_OVERFLOW"),
                    name + " decides visibility from the raw bit without checking the layer-budget truncation, so a "
                        + "blocker behind sixteen filters leaks: use surfaceVisibilityProven or check the flag");
            }
        }
        for (String consumer : new String[] {
            "pathtrace/path_tracer.slang", "restir/restir_di.slang",
            "radiance_cascades/rc_trace_segments.slang" }) {
            String source = read(SHADERS + "/" + consumer);
            assertTrue(source.contains("surfaceVisibilityProven("), consumer + " must ask for proof");
            assertEquals(0, count(source, "result.visible") - count(source, "result.visible ="),
                consumer + " still decides a shadow from the raw bit");
        }
    }

    /**
     * A dropped contribution has to travel: the path tracer reports the term as occluded, which is what makes a reused
     * reservoir record fade instead of sitting at full weight for the rest of the history.
     */
    @Test
    void adroppedTermStillFadesTheReusedRecord() {
        String tracer = read(SHADERS + "/pathtrace/path_tracer.slang");
        String term = block(tracer, "float3 pathTraceDirectTerm<T : ISceneTracer>(T traversal,");
        int proven = term.indexOf("float3 transmittance = surfaceVisibilityProven(shadow)");
        int occluded = term.indexOf("occluded = true;");
        assertTrue(proven >= 0, "the direct term asks the walk for proof");
        assertTrue(occluded > proven,
            "and a walk without proof falls into the same zero-transmittance branch, so the caller marks it occluded");
        assertTrue(term.indexOf("traced = true;") < proven,
            "the ray really ran, so reuseConfirm sees a proven-occluded sample rather than an untraced one");
        String di = read(SHADERS + "/restir/restir_di.slang");
        assertTrue(di.contains("result.valid = proven;"),
            "the reservoir resolve answers with the same verdict, so a record behind the filters is not treated as visible");
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

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
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
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
