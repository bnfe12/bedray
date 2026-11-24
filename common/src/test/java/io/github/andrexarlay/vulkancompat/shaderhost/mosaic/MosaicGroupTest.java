package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Group operations of the editor: copies with wire remapping, clipboard fragments and automatic layout. */
class MosaicGroupTest {

    private static MosaicDocument example() throws Exception {
        Path p = Path.of("../examples/custom-rt-mosaic/shaders/bedray/mosaic.json");
        if (!Files.exists(p)) p = Path.of(System.getProperty("mosaic.example", p.toString()));
        return MosaicDocument.load(p);
    }

    @Test
    void duplicatingAGroupRewiresInternalWiresAndKeepsExternalOnes() throws Exception {
        MosaicDocument d = example();
        List<MosaicDocument.ENode> group = List.of(d.byId("tint"), d.byId("strength"), d.byId("tinted"));
        int before = d.nodes.size();
        List<MosaicDocument.ENode> copies = d.insertCopies(MosaicDocument.copyOf(group), 30, 40, true);
        assertEquals(before + 3, d.nodes.size());
        MosaicDocument.ENode strength2 = copies.get(1), tinted2 = copies.get(2);
        assertNotEquals("strength", strength2.id);
        assertEquals(copies.get(0).id, strength2.inputs.get("a"));        // internal wire follows the copy
        assertEquals(strength2.id, tinted2.inputs.get("b"));
        assertEquals("shaded", tinted2.inputs.get("a"));                  // external wire kept on duplicate
        assertEquals(d.byId("tinted").x + 30, tinted2.x);
        assertNull(d.validate());
    }

    @Test
    void pastingAFragmentDropsWiresToNodesItDoesNotCarry() throws Exception {
        MosaicDocument src = example();
        String clip = MosaicDocument.fragmentJson(List.of(src.byId("strength"), src.byId("tinted")));
        assertTrue(clip.contains("mosaicFragment"));
        List<MosaicDocument.ENode> nodes = MosaicDocument.readFragment(clip);
        assertNotNull(nodes);

        MosaicDocument target = new MosaicDocument();
        target.add("mosaic.const", 0, 0);
        List<MosaicDocument.ENode> pasted = target.insertCopies(nodes, 0, 0, false);
        assertEquals("strength", pasted.get(0).id);
        assertFalse(pasted.get(0).inputs.containsKey("a"));               // tint was not copied
        assertEquals("strength", pasted.get(1).inputs.get("b"));
        assertFalse(pasted.get(1).inputs.containsKey("a"));               // shaded was not copied

        // pasting the same fragment again picks fresh ids
        List<MosaicDocument.ENode> again = target.insertCopies(nodes, 0, 0, false);
        assertNotEquals("strength", again.get(0).id);
        assertEquals(again.get(0).id, again.get(1).inputs.get("b"));
    }

    @Test
    void foreignClipboardTextIsNotAFragment() {
        assertNull(MosaicDocument.readFragment("hello"));
        assertNull(MosaicDocument.readFragment("{\"nodes\": []}"));
        assertNull(MosaicDocument.readFragment("{\"nodes\": [{\"id\": \"x\", \"type\": \"mosaic.nope\"}]}"));
        assertNull(MosaicDocument.readFragment(null));
        assertNotNull(MosaicDocument.readFragment("{\"nodes\": [{\"id\": \"x\", \"type\": \"mosaic.uv\"}]}"));
    }

    @Test
    void arrangeSendsEveryWireLeftToRightWithoutOverlaps() throws Exception {
        MosaicDocument d = example();
        for (MosaicDocument.ENode n : d.nodes) { // scramble
            n.x = (n.id.hashCode() & 255) * 3;
            n.y = (n.id.hashCode() >> 8 & 255) * 2;
        }
        int minX = d.nodes.stream().mapToInt(n -> n.x).min().orElseThrow();
        int minY = d.nodes.stream().mapToInt(n -> n.y).min().orElseThrow();
        int outBefore = d.byId("out").x;
        MosaicArrange.arrange(d.nodes, 124, n -> 40);
        assertEquals(outBefore, d.byId("out").x, "the output stays where it was; the rest flows to its left");
        for (MosaicDocument.ENode n : d.nodes)
            for (String ref : n.inputs.values())
                assertTrue(d.byId(MosaicGraph.split(ref)[0]).x < n.x, ref + " -> " + n.id + " runs backwards");
        for (MosaicDocument.ENode a : d.nodes)
            for (MosaicDocument.ENode b : d.nodes)
                if (a != b && a.x == b.x) assertTrue(Math.abs(a.y - b.y) >= 40, a.id + " overlaps " + b.id);
        int outX = d.byId("out").x;
        assertTrue(minX <= outX && minY <= d.byId("out").y + 10_000); // the output side keeps its place (checked below)
        assertEquals("out", d.nodes.stream().max(java.util.Comparator.comparingInt(n -> n.x)).orElseThrow().id);
        assertNull(d.validate());
    }

