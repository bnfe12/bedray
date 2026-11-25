package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Graph settings: the numbers of the helpers every node builds on (the sky model, the sun's path, the material colours,
 * ray lengths and offsets, march step limits), stored with the graph ({@code "settings": {key: [numbers]}} in mosaic.json)
 * and emitted as constants {@code ms_<key>} that the generated helpers read. Nothing that changes the picture is fixed in
 * the generator: a graph that omits a key gets the default listed here.
 */
public final class MosaicSettings {

    private MosaicSettings() {}

    /** One setting: key, label, help, number of components (1 float, 3 colour/vector, 45 = 15 colours), default, integer. */
    public record Setting(String key, String label, String group, String help, int size, double[] def, boolean integer) {}

    private static Setting f(String key, String label, String group, String help, double def) {
        return new Setting(key, label, group, help, 1, new double[] {def}, false);
    }

    private static Setting i(String key, String label, String group, String help, int def) {
        return new Setting(key, label, group, help, 1, new double[] {def}, true);
    }

    private static Setting v(String key, String label, String group, String help, double x, double y, double z) {
        return new Setting(key, label, group, help, 3, new double[] {x, y, z}, false);
    }

    /** Material colours (MosaicWorld.Material order): air, water, lava, stone, dirt, leaves, plants, wood, sand, snow, ice, glass, metal, lights, other. */
    public static final double[] MATERIAL_COLORS = {0.0, 0.0, 0.0, 0.12, 0.30, 0.50, 1.0, 0.45, 0.10, 0.50, 0.50, 0.52,
        0.36, 0.50, 0.22, 0.20, 0.42, 0.12, 0.30, 0.55, 0.20, 0.45, 0.32, 0.18, 0.82, 0.76, 0.55, 0.95, 0.97, 1.00,
        0.60, 0.75, 0.95, 0.85, 0.90, 0.95, 0.75, 0.75, 0.78, 1.00, 0.85, 0.55, 0.55, 0.50, 0.45};

    public static final List<Setting> ALL = List.of(
        f("sunTilt", "Sun path tilt", "Sun and sky", "Sideways tilt of the sun's path (z of its direction): shadows fall at an angle at noon.", 0.25),
        v("skyHorizon", "Sky at the horizon", "Sun and sky", "Colour of the sky model at the horizon (daylight).", 0.70, 0.80, 0.95),
        v("skyZenith", "Sky overhead", "Sun and sky", "Colour of the sky model straight up (daylight).", 0.25, 0.45, 0.85),
        f("skyNight", "Night sky level", "Sun and sky", "Brightness of the sky model at night (0..1 of daylight).", 0.05),
        f("skyDayRise", "Daylight rise", "Sun and sky", "How fast the sky brightens with the sun's height (day = height x rise + offset).", 2.0),
        f("skyDayOffset", "Daylight offset", "Sun and sky", "Sky brightness when the sun is at the horizon.", 0.3),
        v("sunDiscColor", "Sun disc colour", "Sun and sky", "Colour of the sun's disc in the sky model.", 1.0, 0.9, 0.7),
        f("sunDiscSharpness", "Sun disc sharpness", "Sun and sky", "Higher = smaller, sharper sun disc.", 256),
        f("sunDiscBrightness", "Sun disc brightness", "Sun and sky", "Brightness of the sun's disc.", 4),
        v("shadowDirection", "Shadow ray default direction", "Rays", "Direction of a Shadow ray node whose direction input is not wired.", 0.32, 0.90, 0.28),
        f("shadowRayStart", "Shadow ray start", "Rays", "Distance (blocks) a hardware shadow ray skips at its start.", 0.01),
        f("shadowRayLength", "Shadow ray length", "Rays", "How far (blocks) shadow rays look for a blocker.", 256),
        f("hitOffset", "Surface probe offset", "World volume", "How far past a hit the material is read and before it the light (blocks).", 0.02),
        i("voxelSteps", "Voxel ray steps", "World volume", "Most blocks a voxel ray crosses (longer rays cost more).", 192),
        i("materialPathSteps", "Material path steps", "World volume", "Most blocks crossed when measuring water / material thickness.", 160),
        i("farShadowSteps", "Far shadow steps", "Height field", "Most steps of a far-shadow march over the height field.", 96),
        f("farShadowStep", "Far shadow step", "Height field", "First step length of the far-shadow march (blocks).", 1.0),
        f("farShadowGrowth", "Far shadow step growth", "Height field", "How much each far-shadow step grows with distance (0.03 = 3 %).", 0.03),
        f("farShadowBias", "Far shadow bias", "Height field", "How far below the terrain top a point counts as shadowed (blocks).", 0.05),
        new Setting("materialColors", "Material colours", "World volume", "Colour of every material class as rays see it: GI bounces,"
            + " voxel render, reflections (air, water, lava, stone, dirt, leaves, plants, wood, sand, snow, ice, glass, metal, lights, other).",
            45, MATERIAL_COLORS, false));

