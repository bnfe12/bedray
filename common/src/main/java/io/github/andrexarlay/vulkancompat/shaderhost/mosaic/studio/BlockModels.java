package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
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
    record Quad(double[][] p, double u1, double v1, double u2, double v2, int uvRotation, String texture, String face) {}

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
        double a = s, b = t;
        switch (((q.uvRotation() % 360) + 360) % 360) {
            case 90 -> { a = t; b = 1 - s; }
            case 180 -> { a = 1 - s; b = 1 - t; }
            case 270 -> { a = 1 - t; b = s; }
            default -> { }
        }
        return new double[] {q.u1() + (q.u2() - q.u1()) * a, q.v1() + (q.v2() - q.v1()) * b};
    }

    static final Map<String, StudioLibrary.Source> NONE = new HashMap<>();
}