    @Test
    void labelsAndFramesAreEditorOnlyAndSurviveHistory() throws Exception {
        MosaicDocument d = example();
        for (int i = 0; i < d.nodes.size(); i++) d.nodes.get(i).x = i * 200; // spread out: the example has no positions
        String before = d.snapshot();
        d.byId("tint").label = "Tint from rt.push";
        MosaicDocument.Frame f = d.frameAround(List.of(d.byId("tint"), d.byId("strength")), "Tint", 0x8844AA, n -> 124, n -> 40);
        d.commit(before);
        String json = d.toJson();
        assertTrue(json.contains("\"frames\"") && json.contains("Tint from rt.push"));
        assertNull(MosaicGraph.analyze(json).firstError(), "frames and labels must not disturb the shader");
        assertEquals(2, d.nodesIn(f).size());
        d.undo();
        assertTrue(d.frames.isEmpty());
        assertNull(d.byId("tint").label);
        d.redo();
        assertEquals("Tint", d.frames.get(0).title);
        assertEquals("Tint from rt.push", d.byId("tint").label);
        assertEquals(0x8844AA, d.frames.get(0).color);
    }

    @Test
    void alignLinesUpTheGroup() {
        MosaicDocument d = new MosaicDocument();
        MosaicDocument.ENode a = d.add("mosaic.uv", 10, 50), b = d.add("mosaic.time", 40, 20);
        MosaicArrange.alignLeft(List.of(a, b));
        MosaicArrange.alignTop(List.of(a, b));
        assertEquals(10, b.x);
        assertEquals(20, a.y);
    }

    @Test
    void arrangeKeepsWiresLeftToRightWithoutOverlap() {
        List<MosaicDocument.ENode> nodes = MosaicDocument.readFragment("{\"nodes\": [" +
            "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\", \"x\": 500, \"y\": 400}," +
            "{\"id\": \"t\", \"type\": \"mosaic.time\", \"x\": 0, \"y\": 0}," +
            "{\"id\": \"n\", \"type\": \"mosaic.noise\", \"x\": 300, \"y\": 900}," +
            "{\"id\": \"m\", \"type\": \"mosaic.mixColor\", \"inputs\": {\"a\": \"c\", \"b\": \"n.color\", \"fac\": \"t\"}, \"x\": 10, \"y\": 10}," +
            "{\"id\": \"v\", \"type\": \"mosaic.uv\", \"x\": 40, \"y\": 700}," +
            "{\"id\": \"lonely\", \"type\": \"mosaic.time\", \"x\": 900, \"y\": 50}," +
            "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"m\"}, \"x\": 1000, \"y\": 300}]}");
        java.util.function.ToIntFunction<MosaicDocument.ENode> h = n -> 60 + 20 * n.inputs.size();
        java.util.Map<String, MosaicDocument.ENode> ids = new java.util.HashMap<>();
        for (MosaicDocument.ENode n : nodes) ids.put(n.id, n);
        record D(java.util.Map<String, MosaicDocument.ENode> m, List<MosaicDocument.ENode> nodes) {
            MosaicDocument.ENode byId(String id) { return m.get(id); }
        }
        D d = new D(ids, nodes);
        MosaicArrange.arrange(d.nodes, 160, h, n -> MosaicGraph.portsOf(n.type, MosaicEffects.all().stream().collect(java.util.stream.Collectors.toMap(MosaicFunction::name, f -> f)))
            .stream().map(MosaicGraph.Port::name).toList());
        MosaicDocument.ENode o = d.byId("o"), m = d.byId("m"), t = d.byId("t"), c = d.byId("c"), lonely = d.byId("lonely");
        assertEquals(1000, o.x, "the output keeps its place");
        for (MosaicDocument.ENode n : d.nodes)
            for (String ref : n.inputs.values()) assertTrue(d.byId(MosaicGraph.split(ref)[0]).x < n.x, "wire into " + n.id + " runs right");
        assertEquals(m.x - 160 - MosaicArrange.COLUMN_GAP, t.x, "time sits right next to the mix that uses it");
        assertEquals(t.x, c.x);
        // the inputs follow the socket order of the mix: a (color) above b (noise) above fac (time)
        assertTrue(c.y < d.byId("n").y && d.byId("n").y < t.y);
        for (MosaicDocument.ENode a : d.nodes)
            for (MosaicDocument.ENode b : d.nodes)
                if (a != b && a.x == b.x) assertTrue(a.y + h.applyAsInt(a) <= b.y || b.y + h.applyAsInt(b) <= a.y, a.id + " overlaps " + b.id);
        assertTrue(lonely.y > o.y && lonely.y > m.y, "unconnected nodes go underneath");
    }
}
