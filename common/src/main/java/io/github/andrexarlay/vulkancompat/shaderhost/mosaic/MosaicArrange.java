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

    public static final int COLUMN_GAP = 56, ROW_GAP = 14;

    /** Lays out {@code group} in place; {@code height} gives a node's drawn height, {@code width} the node width. */
    public static void arrange(Collection<MosaicDocument.ENode> group, int width, ToIntFunction<MosaicDocument.ENode> height) {
        if (group.isEmpty()) return;
        Map<String, MosaicDocument.ENode> byId = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (MosaicDocument.ENode n : group) {
            byId.put(n.id, n);
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
        }
        // depth = longest path from a source inside the group (cycles cannot occur in valid graphs; guarded anyway)
        Map<String, Integer> depth = new HashMap<>();
        for (MosaicDocument.ENode n : group) depthOf(n, byId, depth, new ArrayList<>());
        int columns = depth.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<List<MosaicDocument.ENode>> cols = new ArrayList<>();
        for (int i = 0; i < columns; i++) cols.add(new ArrayList<>());
        for (MosaicDocument.ENode n : group) cols.get(depth.get(n.id)).add(n);

        Map<String, Double> row = new HashMap<>();
        for (int c = 0; c < columns; c++) {
            List<MosaicDocument.ENode> col = cols.get(c);
            if (c == 0) {
                col.sort(Comparator.comparingInt((MosaicDocument.ENode n) -> n.y)); // sources keep their order
            } else {
                Map<String, Double> key = new HashMap<>();
                for (MosaicDocument.ENode n : col) {
                    double sum = 0;
                    int count = 0;
                    for (String ref : n.inputs.values()) {
                        Double r = row.get(MosaicGraph.split(ref)[0]);
                        if (r != null) {
                            sum += r;
                            count++;
                        }
                    }
                    key.put(n.id, count == 0 ? n.y / 1000.0 : sum / count);
                }
                col.sort(Comparator.comparingDouble((MosaicDocument.ENode n) -> key.get(n.id)));
            }
            int y = minY;
            for (int i = 0; i < col.size(); i++) {
                MosaicDocument.ENode n = col.get(i);
                n.x = minX + c * (width + COLUMN_GAP);
                n.y = y;
                y += height.applyAsInt(n) + ROW_GAP;
                row.put(n.id, (double) i);
            }
        }
    }

    private static int depthOf(MosaicDocument.ENode n, Map<String, MosaicDocument.ENode> byId, Map<String, Integer> depth,
                               List<String> visiting) {
        Integer known = depth.get(n.id);
        if (known != null) return known;
        if (visiting.contains(n.id)) return 0;
        visiting.add(n.id);
        int d = 0;
        for (String ref : n.inputs.values()) {
            MosaicDocument.ENode src = byId.get(MosaicGraph.split(ref)[0]);
            if (src != null) d = Math.max(d, depthOf(src, byId, depth, visiting) + 1);
        }
        visiting.remove(visiting.size() - 1);
        depth.put(n.id, d);
        return d;
    }

    /** Lines a group up on the left edge of its leftmost node. */
    public static void alignLeft(Collection<MosaicDocument.ENode> group) {
        int x = group.stream().mapToInt(n -> n.x).min().orElse(0);
        for (MosaicDocument.ENode n : group) n.x = x;
    }

    /** Lines a group up on the top edge of its highest node. */
    public static void alignTop(Collection<MosaicDocument.ENode> group) {
        int y = group.stream().mapToInt(n -> n.y).min().orElse(0);
        for (MosaicDocument.ENode n : group) n.y = y;
    }
}
