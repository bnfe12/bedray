package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path uses BSDF-hit emission on diffuse bounces when there is no emitter proposal.
 */
class RtEmissionNeeGateTest {

    private static String tracer() throws IOException {
        return Files.readString(Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang")).replace("\r\n", "\n");
    }

    @Test
    void emissionIsGatedByHowTheRayArrived() throws IOException {
        String source = tracer();
        assertTrue(source.contains("if (pathSeesEmission(result.surfaceHits, glossyEscape, options.emitterNeeAvailable)) {\n"
                + "            float emissionWeight = 1.0;"),
            "the hit's emission is added only behind the gate");
        assertTrue(source.contains("result.radiance += throughput * surface.material.emission * emissionWeight;"),
            "a glossy reflection ray adds only the share NEE does not carry");
        assertTrue(source.contains("emissionWeight = 1.0 - pathEmitterSpecularNeeWeight(previousGlossyRoughness, hit.t);"),
            "the BSDF-hit share is the complement of the NEE share");
        assertTrue(source.contains(
                "specularWeight = pathEmitterSpecularNeeWeight(surface.material.roughness, light.distance);"),
            "NEE evaluates the same split before the highlight is widened");
        assertFalse(source.contains("\n        result.radiance += throughput * surface.material.emission;\n\n"),
            "no ungated emission add remains");
    }

    @Test
    void onlyTheScreenPassWithEmitterProposalsEnablesTheDiffuseGate() throws IOException {
        String source = tracer();
        String entry = Files.readString(Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang"));
        assertTrue(source.contains("options.emitterNeeAvailable = false;"));
        assertTrue(entry.contains(
                "options.emitterNeeAvailable = lights.emitterProbability > 0.0 && blockEmitterCount(emitters) > 0u;"));
        assertTrue(source.contains("glossyEscape = bsdf.lobe != BSDF_LOBE_DIFFUSE;"),
            "glossyEscape is the lobe that produced the current ray");
    }
}
