package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-parse tests for the mosaic node graph (no Vulkan, no compiler). */
class MosaicGraphTest {

    private static final String SHOWCASE = """
        {"nodes": [
          {"id": "color",      "type": "mosaic.sceneColor"},
          {"id": "ray",        "type": "mosaic.trace"},
          {"id": "lit",        "type": "mosaic.shadow"},
          {"id": "zero",       "type": "mosaic.const",  "value": [0.0]},
          {"id": "miss",       "type": "mosaic.lt",     "inputs": {"a": "ray", "b": "zero"}},
          {"id": "sky",        "type": "mosaic.const",  "value": [0.1, 0.2, 0.4, 0.0]},
          {"id": "gain",       "type": "mosaic.const",  "value": [0.8]},
          {"id": "posTint",    "type": "mosaic.mix",    "inputs": {"a": "white", "b": "dark", "t": "lit"}},
          {"id": "white",      "type": "mosaic.const",  "value": [1.0, 1.0, 1.0, 1.0]},
          {"id": "dark",       "type": "mosaic.const",  "value": [0.3, 0.3, 0.3, 1.0]},
          {"id": "shaded",     "type": "mosaic.mul",    "inputs": {"a": "color", "b": "posTint"}},
          {"id": "tint",       "type": "mosaic.push",   "index": 0},
          {"id": "strength",   "type": "mosaic.swizzle","mask": "w", "inputs": {"a": "tint"}},
          {"id": "tinted",     "type": "mosaic.mul",    "inputs": {"a": "shaded", "b": "strength"}},
          {"id": "glow",       "type": "mosaic.mul",    "inputs": {"a": "sky", "b": "gain"}},
          {"id": "final",      "type": "mosaic.add",    "inputs": {"a": "tinted", "b": "glow"}},
          {"id": "out",        "type": "mosaic.output", "inputs": {"color": "final"}}
        ]}""";

    @Test
    void showcaseParsesAndResolvesKinds() {
        MosaicGraph g = assertDoesNotThrow(() -> MosaicGraph.parse(SHOWCASE));
        assertEquals(17, g.ordered().size());
        assertEquals(Kind.V4, g.kind("color"));
        assertEquals(Kind.F, g.kind("ray"));            // primary trace output is t
        assertEquals(Kind.V4, g.kind("posTint"));       // mix of two vec4 constants
        assertEquals(Kind.F, g.kind("strength"));       // swizzle to one component
        assertEquals("out", g.outputId());
        assertTrue(g.needsRay());
        assertTrue(g.needsPush());
        assertTrue(g.needsSceneColor());
        assertFalse(g.needsBuffer());
        assertFalse(g.needsLut());
        assertFalse(g.needsDepth());
        // topological: every input precedes its user
        var seen = new java.util.HashSet<String>();
        for (MosaicGraph.Node n : g.ordered()) {
            for (String ref : n.inputs().values())
                assertTrue(seen.contains(MosaicGraph.split(ref)[0]), n.id() + " depends on " + ref);
            seen.add(n.id());
        }
    }

    @Test
    void traceNamedOutputsResolveToExactKinds() {
        // ray.pos is vec3: normalize (vec3-in) accepts it; the bare trace output (t, float) feeds the output color
        String json = """
            {"nodes": [
              {"id": "ray", "type": "mosaic.trace"},
              {"id": "n",   "type": "mosaic.normalize", "inputs": {"a": "ray.pos"}},
              {"id": "o",   "type": "mosaic.output", "inputs": {"color": "ray"}}
            ]}""";
        MosaicGraph g = assertDoesNotThrow(() -> MosaicGraph.parse(json));
        assertEquals(Kind.F, g.kind("ray"));
        assertEquals(Kind.V3, g.kind("n"));
    }

    @Test
    void namedOutputOfNonTraceNodeIsRefused() {
        String json = """
            {"nodes": [
              {"id": "c", "type": "mosaic.const", "value": [1, 2, 3]},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "c.pos"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("named outputs"), e.getMessage());
    }

    @Test
    void unknownTypeIsRefusedWithNodeId() {
        String json = """
            {"nodes": [
              {"id": "x", "type": "mosaic.wat"},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "x"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("x") && e.getMessage().contains("mosaic.wat"), e.getMessage());
    }

    @Test
    void cycleIsRefused() {
        String json = """
            {"nodes": [
              {"id": "a", "type": "mosaic.add", "inputs": {"a": "b", "b": "b"}},
              {"id": "b", "type": "mosaic.add", "inputs": {"a": "a", "b": "a"}},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "a"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void twoOutputsAreRefused() {
        String json = """
            {"nodes": [
              {"id": "x",  "type": "mosaic.const", "value": [1]},
              {"id": "o1", "type": "mosaic.output", "inputs": {"color": "x"}},
              {"id": "o2", "type": "mosaic.output", "inputs": {"color": "x"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("exactly one output"), e.getMessage());
    }

    @Test
    void unknownPortIsRefused() {
        String json = """
            {"nodes": [
              {"id": "x", "type": "mosaic.const", "value": [1]},
              {"id": "o", "type": "mosaic.output", "inputs": {"colour": "x"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("no port"), e.getMessage());
    }

    @Test
    void missingNodeReferenceIsRefused() {
        String json = """
            {"nodes": [
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "ghost"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void outOfRangeIndexIsRefused() {
        String push = """
            {"nodes": [
              {"id": "x", "type": "mosaic.push", "index": 9},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "x"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(push));
        assertTrue(e.getMessage().contains("push index"), e.getMessage());
        String buffer = """
            {"nodes": [
              {"id": "x", "type": "mosaic.buffer", "index": -1},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "x"}}
            ]}""";
        e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(buffer));
        assertTrue(e.getMessage().contains("buffer index"), e.getMessage());
    }

    @Test
    void badSwizzleIsRefused() {
        String json = """
            {"nodes": [
              {"id": "c", "type": "mosaic.const", "value": [1, 2]},
              {"id": "s", "type": "mosaic.swizzle", "mask": "zz", "inputs": {"a": "c"}},
              {"id": "o", "type": "mosaic.output", "inputs": {"color": "s"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void mixedVectorWidthsOfInfixMathAreRefused() {
        String json = """
            {"nodes": [
              {"id": "v3", "type": "mosaic.const", "value": [1, 2, 3]},
              {"id": "v4", "type": "mosaic.const", "value": [1, 2, 3, 4]},
              {"id": "m",  "type": "mosaic.mul",   "inputs": {"a": "v3", "b": "v4"}},
              {"id": "o",  "type": "mosaic.output","inputs": {"color": "m"}}
            ]}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(json));
        assertTrue(e.getMessage().contains("same kind"), e.getMessage());
    }
}
