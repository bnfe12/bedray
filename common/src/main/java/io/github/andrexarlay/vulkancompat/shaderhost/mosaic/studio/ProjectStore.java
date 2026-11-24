package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicDocument;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicShaderCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Per-project metadata and cache next to the graph, in {@code <pack>/shaders/bedray/.studio/}: the named edit history
 * (so undo survives a restart), the view and selection, and a compile cache keyed by the generated source's hash (the
 * result and the SPIR-V), so reopening a large project and recompiling an unchanged shader are instant.
 * Unsaved graphs use {@code ~/.bedray/studio-cache/untitled}.
 */
final class ProjectStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final int HISTORY_KEEP = 100;

    private ProjectStore() {}

    static Path dir(MosaicDocument doc) {
        return doc.file != null ? doc.file.getParent().resolve(".studio")
            : Path.of(System.getProperty("user.home"), ".bedray", "studio-cache", "untitled");
    }

    // ---------------------------------------------------------------- project meta

    static void save(StudioModel m, GraphPanel g) {
        try {
            Path d = dir(m.doc);
            Files.createDirectories(d);
            JsonObject o = new JsonObject();
            o.addProperty("graph", m.doc.snapshot());
            List<List<String>> h = m.doc.historyState();
            int keep = Math.max(0, h.get(0).size() - HISTORY_KEEP);
            o.add("undo", array(h.get(0).subList(keep, h.get(0).size())));
            o.add("undoNames", array(h.get(1).subList(keep, h.get(1).size())));
            o.add("redo", array(h.get(2)));
            o.add("redoNames", array(h.get(3)));
            JsonArray sel = new JsonArray();
            for (MosaicDocument.ENode n : m.ordered()) sel.add(n.id);
            o.add("selection", sel);
            if (g != null) {
                o.addProperty("panX", g.panX);
                o.addProperty("panY", g.panY);
                o.addProperty("zoom", g.zoom);
            }
            Files.writeString(d.resolve("project.json"), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // metadata is a convenience; never block editing
        }
    }

    /**
     * Restores history, selection and view when the saved project matches the graph on disk (the same document
     * state); a graph edited elsewhere keeps only the view.
     */
    static void load(StudioModel m, GraphPanel g) {
        try {
            Path f = dir(m.doc).resolve("project.json");
            if (!Files.exists(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("graph") && sameGraph(o.get("graph").getAsString(), m.doc.snapshot())) {
                m.doc.restoreHistory(strings(o, "undo"), strings(o, "undoNames"), strings(o, "redo"), strings(o, "redoNames"));
                for (String id : strings(o, "selection")) {
                    MosaicDocument.ENode n = m.doc.byId(id);
                    if (n != null) m.selection.add(n);
                }
                m.primary = m.selection.isEmpty() ? null : m.selection.iterator().next();
            }
            if (g != null && o.has("zoom")) {
                g.panX = o.get("panX").getAsDouble();
                g.panY = o.get("panY").getAsDouble();
                g.zoom = o.get("zoom").getAsDouble();
                g.markFitted();
            }
            m.changed();
        } catch (Exception ignored) {
            // a damaged meta file is ignored
        }
    }

    private static boolean sameGraph(String a, String b) {
        return JsonParser.parseString(a).equals(JsonParser.parseString(b));
    }

    // ---------------------------------------------------------------- compile cache

    static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    /** A cached compile result for this exact source, or null. */
    static MosaicShaderCheck.Result cached(MosaicDocument doc, String source) {
        try {
            Path c = dir(doc).resolve("cache").resolve(hash(source) + ".json");
            if (!Files.exists(c)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(c, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.get("ok").getAsBoolean()) return null; // errors are recompiled so the mapping stays fresh
            Path spv = c.resolveSibling(hash(source) + ".spv");
            byte[] bytes = Files.exists(spv) ? Files.readAllBytes(spv) : null;
            return new MosaicShaderCheck.Result(true, o.get("bytes").getAsInt(), 0, List.of(), "", bytes);
        } catch (Exception e) {
            return null;
        }
    }

    static void store(MosaicDocument doc, String source, MosaicShaderCheck.Result r) {
        if (!r.ok()) return;
        try {
            Path d = dir(doc).resolve("cache");
            Files.createDirectories(d);
            String h = hash(source);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("bytes", r.spirvBytes());
            o.addProperty("micros", r.micros());
            Files.writeString(d.resolve(h + ".json"), o.toString());
            if (r.spirv() != null) Files.write(d.resolve(h + ".spv"), r.spirv());
            trim(d, 400);
        } catch (Exception ignored) {
            // cache is optional
        }
    }

    /** Keeps the cache folder bounded (oldest files go first). */
    private static void trim(Path d, int max) throws java.io.IOException {
        try (var s = Files.list(d)) {
            List<Path> files = new ArrayList<>(s.toList());
            if (files.size() <= max) return;
            files.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                } catch (Exception e) {
                    return 0;
                }
            });
            for (int i = 0; i < files.size() - max; i++) Files.deleteIfExists(files.get(i));
        }
    }

    private static JsonArray array(List<String> v) {
        JsonArray a = new JsonArray();
        v.forEach(a::add);
        return a;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key)) for (var e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return out;
    }
}
