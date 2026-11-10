package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four verified trace-cost reductions from the 2026-11-08 torch-room audit (read-only workflow + skeptic). Each keeps the
 * estimate unbiased or exact; the in-game A/B is recorded in the commit message.
 */
class RtTraceCostTest {
    private static final String SHADERS = "src/main/resources/shaders/slang/";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(SHADERS + path)).replace("\r\n", "\n");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    void russianRouletteStartsAtTheSecondBounceInTheScreenPass() throws IOException {
        String entry = read("pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("static const uint PATH_TRACE_ENTRY_MIN_RUSSIAN_DEPTH = 2u;"));
        assertTrue(entry.contains("options.minRussianDepth = PATH_TRACE_ENTRY_MIN_RUSSIAN_DEPTH;"),
            "with 3 bounces a start at depth 3 never ran: the depth limit breaks first");
        assertTrue(read("pathtrace/path_tracer.slang").contains("options.minRussianDepth = 3u;"),
            "other callers keep the default");
    }

    @Test
    void anIndoorPrimaryVertexDrawsTheKeyLightWithAProbabilityItDividesBy() throws IOException {
        String light = read("contracts/light.slang");
        assertTrue(light.contains("float  skyLight;") && light.contains("receiver.skyLight = 1.0;"),
            "unknown sky light means open sky: every other caller keeps today's sampling");
        assertTrue(read("pathtrace/path_tracer.slang").contains("receiver.skyLight = hit.light.y;"));
        String emitters = read("light/block_emitters.slang");
        assertTrue(emitters.contains("return sampleKeyLightIndoorAware(receiver, seed);"));
        int start = emitters.indexOf("LightSample sampleKeyLightIndoorAware(");
        String body = emitters.substring(start, emitters.indexOf("\n    }\n", start));
        assertTrue(body.contains("keySample.sourcePdf *= keyProbability;"), "the selection probability is divided out");
        assertTrue(body.contains("return invalidLightSample();"), "a skipped draw traces no ray");
    }

    @Test
    void anOpaqueSectionBlockerEndsBothShadowWalksBeforeTheDecode() throws IOException {
        String visibility = read("surface/visibility.slang");
        assertTrue(visibility.contains("return hit.kind == HitKind.Triangle\n"
            + "        && (hit.flags & (HIT_FLAG_CUTOUT | HIT_FLAG_TRANSLUCENT | HIT_FLAG_FOLIAGE)) == 0u;"));
        assertEquals(2, count(visibility, "if (visibilityOpaqueSectionBlocker(hit)) {"), "plain and medium walks");
        int check = visibility.indexOf("if (visibilityOpaqueSectionBlocker(hit)) {");
        int decode = visibility.indexOf("SurfaceSample surface = decodeSurface(", check);
        assertTrue(check > 0 && decode > check, "the early-out comes before the surface decode");
    }

    @Test
    void theDiTemporalImportNoLongerWalksTheLightTree() throws IOException {
        String di = read("restir/di_block_emitters.slang");
        assertTrue(di.contains("LightEvaluation evaluation = emitters().evaluateTarget(receiver, historySample);"));
        assertFalse(di.contains("emitters().evaluate(receiver, historySample)"));
        assertTrue(di.contains("imported.solidAnglePdf = DI_IMPORT_SOLID_ANGLE_PDF;"),
            "the stored source pdf only cancels between the estimate and the direct term");
    }
}
