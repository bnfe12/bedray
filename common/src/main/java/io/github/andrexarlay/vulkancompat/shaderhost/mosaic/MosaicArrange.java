package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Automatic layout for Mosaic graphs: nodes go into columns by dependency depth (every wire runs left to right),
 * each column is ordered by the average row of its inputs to keep wires short and uncrossed, and columns are stacked
 * top-down without overlap. Works on any subset of nodes (wires to nodes outside the subset are ignored), keeping the
 * subset's top-left corner where it was. Pure Java.
 */
public final class MosaicArrange {

    private MosaicArrange() {}

    public static final int COLUMN_GAP = 64, ROW_GAP = 18;

    /** Lays out {@code group} in place with the inputs in map order; see the four-argument form. */
    public static void arrange(Collection<MosaicDocument.ENode> group, int width, ToIntFunction<MosaicDocument.ENode> height) {
        arrange(group, width, height, n -> new ArrayList<>(n.inputs.keySet()));
    }

    /**
     * Lays out {@code group} in place, Blender-style: every node sits one column left of its nearest consumer (so a
     * time or texture node used only at the end stays next to where it is used), columns are ordered to follow the
     * consumers' input sockets (which removes most crossings), and each node is centred on the socket it feeds, with
     * overlaps resolved by moving blocks as little as possible. The sinks (output side) keep their place, the rest
     * flows to their left. Nodes without any wire inside the group are lined up underneath.
     *
     * @param inputOrder a node's input ports in drawn order (top to bottom)
     */
    public static void arrange(Collection<MosaicDocument.ENode> group, int width, ToIntFunction<MosaicDocument.ENode> height,
                               java.util.function.Function<MosaicDocument.ENode, List<String>> inputOrder) {
        if (group.isEmpty()) return;
        Map<String, MosaicDocument.ENode> byId = new LinkedHashMap<>();
        for (MosaicDocument.ENode n : group) byId.put(n.id, n);
        // edges inside the group: consumer -> (source, socket fraction down the consumer)
        Map<MosaicDocument.ENode, List<MosaicDocument.ENode>> sources = new HashMap<>(), consumers = new HashMap<>();
        Map<String, Double> socketFrac = new HashMap<>(); // "src>dst" -> fraction of dst height of the first socket fed
        for (MosaicDocument.ENode n : group) {
            List<String> ports = inputOrder.apply(n);
            int count = Math.max(1, ports.size());
            for (int i = 0; i < ports.size(); i++) {
                String ref = n.inputs.get(ports.get(i));
                MosaicDocument.ENode src = ref == null ? null : byId.get(MosaicGraph.split(ref)[0]);
                if (src == null || src == n) continue;
                if (!sources.computeIfAbsent(n, k -> new ArrayList<>()).contains(src)) sources.get(n).add(src);
                if (!consumers.computeIfAbsent(src, k -> new ArrayList<>()).contains(n)) consumers.get(src).add(n);
                socketFrac.putIfAbsent(src.id + ">" + n.id, (i + 1.0) / (count + 1.0));
            }
        }
        List<MosaicDocument.ENode> connected = new ArrayList<>(), loose = new ArrayList<>();
        for (MosaicDocument.ENode n : group) (sources.containsKey(n) || consumers.containsKey(n) ? connected : loose).add(n);

        int anchorX = Integer.MIN_VALUE, minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (MosaicDocument.ENode n : group) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
        }
        int bottom = minY;
        if (!connected.isEmpty()) {
            // columns counted from the sinks: distance = longest path to a node nothing in the group consumes
            Map<MosaicDocument.ENode, Integer> dist = new HashMap<>();
            for (MosaicDocument.ENode n : connected) distToSink(n, consumers, dist, new ArrayList<>());
            int maxDist = dist.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<List<MosaicDocument.ENode>> cols = new ArrayList<>();
            for (int i = 0; i <= maxDist; i++) cols.add(new ArrayList<>());
            for (MosaicDocument.ENode n : connected) cols.get(maxDist - dist.get(n)).add(n);
            for (MosaicDocument.ENode n : cols.get(maxDist)) anchorX = Math.max(anchorX, n.x);

            // ordering: sinks by their current height, then sweeps by socket barycentres
            Map<MosaicDocument.ENode, Double> pos = new HashMap<>();
            cols.get(maxDist).sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.y));
            index(cols.get(maxDist), pos);
            for (int c = maxDist - 1; c >= 0; c--) orderBy(cols.get(c), consumers, pos, socketFrac, true);
            for (int sweep = 0; sweep < 3; sweep++) {
                for (int c = 1; c <= maxDist; c++) if (c != maxDist) orderBy(cols.get(c), sources, pos, null, false);
                for (int c = maxDist - 1; c >= 0; c--) orderBy(cols.get(c), consumers, pos, socketFrac, true);
            }

            // y: sinks keep their place (overlaps resolved), every other node centres on the socket it feeds
            List<MosaicDocument.ENode> sinks = cols.get(maxDist);
            double[] want = new double[sinks.size()];
            for (int i = 0; i < want.length; i++) want[i] = sinks.get(i).y;
            place(sinks, want, height);
            for (int c = maxDist - 1; c >= 0; c--) {
                List<MosaicDocument.ENode> col = cols.get(c);
                double[] w = new double[col.size()];
                for (int i = 0; i < col.size(); i++) {
                    MosaicDocument.ENode n = col.get(i);
                    double sum = 0;
                    int k = 0;
                    for (MosaicDocument.ENode cons : consumers.get(n)) {
                        if (dist.get(cons) != dist.get(n) - 1) continue; // only the column right next to it
                        sum += cons.y + height.applyAsInt(cons) * socketFrac.getOrDefault(n.id + ">" + cons.id, 0.5);
                        k++;
                    }
                    if (k == 0) for (MosaicDocument.ENode cons : consumers.get(n)) {
                        sum += cons.y + height.applyAsInt(cons) * socketFrac.getOrDefault(n.id + ">" + cons.id, 0.5);
                        k++;
                    }
                    w[i] = sum / k - height.applyAsInt(n) / 2.0;
                }
                place(col, w, height);
            }
            for (int c = 0; c <= maxDist; c++)
                for (MosaicDocument.ENode n : cols.get(c)) {
                    n.x = anchorX - (maxDist - c) * (width + COLUMN_GAP);
                    bottom = Math.max(bottom, n.y + height.applyAsInt(n));
                    maxY = Math.max(maxY, n.y);
                }
            minX = Math.min(minX, anchorX - maxDist * (width + COLUMN_GAP));
        }
        // loose nodes: a row underneath, in their old left-to-right order
        loose.sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.x));
        int x = minX;
        int y = connected.isEmpty() ? minY : bottom + ROW_GAP * 3;
        for (MosaicDocument.ENode n : loose) {
            n.x = x;
            n.y = y;
            x += width + COLUMN_GAP / 2;
        }
    }

    private static void index(List<MosaicDocument.ENode> col, Map<MosaicDocument.ENode, Double> pos) {
        for (int i = 0; i < col.size(); i++) pos.put(col.get(i), (double) i);
    }

    /** Sorts a column by the average position of its neighbours (plus socket fraction toward consumers). */
    private static void orderBy(List<MosaicDocument.ENode> col, Map<MosaicDocument.ENode, List<MosaicDocument.ENode>> neighbours,
                                Map<MosaicDocument.ENode, Double> pos, Map<String, Double> socketFrac, boolean towardConsumers) {
        Map<MosaicDocument.ENode, Double> key = new HashMap<>();
        for (int i = 0; i < col.size(); i++) {
            MosaicDocument.ENode n = col.get(i);
            double sum = 0;
            int k = 0;
            for (MosaicDocument.ENode o : neighbours.getOrDefault(n, List.of())) {
                Double p = pos.get(o);
                if (p == null) continue;
                sum += p + (towardConsumers ? socketFrac.getOrDefault(n.id + ">" + o.id, 0.5) : 0.5);
                k++;
            }
            key.put(n, k == 0 ? pos.getOrDefault(n, (double) i) : sum / k);
        }
        col.sort(Comparator.comparingDouble(key::get));
        index(col, pos);
    }

    /**
     * Puts a column's nodes (already in order) as close as possible to their wanted tops without overlap: blocks of
     * touching nodes are merged and centred on the average of what their members want.
     */
    static void place(List<MosaicDocument.ENode> col, double[] want, ToIntFunction<MosaicDocument.ENode> height) {
        int n = col.size();
        double[] off = new double[n]; // offset of node i from the top of its block
        List<int[]> blocks = new ArrayList<>(); // {first, last}
        List<Double> top = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            blocks.add(new int[] {i, i});
            top.add(want[i]);
            off[i] = 0;
            while (blocks.size() > 1) {
                int b = blocks.size() - 1;
                int[] prev = blocks.get(b - 1), cur = blocks.get(b);
                double prevBottom = top.get(b - 1) + off[prev[1]] + height.applyAsInt(col.get(prev[1])) + ROW_GAP;
                if (top.get(b) >= prevBottom) break;
                // merge cur into prev: members of cur stack under prev
                double shift = off[prev[1]] + height.applyAsInt(col.get(prev[1])) + ROW_GAP;
                for (int j = cur[0]; j <= cur[1]; j++) off[j] += shift;
                prev[1] = cur[1];
                double sum = 0;
                for (int j = prev[0]; j <= prev[1]; j++) sum += want[j] - off[j];
                top.set(b - 1, sum / (prev[1] - prev[0] + 1));
                blocks.remove(b);
                top.remove(b);
            }
        }
        for (int b = 0; b < blocks.size(); b++)
            for (int j = blocks.get(b)[0]; j <= blocks.get(b)[1]; j++) col.get(j).y = (int) Math.round(top.get(b) + off[j]);
    }

    private static int distToSink(MosaicDocument.ENode n, Map<MosaicDocument.ENode, List<MosaicDocument.ENode>> consumers,
                                  Map<MosaicDocument.ENode, Integer> dist, List<MosaicDocument.ENode> visiting) {
        Integer known = dist.get(n);
        if (known != null) return known;
        if (visiting.contains(n)) return 0;
        visiting.add(n);
        int d = 0;
        for (MosaicDocument.ENode c : consumers.getOrDefault(n, List.of())) d = Math.max(d, distToSink(c, consumers, dist, visiting) + 1);
        visiting.remove(visiting.size() - 1);
        dist.put(n, d);
        return d;
    }

    /** Lines a group up on the left edge of its leftmost node. */
    public static void alignLeft(Collection<MosaicDocument.ENode> group) {
        int x = group.stream().mapToInt(n -> n.x).min().orElse(0);
        for (MosaicDocument.ENode n : group) n.x = x;
    }

    /** Spaces a group evenly from left to right (keeps the outer nodes). */
    public static void distributeHorizontally(Collection<MosaicDocument.ENode> group, int width) {
        List<MosaicDocument.ENode> l = new ArrayList<>(group);
        if (l.size() < 3) return;
        l.sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.x));
        int a = l.get(0).x, b = l.get(l.size() - 1).x;
        for (int i = 1; i < l.size() - 1; i++) l.get(i).x = a + (int) Math.round((b - a) * i / (double) (l.size() - 1));
    }

    /** Stacks a group top to bottom with equal gaps (keeps the top node). */
    public static void distributeVertically(Collection<MosaicDocument.ENode> group, ToIntFunction<MosaicDocument.ENode> height) {
        List<MosaicDocument.ENode> l = new ArrayList<>(group);
        l.sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.y));
        int y = l.isEmpty() ? 0 : l.get(0).y;
        for (MosaicDocument.ENode n : l) {
            n.y = y;
            y += height.applyAsInt(n) + ROW_GAP;
        }
    }

    /** Lines a group up on the top edge of its highest node. */
    public static void alignTop(Collection<MosaicDocument.ENode> group) {
        int y = group.stream().mapToInt(n -> n.y).min().orElse(0);
        for (MosaicDocument.ENode n : group) n.y = y;
    }
}
