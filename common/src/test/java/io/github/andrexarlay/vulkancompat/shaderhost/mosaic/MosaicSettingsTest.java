package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Graph settings: the helpers' numbers come from the graph, survive the pass plan and compile in both languages. */
class MosaicSettingsTest {

    private static final String GRAPH = """
        {"settings": {"sunTilt": [0.5], "skyZenith": [0.1, 0.2, 0.9], "voxelSteps": [64], "farShadowSteps": [32]},
         "functions": [%s],
         "nodes": [{"id": "uv", "type": "mosaic.uv"}, {"id": "c", "type": "mosaic.sceneColor"},
           {"id": "s", "type": "fn.voxelSunShadow", "inputs": {"color": "c", "uv": "uv"}},
           {"id": "p", "type": "mosaic.pass", "mask": "lit", "inputs": {"color": "s"}},
           {"id": "r", "type": "mosaic.bufferIn", "mask": "lit"},
           {"id": "out", "type": "mosaic.output", "inputs": {"color": "r"}}]}""".formatted(MosaicEffects.get("voxelSunShadow").toJson());

    @Test
    void settingsReachTheGeneratedConstantsOfEveryPass() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(GRAPH);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertArrayEquals(new double[] {0.5}, a.graph().settings().get("sunTilt"));
        for (MosaicPasses.Pass p : MosaicPasses.plan(a.graph()).passes()) {
            String src = MosaicCodegen.generate(MosaicGraph.parse(p.json()), MosaicCodegen.Options.DEFAULT, false).source();
            assertTrue(src.contains("const float ms_sunTilt = 0.5;"), p.name());
            assertTrue(src.contains("const int ms_voxelSteps = 64;"), p.name());
            assertTrue(src.contains("ms_skyZenith = vec3(0.1, 0.2, 0.9)"), p.name());
        }
    }

    @Test
    void settingsCompileInGlslAndSlang() {
        MosaicGraph g = MosaicGraph.parse(MosaicPasses.plan(MosaicGraph.parse(GRAPH)).passes().get(0).json());
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false));
        assertTrue(r.ok(), String.valueOf(r.errors()));
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        if (slangc != null) {
            MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
            assertTrue(s.ok(), String.valueOf(s.errors()));
        }
    }

    @Test
    void unknownOrMalformedSettingsAreErrors() {
        assertNotNull(MosaicGraph.analyze("""
            {"settings": {"noSuchThing": [1]}, "nodes": [{"id": "c", "type": "mosaic.sceneColor"},
              {"id": "out", "type": "mosaic.output", "inputs": {"color": "c"}}]}""").firstError());
        assertNotNull(MosaicGraph.analyze("""
            {"settings": {"skyZenith": [1]}, "nodes": [{"id": "c", "type": "mosaic.sceneColor"},
              {"id": "out", "type": "mosaic.output", "inputs": {"color": "c"}}]}""").firstError());
    }

    @Test
    void documentKeepsSettingsThroughSaveAndUndo() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("mosaic", ".json");
        java.nio.file.Files.writeString(f, GRAPH);
        MosaicDocument d = MosaicDocument.load(f);
        assertArrayEquals(new double[] {0.1, 0.2, 0.9}, d.settings.get("skyZenith"));
        assertTrue(d.toJson().contains("\"skyZenith\""));
        java.nio.file.Files.deleteIfExists(f);
    }
}
