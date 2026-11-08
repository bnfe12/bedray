package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounce vertices pick block emitters from the global (importance-rank) branch only; the primary vertex keeps the light
 * tree. Torch room, 674 lights, 2560x1440 (2026-11-08): the tree cost ~12 of 29 ms of trace.
 */
class RtBounceLightSelectionTest {
    private static final String SHADERS = "src/main/resources/shaders/slang/";

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(SHADERS + path)).replace("\r\n", "\n");
    }

    @Test
    void onlyThePrimaryVertexWalksTheLightTree() throws IOException {
        String batch = read("light/block_emitters.slang");
        assertTrue(batch.contains("BlockEmitterSelection selected = receiver.primary\n"
                + "            ? blockEmitterSelect(records, receiver.position + cameraRelative, rand01(seed))\n"
                + "            : blockEmitterSelectGlobal(records, rand01(seed));"),
            "primary keeps the tree (ReSTIR DI stores it), bounces take the global branch; one draw either way");
    }

    @Test
    void theGlobalBranchReturnsItsOwnExactPmf() throws IOException {
        String selection = read("light/emitter_selection.slang");
        int start = selection.indexOf("BlockEmitterSelection blockEmitterSelectGlobal(");
        String body = selection.substring(start, selection.indexOf("\n}\n", start));
        assertTrue(body.contains("selected.index = min(uint(u * u * float(count)), count - 1u);"));
        assertTrue(body.contains("selected.probability = blockEmitterIndexProbability(selected.index, count);"),
            "the RIS weight divides by this PMF, so it must be the global branch's alone, not the mixture");
        assertFalse(body.contains("emitterLocalProbability") || body.contains("blockEmitterSelectionProbability"),
            "no tree walk in the cheap branch");
    }

    @Test
    void theIndexPmfMatchesTheSquaredDraw() {
        // index i is drawn when u in [sqrt(i/n), sqrt((i+1)/n)): its probability is (sqrt(i+1) - sqrt(i)) / sqrt(n)
        int n = 674;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += (Math.sqrt(i + 1) - Math.sqrt(i)) / Math.sqrt(n);
        assertEquals(1.0, sum, 1e-9, "the PMF sums to one over the list");
    }
}
