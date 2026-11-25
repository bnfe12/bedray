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

    /** Three passes: bright -> blur -> final; the classic bloom chain built from Buffer / Read buffer nodes. */
    static final String BLOOM = "{\"nodes\":[{\"id\":\"c\",\"type\":\"mosaic.sceneColor\"},{\"id\":\"s\",\"type\":\"mosaic.math\",\"mask\":\"subtract\",\"inputs\":{\"a\":\"c\"},\"defaults\":{\"b\":[0.72]}},{\"id\":\"m\",\"type\":\"mosaic.math\",\"mask\":\"maximum\",\"inputs\":{\"a\":\"s\"},\"defaults\":{\"b\":[0]}},{\"id\":\"k\",\"type\":\"mosaic.math\",\"mask\":\"multiply\",\"inputs\":{\"a\":\"m\"},\"defaults\":{\"b\":[3]}},{\"id\":\"p1\",\"type\":\"mosaic.pass\",\"mask\":\"bright\",\"index\":4,\"inputs\":{\"color\":\"k\"}},{\"id\":\"uv\",\"type\":\"mosaic.uv\"},{\"id\":\"o1\",\"type\":\"mosaic.const\",\"value\":[0.012,0]},{\"id\":\"o2\",\"type\":\"mosaic.const\",\"value\":[0,0.02]},{\"id\":\"u1\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"uv\",\"b\":\"o1\"}},{\"id\":\"u2\",\"type\":\"mosaic.sub\",\"inputs\":{\"a\":\"uv\",\"b\":\"o1\"}},{\"id\":\"u3\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"uv\",\"b\":\"o2\"}},{\"id\":\"u4\",\"type\":\"mosaic.sub\",\"inputs\":{\"a\":\"uv\",\"b\":\"o2\"}},{\"id\":\"r0\",\"type\":\"mosaic.bufferIn\",\"mask\":\"bright\"},{\"id\":\"r1\",\"type\":\"mosaic.bufferIn\",\"mask\":\"bright\",\"inputs\":{\"uv\":\"u1\"}},{\"id\":\"r2\",\"type\":\"mosaic.bufferIn\",\"mask\":\"bright\",\"inputs\":{\"uv\":\"u2\"}},{\"id\":\"r3\",\"type\":\"mosaic.bufferIn\",\"mask\":\"bright\",\"inputs\":{\"uv\":\"u3\"}},{\"id\":\"r4\",\"type\":\"mosaic.bufferIn\",\"mask\":\"bright\",\"inputs\":{\"uv\":\"u4\"}},{\"id\":\"a1\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"r1\",\"b\":\"r2\"}},{\"id\":\"a2\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"r3\",\"b\":\"r4\"}},{\"id\":\"a3\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"a1\",\"b\":\"a2\"}},{\"id\":\"a4\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"a3\",\"b\":\"r0\"}},{\"id\":\"avg\",\"type\":\"mosaic.math\",\"mask\":\"multiply\",\"inputs\":{\"a\":\"a4\"},\"defaults\":{\"b\":[0.2]}},{\"id\":\"p2\",\"type\":\"mosaic.pass\",\"mask\":\"blur\",\"index\":2,\"inputs\":{\"color\":\"avg\"}},{\"id\":\"g\",\"type\":\"mosaic.bufferIn\",\"mask\":\"blur\"},{\"id\":\"sum\",\"type\":\"mosaic.add\",\"inputs\":{\"a\":\"c\",\"b\":\"g\"}},{\"id\":\"out\",\"type\":\"mosaic.output\",\"inputs\":{\"color\":\"sum\"}}]}";

    /**
     * Bloom as a mip chain (the way large shader packs build it): bright parts at 1/2, two Kawase steps down to 1/4 and
     * 1/8, then each level blurred and added back - wide, smooth glow from a handful of taps per pixel.
     */
    static final String BLOOM_CHAIN = """
{"nodes":[
 {"id":"c","type":"mosaic.sceneColor","x":0,"y":0},
 {"id":"cut","type":"mosaic.math","mask":"subtract","inputs":{"a":"c"},"defaults":{"b":[0.8]},"x":180,"y":0},
 {"id":"pos","type":"mosaic.math","mask":"maximum","inputs":{"a":"cut"},"defaults":{"b":[0]},"x":360,"y":0},
 {"id":"half","type":"mosaic.pass","mask":"half","index":2,"inputs":{"color":"pos"},"x":540,"y":0},
 {"id":"k1","type":"mosaic.kawase","mask":"half","defaults":{"radius":[1]},"x":0,"y":160},
 {"id":"quarter","type":"mosaic.pass","mask":"quarter","index":4,"inputs":{"color":"k1"},"x":180,"y":160},
 {"id":"k2","type":"mosaic.kawase","mask":"quarter","defaults":{"radius":[2]},"x":360,"y":160},
 {"id":"eighth","type":"mosaic.pass","mask":"eighth","index":8,"inputs":{"color":"k2"},"x":540,"y":160},
 {"id":"b1","type":"mosaic.blur","mask":"half","x":0,"y":320},
 {"id":"b2","type":"mosaic.blur","mask":"quarter","x":0,"y":400},
 {"id":"b3","type":"mosaic.blur","mask":"eighth","defaults":{"radius":[1.5]},"x":0,"y":480},
 {"id":"s1","type":"mosaic.add","inputs":{"a":"b1","b":"b2"},"x":180,"y":360},
 {"id":"s2","type":"mosaic.add","inputs":{"a":"s1","b":"b3"},"x":360,"y":400},
 {"id":"glow","type":"mosaic.math","mask":"multiply","inputs":{"a":"s2"},"defaults":{"b":[0.45]},"x":540,"y":400},
 {"id":"sum","type":"mosaic.add","inputs":{"a":"c","b":"glow"},"x":720,"y":200},
 {"id":"out","type":"mosaic.output","inputs":{"color":"sum"},"x":900,"y":200}]}""";

    /** Temporal smoothing: last frame read where this pixel's surface was (Reproject), blended in. */
    static final String TAA = """
{"nodes":[
 {"id":"taa","type":"mosaic.taa","defaults":{"blend":[0.9]},"x":0,"y":0},
 {"id":"out","type":"mosaic.output","inputs":{"color":"taa"},"x":220,"y":0}]}""";

    static {
        ALL.add(new Template("Pass-through", "Start", "The world image unchanged: the smallest valid graph.", chain()));
        ALL.add(new Template("Cinematic", "Post effects", "Color grade, vignette and film grain.",
            chain("colorGrade", "vignette", "filmGrain")));
        ALL.add(new Template("Retro screen", "Post effects", "Posterized colors, scanlines and a lens split.",
            chain("chromaticAberration", "posterize", "scanlines")));
        ALL.add(new Template("Comic", "Post effects", "Black outlines and flat color bands.",
            chain("posterize", "depthOutline")));
        ALL.add(new Template("Dreamy glow", "Post effects", "Soft bloom with a warm grade.", chain("glow", "colorGrade")));
        ALL.add(new Template("Bloom (multi-pass)", "Passes", "Bright parts at 1/4 size, blurred at 1/2 in its own pass, added back:"
            + " Buffer and Read buffer nodes.", BLOOM));
        ALL.add(new Template("Temporal smoothing", "Passes", "The previous frame, reprojected to where each surface was,"
            + " blended in: calmer noise and edges while the camera moves.", TAA));
        ALL.add(new Template("Bloom chain (mip)", "Passes", "Bright parts at 1/2, Kawase steps down to 1/4 and 1/8, every level"
            + " blurred and added back: the wide glow of big shader packs.", BLOOM_CHAIN));
        ALL.add(new Template("Realistic water", "World", "Water absorbs light by depth, caustics under it, sparkle on"
            + " top and a full underwater look.", chain("waterColor", "caustics", "waterSparkle", "underwater")));
        ALL.add(new Template("Caves and torches", "World", "Dark caves, warm torch light, glowing leaves.",
            chain("caveDarkness", "torchWarmth", "leafGlow")));
        ALL.add(new Template("Hot nether", "World", "Lava heat haze and a red grade.", chain("lavaHeat", "colorGrade")));
        ALL.add(new Template("Winter", "World", "Snow sparkle and cold fog.", chain("snowSparkle", "depthFog")));
        ALL.add(new Template("Ray-traced sky shadow", "Ray tracing", "One ray per pixel toward the sky darkens"
            + " covered areas.", chain("skyShadow")));
        ALL.add(new Template("Sun shadows (no RT)", "Rendering", "Sun and moon shadows traced through the world volume over"
            + " the game's own image, with a grade: shadows on any GPU.", chain("voxelSunShadow", "colorGrade")));
        ALL.add(new Template("Shader pack look (no RT)", "Rendering", "Sun shadows through the world volume, ambient"
            + " occlusion, light shafts, distance fog, a grade and a vignette over the game's image: the classic shader pack"
            + " look on any GPU. Add the Bloom chain for glow.", chain("voxelSunShadow", "ssao", "godRays", "depthFog", "colorGrade", "vignette")));
        ALL.add(new Template("Voxel world", "Rendering", "Draws the world volume by itself: works with every renderer,"
            + " no ray tracing hardware needed.", chain("voxelRender")));
        ALL.add(new Template("Voxel path-traced GI", "Rendering", "Light bounces between blocks on top of the image.",
            chain("pathTraceGI", "colorGrade")));
        ALL.add(new Template("Hybrid RT", "Rendering", "Classic image + hardware RT ambient occlusion and water"
            + " reflections + voxel GI.", chain("pathTraceGI", "rtAmbientOcclusion", "rtReflections")));
        ALL.add(new Template("Renderer from scratch (voxel GI)", "Renderer", "A whole renderer of your own from render-pipeline nodes:"
            + " albedo, sun and moon with voxel shadows, block light, emission, one-bounce voxel GI accumulated over frames and"
            + " denoised (a-trous x3), tone map and Temporal AA. No ray tracing hardware needed.", renderer(true)));
        ALL.add(new Template("Renderer from scratch (fast)", "Renderer", "The same renderer with sky ambient instead of GI:"
            + " sun and moon shadows, block light and emission on any GPU.", renderer(false)));
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

    /**
     * A renderer of your own from the render-pipeline nodes: G-buffer (albedo, position, normal, light levels), sun and moon
     * with voxel shadows, block light, emission and either voxel GI (one path per pixel into a 1/2 Buffer, Accumulate,
     * three Denoise steps) or the cheap sky ambient; combined, tone mapped into a Buffer and smoothed by Temporal AA.
     */
    static String renderer(boolean gi) {
        JsonObject root = new JsonObject();
        JsonArray fns = new JsonArray(), nodes = new JsonArray();
        java.util.function.BiConsumer<JsonObject, String[]> wire = (n, kv) -> {
            JsonObject in = n.has("inputs") ? n.getAsJsonObject("inputs") : new JsonObject();
            for (int i = 0; i + 1 < kv.length; i += 2) in.addProperty(kv[i], kv[i + 1]);
            n.add("inputs", in);
        };
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        java.util.function.Function<Object[], JsonObject> add = a -> {
            String id = (String) a[0], type = (String) a[1];
            if (type.startsWith("fn:")) {
                MosaicFunction f = MosaicEffects.get(type.substring(3));
                if (used.add(f.name())) fns.add(f.toJson());
                type = f.type();
            }
            JsonObject n = node(id, type, (Integer) a[2], (Integer) a[3]);
            nodes.add(n);
            return n;
        };
        add.apply(new Object[] {"uv", "mosaic.uv", 0, 0});
        add.apply(new Object[] {"pos", "mosaic.position", 0, 90});
        add.apply(new Object[] {"nrm", "mosaic.normal", 0, 180});
        add.apply(new Object[] {"lamp", "mosaic.blockLight", 0, 360});
        wire.accept(add.apply(new Object[] {"albedo", "fn:sceneAlbedo", 220, 0}), new String[] {"uv", "uv"});
        wire.accept(add.apply(new Object[] {"sun", "fn:sunLight", 220, 110}), new String[] {"position", "pos", "normal", "nrm"});
        wire.accept(add.apply(new Object[] {"torch", "fn:torchLight", 220, 360}), new String[] {"blockLight", "lamp"});
        wire.accept(add.apply(new Object[] {"glow", "fn:selfGlow", 220, 450}), new String[] {"uv", "uv", "albedo", "albedo"});
        wire.accept(add.apply(new Object[] {"l1", "mosaic.add", 460, 200}), new String[] {"a", "sun", "b", "torch"});
        String indirect;
        if (gi) {
            wire.accept(add.apply(new Object[] {"giRaw", "fn:diffuseGI", 220, 560}), new String[] {"position", "pos", "normal", "nrm"});
            JsonObject acc = add.apply(new Object[] {"acc", "mosaic.accumulate", 460, 560});
            acc.addProperty("mask", "gi");
            wire.accept(acc, new String[] {"color", "giRaw"});
            JsonObject p0 = add.apply(new Object[] {"giBuf", "mosaic.pass", 660, 560});
            p0.addProperty("mask", "gi");
            p0.addProperty("index", 2);
            wire.accept(p0, new String[] {"color", "acc"});
            String prev = "gi";
            String[] steps = {"giA", "giB"};
            int x = 860;
            for (int i = 0; i < 3; i++) {
                JsonObject d = add.apply(new Object[] {"dn" + i, "mosaic.atrous", x, 560});
                d.addProperty("mask", prev);
                JsonObject def = new JsonObject();
                JsonArray s = new JsonArray();
                s.add(1 << i);
                def.add("step", s);
                d.add("defaults", def);
                if (i == 2) break;
                JsonObject p = add.apply(new Object[] {"buf" + i, "mosaic.pass", x + 190, 560});
                p.addProperty("mask", steps[i]);
                p.addProperty("index", 2);
                wire.accept(p, new String[] {"color", "dn" + i});
                prev = steps[i];
                x += 380;
            }
            JsonObject sw = add.apply(new Object[] {"giRgb", "mosaic.swizzle", 1640, 420});
            sw.addProperty("mask", "xyz");
            wire.accept(sw, new String[] {"a", "dn2"});
            indirect = "giRgb";
        } else {
            add.apply(new Object[] {"sky", "mosaic.skyLight", 0, 270});
            wire.accept(add.apply(new Object[] {"amb", "fn:skyAmbient", 220, 240}), new String[] {"normal", "nrm", "skyLight", "sky"});
            indirect = "amb";
        }
        wire.accept(add.apply(new Object[] {"light", "mosaic.add", 1800, 200}), new String[] {"a", "l1", "b", indirect});
        wire.accept(add.apply(new Object[] {"shade", "fn:litSurface", 1980, 120}),
            new String[] {"uv", "uv", "albedo", "albedo", "light", "light", "glow", "glow"});
        wire.accept(add.apply(new Object[] {"tone", "fn:tonemap", 2180, 120}), new String[] {"color", "shade"});
        JsonObject fb = add.apply(new Object[] {"frameBuf", "mosaic.pass", 2380, 120});
        fb.addProperty("mask", "frame");
        fb.addProperty("index", 1);
        wire.accept(fb, new String[] {"color", "tone"});
        JsonObject taa = add.apply(new Object[] {"taa", "mosaic.taa", 2580, 120});
        taa.addProperty("mask", "frame");
        JsonObject out = add.apply(new Object[] {"out", "mosaic.output", 2780, 120});
        wire.accept(out, new String[] {"color", "taa"});
        root.add("functions", fns);
        root.add("nodes", nodes);
        return root.toString();
    }

    /** One function alone, every required input fed by a source of its kind, its result shown (vec3: as xyzx): checks and docs. */
    static String chainOf(String name) {
        MosaicFunction f = MosaicEffects.get(name);
        JsonObject root = new JsonObject();
        JsonArray fns = new JsonArray(), nodes = new JsonArray();
        fns.add(f.toJson());
        nodes.add(node("uv", "mosaic.uv", 0, 0));
        nodes.add(node("pos", "mosaic.position", 0, 80));
        nodes.add(node("nrm", "mosaic.normal", 0, 160));
        nodes.add(node("lvl", "mosaic.skyLight", 0, 240));
        nodes.add(node("col", "mosaic.sceneColor", 0, 320));
        JsonObject n = node("f", f.type(), 200, 0), in = new JsonObject();
        for (MosaicFunction.Param p : f.params()) {
            if (!p.required()) continue;
            in.addProperty(p.name(), switch (p.kind()) {
                case V2 -> "uv";
                case V3 -> p.name().toLowerCase(java.util.Locale.ROOT).contains("normal") ? "nrm" : "pos";
                case V4 -> "col";
                default -> "lvl";
            });
        }
        n.add("inputs", in);
        nodes.add(n);
        String last = "f";
        if (f.output() != MosaicGraph.Kind.V4) {
            JsonObject sw = node("sw", "mosaic.swizzle", 400, 0);
            sw.addProperty("mask", f.output() == MosaicGraph.Kind.V3 ? "xyzx" : f.output() == MosaicGraph.Kind.V2 ? "xyxy" : "xxxx");
            JsonObject si = new JsonObject();
            si.addProperty("a", "f");
            sw.add("inputs", si);
            nodes.add(sw);
            last = "sw";
        }
        JsonObject out = node("out", "mosaic.output", 600, 0), oi = new JsonObject();
        oi.addProperty("color", last);
        out.add("inputs", oi);
        nodes.add(out);
        root.add("functions", fns);
        root.add("nodes", nodes);
        return root.toString();
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
