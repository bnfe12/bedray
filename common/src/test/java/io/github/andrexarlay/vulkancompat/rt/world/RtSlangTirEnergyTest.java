package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Total internal reflection reflects everything once. The optical interface picks its specular lobe with probability p
 * (weight F/p) and its transmission branch with 1-p; under TIR that branch becomes a mirror. Its weight used to be
 * 1/(1-p), so the expected reflectance was F + 1: a swimmer's mirror above them was brighter than what it reflects
 * (1.34x at cos 0.2, 1.79x at cos 0.05). The mirror branch now carries the remaining (1-F)/(1-p), and the two branches
 * together reflect exactly 1.
 */
class RtSlangTirEnergyTest {

    private static final Path BSDF = Path.of("src/main/resources/shaders/slang/shading/bsdf.slang");

    private static double schlick(double cosine, double f0) {
        return f0 + (1.0 - f0) * Math.pow(1.0 - cosine, 5.0);
    }

    @Test
    void bothBranchesTogetherReflectOnce() {
        double f0 = 0.02;
        for (double cosine : new double[] {0.05, 0.2, 0.5}) {
            double fresnel = schlick(cosine, f0);
            for (double p : new double[] {0.1, 0.5, 0.9}) {
                double old = p * (fresnel / p) + (1.0 - p) * (1.0 / (1.0 - p));
                double fixed = p * (fresnel / p) + (1.0 - p) * ((1.0 - fresnel) / (1.0 - p));
                assertEquals(1.0 + fresnel, old, 1.0e-12, "the old mirror added F on top of everything");
                assertEquals(1.0, fixed, 1.0e-12, "the mirror reflects all of it, once");
            }
        }
    }

    @Test
    void theShaderMirrorCarriesTheRemainder() throws IOException {
        String bsdf = Files.readString(BSDF, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int tir = bsdf.indexOf("// Total internal reflection: the interface becomes a mirror.");
        assertTrue(tir > 0);
        String branch = bsdf.substring(tir, bsdf.indexOf("return result;", tir));
        assertTrue(branch.contains("result.weight = (float3(1.0) - fresnel) / transmissionProbability;"),
            "the specular lobe already reflected F of it");
    }
}
