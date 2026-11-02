package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glass is a coloured filter on the same ray, not a bounce. As a BSDF vertex each face spent one of the path's four
 * bounces, so the autotest's chain of five glass layers (ten faces) ended every path inside it and rendered the glass
 * boxes black, where v0.1.0-alpha.1 shows them transparent and coloured (rt_look G scene, 2026-11-02). Panes now tint the
 * throughput by surfaceTransmittance and continue like a cutout hole; the step budget is raised so ten faces fit.
 */
class RtSlangPaneFilterTest {
    private static final Path TRACER = Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang");

    @Test
    void aPaneTintsTheRayAndCostsNoBounce() throws IOException {
        String tracer = Files.readString(TRACER).replace("\r\n", "\n");
        int pane = tracer.indexOf("        bool paneFilter = surface.material.alphaMode == AlphaMode.Translucent");
        assertTrue(pane > 0, "the tracer recognises a thin pane");
        String branch = tracer.substring(pane, tracer.indexOf("continue;", pane));
        assertTrue(branch.contains("(surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) == 0u"),
            "water keeps its refracting interface");
        assertTrue(branch.contains("throughput *= surfaceTransmittance(surface, ray.direction);"), "tinted like a shadow ray");
        assertTrue(branch.contains("if (paneFilter || surfaceIsHole(surface, options.alphaCutoff)) {"),
            "and advanced like a hole, without result.depth++");
    }

    @Test
    void tenGlassFacesFitTheStepBudget() {
        assertTrue(RtSlangPathPass.MAX_SURFACE_STEPS >= 2 * 5 + 2, "five glass blocks and the surface behind them");
        assertEquals(16, RtSlangPathPass.MAX_SURFACE_STEPS);
    }
}
