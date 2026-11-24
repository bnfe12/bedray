package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The Mosaic editor's editable document: nodes with canvas positions, serializable to mosaic.json.
 * Every mutator marks the document dirty; {@link #validate()} carries the parse error back to the editor.
 */
public final class MosaicDocument {

    /** One canvas node: type + ports + position in world (unzoomed) coordinates. */
    public static final class ENode {
        public String id, type;
        public final LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        public double[] value;
        public int index;
        public String mask;
        public int x, y;
        /** Editor-only caption shown instead of the type name (the shader ignores it). */
        public String label;
        /** Muted (M): passes its first input through; kept in mosaic.json so the game does the same. */
        public boolean muted;
        /** Inline values of unwired inputs (Blender-style sliders / numbers); a wire overrides its port's value. */
        public final LinkedHashMap<String, double[]> defaults = new LinkedHashMap<>();

        public ENode(String id, String type) { this.id = id; this.type = type; }
    }

    public final List<ENode> nodes = new ArrayList<>();

    /**
     * A user function as edited: kept as text so a definition with a typo survives load, undo and save unchanged
     * (the analyzer reports it; nothing is silently dropped).
     */
    public static final class FDef {
        public String name, signature, glsl, slang, label, category, help;

        public FDef copy() {
            FDef f = new FDef();
            f.name = name;
            f.signature = signature;
            f.glsl = glsl;
            f.slang = slang;
            f.label = label;
            f.category = category;
            f.help = help;
            return f;
        }

        public static FDef of(MosaicFunction fn) {
            FDef f = new FDef();
            f.name = fn.name();
            f.signature = fn.signature();
            f.glsl = fn.glsl();
            f.slang = fn.slang();
            f.label = fn.label();
            f.category = fn.category();
            f.help = fn.help();
            return f;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            if (label != null && !label.equals(name)) o.addProperty("label", label);
            if (category != null && !category.equals("My nodes")) o.addProperty("category", category);
            if (help != null && !help.isEmpty()) o.addProperty("help", help);
            if (signature != null) o.addProperty("signature", signature);
            o.addProperty("glsl", glsl == null ? "" : glsl);
            if (slang != null) o.addProperty("slang", slang);
            return o;
        }

        static FDef fromJson(JsonObject o) {
            FDef f = new FDef();
            f.name = string(o, "name", "");
            f.signature = o.has("signature") ? string(o, "signature", null) : null;
            f.glsl = string(o, "glsl", "");
            f.slang = o.has("slang") ? string(o, "slang", null) : null;
            f.label = o.has("label") ? string(o, "label", null) : null;
            f.category = o.has("category") ? string(o, "category", null) : null;
            f.help = o.has("help") ? string(o, "help", null) : null;
            if (f.signature == null) {
                try {
                    f.signature = MosaicFunction.fromJson(o).signature(); // legacy inputs/output form
                } catch (RuntimeException e) {
                    f.signature = "vec4 " + f.name + "(vec4 color)";
                }
            }
            return f;
        }

        /** The parsed function, or null while its signature or code has an error. */
        public MosaicFunction parsed() {
            try {
                return MosaicFunction.fromJson(toJson());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** An editor-only group box around nodes: a title, a color and a rectangle (the shader ignores it). */
    public static final class Frame {
        public String id, title;
        public int x, y, w, h, color;

        public Frame copy() {
            Frame f = new Frame();
            f.id = id;
            f.title = title;
            f.x = x;
            f.y = y;
            f.w = w;
            f.h = h;
            f.color = color;
            return f;
        }

        public boolean contains(int px, int py) { return px >= x && py >= y && px < x + w && py < y + h; }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("title", title == null ? "" : title);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("w", w);
            o.addProperty("h", h);
            o.addProperty("color", String.format("#%06X", color & 0xFFFFFF));
            return o;
        }

        static Frame fromJson(JsonObject o) {
            Frame f = new Frame();
            f.id = string(o, "id", "frame");
            f.title = string(o, "title", "");
            f.x = o.has("x") ? o.get("x").getAsInt() : 0;
            f.y = o.has("y") ? o.get("y").getAsInt() : 0;
            f.w = Math.max(60, o.has("w") ? o.get("w").getAsInt() : 200);
            f.h = Math.max(40, o.has("h") ? o.get("h").getAsInt() : 120);
            try {
                f.color = Integer.parseInt(string(o, "color", "#3A6EA5").replace("#", ""), 16);
            } catch (NumberFormatException e) {
                f.color = 0x3A6EA5;
            }
            return f;
        }
    }

    /** Group boxes of this graph (written as "frames"; editor-only). */
    public final List<Frame> frames = new ArrayList<>();

    public Frame frame(String id) {
        for (Frame f : frames) if (f.id.equals(id)) return f;
        return null;
    }

    /** A frame around nodes with some padding (room for the title bar on top). */
    public Frame frameAround(java.util.Collection<ENode> group, String title, int color, java.util.function.ToIntFunction<ENode> width,
                             java.util.function.ToIntFunction<ENode> height) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (ENode n : group) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + width.applyAsInt(n));
            maxY = Math.max(maxY, n.y + height.applyAsInt(n));
        }
        Frame f = new Frame();
        String id = "frame";
        for (int i = 2; frame(id) != null; i++) id = "frame" + i;
        f.id = id;
        f.title = title;
        f.color = color;
        f.x = minX - 20;
        f.y = minY - 44;
        f.w = maxX - minX + 40;
        f.h = maxY - minY + 64;
        frames.add(f);
        dirty = true;
        return f;
    }

    /** Nodes whose top-left corner lies inside the frame. */
    public List<ENode> nodesIn(Frame f) {
        List<ENode> out = new ArrayList<>();
        for (ENode n : nodes) if (f.contains(n.x, n.y)) out.add(n);
        return out;
    }

    /** User functions of this graph (written as "functions" in mosaic.json). */
    public final List<FDef> functions = new ArrayList<>();

    public FDef function(String name) {
        for (FDef f : functions) if (f.name.equals(name)) return f;
        return null;
    }

    /** The parsed functions by name (broken ones are left out), for port lookups. */
    public java.util.Map<String, MosaicFunction> functionMap() {
        java.util.Map<String, MosaicFunction> m = new LinkedHashMap<>();
        for (FDef f : functions) {
            MosaicFunction p = f.parsed();
            if (p != null) m.put(p.name(), p);
        }
        return m;
    }

    /** Adds a function definition unless one with that name exists; returns the definition in use. */
    public FDef ensureFunction(MosaicFunction fn) {
        FDef have = function(fn.name());
        if (have != null) return have;
        FDef f = FDef.of(fn);
        functions.add(f);
        dirty = true;
        return f;
    }

    /** A free function name based on {@code base} (base, base2, base3...). */
    public String freeFunctionName(String base) {
        String n = base;
        for (int i = 2; function(n) != null; i++) n = base + i;
        return n;
    }
    public Path file;
    public boolean dirty;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ------------------------------------------------------------------ load / save

    public static MosaicDocument load(Path json) throws java.io.IOException {
        MosaicDocument d = new MosaicDocument();
        d.file = json;
        d.dirty = false;
        try {
            String text = Files.readString(json, StandardCharsets.UTF_8);
            d.nodes.addAll(readNodes(text));
            d.functions.addAll(readFunctions(text));
            d.frames.addAll(readFrames(text));
        } catch (RuntimeException e) {
            throw new java.io.IOException("mosaic.json: " + e.getMessage());
        }
        return d;
    }

    /** Reads the editor frames of a mosaic.json text. */
    static List<Frame> readFrames(String json) {
        List<Frame> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("frames") || !root.getAsJsonObject().get("frames").isJsonArray())
            return out;
        for (JsonElement el : root.getAsJsonObject().getAsJsonArray("frames"))
            if (el.isJsonObject()) out.add(Frame.fromJson(el.getAsJsonObject()));
        return out;
    }

    /** Reads the function definitions of a mosaic.json text, broken ones included. */
    static List<FDef> readFunctions(String json) {
        List<FDef> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("functions") || !root.getAsJsonObject().get("functions").isJsonArray())
            return out;
        for (JsonElement el : root.getAsJsonObject().getAsJsonArray("functions"))
            if (el.isJsonObject()) out.add(FDef.fromJson(el.getAsJsonObject()));
        return out;
    }

    /** Reads the editable nodes of a mosaic.json text (lenient: the parser, not the loader, judges the graph). */
    static List<ENode> readNodes(String json) {
        List<ENode> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.getAsJsonObject().has("nodes")) return out;
        for (JsonElement el : root.getAsJsonObject().getAsJsonArray("nodes")) {
            JsonObject o = el.getAsJsonObject();
            ENode n = new ENode(string(o, "id", ""), string(o, "type", "mosaic.const"));
            if (o.has("value")) {
                JsonArray v = o.getAsJsonArray("value");
                double[] a = new double[v.size()];
                for (int i = 0; i < a.length; i++)
                    a[i] = v.get(i).isJsonPrimitive() && v.get(i).getAsJsonPrimitive().isNumber()
                        ? v.get(i).getAsDouble() : 0;
                n.value = a;
            }
            if (o.has("index")) n.index = o.get("index").getAsInt();
            if (o.has("size")) n.index = o.get("size").getAsInt();
            if (o.has("mask")) n.mask = o.get("mask").getAsString();
            if (o.has("label") && o.get("label").isJsonPrimitive()) n.label = o.get("label").getAsString();
            if (o.has("muted") && o.get("muted").isJsonPrimitive()) n.muted = o.get("muted").getAsBoolean();
            if (o.has("defaults") && o.get("defaults").isJsonObject())
                for (var e : o.getAsJsonObject("defaults").entrySet()) {
                    if (!e.getValue().isJsonArray()) continue;
                    JsonArray a = e.getValue().getAsJsonArray();
                    double[] v = new double[a.size()];
                    for (int i = 0; i < v.length; i++) v[i] = a.get(i).isJsonPrimitive() ? a.get(i).getAsDouble() : 0;
                    n.defaults.put(e.getKey(), v);
                }
            if (o.has("x")) n.x = Math.round(o.get("x").getAsFloat());
            if (o.has("y")) n.y = Math.round(o.get("y").getAsFloat());
            if (o.has("inputs")) {
                for (var e : o.getAsJsonObject("inputs").entrySet())
                    if (e.getValue().isJsonPrimitive()) n.inputs.put(e.getKey(), e.getValue().getAsString());
            }
            out.add(n);
        }
        return out;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        if (!functions.isEmpty()) {
            JsonArray fns = new JsonArray();
            for (FDef f : functions) fns.add(f.toJson());
            root.add("functions", fns);
        }
        JsonArray arr = new JsonArray();
        for (ENode n : nodes) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id);
            o.addProperty("type", n.type);
            if (n.value != null) {
                JsonArray v = new JsonArray();
                for (double d : n.value) v.add(d);
                o.add("value", v);
            }
            if (n.type.equals("mosaic.vec")) o.addProperty("size", n.index == 0 ? 3 : n.index);
            else if (n.index != 0) o.addProperty("index", n.index);
            if (n.mask != null) o.addProperty("mask", n.mask);
            if (!n.inputs.isEmpty()) {
                JsonObject in = new JsonObject();
                for (var e : n.inputs.entrySet()) in.addProperty(e.getKey(), e.getValue());
                o.add("inputs", in);
            }
            if (n.label != null && !n.label.isBlank()) o.addProperty("label", n.label);
            if (n.muted) o.addProperty("muted", true);
            if (!n.defaults.isEmpty()) {
                JsonObject defs = new JsonObject();
                for (var e : n.defaults.entrySet()) {
                    JsonArray a = new JsonArray();
                    for (double v : e.getValue()) a.add(v);
                    defs.add(e.getKey(), a);
                }
                o.add("defaults", defs);
            }
            o.addProperty("x", n.x);
            o.addProperty("y", n.y);
            arr.add(o);
        }
        root.add("nodes", arr);
        if (!frames.isEmpty()) {
            JsonArray fr = new JsonArray();
            for (Frame f : frames) fr.add(f.toJson());
            root.add("frames", fr);
        }
        return GSON.toJson(root);
    }

    public void save() throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        dirty = false;
    }

    // ------------------------------------------------------------------ mutators

    /** Adds a node of the type with a unique short id (base1, base2, ...). */
    public ENode add(String type, int x, int y) {
        String base = type.substring(type.lastIndexOf('.') + 1);
        String id = base;
        for (int i = 1; takenId(id); i++) id = base + i;
        ENode n = new ENode(id, type);
        n.x = x;
        n.y = y;
        nodes.add(n);
        return n;
    }

    /** Removes a node and every reference to it. */
    public void remove(ENode n) {
        nodes.remove(n);
        for (ENode o : nodes) {
            var it = o.inputs.entrySet().iterator();
            while (it.hasNext()) {
                String[] ref = MosaicGraph.split(it.next().getValue());
                if (ref[0].equals(n.id)) it.remove();
            }
        }
    }

    /** Copies a node (type, parameters and incoming wires) under a fresh id, offset so both stay visible. */
    public ENode duplicate(ENode src) {
        ENode copy = add(src.type, src.x + 20, src.y + 20);
        copy.inputs.putAll(src.inputs);
        copy.value = src.value == null ? null : src.value.clone();
        copy.index = src.index;
        copy.mask = src.mask;
        dirty = true;
        return copy;
    }

    // ------------------------------------------------------------------ groups and fragments

    /** Detached deep copies (ids, parameters, wires and positions kept) for a clipboard or a duplicate. */
    public static List<ENode> copyOf(java.util.Collection<ENode> src) {
        List<ENode> out = new ArrayList<>();
        for (ENode n : src) {
            ENode c = new ENode(n.id, n.type);
            c.inputs.putAll(n.inputs);
            c.value = n.value == null ? null : n.value.clone();
            c.index = n.index;
            c.mask = n.mask;
            c.label = n.label;
            c.muted = n.muted;
            for (var e : n.defaults.entrySet()) c.defaults.put(e.getKey(), e.getValue().clone());
            c.x = n.x;
            c.y = n.y;
            out.add(c);
        }
        return out;
    }

    /**
     * Inserts copies of a node group shifted by (dx, dy) under fresh ids. Wires between group members follow the
     * copies; wires from outside the group are kept when {@code keepExternal} is set and their source still exists
     * (duplicate), else dropped (paste from elsewhere). Returns the inserted nodes in group order.
     */
    public List<ENode> insertCopies(List<ENode> group, int dx, int dy, boolean keepExternal) {
        java.util.Map<String, String> rename = new LinkedHashMap<>();
        List<ENode> added = new ArrayList<>();
        for (ENode src : group) {
            String base = src.id.replaceAll("\\d+$", "");
            if (base.isEmpty() || !base.matches("\\w+")) base = src.type.substring(src.type.lastIndexOf('.') + 1);
            String id = base;
            for (int i = 1; takenId(id) || rename.containsValue(id); i++) id = base + i;
            rename.put(src.id, id);
            ENode n = new ENode(id, src.type);
            n.value = src.value == null ? null : src.value.clone();
            n.index = src.index;
            n.mask = src.mask;
            n.label = src.label;
            for (var e : src.defaults.entrySet()) n.defaults.put(e.getKey(), e.getValue().clone());
            n.x = src.x + dx;
            n.y = src.y + dy;
            added.add(n);
        }
        for (int i = 0; i < group.size(); i++) {
            for (var e : group.get(i).inputs.entrySet()) {
                String[] ref = MosaicGraph.split(e.getValue());
                String mapped = rename.get(ref[0]);
                if (mapped != null) added.get(i).inputs.put(e.getKey(), ref[1].isEmpty() ? mapped : mapped + "." + ref[1]);
                else if (keepExternal && byId(ref[0]) != null) added.get(i).inputs.put(e.getKey(), e.getValue());
            }
        }
        nodes.addAll(added);
        if (!added.isEmpty()) dirty = true;
        return added;
    }

    /** Adds the function definitions carried by pasted text that this graph does not have yet. */
    public void mergeFunctions(String text) {
        try {
            for (FDef f : readFunctions(text)) if (function(f.name) == null) functions.add(f);
        } catch (RuntimeException ignored) {
            // not JSON: nothing to merge
        }
    }

    /** Removes a group and every wire into it. */
    public void removeAll(java.util.Collection<ENode> group) {
        for (ENode n : new ArrayList<>(group)) remove(n);
    }

    /** Clipboard text of a node group: a mosaic.json-shaped document marked as a fragment. */
    public static String fragmentJson(List<ENode> group) { return fragmentJson(group, List.of()); }

    /** A fragment that also carries the definitions of the user functions its nodes use. */
    public static String fragmentJson(List<ENode> group, List<FDef> available) {
        MosaicDocument d = new MosaicDocument();
        d.nodes.addAll(copyOf(group));
        for (ENode n : group)
            if (MosaicFunction.isFunctionType(n.type))
                for (FDef f : available)
                    if (f.name.equals(MosaicFunction.nameOf(n.type)) && d.function(f.name) == null) d.functions.add(f.copy());
        JsonObject root = JsonParser.parseString(d.toJson()).getAsJsonObject();
        root.addProperty("mosaicFragment", 1);
        return GSON.toJson(root);
    }

    /** Nodes of clipboard text (a fragment or a whole mosaic.json), or null when the text is neither. */
    public static List<ENode> readFragment(String text) {
        if (text == null || text.length() > 1_000_000) return null;
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("nodes")) return null;
            List<ENode> nodes = readNodes(text);
            for (ENode n : nodes)
                if ((!MosaicGraph.isKnown(n.type) && !MosaicFunction.isFunctionType(n.type)) || !n.id.matches("\\w+")) return null;
            return nodes.isEmpty() ? null : nodes;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ history

    private static final int HISTORY_LIMIT = 100;
    private final ArrayDeque<String> undoStack = new ArrayDeque<>();
    private final ArrayDeque<String> redoStack = new ArrayDeque<>();
    private final ArrayDeque<String> undoLabels = new ArrayDeque<>();
    private final ArrayDeque<String> redoLabels = new ArrayDeque<>();

    /** The current state as an opaque history token (the serialized graph, positions included). */
    public String snapshot() { return toJson(); }

    /**
     * Records {@code before} as one undo step when the document changed since it was taken. Editors take a snapshot
     * before a gesture (wire, move, delete, parameter edit) and commit it once the gesture is done, so a whole drag is
     * one step and a refused edit (already rolled back) records nothing.
     */
    public boolean commit(String before) { return commit(before, "Edit"); }

    /** Records one undo step with a readable name (shown in the editor's History). */
    public boolean commit(String before, String label) {
        if (before == null || before.equals(toJson())) return false;
        undoStack.push(before);
        undoLabels.push(label == null ? "Edit" : label);
        while (undoStack.size() > HISTORY_LIMIT) {
            undoStack.removeLast();
            undoLabels.removeLast();
        }
        redoStack.clear();
        redoLabels.clear();
        dirty = true;
        return true;
    }

    /** Names of the steps that can be undone, oldest first (the last one is the most recent edit). */
    public List<String> undoSteps() {
        List<String> out = new ArrayList<>(undoLabels);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Names of the steps that can be redone, next first. */
    public List<String> redoSteps() { return new ArrayList<>(redoLabels); }

    /** The undo/redo stacks (states and names), oldest undo first, for saving the history with a project. */
    /** Forgets undo / redo (another document was opened in place of this one). */
    public void resetHistory() {
        undoStack.clear();
        redoStack.clear();
        undoLabels.clear();
        redoLabels.clear();
    }

    public List<List<String>> historyState() {
        List<String> u = new ArrayList<>(undoStack), ul = new ArrayList<>(undoLabels), r = new ArrayList<>(redoStack), rl = new ArrayList<>(redoLabels);
        java.util.Collections.reverse(u);
        java.util.Collections.reverse(ul);
        return List.of(u, ul, r, rl);
    }

    /** Restores stacks saved by {@link #historyState()} (sizes must match; otherwise nothing changes). */
    public void restoreHistory(List<String> undo, List<String> undoNames, List<String> redo, List<String> redoNames) {
        if (undo.size() != undoNames.size() || redo.size() != redoNames.size()) return;
        undoStack.clear();
        undoLabels.clear();
        redoStack.clear();
        redoLabels.clear();
        for (int i = 0; i < undo.size(); i++) {
            undoStack.push(undo.get(i));
            undoLabels.push(undoNames.get(i));
        }
        for (int i = redo.size() - 1; i >= 0; i--) {
            redoStack.push(redo.get(i));
            redoLabels.push(redoNames.get(i));
        }
    }

    /** Undoes or redoes until {@code undoSteps().size() == target}. */
    public void jumpTo(int target) {
        while (undoSteps().size() > target && undo()) { }
        while (undoSteps().size() < target && redo()) { }
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** Restores the previous step; node objects are replaced, so editors must drop references they hold. */
    public boolean undo() {
        if (!step(undoStack, redoStack)) return false;
        redoLabels.push(undoLabels.pop());
        return true;
    }

    public boolean redo() {
        if (!step(redoStack, undoStack)) return false;
        undoLabels.push(redoLabels.pop());
        return true;
    }

    private boolean step(ArrayDeque<String> from, ArrayDeque<String> to) {
        if (from.isEmpty()) return false;
        to.push(toJson());
        String state = from.pop();
        List<ENode> restored = readNodes(state);
        nodes.clear();
        nodes.addAll(restored);
        functions.clear();
        functions.addAll(readFunctions(state));
        frames.clear();
        frames.addAll(readFrames(state));
        dirty = true;
        return true;
    }

    private boolean takenId(String id) {
        for (ENode n : nodes) if (n.id.equals(id)) return true;
        return false;
    }

    public ENode byId(String id) {
        for (ENode n : nodes) if (n.id.equals(id)) return n;
        return null;
    }

    /** Validates the document through the real parser; returns the error or null. */
    public String validate() {
        String m = MosaicGraph.analyze(toJson()).firstError();
        if (m == null) return null;
        return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }

    /** Every problem of the current state (errors, warnings, hints). */
    public MosaicGraph.Analysis analyze() { return MosaicGraph.analyze(toJson()); }

    private static String string(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }
}
