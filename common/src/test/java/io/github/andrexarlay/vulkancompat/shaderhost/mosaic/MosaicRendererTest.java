package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MosaicRendererTest {

    @Test
    void defaultGraphIsValidAndTurnsLeftOutStagesOff() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(MosaicRenderer.defaultGraph(null));
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertTrue(a.graph().isRenderer());
        Map<String, String> s = MosaicRenderer.settings(a.graph());
        assertEquals("true", s.get("path_restir_di"), "ReSTIR DI is wired in");
        assertEquals("false", s.get("path_restir_pt"), "ReSTIR PT is left out");
        assertEquals("false", s.get("path_radiance_cascades"));
        assertEquals("nrd", s.get("path_denoiser"));
        assertEquals("3", s.get("path_bounces"));
    }

    @Test
    void stateOfTheGameIsTakenIn() {
        Map<String, String> state = Map.of("path_restir_pt", "true", "path_bounces", "6", "path_denoiser", "dlss_rr", "tonemapper", "agx");
        MosaicGraph.Analysis a = MosaicGraph.analyze(MosaicRenderer.defaultGraph(state));
        assertNull(a.firstError(), String.valueOf(a.problems()));
        Map<String, String> s = MosaicRenderer.settings(a.graph());
        assertEquals("true", s.get("path_restir_pt"));
        assertEquals("6", s.get("path_bounces"));
        assertEquals("dlss_rr", s.get("path_denoiser"));
        assertEquals("agx", s.get("tonemapper"));
    }

    @Test
    void orderAndNeedsAreChecked() {
        MosaicGraph.Analysis back = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"d\", \"type\": \"bedray.denoiser\", \"inputs\": {\"frame\": \"s\"}},"
            + "{\"id\": \"s\", \"type\": \"bedray.scene\"},"
            + "{\"id\": \"r\", \"type\": \"bedray.restirDi\", \"inputs\": {\"frame\": \"d\"}},"
            + "{\"id\": \"o\", \"type\": \"bedray.output\", \"inputs\": {\"frame\": \"r\"}}]}");
        assertTrue(back.problems().stream().anyMatch(p -> "r".equals(p.node()) && p.message().contains("earlier")), String.valueOf(back.problems()));
        MosaicGraph.Analysis pt = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"s\", \"type\": \"bedray.scene\"},"
            + "{\"id\": \"p\", \"type\": \"bedray.restirPt\", \"inputs\": {\"frame\": \"s\"}},"
            + "{\"id\": \"o\", \"type\": \"bedray.output\", \"inputs\": {\"frame\": \"p\"}}]}");
        assertTrue(pt.problems().stream().anyMatch(p -> "p".equals(p.node()) && p.message().contains("needs ReSTIR DI")), String.valueOf(pt.problems()));
        MosaicGraph.Analysis mixed = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"},"
            + "{\"id\": \"o\", \"type\": \"bedray.output\", \"inputs\": {\"frame\": \"c\"}}]}");
        assertTrue(mixed.problems().stream().anyMatch(p -> "c".equals(p.node())));
    }
}
