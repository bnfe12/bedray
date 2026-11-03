package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pane of glass passes the ray straight on. The thin-pane branch of bsdfSample scattered the transmitted ray into a
 * cosine lobe around -N, so nothing behind a stained glass block could be seen through it: the block read as a frosted,
 * opaque cube of its colour (owner screenshot 2026-11-01, blue stained glass). The pass-through is now a delta lobe along
 * -V, tinted by the colour to the power of the texel alpha, the same rule surfaceTransmittance uses for shadows.
 */
class RtSlangGlassPassTest {
    private static final Path BSDF = Path.of("src/main/resources/shaders/slang/shading/bsdf.slang");

    @Test
    void thePaneTransmitsAlongTheViewRay() throws IOException {
        String bsdf = Files.readString(BSDF).replace("\r\n", "\n");
        int pane = bsdf.indexOf("    if (transmit) {\n        float3 tint = pow(");
        assertTrue(pane > 0, "the pane has its own pass-through branch");
        String branch = bsdf.substring(pane, bsdf.indexOf("return result;", pane));
        assertTrue(branch.contains("result.direction = -V;"), "straight on, not scattered");
        assertTrue(branch.contains("result.delta = true;"), "a delta lobe: MIS and NEE must not weigh it as diffuse");
        assertTrue(branch.contains("float3(max(material.alpha, 0.0) * transmission));"),
            "tinted by the authored thickness, like the shadow filter");
        assertFalse(bsdf.contains("float3 lobeNormal = transmit ? -N : N;"), "no cosine lobe through a pane");
    }
}
