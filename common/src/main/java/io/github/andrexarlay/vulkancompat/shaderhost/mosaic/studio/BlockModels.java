package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft block models (assets/<ns>/models/block/*.json): finds a model that uses a texture, resolves parents and
 * texture variables, and turns elements into textured quads (with element rotation, face UV and UV rotation) for the
 * studio's software preview and the UV layout view.
 */
final class BlockModels {

    private BlockModels() {}

    /** A quad: corners p[0..3] in block units (0..16) matching uv (u1,v1),(u2,v1),(u2,v2),(u1,v2); texture id; face name. */
    record Quad(double[][] p, double u1, double v1, double u2, double v2, int uvRotation, String texture, String face, double[][] uv4) {
        Quad(double[][] p, double u1, double v1, double u2, double v2, int uvRotation, String texture, String face) {
            this(p, u1, v1, u2, v2, uvRotation, texture, face, null);
        }
    }

    record Model(String name, List<Quad> quads) {}

    private static final Map<String, Model> CACHE = new ConcurrentHashMap<>();

    /** The model that shows this texture (block/<name> first, else any model that uses it), or null. */
    static Model forTexture(StudioLibrary.Source src, String textureEntry) {
        String id = StudioLibrary.id(textureEntry); // minecraft:block/stone
        return CACHE.computeIfAbsent(src.id() + "|" + id, k -> find(src, id));
    }

    private static Model find(StudioLibrary.Source src, String textureId) {
        String ns = textureId.substring(0, textureId.indexOf(':')), path = textureId.substring(textureId.indexOf(':') + 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        List<String> tries = new ArrayList<>(List.of("block/" + name, "block/" + name.replaceAll("_(top|side|bottom|front|end)$", "")));
        for (String t : tries) {
            Model m = load(src, ns + ":" + t, textureId);
            if (m != null && !m.quads().isEmpty()) return m;
        }
        return null;
    }

    static Model load(StudioLibrary.Source src, String modelId, String mustUse) {
        try {
            Map<String, String> textures = new LinkedHashMap<>();
            JsonArray elements = null;
            String id = modelId;
            for (int depth = 0; depth < 12 && id != null; depth++) {
                String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
                String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (p.startsWith("builtin/")) break;
                JsonObject o;
                try {
                    o = JsonParser.parseString(new String(read(src, "assets/" + ns + "/models/" + p + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception e) {
                    break;
                }
                if (o.has("textures")) for (var e : o.getAsJsonObject("textures").entrySet()) textures.putIfAbsent(e.getKey(), e.getValue().getAsString());
                if (elements == null && o.has("elements")) elements = o.getAsJsonArray("elements");
                id = o.has("parent") ? o.get("parent").getAsString() : null;
            }
            if (elements == null) return null;
            List<Quad> quads = new ArrayList<>();
            for (JsonElement el : elements) {
                JsonObject e = el.getAsJsonObject();
                double[] f = vec(e.getAsJsonArray("from")), t = vec(e.getAsJsonArray("to"));
                JsonObject rot = e.has("rotation") ? e.getAsJsonObject("rotation") : null;
                for (var fe : e.getAsJsonObject("faces").entrySet()) {
                    JsonObject face = fe.getValue().getAsJsonObject();
                    String tex = resolve(face.get("texture").getAsString(), textures);
                    if (tex == null) continue;
                    double[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : defaultUv(fe.getKey(), f, t);
                    double[][] p = corners(fe.getKey(), f, t);
                    if (p == null) continue;
                    if (rot != null) rotate(p, rot);
                    quads.add(new Quad(p, uv[0], uv[1], uv[2], uv[3], face.has("rotation") ? face.get("rotation").getAsInt() : 0,
                        tex.contains(":") ? tex : "minecraft:" + tex, fe.getKey()));
                }
            }
            if (mustUse != null && quads.stream().noneMatch(q -> q.texture().equals(mustUse))) return null;
            return new Model(modelId, quads);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(StudioLibrary.Source src, String entry) throws java.io.IOException {
        try {
            return StudioLibrary.read(src, entry);
        } catch (java.io.IOException e) {
            // models of a resource pack or mod often rely on vanilla parents: fall back to the newest vanilla jar
            for (StudioLibrary.Source v : StudioLibrary.vanillaJars()) {
                try {
                    return StudioLibrary.read(v, entry);
                } catch (java.io.IOException ignored) {
                    // next jar
                }
            }
            throw e;
        }
    }

    private static String resolve(String ref, Map<String, String> textures) {
        for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) ref = textures.get(ref.substring(1));
        return ref;
    }

    private static double[] vec(JsonArray a) { return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }

    private static double[] vec4(JsonArray a) {
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()};
    }

    static double[] defaultUv(String face, double[] f, double[] t) {
        return switch (face) {
            case "up" -> new double[] {f[0], f[2], t[0], t[2]};
            case "down" -> new double[] {f[0], 16 - t[2], t[0], 16 - f[2]};
            case "north" -> new double[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new double[] {f[0], 16 - t[1], t[0], 16 - f[1]};
            case "west" -> new double[] {f[2], 16 - t[1], t[2], 16 - f[1]};
            default -> new double[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]}; // east
        };
    }

    static double[][] corners(String face, double[] f, double[] t) {
        double x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        return switch (face) {
            case "up" -> new double[][] {{x1, y2, z1}, {x2, y2, z1}, {x2, y2, z2}, {x1, y2, z2}};
            case "down" -> new double[][] {{x1, y1, z2}, {x2, y1, z2}, {x2, y1, z1}, {x1, y1, z1}};
            case "north" -> new double[][] {{x2, y2, z1}, {x1, y2, z1}, {x1, y1, z1}, {x2, y1, z1}};
            case "south" -> new double[][] {{x1, y2, z2}, {x2, y2, z2}, {x2, y1, z2}, {x1, y1, z2}};
            case "west" -> new double[][] {{x1, y2, z1}, {x1, y2, z2}, {x1, y1, z2}, {x1, y1, z1}};
            case "east" -> new double[][] {{x2, y2, z2}, {x2, y2, z1}, {x2, y1, z1}, {x2, y1, z2}};
            default -> null;
        };
    }

    private static void rotate(double[][] p, JsonObject rot) {
        double[] o = vec(rot.getAsJsonArray("origin"));
        double a = Math.toRadians(rot.get("angle").getAsDouble());
        String axis = rot.get("axis").getAsString();
        double c = Math.cos(a), s = Math.sin(a);
        for (double[] q : p) {
            double x = q[0] - o[0], y = q[1] - o[1], z = q[2] - o[2];
            switch (axis) {
                case "x" -> { q[1] = o[1] + y * c - z * s; q[2] = o[2] + y * s + z * c; }
                case "y" -> { q[0] = o[0] + x * c + z * s; q[2] = o[2] - x * s + z * c; }
                default -> { q[0] = o[0] + x * c - y * s; q[1] = o[1] + x * s + y * c; }
            }
        }
    }

    /** Texture coordinate (0..16) at parameters s (along p0->p1) and t (along p0->p3), honouring the face's UV rotation. */
    static double[] uvAt(Quad q, double s, double t) {
        if (q.uv4() != null) { // entity polygons: per-corner UV in 0..1
            double[][] u = q.uv4();
            return new double[] {(u[0][0] + (u[1][0] - u[0][0]) * s + (u[3][0] - u[0][0]) * t) * 16,
                (u[0][1] + (u[1][1] - u[0][1]) * s + (u[3][1] - u[0][1]) * t) * 16};
        }
        double a = s, b = t;
        switch (((q.uvRotation() % 360) + 360) % 360) {
            case 90 -> { a = t; b = 1 - s; }
            case 180 -> { a = 1 - s; b = 1 - t; }
            case 270 -> { a = 1 - t; b = s; }
            default -> { }
        }
        return new double[] {q.u1() + (q.u2() - q.u1()) * a, q.v1() + (q.v2() - q.v1()) * b};
    }

    private static final Map<String, Map<String, List<String>>> INDEX = new ConcurrentHashMap<>();

    /** Block models (ids) of a source whose own "textures" mention this texture: cube, fence post, stairs, slab... */
    static List<String> modelsUsing(StudioLibrary.Source src, String textureEntry) {
        Map<String, List<String>> index = INDEX.computeIfAbsent(src.id(), k -> buildIndex(src));
        String id = StudioLibrary.id(textureEntry);
        List<String> direct = index.getOrDefault(id, List.of());
        List<String> out = new ArrayList<>(direct);
        String name = id.substring(id.lastIndexOf('/') + 1), stem = name.replaceAll("_(planks|log|top|side|bottom)$", "");
        out.sort(java.util.Comparator.comparingInt((String m) -> {
            String n = m.substring(m.lastIndexOf('/') + 1);
            return n.equals(name) ? 0 : n.startsWith(stem) ? 1 : 2;
        }).thenComparingInt(String::length));
        return out;
    }

    private static Map<String, List<String>> buildIndex(StudioLibrary.Source src) {
        Map<String, List<String>> index = new HashMap<>();
        java.util.regex.Pattern entry = java.util.regex.Pattern.compile("^assets/([a-z0-9_.-]+)/models/(block/.+)\\.json$");
        try {
            List<String> names = new ArrayList<>();
            if (src.zip()) try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(src.path().toFile())) {
                z.stream().map(java.util.zip.ZipEntry::getName).filter(n -> entry.matcher(n).matches()).forEach(names::add);
                for (String n : names) {
                    var m = entry.matcher(n);
                    if (!m.matches()) continue;
                    String text = new String(z.getInputStream(z.getEntry(n)).readAllBytes(), StandardCharsets.UTF_8);
                    index(index, m.group(1) + ":" + m.group(2), text);
                }
            }
        } catch (Exception ignored) {
            // an unreadable jar has no models
        }
        return index;
    }

    private static void index(Map<String, List<String>> index, String modelId, String text) {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (!o.has("textures")) return;
            for (var e : o.getAsJsonObject("textures").entrySet()) {
                String t = e.getValue().getAsString();
                if (t.startsWith("#")) continue;
                if (!t.contains(":")) t = "minecraft:" + t;
                List<String> l = index.computeIfAbsent(t, k -> new ArrayList<>());
                if (!l.contains(modelId)) l.add(modelId);
            }
        } catch (Exception ignored) {
            // not a model
        }
    }

    // ------------------------------------------------------------------ entity models (exported by the game)

    private static Path entityFile;
    private static long entityStamp = -1;
    private static Map<String, double[]> entityLayers = Map.of();

    /** The entity model export of the game folder: layer id -> flat x,y,z,u,v floats (4 per quad). */
    static synchronized Map<String, double[]> entityLayers(Path gameDir) {
        if (gameDir == null) return Map.of();
        Path f = gameDir.resolve("bedray-studio/models/entities.json");
        try {
            if (!java.nio.file.Files.exists(f)) return Map.of();
            long m = java.nio.file.Files.getLastModifiedTime(f).toMillis();
            if (f.equals(entityFile) && m == entityStamp) return entityLayers;
            Map<String, double[]> out = new java.util.TreeMap<>();
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("layers")) {
                JsonObject o = el.getAsJsonObject();
                JsonArray q = o.getAsJsonArray("q");
                double[] v = new double[q.size()];
                for (int i = 0; i < v.length; i++) v[i] = q.get(i).getAsDouble();
                out.put(o.get("id").getAsString(), v);
            }
            entityFile = f;
            entityStamp = m;
            entityLayers = out;
        } catch (Exception e) {
            return Map.of();
        }
        return entityLayers;
    }

    /** Entity layers that probably use this texture (entity/cow/cow.png -> minecraft:cow#main), best first. */
    static List<String> entityModelsFor(Path gameDir, String textureEntry) {
        Map<String, double[]> layers = entityLayers(gameDir);
        String id = StudioLibrary.id(textureEntry);
        String path = id.substring(id.indexOf(':') + 1).replace("entity/", "");
        String[] words = path.split("[/_]");
        String last = path.substring(path.lastIndexOf('/') + 1);
        List<String> out = new ArrayList<>();
        for (String l : layers.keySet()) {
            String model = l.contains(":") ? l.substring(l.indexOf(':') + 1) : l;
            model = model.contains("#") ? model.substring(0, model.indexOf('#')) : model;
            boolean hit = model.equals(last) || model.equals(path.replace('/', '_'));
            for (String w : words) if (w.length() > 2 && model.contains(w)) hit = true;
            if (hit) out.add("entity:" + l);
        }
        // best first: the layer whose name shares the most words with the texture (cow_cold -> cold_cow), main layers, short names
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String w : last.split("_")) if (w.length() > 2) want.add(w);
        out.sort(java.util.Comparator.comparingInt((String l) -> {
            String m = l.substring(l.indexOf(':', 7) + 1);
            m = m.contains("#") ? m.substring(0, m.indexOf('#')) : m;
            int shared = 0;
            for (String w : m.split("_")) if (want.contains(w)) shared++;
            int extra = m.split("_").length - shared;
            return m.equals(last) ? -100 : -shared * 10 + extra * 3;
        }).thenComparing(l -> !l.endsWith("#main")).thenComparingInt(String::length));
        return out;
    }

    /** An exported entity layer as a model fitted into the 0..16 preview box, every quad textured with {@code textureId}. */
    static Model entity(Path gameDir, String layerId, String textureId) {
        double[] v = entityLayers(gameDir).get(layerId);
        if (v == null) return null;
        double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
        for (int i = 0; i + 4 < v.length; i += 5) {
            minX = Math.min(minX, v[i]); maxX = Math.max(maxX, v[i]);
            minY = Math.min(minY, -v[i + 1]); maxY = Math.max(maxY, -v[i + 1]);
            minZ = Math.min(minZ, v[i + 2]); maxZ = Math.max(maxZ, v[i + 2]);
        }
        double size = Math.max(1e-6, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ))), k = 16 / size;
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        List<Quad> quads = new ArrayList<>();
        for (int i = 0; i + 19 < v.length; i += 20) {
            double[][] p = new double[4][], uv = new double[4][];
            for (int c = 0; c < 4; c++) {
                int o = i + c * 5;
                p[c] = new double[] {8 + (v[o] - cx) * k, 8 + (-v[o + 1] - cy) * k, 8 + (v[o + 2] - cz) * k};
                uv[c] = new double[] {v[o + 3], v[o + 4]};
            }
            quads.add(new Quad(p, 0, 0, 16, 16, 0, textureId, "part", uv));
        }
        return new Model(layerId, quads);
    }

    static final Map<String, StudioLibrary.Source> NONE = new HashMap<>();
}
