package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The shipped original example packs, compiled through the real scanner, preprocessor, translator and SPIR-V compiler. */
class IrisExamplePacksTest {

    static Path example(String name) {
        for (Path p : List.of(Path.of("..", "examples", name), Path.of("examples", name))) {
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("examples/" + name + " not found");
    }

    static IrisPassGraph build(String name) throws Exception {
        IrisPassGraph.ParseResult result = IrisPassGraph.build(example(name), ScanLimits.DEFAULT);
        assertTrue(result.success(), name + ": " + result.status().detail());
        return result.graph();
    }

    @Test
    void existingExamplesStillCompile() throws Exception {
        assertEquals(1, build("iris-color-post").passCount());
        assertEquals(1, build("iris-depth-post").passCount());
        assertEquals(3, build("iris-mrt-post").passCount());
    }

    @Test
    void historyExampleResolvesFormatsPersistenceAndMipmaps() throws Exception {
        IrisPassGraph graph = build("iris-history-post");
        var targets = graph.targets();
        assertTrue(targets.problems().isEmpty(), targets.problems().toString());
        assertEquals("R16F", targets.target(2).format().glName());
        assertEquals("RGB8", targets.target(3).format().glName());
        assertTrue(targets.target(3).format().alphaLocked());
        assertEquals("RGBA16F", targets.target(5).format().glName());
        assertTrue(targets.target(5).persistent());
        assertEquals("R11F_G11F_B10F", targets.target(6).format().glName());
        assertEquals("RG16", targets.target(7).format().glName());
        assertArrayEquals(new float[] {0, 0, 0, 1}, targets.target(4).clearColor());
        assertEquals(Set.of(6), targets.mipmapped("composite1"));

        IrisResourcePlan plan = IrisResourcePlan.build(graph);
        assertEquals(6, plan.steps().get(0).writes().size(), "six draw buffers in one pass");
        assertEquals(List.of(5), plan.persistCopies());
        assertEquals(Set.of(6), plan.mipmappedTargets());
        assertEquals(Set.of(6), plan.steps().get(1).mipmapped());
        assertTrue(plan.hasFinal());
        for (var pass : graph.passes()) {
            assertTrue(pass.vertexSpirv().remaining() > 0 && pass.fragmentSpirv().remaining() > 0, pass.name());
        }
    }
}
