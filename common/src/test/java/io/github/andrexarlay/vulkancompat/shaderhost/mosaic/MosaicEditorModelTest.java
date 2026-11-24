package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicLayout.Rect;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The editor's pure model: catalog, layout, history and the validator fixes the editor relies on. */
class MosaicEditorModelTest {

    // ------------------------------------------------------------------ catalog

    @Test
    void catalogCoversExactlyTheKnownTypes() {
        Set<String> catalog = new HashSet<>();
        for (MosaicCatalog.Entry e : MosaicCatalog.all()) assertTrue(catalog.add(e.type()), "duplicate " + e.type());
        assertEquals(MosaicGraph.knownTypes(), catalog);
    }

    @Test
    void searchRanksPrefixMatchesFirst() {
        assertEquals("mosaic.mul", MosaicCatalog.search("mul").get(0).type());
        assertEquals("mosaic.min", MosaicCatalog.search("min").get(0).type());
        assertEquals("mosaic.mix", MosaicCatalog.search("mix").get(0).type());
        assertEquals("mosaic.sceneColor", MosaicCatalog.search("scene c").get(0).type());
        assertTrue(MosaicCatalog.search("ray").stream().anyMatch(e -> e.type().equals("mosaic.trace")));
        assertEquals(MosaicCatalog.all().size(), MosaicCatalog.search("  ").size());
        assertTrue(MosaicCatalog.search("zzzz").isEmpty());
    }

    // ------------------------------------------------------------------ layout

