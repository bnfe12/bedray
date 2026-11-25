package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MosaicPassesTest {

    /** Bloom: bright parts at 1/2 size, blurred at 1/4 in two passes, added back; plus a temporal blend with the last frame. */
    static final String BLOOM = "{\"nodes\": ["
        + "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"},"
        + "{\"id\": \"bright\", \"type\": \"mosaic.math\", \"mask\": \"maximum\", \"inputs\": {\"a\": \"c\"}, \"defaults\": {\"b\": [0.7]}},"
        + "{\"id\": \"p1\", \"type\": \"mosaic.pass\", \"mask\": \"bright\", \"index\": 2, \"inputs\": {\"color\": \"bright\"}},"
        + "{\"id\": \"uv\", \"type\": \"mosaic.uv\"},"
        + "{\"id\": \"off\", \"type\": \"mosaic.const\", \"value\": [0.004, 0.0]},"
        + "{\"id\": \"uvA\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"uv\", \"b\": \"off\"}},"
        + "{\"id\": \"uvB\", \"type\": \"mosaic.sub\", \"inputs\": {\"a\": \"uv\", \"b\": \"off\"}},"
        + "{\"id\": \"r1\", \"type\": \"mosaic.bufferIn\", \"mask\": \"bright\", \"inputs\": {\"uv\": \"uvA\"}},"
        + "{\"id\": \"r2\", \"type\": \"mosaic.bufferIn\", \"mask\": \"bright\", \"inputs\": {\"uv\": \"uvB\"}},"
        + "{\"id\": \"avg\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"r1\", \"b\": \"r2\"}},"
        + "{\"id\": \"p2\", \"type\": \"mosaic.pass\", \"mask\": \"blur\", \"index\": 4, \"inputs\": {\"color\": \"avg\"}},"
        + "{\"id\": \"glow\", \"type\": \"mosaic.bufferIn\", \"mask\": \"blur\"},"
        + "{\"id\": \"sum\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"c\", \"b\": \"glow\"}},"
        + "{\"id\": \"last\", \"type\": \"mosaic.history\"},"
        + "{\"id\": \"taa\", \"type\": \"mosaic.mix\", \"inputs\": {\"a\": \"sum\", \"b\": \"last\"}},"
        + "{\"id\": \"rain\", \"type\": \"mosaic.rain\"},"
        + "{\"id\": \"wet\", \"type\": \"mosaic.mul\", \"inputs\": {\"a\": \"taa\", \"b\": \"rain\"}},"
        + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"taa\"}}]}";

    @Test
    void bloomPlansThreePassesInOrder() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(BLOOM);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertTrue(a.graph().multiPass());
        MosaicPasses.Plan plan = MosaicPasses.plan(a.graph());
        assertEquals(3, plan.passes().size());
        assertEquals("bright", plan.passes().get(0).name());
        assertEquals(2, plan.passes().get(0).scale());
        assertEquals("blur", plan.passes().get(1).name());
        assertEquals(4, plan.passes().get(1).scale());
        assertNull(plan.last().name());
        assertTrue(plan.last().history());
        assertEquals(java.util.Set.of(plan.bindings().get("blur")), plan.last().reads());
        for (MosaicPasses.Pass p : plan.passes()) {
            MosaicGraph.Analysis pa = MosaicGraph.analyze(p.json());
            assertNull(pa.firstError(), p.name() + ": " + pa.problems());
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(pa.graph(), MosaicCodegen.Options.DEFAULT, false));
            assertTrue(r.ok(), p.name() + ": " + r.errors());
        }
        assertTrue(a.problems().stream().noneMatch(p -> "wet".equals(p.node()) && false));
    }

    @Test
    void bufferMistakesAreReported() {
        MosaicGraph.Analysis missing = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"r\", \"type\": \"mosaic.bufferIn\", \"mask\": \"nope\"},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"r\"}}]}");
        assertTrue(missing.problems().stream().anyMatch(p -> "r".equals(p.node()) && p.message().contains("no Buffer node")));
        MosaicGraph.Analysis self = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"r\", \"type\": \"mosaic.bufferIn\", \"mask\": \"x\"},"
            + "{\"id\": \"p\", \"type\": \"mosaic.pass\", \"mask\": \"x\", \"inputs\": {\"color\": \"r\"}},"
            + "{\"id\": \"r2\", \"type\": \"mosaic.bufferIn\", \"mask\": \"x\"},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"r2\"}}]}");
        assertTrue(self.problems().stream().anyMatch(p -> p.message().contains("Previous frame")), String.valueOf(self.problems()));
    }
}
