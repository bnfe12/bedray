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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mosaic editor's own settings (not part of any pack): shortcuts, grid and wire look, panel and Live defaults,
 * palette favorites, recently used nodes and collapsed palette groups. Stored as
 * {@code config/bedray/mosaic-editor.json}; a missing or damaged file falls back to defaults field by field.
 */
public final class MosaicPrefs {

    public static final int RECENT_MAX = 8;

    public final MosaicKeymap keys = new MosaicKeymap();
    public boolean snap = false;
    public int grid = 16;              // 8..64
    public boolean portLabels = true;
    public boolean leftDragSelects = true; // left-drag on empty canvas: box select (true) or pan (false)
    public int wireThickness = 2;      // 1..3
    public boolean liveByDefault = true;
    public boolean previewByDefault = false;
    public boolean showPalette = true, showInspector = true;
    public final Set<String> collapsed = new LinkedHashSet<>();   // palette group ids
    public final List<String> favorites = new ArrayList<>();      // node types
    public final List<String> recent = new ArrayList<>();         // node types, newest first

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Path defaultFile(Path gameDir) {
        return gameDir.resolve("config").resolve("bedray").resolve("mosaic-editor.json");
    }

    /** Back to the default look and behaviour; shortcuts, favorites and recents stay. */
    public void resetLook() {
        snap = false;
        grid = 16;
        portLabels = true;
        leftDragSelects = true;
        wireThickness = 2;
        liveByDefault = true;
        previewByDefault = false;
    }

    /** Puts a type at the front of the recent list (deduplicated, capped). */
    public void used(String type) {
        recent.remove(type);
        recent.add(0, type);
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
    }

    /** Adds or removes a favorite; returns true when it is a favorite afterwards. */
    public boolean toggleFavorite(String type) {
        if (favorites.remove(type)) return false;
        favorites.add(type);
        return true;
    }

    public int snapped(double v) { return (int) (Math.round(v / grid) * grid); }

    // ---------------------------------------------------------------- file

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("snap", snap);
        o.addProperty("grid", grid);
        o.addProperty("portLabels", portLabels);
        o.addProperty("leftDragSelects", leftDragSelects);
        o.addProperty("wireThickness", wireThickness);
        o.addProperty("liveByDefault", liveByDefault);
        o.addProperty("previewByDefault", previewByDefault);
        o.addProperty("showPalette", showPalette);
        o.addProperty("showInspector", showInspector);
        o.add("collapsed", array(collapsed));
        o.add("favorites", array(favorites));
        o.add("recent", array(recent));
        JsonObject k = new JsonObject();
        for (Map.Entry<String, List<String>> e : keys.toText().entrySet()) k.add(e.getKey(), array(e.getValue()));
        o.add("keys", k);
        return GSON.toJson(o);
    }

    public static MosaicPrefs fromJson(String json) {
        MosaicPrefs p = new MosaicPrefs();
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return p;
        }
        p.snap = bool(o, "snap", p.snap);
        p.grid = Math.clamp(integer(o, "grid", p.grid), 8, 64);
        p.portLabels = bool(o, "portLabels", p.portLabels);
        p.leftDragSelects = bool(o, "leftDragSelects", p.leftDragSelects);
        p.wireThickness = Math.clamp(integer(o, "wireThickness", p.wireThickness), 1, 3);
        p.liveByDefault = bool(o, "liveByDefault", p.liveByDefault);
        p.previewByDefault = bool(o, "previewByDefault", p.previewByDefault);
        p.showPalette = bool(o, "showPalette", p.showPalette);
        p.showInspector = bool(o, "showInspector", p.showInspector);
        p.collapsed.addAll(strings(o, "collapsed"));
        for (String t : strings(o, "favorites")) if (MosaicGraph.isKnown(t) && !p.favorites.contains(t)) p.favorites.add(t);
        for (String t : strings(o, "recent")) if (MosaicGraph.isKnown(t) && !p.recent.contains(t)) p.recent.add(t);
        while (p.recent.size() > RECENT_MAX) p.recent.remove(p.recent.size() - 1);
        if (o.has("keys") && o.get("keys").isJsonObject()) {
            JsonObject keys = o.getAsJsonObject("keys");
            Map<String, List<String>> text = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : keys.entrySet())
                if (e.getValue().isJsonArray()) text.put(e.getKey(), strings(keys, e.getKey()));
            p.keys.fromText(text);
        }
        return p;
    }

    public static MosaicPrefs load(Path file) {
        try {
            return Files.isRegularFile(file) ? fromJson(Files.readString(file, StandardCharsets.UTF_8)) : new MosaicPrefs();
        } catch (Exception e) {
            return new MosaicPrefs();
        }
    }

    public void save(Path file) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    private static JsonArray array(Iterable<String> values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int integer(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray(key))
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) out.add(e.getAsString());
        return out;
    }
}