    public static Setting of(String key) {
        for (Setting s : ALL) if (s.key().equals(key)) return s;
        return null;
    }

    /** Reads "settings" of a graph: known keys with the right number of values; anything else is reported. */
    public static Map<String, double[]> read(JsonElement el, java.util.function.Consumer<String> problem) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (el == null || el.isJsonNull()) return out;
        if (!el.isJsonObject()) {
            problem.accept("\"settings\" must be an object of name: [numbers]");
            return out;
        }
        for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
            Setting s = of(e.getKey());
            if (s == null) {
                problem.accept("unknown graph setting " + e.getKey());
                continue;
            }
            double[] v;
            try {
                JsonArray a = e.getValue().isJsonArray() ? e.getValue().getAsJsonArray() : null;
                if (a == null) {
                    v = new double[] {e.getValue().getAsDouble()};
                } else {
                    v = new double[a.size()];
                    for (int k = 0; k < v.length; k++) v[k] = a.get(k).getAsDouble();
                }
            } catch (RuntimeException ex) {
                problem.accept("graph setting " + e.getKey() + " must be numbers");
                continue;
            }
            if (v.length != s.size()) {
                problem.accept("graph setting " + e.getKey() + " needs " + s.size() + " numbers, found " + v.length);
                continue;
            }
            for (double d : v) if (!Double.isFinite(d)) problem.accept("graph setting " + e.getKey() + " is not a finite number");
            out.put(s.key(), v);
        }
        return out;
    }

    public static JsonObject toJson(Map<String, double[]> values) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, double[]> e : values.entrySet()) {
            JsonArray a = new JsonArray();
            for (double d : e.getValue()) a.add(d);
            o.add(e.getKey(), a);
        }
        return o;
    }

    public static double[] value(Map<String, double[]> values, Setting s) {
        double[] v = values == null ? null : values.get(s.key());
        return v != null && v.length == s.size() ? v : s.def();
    }

    private static String num(double d) {
        String s = String.format(Locale.ROOT, "%.6f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    /** The constants block ({@code ms_<key>}) for the generated shader. */
    public static String constants(Map<String, double[]> values, boolean slang) {
        StringBuilder sb = new StringBuilder("// graph settings (mosaic.json \"settings\")\n");
        String c = slang ? "static const " : "const ", v3 = slang ? "float3" : "vec3";
        for (Setting s : ALL) {
            double[] v = value(values, s);
            if (s.integer()) sb.append(c).append("int ms_").append(s.key()).append(" = ").append(Math.max(1, (int) Math.round(v[0]))).append(";\n");
            else if (s.size() == 1) sb.append(c).append("float ms_").append(s.key()).append(" = ").append(num(v[0])).append(";\n");
            else if (s.size() == 3) sb.append(c).append(v3).append(" ms_").append(s.key()).append(" = ").append(v3)
                .append("(").append(num(v[0])).append(", ").append(num(v[1])).append(", ").append(num(v[2])).append(");\n");
            else {
                int n = s.size() / 3;
                sb.append(c).append(v3).append(" ms_").append(s.key()).append("[").append(n).append("] = ")
                    .append(slang ? "{ " : v3 + "[" + n + "](");
                for (int k = 0; k < n; k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(v3).append("(").append(num(v[k * 3])).append(", ").append(num(v[k * 3 + 1])).append(", ").append(num(v[k * 3 + 2])).append(")");
                }
                sb.append(slang ? " };\n" : ");\n");
            }
        }
        return sb.toString();
    }
}
