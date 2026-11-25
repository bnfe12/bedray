package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Multi-pass graphs: each Buffer node ({@code mosaic.pass}, name in mask, scale 1/2/4/8 in index) becomes its own compute
 * pass writing an rgba16f image; Read buffer nodes ({@code mosaic.bufferIn}) sample earlier passes at any uv (blur
 * kernels, bloom chains), Previous frame ({@code mosaic.history}) samples the last final image (temporal effects). The
 * plan orders the passes by what they read and gives every pass a standalone graph the normal codegen compiles.
 * Bindings: 16 weather and sky, 19 previous frame, 20 + i buffer i (names sorted).
 */
public final class MosaicPasses {

    private MosaicPasses() {}

    public static final int WORLD_INFO_BINDING = 16, PREV_FRAME_BINDING = 17, HISTORY_BINDING = 19, FIRST_BUFFER_BINDING = 20;
    /** BedrayPrevFrame (binding 17, std140): the previous frame's projection, model-view and camera position. */
    public static final int PREV_FRAME_BYTES = 144;

    /** One pass: its buffer name (null for the final image), scale divisor, its graph JSON, buffer bindings it reads. */
    public record Pass(String name, int scale, String json, Set<Integer> reads, boolean history) {}

    public record Plan(List<Pass> passes, Map<String, Integer> bindings) {
        public Pass last() { return passes.get(passes.size() - 1); }
    }

    private static final String FINAL = "#final";

    /** Orders the passes the final image needs; throws IllegalArgumentException on a buffer cycle. */
    public static Plan plan(MosaicGraph g) {
        Map<String, MosaicGraph.Node> writers = new LinkedHashMap<>();
        for (MosaicGraph.Node n : g.nodes()) if (n.type().equals("mosaic.pass")) writers.put(n.mask(), n);
        Map<String, Integer> bindings = new LinkedHashMap<>();
        int b = FIRST_BUFFER_BINDING;
        for (String name : new TreeSet<>(writers.keySet())) bindings.put(name, b++);
        Map<String, Set<String>> upstream = new LinkedHashMap<>();
        for (Map.Entry<String, MosaicGraph.Node> e : writers.entrySet()) upstream.put(e.getKey(), up(g, e.getValue().inputs().get("color")));
        upstream.put(FINAL, up(g, g.node(g.outputId()).inputs().get("color")));
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : upstream.entrySet()) {
            Set<String> r = new TreeSet<>();
            for (String id : e.getValue()) if (MosaicGraph.readsBuffer(g.node(id).type()) && g.node(id).mask() != null) r.add(g.node(id).mask());
            reads.put(e.getKey(), r);
        }
        List<String> order = new ArrayList<>();
        visit(FINAL, reads, order, new LinkedHashSet<>());
        List<Pass> passes = new ArrayList<>();
        for (String name : order) {
            boolean last = name.equals(FINAL);
            String root = last ? g.node(g.outputId()).inputs().get("color") : writers.get(name).inputs().get("color");
            int scale = last ? 1 : Math.max(1, writers.get(name).index());
            Set<Integer> rb = new TreeSet<>();
            for (String r : reads.get(name)) rb.add(bindings.get(r));
            boolean history = upstream.get(name).stream().anyMatch(id -> g.node(id).type().equals("mosaic.history"));
            passes.add(new Pass(last ? null : name, scale, passGraph(g, upstream.get(name), root, bindings), rb, history));
        }
        return new Plan(passes, bindings);
    }

    private static void visit(String name, Map<String, Set<String>> reads, List<String> order, Set<String> visiting) {
        if (order.contains(name)) return;
        if (!visiting.add(name)) throw new IllegalArgumentException("buffers read each other in a circle at " + name);
        for (String r : reads.getOrDefault(name, Set.of())) visit(r, reads, order, visiting);
        order.add(name);
    }

    private static Set<String> up(MosaicGraph g, String ref) {
        Set<String> out = new LinkedHashSet<>();
        if (ref != null) walk(g, MosaicGraph.split(ref)[0], out);
        return out;
    }

    private static void walk(MosaicGraph g, String id, Set<String> out) {
        if (!out.add(id)) return;
        MosaicGraph.Node n = g.node(id);
        if (n == null) return;
        for (String r : n.inputs().values()) walk(g, MosaicGraph.split(r)[0], out);
    }

    private static final Gson GSON = new Gson();

    /** A standalone graph: the nodes a pass needs plus an output fed by its root; Read buffer nodes carry their binding. */
    static String passGraph(MosaicGraph g, Set<String> ids, String root, Map<String, Integer> bindings) {
        JsonObject o = new JsonObject();
        JsonArray nodes = new JsonArray();
        Set<String> fns = new TreeSet<>();
        for (MosaicGraph.Node n : g.nodes()) {
            if (!ids.contains(n.id())) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", n.id());
            j.addProperty("type", n.type());
            if (!n.inputs().isEmpty()) j.add("inputs", GSON.toJsonTree(n.inputs()));
            if (n.value() != null) j.add("value", GSON.toJsonTree(n.value()));
            int index = MosaicGraph.readsBuffer(n.type()) ? bindings.getOrDefault(n.mask(), 0) : n.index();
            if (index != 0) j.addProperty("index", index);
            if (n.mask() != null) j.addProperty("mask", n.mask());
            if (!n.defaults().isEmpty()) j.add("defaults", GSON.toJsonTree(n.defaults()));
            nodes.add(j);
            if (MosaicFunction.isFunctionType(n.type())) fns.add(MosaicFunction.nameOf(n.type()));
            if (n.type().equals("mosaic.repeat") && n.mask() != null && g.functions().containsKey(n.mask())) fns.add(n.mask());
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", "m_pass_out");
        out.addProperty("type", "mosaic.output");
        JsonObject in = new JsonObject();
        in.addProperty("color", root);
        out.add("inputs", in);
        nodes.add(out);
        o.add("nodes", nodes);
        if (!fns.isEmpty()) {
            JsonArray fa = new JsonArray();
            for (String f : fns) fa.add(g.functions().get(f).toJson());
            o.add("functions", fa);
        }
        return o.toString();
    }
}
