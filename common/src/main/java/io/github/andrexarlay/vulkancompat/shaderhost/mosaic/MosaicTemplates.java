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
        ALL.add(new Template("Renderer from scratch (hardware RT)", "Renderer", "The voxel GI renderer with exact shadows and GI rays"
            + " through the real scene geometry: needs a GPU with hardware ray tracing (RTX-class).", renderer(true, true)));
        ALL.add(new Template("Bedray render + your passes", "Renderer", "Bedray's own renderer as one node (double-click: its stages),"
            + " then passes of your own: light shafts, a grade and Temporal AA.", """
            {"nodes":[
             {"id":"bedray","type":"bedray.render","x":0,"y":0},
             {"id":"uv","type":"mosaic.uv","x":0,"y":90},
             {"id":"shafts","type":"fn.godRays","inputs":{"color":"bedray","uv":"uv"},"x":200,"y":0},
             {"id":"grade","type":"fn.colorGrade","inputs":{"color":"shafts"},"x":400,"y":0},
             {"id":"buf","type":"mosaic.pass","mask":"frame","index":1,"inputs":{"color":"grade"},"x":600,"y":0},
             {"id":"taa","type":"mosaic.taa","mask":"frame","x":800,"y":0},
             {"id":"out","type":"mosaic.output","inputs":{"color":"taa"},"x":1000,"y":0}],
             "functions":[%s,%s]}""".formatted(MosaicEffects.get("godRays").toJson(), MosaicEffects.get("colorGrade").toJson())));
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
    static String renderer(boolean gi) { return renderer(gi, false); }

    /**
     * A renderer of your own, laid out in frames: G-buffer, surface (albedo, roughness), direct light (one shared sun shadow
     * ray for diffuse and specular, block light, emission), indirect light (voxel or RT GI at 1/2 size, Accumulate, three
     * Denoise steps; or sky ambient), and the shading chain (combine, water reflections, atmosphere, tone map, TAA).
     * {@code rt}: the hardware ray tracing pieces (Sun & moon light (RT), Diffuse GI (RT)) instead of the voxel ones.
     */
    static String renderer(boolean gi, boolean rt) {
        JsonObject root = new JsonObject();
        JsonArray fns = new JsonArray(), nodes = new JsonArray(), frames = new JsonArray();
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        java.util.Map<String, int[]> at = new java.util.LinkedHashMap<>();
        final int CX = 240, RY = 150;
        java.util.function.Function<Object[], JsonObject> add = a -> {
            String id = (String) a[0], type = (String) a[1];
            if (type.startsWith("fn:")) {
                MosaicFunction f = MosaicEffects.get(type.substring(3));
                if (used.add(f.name())) fns.add(f.toJson());
                type = f.type();
            }
            int x = (int) Math.round(((Number) a[2]).doubleValue() * CX), y = (int) Math.round(((Number) a[3]).doubleValue() * RY);
            JsonObject n = node(id, type, x, y);
            JsonObject in = new JsonObject();
            for (int i = 4; i + 1 < a.length; i += 2) in.addProperty((String) a[i], (String) a[i + 1]);
            if (in.size() > 0) n.add("inputs", in);
            nodes.add(n);
            at.put(id, new int[] {x, y});
            return n;
        };
        java.util.function.BiConsumer<String, Object[]> frame = (title, spec) -> {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (int i = 1; i < spec.length; i++) {
                int[] p = at.get((String) spec[i]);
                if (p == null) continue;
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0] + 180);
                maxY = Math.max(maxY, p[1] + 120);
            }
            JsonObject f = new JsonObject();
            f.addProperty("id", "f_" + title.replaceAll("\\W", "").toLowerCase(java.util.Locale.ROOT));
            f.addProperty("title", title);
            f.addProperty("x", minX - 20);
            f.addProperty("y", minY - 40);
            f.addProperty("w", maxX - minX + 40);
            f.addProperty("h", maxY - minY + 60);
            f.addProperty("color", (String) spec[0]);
            frames.add(f);
        };
        java.util.function.BiConsumer<JsonObject, Object[]> buf = (n, mi) -> {
            n.addProperty("mask", (String) mi[0]);
            if (mi.length > 1) n.addProperty("index", (Integer) mi[1]);
        };
        // G-buffer
        add.apply(new Object[] {"uv", "mosaic.uv", 0, 0});
        add.apply(new Object[] {"pos", "mosaic.position", 0, 1});
        add.apply(new Object[] {"nrm", "mosaic.normal", 0, 2});
        add.apply(new Object[] {"lamp", "mosaic.blockLight", 0, 3});
        if (!gi) add.apply(new Object[] {"sky", "mosaic.skyLight", 0, 4});
        // surface
        add.apply(new Object[] {"albedo", "fn:sceneAlbedo", 1, 0, "uv", "uv"});
        add.apply(new Object[] {"rough", "fn:materialRoughness", 1, 1, "uv", "uv"});
        // direct light
        // one sun shadow ray (voxel or hardware) shared by diffuse sun and specular, traced at 1/2 size below
        add.apply(new Object[] {"shadow", rt ? "fn:sunShadowRT" : "fn:sunShadow", 2, 0, "position", "pos", "normal", "nrm"});
        add.apply(new Object[] {"sun", rt ? "fn:sunLightRT" : "fn:sunLight", 2, 1, "position", "pos", "normal", "nrm", "visibility", "visUp"});
        add.apply(new Object[] {"spec", "fn:sunSpecular", 2, 2, "position", "pos", "normal", "nrm", "roughness", "rough", "visibility", "visUp"});
        add.apply(new Object[] {"torch", "fn:torchLightShadowed", 2, 3, "position", "pos", "normal", "nrm", "blockLight", "lamp"});
        {
            // the traced shadows (sun visibility, shadowed block light) at 1/2 size, read back sharp at block edges
            add.apply(new Object[] {"pack", "fn:packLight", 1, 4.2, "light", "torch", "visibility", "shadow"});
            buf.accept(add.apply(new Object[] {"lightBuf", "mosaic.pass", 1, 5.4, "color", "pack"}), new Object[] {"light", 2});
            buf.accept(add.apply(new Object[] {"lightUp", "mosaic.upsample", 2, 5.4}), new Object[] {"light"});
            JsonObject sv = add.apply(new Object[] {"visUp", "mosaic.swizzle", 2.5, 4.4, "a", "lightUp"});
            sv.addProperty("mask", "w");
            JsonObject st = add.apply(new Object[] {"torchUp", "mosaic.swizzle", 3, 4.4, "a", "lightUp"});
            st.addProperty("mask", "xyz");
        }
        add.apply(new Object[] {"glow", "fn:selfGlow", 2, 4, "uv", "uv", "albedo", "albedo"});
        // indirect light
        String indirect;
        if (gi) {
            add.apply(new Object[] {"giRaw", rt ? "fn:diffuseGIRT" : "fn:diffuseGI", 1, 7, "position", "pos", "normal", "nrm"});
            JsonObject acc = add.apply(new Object[] {"acc", "mosaic.accumulate", 2, 7, "color", "giRaw"});
            acc.addProperty("mask", "gi");
            JsonObject p0 = add.apply(new Object[] {"giBuf", "mosaic.pass", 3, 7, "color", "acc"});
            p0.addProperty("mask", "gi");
            p0.addProperty("index", 2);
            String prev = "gi";
            String[] steps = {"giA", "giB"};
            for (int i = 0; i < 3; i++) {
                JsonObject d = add.apply(new Object[] {"dn" + i, "mosaic.atrous", 1 + i, 8.2});
                d.addProperty("mask", prev);
                JsonObject def = new JsonObject();
                JsonArray s = new JsonArray();
                s.add(1 << i);
                def.add("step", s);
                d.add("defaults", def);
                if (i == 2) break;
                JsonObject p = add.apply(new Object[] {"buf" + i, "mosaic.pass", 1 + i + 0.5, 9.2, "color", "dn" + i});
                p.addProperty("mask", steps[i]);
                p.addProperty("index", 2);
                prev = steps[i];
            }
            buf.accept(add.apply(new Object[] {"giBufC", "mosaic.pass", 4, 9.2, "color", "dn2"}), new Object[] {"giC", 2});
            buf.accept(add.apply(new Object[] {"giUp", "mosaic.upsample", 5, 9.2}), new Object[] {"giC"});
            JsonObject sw = add.apply(new Object[] {"giRgb", "mosaic.swizzle", 4, 8.2, "a", "giUp"});
            sw.addProperty("mask", "xyz");
            indirect = "giRgb";
        } else {
            add.apply(new Object[] {"amb", "fn:skyAmbient", 1, 3, "normal", "nrm", "skyLight", "sky"});
            indirect = "amb";
        }
        // shading chain
        add.apply(new Object[] {"direct", "mosaic.add", 3, 0, "a", "sun", "b", "torchUp"});
        add.apply(new Object[] {"light", "mosaic.add", 3, 1, "a", "direct", "b", indirect});
        add.apply(new Object[] {"extra", "mosaic.add", 3, 2, "a", "glow", "b", "spec"});
        add.apply(new Object[] {"shade", "fn:litSurface", 4, 0, "uv", "uv", "albedo", "albedo", "light", "light", "glow", "extra"});
        add.apply(new Object[] {"water", "fn:waterBody", 4, 1.2, "color", "shade", "uv", "uv"});
        JsonObject skyBg = add.apply(new Object[] {"skyBg", "fn:skyBackground", 4, 2.4, "color", "water", "uv", "uv"});
        JsonObject flat = new JsonObject();
        JsonArray zero = new JsonArray();
        zero.add(0);
        flat.add("cloudCoverage", zero); // the volumetric clouds below replace the flat layer
        skyBg.add("defaults", flat);
        // volumetric clouds at half size in their own buffer, composited over the sky (about a quarter of the cost)
        add.apply(new Object[] {"cloudRay", "fn:cloudLayer", 3, 3.6, "uv", "uv"});
        buf.accept(add.apply(new Object[] {"cloudBuf", "mosaic.pass", 3, 4.8, "color", "cloudRay"}), new Object[] {"clouds", 4});
        buf.accept(add.apply(new Object[] {"cloudIn", "mosaic.bufferIn", 3, 6}), new Object[] {"clouds"});
        add.apply(new Object[] {"clouds", "fn:cloudComposite", 4, 3.6, "color", "skyBg", "layer", "cloudIn", "uv", "uv"});
        // the cloud layer at 1/4 size: more steps fit in the same time (soft shapes lose nothing)
        JsonObject cr = null;
        for (var el : nodes) if (el.getAsJsonObject().get("id").getAsString().equals("cloudRay")) cr = el.getAsJsonObject();
        if (cr != null) {
            JsonObject cd = new JsonObject();
            JsonArray st = new JsonArray();
            st.add(20);
            cd.add("steps", st);
            cr.add("defaults", cd);
        }
        add.apply(new Object[] {"air", "fn:atmosphere", 4, 4.8, "color", "clouds", "uv", "uv"});
        // HDR into a buffer, then a bloom chain at 1/2, 1/4 and 1/8 added back before the tone map
        buf.accept(add.apply(new Object[] {"hdrBuf", "mosaic.pass", 5, 0, "color", "air"}), new Object[] {"hdr", 1});
        buf.accept(add.apply(new Object[] {"hdrIn", "mosaic.bufferIn", 5, 1.2}), new Object[] {"hdr"});
        add.apply(new Object[] {"bright", "fn:brightPass", 5, 2.4, "color", "hdrIn"});
        buf.accept(add.apply(new Object[] {"bloomA", "mosaic.pass", 5, 3.6, "color", "bright"}), new Object[] {"bloomA", 2});
        buf.accept(add.apply(new Object[] {"kA", "mosaic.kawase", 6, 0}), new Object[] {"bloomA"});
        buf.accept(add.apply(new Object[] {"bloomB", "mosaic.pass", 6, 1.2, "color", "kA"}), new Object[] {"bloomB", 4});
        JsonObject kB = add.apply(new Object[] {"kB", "mosaic.kawase", 6, 2.4});
        buf.accept(kB, new Object[] {"bloomB"});
        JsonObject kd = new JsonObject();
        JsonArray kr = new JsonArray();
        kr.add(2);
        kd.add("radius", kr);
        kB.add("defaults", kd);
        buf.accept(add.apply(new Object[] {"bloomC", "mosaic.pass", 6, 3.6, "color", "kB"}), new Object[] {"bloomC", 8});
        buf.accept(add.apply(new Object[] {"bA", "mosaic.blur", 7, 0}), new Object[] {"bloomA"});
        buf.accept(add.apply(new Object[] {"bB", "mosaic.blur", 7, 1.2}), new Object[] {"bloomB"});
        buf.accept(add.apply(new Object[] {"bC", "mosaic.blur", 7, 2.4}), new Object[] {"bloomC"});
        // reflections read from the HDR image (its sky and volumetric clouds included); the sky model where they leave the screen
        buf.accept(add.apply(new Object[] {"ssr", "mosaic.ssr", 7, 6}), new Object[] {"hdr"});
        add.apply(new Object[] {"bloom", "fn:bloomMix", 8, 0, "base", "ssr", "a", "bA", "b", "bB", "c", "bC"});
        // auto exposure: log brightness at 1/8, its average, adapted over time against last frame's value
        add.apply(new Object[] {"logLum", "fn:logLuminance", 7, 3.6, "color", "hdrIn"});
        buf.accept(add.apply(new Object[] {"lumBuf", "mosaic.pass", 7, 4.8, "color", "logLum"}), new Object[] {"lum", 8});
        buf.accept(add.apply(new Object[] {"avg", "mosaic.average", 8, 3.6}), new Object[] {"lum"});
        buf.accept(add.apply(new Object[] {"expPrev", "mosaic.bufferPrev", 8, 4.8}), new Object[] {"exposure"});
        add.apply(new Object[] {"adapt", "fn:adaptExposure", 9, 3.6, "average", "avg", "previous", "expPrev"});
        buf.accept(add.apply(new Object[] {"expBuf", "mosaic.pass", 9, 4.8, "color", "adapt"}), new Object[] {"exposure", 8});
        buf.accept(add.apply(new Object[] {"expIn", "mosaic.bufferIn", 10, 3.6}), new Object[] {"exposure"});
        add.apply(new Object[] {"ae", "fn:autoExposure", 10, 4.8, "color", "bloom", "adapted", "expIn"});
        add.apply(new Object[] {"tone", "fn:tonemap", 8, 1.2, "color", "ae"});
        JsonObject fb = add.apply(new Object[] {"frameBuf", "mosaic.pass", 8, 2.4, "color", "tone"});
        fb.addProperty("mask", "frame");
        fb.addProperty("index", 1);
        JsonObject taa = add.apply(new Object[] {"taa", "mosaic.taa", 9, 0});
        taa.addProperty("mask", "frame");
        add.apply(new Object[] {"out", "mosaic.output", 9, 1.2, "color", "taa"});
        frame.accept("G-buffer", gi ? new Object[] {"#3A6EA5", "uv", "pos", "nrm", "lamp"} : new Object[] {"#3A6EA5", "uv", "pos", "nrm", "lamp", "sky"});
        frame.accept("Surface", gi ? new Object[] {"#7A4FA8", "albedo", "rough"} : new Object[] {"#7A4FA8", "albedo", "rough", "amb"});
        frame.accept("Direct light", new Object[] {"#B07A2E", "shadow", "sun", "spec", "torch", "glow", "pack", "lightBuf", "lightUp", "visUp", "torchUp"});
        if (gi) frame.accept(rt ? "Indirect light (RT GI)" : "Indirect light (voxel GI)",
            new Object[] {"#2F8F7C", "giRaw", "acc", "giBuf", "dn0", "buf0", "dn1", "buf1", "dn2", "giBufC", "giUp", "giRgb"});
        frame.accept("Shade, sky and atmosphere", new Object[] {"#8A5CC8", "direct", "light", "extra", "shade", "water", "skyBg", "cloudRay", "cloudBuf", "cloudIn", "clouds", "air"});
        frame.accept("HDR and bloom", new Object[] {"#C06A3A", "hdrBuf", "hdrIn", "ssr", "bright", "bloomA", "kA", "bloomB", "kB", "bloomC", "bA", "bB", "bC"});
        frame.accept("Auto exposure", new Object[] {"#3E8EB0", "logLum", "lumBuf", "avg", "expPrev", "adapt", "expBuf", "expIn", "ae"});
        frame.accept("Tone map and output", new Object[] {"#4B8A4B", "bloom", "tone", "frameBuf", "taa", "out"});
        root.add("functions", fns);
        root.add("nodes", nodes);
        root.add("frames", frames);
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
        if (f.output() == MosaicGraph.Kind.F) {
            JsonObject v = node("sw", "mosaic.vec", 400, 0);
            v.addProperty("index", 4);
            JsonObject vi = new JsonObject();
            vi.addProperty("x", "f");
            v.add("inputs", vi);
            nodes.add(v);
            last = "sw";
        } else if (f.output() != MosaicGraph.Kind.V4) {
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
