package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spatial ReSTIR DI merges a neighbour's sample by its target density p^ alone. Re-evaluating it with the full
 * {@code evaluate} also walked the light tree leaf to root for a selection probability nothing read, for each of the 8
 * neighbours: di_spatial went from 2.0 to 6.5 ms after the light BVH landed (c440075). The temporal import keeps the full
 * evaluation, because it stores the source density.
 */
class RtDiSpatialTargetOnlyTest {
    private static final String SHADERS = "src/main/resources/shaders/slang/";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(SHADERS + path)).replace("\r\n", "\n");
    }

    private static String method(String source, String header) {
        int start = source.indexOf(header);
        assertTrue(start >= 0, "missing " + header);
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated " + header);
    }

    @Test
    void spatialReuseAsksForTheTargetDensityOnly() throws IOException {
        String spatial = method(read("restir/restir_di.slang"), "DIReservoir restirDiSpatial<S : ILightSampler>(");
        assertTrue(spatial.contains("sampler.evaluateTarget(receiver, neighborSample)"), "spatial reuse uses the p^-only path");
        assertFalse(spatial.contains("sampler.evaluate(receiver"), "and never the full evaluation");
        assertTrue(read("contracts/light.slang").contains(
                "LightEvaluation evaluateTarget(LightReceiver receiver, LightSample sample) {\n        return evaluate(receiver, sample);"),
            "samplers without a costly source density answer with the full evaluation");
    }

    @Test
    void blockEmitterTargetEvaluationSkipsTheLightTreeWalk() throws IOException {
        String emitters = read("light/block_emitters.slang");
        String target = method(emitters, "override LightEvaluation evaluateTarget(LightReceiver receiver, LightSample sample) {\n        LightEvaluation result");
        assertFalse(target.contains("blockEmitterSelectionProbability"), "the leaf-to-root walk is what made di_spatial 3x slower");
        assertTrue(target.contains("receiver, cameraRelative, lightRadius, 1.0);"), "p^ does not depend on the probability argument");
        assertTrue(target.contains("result.targetPdf = candidate.phat;"), "same p^ as evaluate()");
        assertTrue(target.contains("result.sourcePdf = 0.0;"), "no fake source density");
        String full = method(emitters, "LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {\n        LightEvaluation result");
        assertTrue(full.contains("blockEmitterSelectionProbability(records, index, receiver.position + cameraRelative)"),
            "the full evaluation keeps the real selection probability for the temporal import");
        assertTrue(emitters.contains("return emitters.evaluateTarget(receiver, sample);"),
            "the sun-and-emitters composite forwards the cheap path too");
        assertTrue(read("restir/di_block_emitters.slang").contains("LightEvaluation evaluation = emitters().evaluate(receiver, historySample);"),
            "temporal import stores solidAnglePdf, so it keeps evaluate()");
    }
}
