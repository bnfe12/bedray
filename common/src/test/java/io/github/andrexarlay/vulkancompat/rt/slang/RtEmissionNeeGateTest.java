package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A surface's emission is added where the eye sees it (first surface, or through a non-diffuse lobe); after a diffuse
 * bounce the same emitter already lit the previous vertex through next-event estimation (2026-11-08 audit: counted twice).
 */
class RtEmissionNeeGateTest {

    private static String tracer() throws IOException {
        return Files.readString(Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang")).replace("\r\n", "\n");
    }

    @Test
    void emissionIsGatedByHowTheRayArrived() throws IOException {
        String source = tracer();
        assertTrue(source.contains("if (pathSeesEmission(result.surfaceHits, glossyEscape)) {\n"
                + "            result.radiance += throughput * surface.material.emission;"),
            "the hit's emission is added only behind the gate");
        assertFalse(source.contains("\n        result.radiance += throughput * surface.material.emission;\n\n"),
            "no ungated emission add remains");
    }

    @Test
    void theGateIsTheEyeOrANonDiffuseLobe() throws IOException {
        String source = tracer();
        assertTrue(source.contains("bool pathSeesEmission(uint surfaceHits, bool previousLobeNotDiffuse) {\n"
                + "    return surfaceHits == 1u || previousLobeNotDiffuse;"));
        assertTrue(source.contains("glossyEscape = bsdf.lobe != BSDF_LOBE_DIFFUSE;"),
            "glossyEscape is the lobe that produced the current ray");
    }
}
