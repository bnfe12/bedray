package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Starter graphs for the editor's "New from template" window. Most are chains of built-in effects
 * (scene color -> effect -> effect -> output), so every template is also a readable example of wiring.
 * Each one is validated and compiled in the unit tests.
 */
public final class MosaicTemplates {

    private MosaicTemplates() {}

    public record Template(String name, String category, String description, String json) {}

    private static final List<Template> ALL = new ArrayList<>();

    static {
        ALL.add(new Template("Pass-through", "Start", "The world image unchanged: the smallest valid graph.", chain()));
        ALL.add(new Template("Cinematic", "Post effects", "Color grade, vignette and film grain.",
            chain("colorGrade", "vignette", "filmGrain")));
        ALL.add(new Template("Retro screen", "Post effects", "Posterized colors, scanlines and a lens split.",
            chain("chromaticAberration", "posterize", "scanlines")));
        ALL.add(new Template("Comic", "Post effects", "Black outlines and flat color bands.",
            chain("posterize", "depthOutline")));
        ALL.add(new Template("Dreamy glow", "Post effects", "Soft bloom with a warm grade.", chain("glow", "colorGrade")));
        ALL.add(new Template("Realistic water", "World", "Water absorbs light by depth, caustics under it, sparkle on"
            + " top and a full underwater look.", chain("waterColor", "caustics", "waterSparkle", "underwater")));
        ALL.add(new Template("Caves and torches", "World", "Dark caves, warm torch light, glowing leaves.",
            chain("caveDarkness", "torchWarmth", "leafGlow")));
        ALL.add(new Template("Hot nether", "World", "Lava heat haze and a red grade.", chain("lavaHeat", "colorGrade")));
        ALL.add(new Template("Winter", "World", "Snow sparkle and cold fog.", chain("snowSparkle", "depthFog")));
        ALL.add(new Template("Ray-traced sky shadow", "Ray tracing", "One ray per pixel toward the sky darkens"
            + " covered areas.", chain("skyShadow")));
        ALL.add(new Template("Voxel world", "Rendering", "Draws the world volume by itself: works with every renderer,"
            + " no ray tracing hardware needed.", chain("voxelRender")));
        ALL.add(new Template("Voxel path-traced GI", "Rendering", "Light bounces between blocks on top of the image.",
            chain("pathTraceGI", "colorGrade")));
        ALL.add(new Template("Hybrid RT", "Rendering", "Classic image + hardware RT ambient occlusion and water"
            + " reflections + voxel GI.", chain("pathTraceGI", "rtAmbientOcclusion", "rtReflections")));
        ALL.add(new Template("Debug: distance", "Debug", "Grey by hit distance (white = 64+ blocks).", """
            {"nodes": [
              {"id": "depth", "type": "mosaic.sceneDepth", "x": 0, "y": 0},
              {"id": "scale", "type": "mosaic.const", "value": [0.015625], "x": 0, "y": 60},
              {"id": "mul", "type": "mosaic.mul", "inputs": {"a": "depth", "b": "scale"}, "x": 180, "y": 20},
              {"id": "sat", "type": "mosaic.saturate", "inputs": {"a": "mul"}, "x": 360, "y": 20},
              {"id": "out", "type": "mosaic.output", "inputs": {"color": "sat"}, "x": 540, "y": 20}
            ]}"""));
        ALL.add(new Template("Debug: sky light", "Debug", "Grey by sky light at each surface (black = cave).", """
            {"nodes": [
              {"id": "sky", "type": "mosaic.skyLight", "x": 0, "y": 0},
              {"id": "out", "type": "mosaic.output", "inputs": {"color": "sky"}, "x": 200, "y": 0}
            ]}"""));
    }

    public static List<Template> all() { return List.copyOf(ALL); }

    /** scene color -> effects in order -> output, laid out left to right, sharing one uv node. */
    static String chain(String... effects) {
        JsonObject root = new JsonObject();
        JsonArray fns = new JsonArray();
        JsonArray nodes = new JsonArray();
        boolean takesColor = effects.length == 0 || MosaicEffects.get(effects[0]).params().stream()
            .anyMatch(p -> p.required() && p.kind() == MosaicGraph.Kind.V4);
        if (takesColor) nodes.add(node("color", "mosaic.sceneColor", 0, 0));
        boolean needsUv = java.util.Arrays.stream(effects).anyMatch(e -> MosaicEffects.get(e).params().stream()
            .anyMatch(p -> p.required() && p.kind() == MosaicGraph.Kind.V2));
        if (needsUv) nodes.add(node("uv", "mosaic.uv", 0, 70));
        String prev = "color";
        int x = 190;
        for (String name : effects) {
            MosaicFunction f = MosaicEffects.get(name);
            if (f == null) throw new IllegalStateException("no effect " + name);
            fns.add(f.toJson());
            JsonObject n = node(name, f.type(), x, 0);
            JsonObject in = new JsonObject();
            for (MosaicFunction.Param p : f.params()) {
                if (!p.required()) continue;
                if (p.kind() == MosaicGraph.Kind.V4) in.addProperty(p.name(), prev);
                else if (p.kind() == MosaicGraph.Kind.V2) in.addProperty(p.name(), "uv");
            }
            n.add("inputs", in);
            nodes.add(n);
            prev = name;
            x += 180;
        }
        JsonObject out = node("out", "mosaic.output", x, 0);
        JsonObject in = new JsonObject();
        in.addProperty("color", prev);
        out.add("inputs", in);
        nodes.add(out);
        if (!fns.isEmpty()) root.add("functions", fns);
        root.add("nodes", nodes);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static JsonObject node(String id, String type, int x, int y) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("x", x);
        o.addProperty("y", y);
        return o;
    }
}