    @Test
    void layoutRegionsNeverOverlapAndStayOnScreen() {
        int[][] sizes = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {854, 480}, {960, 540}, {1280, 720}, {1920, 1080}};
        for (int[] s : sizes) {
            for (boolean p : new boolean[] {true, false}) {
                for (boolean i : new boolean[] {true, false}) {
                    MosaicLayout l = MosaicLayout.of(s[0], s[1], p, i);
                    List<Rect> all = List.of(l.top(), l.palette(), l.canvas(), l.inspector(), l.status());
                    for (int a = 0; a < all.size(); a++) {
                        Rect r = all.get(a);
                        if (r.visible()) {
                            assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= s[0] && r.bottom() <= s[1],
                                r + " off screen at " + s[0] + "x" + s[1]);
                        }
                        for (int b = a + 1; b < all.size(); b++)
                            assertFalse(r.overlaps(all.get(b)), r + " overlaps " + all.get(b) + " at " + s[0] + "x" + s[1]);
                    }
                    assertTrue(l.canvas().w() >= Math.min(MosaicLayout.MIN_CANVAS, s[0]), "canvas too narrow at " + s[0]);
                    // the body tiles the width exactly: no dead strip between panels
                    assertEquals(s[0], l.palette().w() + l.canvas().w() + l.inspector().w());
                    if (!p) assertFalse(l.paletteShown());
                    if (!i) assertFalse(l.inspectorShown());
                }
            }
        }
    }

    @Test
    void wideScreensShowBothPanels() {
        MosaicLayout l = MosaicLayout.of(854, 480, true, true);
        assertTrue(l.paletteShown());
        assertTrue(l.inspectorShown());
        assertTrue(l.palette().right() == l.canvas().x() && l.canvas().right() == l.inspector().x());
    }

    // ------------------------------------------------------------------ document history

    private static MosaicDocument seed() {
        MosaicDocument d = new MosaicDocument();
        MosaicDocument.ENode c = d.add("mosaic.sceneColor", 0, 0);
        MosaicDocument.ENode o = d.add("mosaic.output", 200, 0);
        o.inputs.put("color", c.id);
        d.dirty = false;
        return d;
    }

    @Test
    void undoAndRedoRestoreWholeSteps() {
        MosaicDocument d = seed();
        String s0 = d.snapshot();
        String before = d.snapshot();
        d.add("mosaic.const", 50, 50).value = new double[] {0.5};
        assertTrue(d.commit(before));
        String s1 = d.snapshot();
        before = d.snapshot();
        d.nodes.get(0).x = 99;                 // a move is a step too
        assertTrue(d.commit(before));
        assertFalse(d.commit(d.snapshot()));   // nothing changed, nothing recorded

        assertTrue(d.undo());
        assertEquals(s1, d.snapshot());
        assertTrue(d.undo());
        assertEquals(s0, d.snapshot());
        assertFalse(d.undo());
        assertTrue(d.redo());
        assertEquals(s1, d.snapshot());
        // a new edit after an undo drops the redo branch
        before = d.snapshot();
        d.remove(d.byId("const"));
        d.commit(before);
        assertFalse(d.canRedo());
    }

    @Test
    void historyStepsHaveNamesAndCanBeJumpedTo() {
        MosaicDocument d = seed();
        String b = d.snapshot();
        d.add("mosaic.time", 0, 0);
        d.commit(b, "Add Time");
        b = d.snapshot();
        d.nodes.get(0).x = 50;
        d.commit(b, "Move 1 node");
        b = d.snapshot();
        d.add("mosaic.uv", 0, 0);
        d.commit(b, "Add UV");
        assertEquals(List.of("Add Time", "Move 1 node", "Add UV"), d.undoSteps());
        d.jumpTo(1);
        assertEquals(List.of("Add Time"), d.undoSteps());
        assertEquals(List.of("Move 1 node", "Add UV"), d.redoSteps());
        assertNull(d.byId("uv"));
        d.jumpTo(3);
        assertNotNull(d.byId("uv"));
        assertTrue(d.redoSteps().isEmpty());
    }

    @Test
    void duplicateCopiesParametersAndWiresUnderANewId() {
        MosaicDocument d = seed();
        MosaicDocument.ENode s = d.add("mosaic.swizzle", 10, 10);
        s.mask = "xy";
        s.inputs.put("a", "sceneColor");
        MosaicDocument.ENode copy = d.duplicate(s);
        assertNotEquals(s.id, copy.id);
        assertEquals("xy", copy.mask);
        assertEquals("sceneColor", copy.inputs.get("a"));
        assertEquals(s.x + 20, copy.x);
        assertNull(d.validate());
    }

    // ------------------------------------------------------------------ validator fixes

    private static String graph(String body) {
        return "{\"nodes\": [" + body + "]}";
    }

    @Test
    void traceNamedOutputFeedsAVectorOperation() {
        MosaicGraph g = MosaicGraph.parse(graph("""
            {"id": "ray", "type": "mosaic.trace"},
            {"id": "s",   "type": "mosaic.swizzle", "mask": "zyx", "inputs": {"a": "ray.pos"}},
            {"id": "o",   "type": "mosaic.output", "inputs": {"color": "s"}}"""));
        assertEquals(Kind.V3, g.kind("s"));
        assertTrue(MosaicCodegen.glsl(g).contains("vec3 g_s = (g_ray_pos).zyx;"));
    }

    @Test
    void dotAcceptsVec3PairsAndCrossTakesTwoOperands() {
        MosaicGraph g = MosaicGraph.parse(graph("""
            {"id": "a", "type": "mosaic.const", "value": [1, 0, 0]},
            {"id": "b", "type": "mosaic.const", "value": [0, 1, 0]},
            {"id": "d", "type": "mosaic.dot",   "inputs": {"a": "a", "b": "b"}},
            {"id": "c", "type": "mosaic.cross", "inputs": {"a": "a", "b": "b"}},
            {"id": "r", "type": "mosaic.reflect", "inputs": {"a": "c", "b": "b"}},
            {"id": "m", "type": "mosaic.mul",   "inputs": {"a": "r", "b": "d"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "m"}}"""));
        assertEquals(Kind.F, g.kind("d"));
        assertEquals(Kind.V3, g.kind("c"));
        String glsl = MosaicCodegen.generate(g, new MosaicCodegen.Options(null, false, false), false).source();
        assertTrue(glsl.contains("cross(g_a, g_b)"), glsl);
        // optimized: every node here is constant, so the whole chain folds to one literal
        assertTrue(MosaicCodegen.glsl(g).contains("vec3 g_m = vec3(0.0, 0.0, 0.0); // folded mul"));
        assertTrue(glsl.contains("reflect(g_c, g_b)"), glsl);
        assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(graph("""
            {"id": "a", "type": "mosaic.const", "value": [1, 0, 0]},
            {"id": "c", "type": "mosaic.cross", "inputs": {"a": "a"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "c"}}""")));
    }

    @Test
    void floatIntoAVectorOnlyPortIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(graph("""
            {"id": "h", "type": "mosaic.const", "value": [0.5]},
            {"id": "c", "type": "mosaic.sceneColor", "inputs": {"uv": "h"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "c"}}""")));
        assertTrue(e.getMessage().contains("port uv takes a vec2"), e.getMessage());
    }

    @Test
    void stepNeedsAFloatEdgeOrMatchingVectors() {
        assertDoesNotThrow(() -> MosaicGraph.parse(graph("""
            {"id": "e", "type": "mosaic.const", "value": [0.5]},
            {"id": "c", "type": "mosaic.sceneColor"},
            {"id": "s", "type": "mosaic.step", "inputs": {"a": "e", "b": "c"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "s"}}""")));
        assertThrows(IllegalArgumentException.class, () -> MosaicGraph.parse(graph("""
            {"id": "e", "type": "mosaic.const", "value": [0.5]},
            {"id": "c", "type": "mosaic.sceneColor"},
            {"id": "s", "type": "mosaic.step", "inputs": {"a": "c", "b": "e"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "s"}}""")));
    }

    @Test
    void traceOutputsKeepSocketOrderAndSlangUsesFrac() {
        assertEquals(List.of("pos", "hit", "inst", "prim"), MosaicGraph.TRACE_OUTPUTS);
        MosaicGraph g = MosaicGraph.parse(graph("""
            {"id": "t", "type": "mosaic.time"},
            {"id": "f", "type": "mosaic.fract", "inputs": {"a": "t"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "f"}}"""));
        assertTrue(MosaicCodegen.slang(g).contains("frac(g_t)"));
        assertTrue(MosaicCodegen.glsl(g).contains("fract(g_t)"));
    }

    @Test
    void acceptedKindExplainsPorts() {
        assertNull(MosaicGraph.acceptedKind("mosaic.mul", "a"));
        assertNull(MosaicGraph.acceptedKind("mosaic.output", "color"));
        assertEquals(Kind.V2, MosaicGraph.acceptedKind("mosaic.sceneColor", "uv"));
        assertEquals(Kind.F, MosaicGraph.acceptedKind("mosaic.mix", "t"));
        assertEquals(Kind.V3, MosaicGraph.acceptedKind("mosaic.cross", "b"));
    }

    @Test
    void examplePackGraphParses() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of("../examples/custom-rt-mosaic/shaders/bedray/mosaic.json");
        if (!java.nio.file.Files.exists(p)) p = java.nio.file.Path.of(System.getProperty("mosaic.example", p.toString()));
        MosaicGraph g = MosaicGraph.parse(java.nio.file.Files.readString(p));
        assertTrue(g.needsRay() && g.needsPush());
    }
}
