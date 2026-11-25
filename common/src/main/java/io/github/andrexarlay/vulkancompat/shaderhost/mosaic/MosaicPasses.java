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

    /**
     * {@code passes}: Buffer passes in dependency order, the final image last. {@code after}: history passes that run after
     * the final image and copy a buffer (or the ray distance) for the next frame's Buffer last frame / Accumulate nodes.
     */
    public record Plan(List<Pass> passes, Map<String, Integer> bindings, List<Pass> after) {
        public Plan(List<Pass> passes, Map<String, Integer> bindings) { this(passes, bindings, List.of()); }

        /** Every pass the host dispatches: the ordered passes, then the history copies. */
        public List<Pass> all() {
            List<Pass> out = new ArrayList<>(passes);
            out.addAll(after);
            return out;
        }

        public Pass last() { return passes.get(passes.size() - 1); }
    }

    private static final String FINAL = "#final";
    /** Buffer names of the history copies (never valid user names, sorted after them so user bindings keep their numbers). */
    public static final String HISTORY_PREFIX = "~prev.", DEPTH_HISTORY = "~depth";

    public static String historyName(String buffer) { return HISTORY_PREFIX + buffer; }

    /** Orders the passes the final image needs; throws IllegalArgumentException on a buffer cycle. */
    public static Plan plan(MosaicGraph g) {
        Map<String, MosaicGraph.Node> writers = new LinkedHashMap<>();
        for (MosaicGraph.Node n : g.nodes()) if (n.type().equals("mosaic.pass")) writers.put(n.mask(), n);
        Map<String, Integer> bindings = new LinkedHashMap<>();
        int b = FIRST_BUFFER_BINDING;
        Set<String> kept = new TreeSet<>();
        boolean keepDepth = false;
        for (MosaicGraph.Node n : g.nodes())
            if (MosaicGraph.readsHistory(n.type()) && n.mask() != null && writers.containsKey(n.mask())) {
                kept.add(n.mask());
                keepDepth |= n.type().equals("mosaic.accumulate");
            }
        Set<String> names = new TreeSet<>(writers.keySet());
        for (String k : kept) names.add(historyName(k));
        if (keepDepth) names.add(DEPTH_HISTORY);
        for (String name : names) bindings.put(name, b++);
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
        List<Pass> after = new ArrayList<>();
        for (String k : kept) {
            int bind = bindings.get(k);
            String json = "{\"nodes\":[{\"id\":\"r\",\"type\":\"mosaic.bufferIn\",\"mask\":" + GSON.toJson(k) + ",\"index\":" + bind
                + "},{\"id\":\"m_pass_out\",\"type\":\"mosaic.output\",\"inputs\":{\"color\":\"r\"}}]}";
            after.add(new Pass(historyName(k), Math.max(1, writers.get(k).index()), json, Set.of(bind), false));
        }
        if (keepDepth) after.add(new Pass(DEPTH_HISTORY, 1, "{\"nodes\":[{\"id\":\"d\",\"type\":\"mosaic.sceneDepth\"},"
            + "{\"id\":\"v\",\"type\":\"mosaic.vec\",\"index\":4,\"inputs\":{\"x\":\"d\"}},"
            + "{\"id\":\"m_pass_out\",\"type\":\"mosaic.output\",\"inputs\":{\"color\":\"v\"}}]}", Set.of(), false));
        return new Plan(passes, bindings, after);
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
            int index = MosaicGraph.readsBuffer(n.type()) ? bindings.getOrDefault(n.mask(), 0)
                : MosaicGraph.readsHistory(n.type()) ? bindings.getOrDefault(historyName(n.mask()), 0) : n.index();
            if (index != 0) j.addProperty("index", index);
            // Accumulate also reads last frame's ray distance: its binding travels in the value
            if (n.type().equals("mosaic.accumulate")) j.add("value", GSON.toJsonTree(new double[] {bindings.getOrDefault(DEPTH_HISTORY, 0)}));
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
