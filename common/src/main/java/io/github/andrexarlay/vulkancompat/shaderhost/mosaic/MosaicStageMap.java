package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The inside of a Bedray renderer stage: its settings as public parameters ({@code bedray.setting}, the numbers the
 * game takes from Studio over its own menu), and every module the stage is made of, framed, with each of its functions
 * as a node ({@code bedray.fn}; passes, the entry points Bedray dispatches, first). Each function opens as nodes, so
 * a stage goes all the way down: stage, modules, functions, their bodies.
 */
public final class MosaicStageMap {

    private MosaicStageMap() {}

    /** The (passive) end node of a stage map: it marks the document as a view of a stage. */
    public static final String OUTPUT = "bedray.stageMap";

    /** The modules a stage is made of (by MosaicPack.stageOf), sorted, passes first. */
    public static List<String> modules(String stageType, MosaicSlangLib.Library lib) {
        List<String> out = new ArrayList<>();
        for (String path : new java.util.TreeSet<>(lib.sources().keySet()))
            if (MosaicPack.stagesOf(path).contains(stageType)) out.add(MosaicSlangLib.moduleId(path));
        out.sort((a, b) -> Boolean.compare(!a.startsWith("passes/"), !b.startsWith("passes/")) * 2 + Integer.signum(a.compareTo(b)));
        return out;
    }

    /** The stage map graph; {@code values} are the stage's current settings (key -> number, see MosaicRenderer.Setting). */
    public static String graph(String stageType, MosaicSlangLib.Library lib, Map<String, Double> values) {
        MosaicRenderer.Stage stage = MosaicRenderer.stage(stageType);
        JsonArray nodes = new JsonArray(), frames = new JsonArray();
        int x = 40, frameColor = 0x3A6EA5;
        // settings: the public parameters
        if (stage != null && !stage.settings().isEmpty()) {
            int y = 80;
            for (MosaicRenderer.Setting s : stage.settings()) {
                JsonObject n = new JsonObject();
                n.addProperty("id", "set_" + s.key());
                n.addProperty("type", "bedray.setting");
                n.addProperty("mask", s.key());
                n.addProperty("label", s.label());
                JsonArray v = new JsonArray();
                v.add(values != null && values.containsKey(s.key()) ? values.get(s.key()) : s.def());
                n.add("value", v);
                if (s.help() != null) n.addProperty("note", s.help());
                n.addProperty("x", x + 20);
                n.addProperty("y", y);
                nodes.add(n);
                y += 120;
            }
            frames.add(frame("settings", "Settings: public parameters (they win over the game's menu)", x, 40, 260, y - 20, 0x7A4FA8));
            x += 320;
        }
        // modules: a frame each, a column of functions
        Map<String, List<MosaicSlangLib.Function>> byModule = new TreeMap<>();
        for (MosaicSlangLib.Function f : lib.functions().values()) byModule.computeIfAbsent(f.module(), k -> new ArrayList<>()).add(f);
        // modules side by side, each a frame with its functions in rows of three
        final int CELL = 250, PER_ROW = 3, MAX_H = 1500;
        int colTop = 40, colWidth = 0;
        for (String module : modules(stageType, lib)) {
            List<MosaicSlangLib.Function> fns = new ArrayList<>(byModule.getOrDefault(module, List.of()));
            fns.removeIf(f -> f.generic() && !f.entry());
            if (fns.isEmpty()) continue;
            fns.sort((a, b) -> Boolean.compare(!a.entry(), !b.entry()) * 2 + Integer.signum(Integer.compare(a.start(), b.start())));
            int cols = Math.min(PER_ROW, fns.size());
            int y = colTop + 44, rowH = 0, i = 0;
            for (MosaicSlangLib.Function f : fns) {
                int c = i % PER_ROW;
                if (c == 0 && i > 0) {
                    y += rowH + 30;
                    rowH = 0;
                }
                JsonObject n = new JsonObject();
                n.addProperty("id", ("f_" + f.id()).replaceAll("[^A-Za-z0-9_]", "_"));
                n.addProperty("type", MosaicSlangGraph.FN + f.id());
                if (f.entry()) n.addProperty("label", "pass " + module.substring(module.lastIndexOf('/') + 1));
                if (f.comment() != null) n.addProperty("note", f.comment());
                n.addProperty("x", x + 20 + c * CELL);
                n.addProperty("y", y);
                nodes.add(n);
                long ins = f.params().stream().filter(MosaicSlangLib.Param::isIn).count() + (f.owner() != null ? 1 : 0) + 1;
                long outs = f.params().stream().filter(MosaicSlangLib.Param::isOut).count() + 1;
                rowH = Math.max(rowH, (int) (56 + Math.max(ins, outs) * 18 + (f.comment() == null ? 0 : 60)));
                i++;
            }
            y += rowH + 20;
            int w = cols * CELL + 20;
            frames.add(frame("m_" + module.replaceAll("[^A-Za-z0-9_]", "_"), module + ".slang", x, colTop, w, y - colTop,
                module.startsWith("passes/") ? 0xA5683A : frameColor));
            colWidth = Math.max(colWidth, w);
            if (y > MAX_H) {
                x += colWidth + 60;
                colTop = 40;
                colWidth = 0;
            } else colTop = y + 50;
        }
        JsonObject out = new JsonObject();
        out.addProperty("id", "stage");
        out.addProperty("type", OUTPUT);
        out.addProperty("label", stage == null ? stageType : stage.label());
        out.addProperty("x", 40);
        out.addProperty("y", -60);
        nodes.add(out);
        JsonObject root = new JsonObject();
        root.add("nodes", nodes);
        root.add("frames", frames);
        root.addProperty("stage", stageType);
        return root.toString();
    }

    private static JsonObject frame(String id, String title, int x, int y, int w, int h, int color) {
        JsonObject f = new JsonObject();
        f.addProperty("id", id);
        f.addProperty("title", title);
        f.addProperty("x", x);
        f.addProperty("y", y);
        f.addProperty("w", w);
        f.addProperty("h", h);
        f.addProperty("color", String.format("#%06X", color));
        return f;
    }
}
