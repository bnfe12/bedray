package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Blur buffer / Kawase blur nodes and the mip bloom chain: planned like Read buffer, compiled by shaderc and slangc. */
class MosaicBlurTest {

    @Test
    void bloomChainPlansFourPassesAtFallingSizes() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(MosaicTemplates.BLOOM_CHAIN);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        MosaicPasses.Plan plan = MosaicPasses.plan(a.graph());
        assertEquals(List.of("half", "quarter", "eighth"), plan.passes().subList(0, 3).stream().map(MosaicPasses.Pass::name).toList());
        assertEquals(List.of(2, 4, 8), plan.passes().subList(0, 3).stream().map(MosaicPasses.Pass::scale).toList());
        assertEquals(java.util.Set.copyOf(plan.bindings().values()), plan.last().reads(), "the final image blurs every level");
    }

    @Test
    void everyPassCompilesThroughShaderc() {
        List<String> failures = new ArrayList<>();
        for (MosaicPasses.Pass p : MosaicPasses.plan(MosaicGraph.parse(MosaicTemplates.BLOOM_CHAIN)).passes()) {
            MosaicGraph.Analysis pa = MosaicGraph.analyze(p.json());
            assertNull(pa.firstError(), p.name() + ": " + pa.problems());
            MosaicCodegen.Generated gen = MosaicCodegen.generate(pa.graph(), MosaicCodegen.Options.DEFAULT, false);
            if (p.name() != null && !p.name().equals("half")) assertTrue(gen.source().contains("mosaicKawase("), p.name());
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(gen);
            if (!r.ok()) failures.add(p.name() + ": " + r.errors());
        }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }

    @Test
    void everyPassCompilesThroughSlangc() {
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        org.junit.jupiter.api.Assumptions.assumeTrue(slangc != null, "slangc not installed");
        List<String> failures = new ArrayList<>();
        for (MosaicPasses.Pass p : MosaicPasses.plan(MosaicGraph.parse(MosaicTemplates.BLOOM_CHAIN)).passes()) {
            MosaicShaderCheck.Result r = MosaicSlang.compile(
                MosaicCodegen.generate(MosaicGraph.parse(p.json()), MosaicCodegen.Options.DEFAULT, true), slangc);
            if (!r.ok()) failures.add(p.name() + ": " + r.errors());
        }
        assertTrue(failures.isEmpty(), String.valueOf(failures));
    }

    @Test
    void reprojectCompilesInBothLanguagesWithThePreviousCamera() {
        MosaicGraph g = MosaicGraph.parse(MosaicTemplates.TAA);
        MosaicCodegen.Generated glsl = MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false);
        assertTrue(glsl.source().contains("binding = 17"), "BedrayPrevFrame");
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(glsl);
        assertTrue(r.ok(), String.valueOf(r.errors()));
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        if (slangc != null) {
            MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
            assertTrue(s.ok(), String.valueOf(s.errors()));
        }
    }

    @Test
    void temporalAaClampsHistoryToTheNeighbourhood() {
        MosaicGraph g = MosaicGraph.analyze(MosaicTemplates.TAA).graph();
        assertNotNull(g);
        String src = MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false).source();
        assertTrue(src.contains("mosaicTaa(bedraySceneColor"), src);
        assertTrue(src.contains("clamp(texture(bedrayHistory"), "history is clamped");
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false));
        assertTrue(r.ok(), String.valueOf(r.errors()));
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        if (slangc != null) {
            MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
            assertTrue(s.ok(), String.valueOf(s.errors()));
        }
    }

    /** A radial blur toward the screen centre written as a loop node: 16 runs of a one-line body. */
    static final String REPEAT = """
        {"functions": [{"signature": "vec4 radial(vec4 acc, float i, vec2 uv)",
                        "glsl": "vec2 q = mix(vec2(0.5), uv, 1.0 - i * 0.004);\\nreturn acc + mosaicScene(q) / 16.0;"}],
         "nodes": [{"id": "r", "type": "mosaic.repeat", "mask": "radial", "index": 16},
                   {"id": "o", "type": "mosaic.output", "inputs": {"color": "r"}}]}""";

    @Test
    void repeatIsALoopNode() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(REPEAT);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        MosaicCodegen.Generated gen = MosaicCodegen.generate(a.graph(), MosaicCodegen.Options.DEFAULT, false);
        assertTrue(gen.source().contains("for (int i_r = 0; i_r < 16; i_r++) g_r = mosaic_fn_radial(g_r, float(i_r), m_uv);"), gen.source());
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(gen);
        assertTrue(r.ok(), String.valueOf(r.errors()));
        MosaicGraph.Analysis bad = MosaicGraph.analyze(REPEAT.replace("\"index\": 16", "\"index\": 99"));
        assertTrue(bad.problems().stream().anyMatch(p -> p.message().contains("1 to 64")), String.valueOf(bad.problems()));
    }

    @Test
    void blurOfAnUnwrittenBufferIsAnError() {
        MosaicGraph.Analysis a = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"b\", \"type\": \"mosaic.kawase\", \"mask\": \"nope\"},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"b\"}}]}");
        assertTrue(a.problems().stream().anyMatch(p -> "b".equals(p.node()) && p.message().contains("no Buffer node")), String.valueOf(a.problems()));
    }
}
