package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Render pipeline nodes: a renderer of your own (G-buffer, lights, voxel GI, Accumulate with its history passes,
 * a-trous denoise, tone map, TAA) planned into passes and compiled by shaderc and slangc.
 */
class MosaicRenderPipelineTest {

    private static List<String> templates() { return List.of(MosaicTemplates.renderer(true), MosaicTemplates.renderer(false), MosaicTemplates.renderer(true, true)); }

    @Test
    void renderersAnalyzeClean() {
        for (String t : templates()) {
            MosaicGraph.Analysis a = MosaicGraph.analyze(t);
            assertNull(a.firstError(), String.valueOf(a.problems()));
        }
    }

    @Test
    void accumulateGetsHistoryPassesAfterTheFinalImage() {
        MosaicPasses.Plan plan = MosaicPasses.plan(MosaicGraph.parse(MosaicTemplates.renderer(true)));
        assertEquals(List.of("~prev.gi", "~depth"), plan.after().stream().map(MosaicPasses.Pass::name).toList());
        assertNull(plan.last().name(), "the final image stays last of the ordered passes");
        assertEquals(List.of("gi", "giA", "giB", "frame"), plan.passes().subList(0, 4).stream().map(MosaicPasses.Pass::name).toList());
        // user buffers keep their bindings (sorted first), history copies come after them
        assertEquals(20, plan.bindings().get("frame"));
        assertTrue(plan.bindings().get("~depth") > plan.bindings().get("giB"));
        String gi = plan.passes().get(0).json();
        assertTrue(gi.contains("\"index\":" + plan.bindings().get("~prev.gi")), gi);
        // history passes read the buffer they keep
        assertEquals(java.util.Set.of(plan.bindings().get("gi")), plan.after().get(0).reads());
    }

    @Test
    void bufferMayFeedItselfThroughAccumulate() {
        String g = """
            {"nodes":[{"id":"c","type":"mosaic.sceneColor"},
             {"id":"a","type":"mosaic.accumulate","mask":"acc","inputs":{"color":"c"}},
             {"id":"p","type":"mosaic.pass","mask":"acc","inputs":{"color":"a"}},
             {"id":"r","type":"mosaic.bufferIn","mask":"acc"},
             {"id":"out","type":"mosaic.output","inputs":{"color":"r"}}]}""";
        MosaicGraph.Analysis a = MosaicGraph.analyze(g);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertEquals(2, MosaicPasses.plan(a.graph()).after().size());
    }

    @Test
    void lastFrameOfAnUnknownBufferIsAnError() {
        MosaicGraph.Analysis a = MosaicGraph.analyze("""
            {"nodes":[{"id":"r","type":"mosaic.bufferPrev","mask":"nope"},{"id":"out","type":"mosaic.output","inputs":{"color":"r"}}]}""");
        assertNotNull(a.firstError());
    }

    @Test
    void everyPassCompilesThroughShaderc() {
        List<String> failures = new ArrayList<>();
        for (String t : templates())
            for (MosaicPasses.Pass p : MosaicPasses.plan(MosaicGraph.parse(t)).all()) {
                MosaicGraph.Analysis pa = MosaicGraph.analyze(p.json());
                assertNull(pa.firstError(), p.name() + ": " + pa.problems());
                MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(pa.graph(), MosaicCodegen.Options.DEFAULT, false));
                if (!r.ok()) failures.add(p.name() + ": " + r.errors());
            }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }

    @Test
    void everyPassCompilesThroughSlangc() {
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        org.junit.jupiter.api.Assumptions.assumeTrue(slangc != null, "slangc not installed");
        List<String> failures = new ArrayList<>();
        for (String t : templates())
            for (MosaicPasses.Pass p : MosaicPasses.plan(MosaicGraph.parse(t)).all()) {
                MosaicShaderCheck.Result r = MosaicSlang.compile(
                    MosaicCodegen.generate(MosaicGraph.parse(p.json()), MosaicCodegen.Options.DEFAULT, true), slangc);
                if (!r.ok()) failures.add(p.name() + ": " + r.errors());
            }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }

    @Test
    void studioPreviewCompilesEveryTemplateWithoutTheAccelerationStructure() {
        List<String> failures = new ArrayList<>();
        for (MosaicTemplates.Template t : MosaicTemplates.all()) {
            MosaicGraph g = MosaicGraph.parse(t.json());
            List<String> jsons = g.multiPass() ? MosaicPasses.plan(g).all().stream().map(MosaicPasses.Pass::json).toList() : List.of(t.json());
            for (String j : jsons) {
                MosaicCodegen.Generated gen = MosaicCodegen.generate(MosaicGraph.parse(j), MosaicCodegen.Options.PREVIEW, false);
                if (gen.source().contains("accelerationStructureEXT")) failures.add(t.name() + ": still declares the TLAS");
                MosaicShaderCheck.Result r = MosaicShaderCheck.compile(gen);
                if (!r.ok()) failures.add(t.name() + ": " + r.errors());
            }
        }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }

    @Test
    void everyRenderPipelineFunctionCompilesAlone() {
        List<String> failures = new ArrayList<>();
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        for (MosaicFunction f : MosaicEffects.all()) {
            if (!f.category().equals("Render pipeline")) continue;
            String json = MosaicTemplates.chainOf(f.name());
            MosaicGraph g = MosaicGraph.parse(json);
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false));
            if (!r.ok()) failures.add(f.name() + " (glsl): " + r.errors());
            if (slangc != null) {
                MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
                if (!s.ok()) failures.add(f.name() + " (slang): " + s.errors());
            }
        }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }
}
