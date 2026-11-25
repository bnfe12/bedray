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
    void blurOfAnUnwrittenBufferIsAnError() {
        MosaicGraph.Analysis a = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"b\", \"type\": \"mosaic.kawase\", \"mask\": \"nope\"},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"b\"}}]}");
        assertTrue(a.problems().stream().anyMatch(p -> "b".equals(p.node()) && p.message().contains("no Buffer node")), String.valueOf(a.problems()));
    }
}
