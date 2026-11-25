package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the Mosaic editor shows for each node type: a readable label, a category for the palette and a one-line
 * explanation for tooltips and the inspector. Pure data plus search, unit-tested against {@link MosaicGraph}'s
 * type list so the palette can never miss or invent a node type.
 */
public final class MosaicCatalog {

    private MosaicCatalog() {}

    /** Palette groups, in palette order, each with the header color its nodes use on the canvas. */
    public enum Category {
        OUTPUT("Output", 0xFFB5524C),
        INPUT("Inputs", 0xFF4A76B8),
        SCENE("Scene", 0xFF2F8F7C),
        RAY("Ray tracing", 0xFF7F58B8),
        SURFACE("World & materials", 0xFF2C7FB0),
        VALUE("Values", 0xFF6C7384),
        CONVERTER("Converter", 0xFF3E7FA8),
        COLOR("Color", 0xFFB0507A),
        TEXTURE("Textures", 0xFFC08A2E),
        MATH("Math", 0xFF4B8A4B),
        VECTOR("Vector", 0xFFA67C34),
        LOGIC("Compare", 0xFF9A4D7A),
        MATERIAL("Material", 0xFFB86A3A),
        PASSES("Passes", 0xFF3A8FA0);

        public final String label;
        public final int color;

        Category(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** One palette entry. */
    public record Entry(String type, String label, Category category, String help) {
        /** The type without the {@code mosaic.} prefix, as users type it in search. */
        public String shortName() { return type.substring(type.indexOf('.') + 1); }
    }

    private static final List<Entry> ALL = List.of(
        e("mosaic.output", "Output", Category.OUTPUT, "Final pixel color. float -> grey, vec3 -> opaque, vec4 as is."),
        e("mosaic.pass", "Buffer (pass)", Category.PASSES, "Writes its input into a named buffer as its own pass, at full, 1/2, 1/4 or 1/8 size (blur, bloom)."),
        e("mosaic.bufferIn", "Read buffer", Category.PASSES, "Reads a buffer written by a Buffer node, at any uv (offsets for blur kernels)."),
        e("mosaic.history", "Previous frame", Category.PASSES, "The last final frame at uv: temporal smoothing, trails, motion effects."),
        e("mosaic.rain", "Rain", Category.SCENE, "Rain strength 0..1 of the world."),
        e("mosaic.thunder", "Thunder", Category.SCENE, "Thunderstorm strength 0..1."),
        e("mosaic.temperature", "Temperature", Category.SCENE, "Biome temperature at the camera (cold < 0.15 < warm)."),
        e("mosaic.skyColor", "Sky color", Category.SCENE, "The game's sky colour this frame."),
        e("mosaic.fogColor", "Fog color", Category.SCENE, "The game's fog colour this frame."),
        e("mat.output", "Material Output", Category.MATERIAL, "The texture's surface: baked into the albedo and LabPBR _s / _n maps."),
        e("mat.bsdf", "Principled BSDF", Category.MATERIAL, "Base colour, roughness, metallic, specular, emission, height (bump), normal strength, AO."),
        e("mat.texture", "Block Texture", Category.MATERIAL, "The texture this material belongs to, per pixel (or at uv)."),
        e("mat.image", "Image Texture", Category.MATERIAL, "An imported image (roughness map, height map, ...), tiled over the texture."),
        e("mosaic.uv", "UV", Category.INPUT, "Pixel position, 0..1 across the screen."),
        e("mosaic.texel", "Pixel", Category.INPUT, "Pixel position in pixels."),
        e("mosaic.size", "Screen size", Category.INPUT, "Render target width and height in pixels."),
        e("mosaic.time", "Time", Category.INPUT, "Seconds since start (wraps at 3600)."),
        e("mosaic.frame", "Frame", Category.INPUT, "Frame counter."),
        e("mosaic.eye", "Camera", Category.INPUT, "Camera position in scene space."),
        e("mosaic.sceneColor", "Scene color", Category.SCENE, "The ray-traced world image at uv."),
        e("mosaic.sceneDepth", "Scene depth", Category.SCENE, "Primary-ray hit distance; 1e6 means sky."),
        e("mosaic.lut", "LUT texture", Category.SCENE, "texture6.png sampled at uv."),
        e("mosaic.buffer", "Buffer value", Category.SCENE, "vec4 number 'index' of buffer5.bin (edit live)."),
        e("mosaic.push", "Push value", Category.SCENE, "vec4 slot 0..3 of rt.push (edit live)."),
        e("mosaic.trace", "Trace ray", Category.RAY, "Ray query into the scene: t (-1 = miss), pos, hit, inst, prim."),
        e("mosaic.shadow", "Shadow ray", Category.RAY, "1 when nothing blocks the direction, else 0."),
        e("mosaic.position", "Hit position", Category.SURFACE, "Where the pixel's ray hits, relative to the camera (blocks)."),
        e("mosaic.normal", "Surface normal", Category.SURFACE, "Direction the surface faces, rebuilt from depth."),
        e("mosaic.sunDir", "Sun direction", Category.INPUT, "Toward the sun, following the world's time of day."),
        e("mosaic.worldPos", "World position", Category.SURFACE, "World coordinates of the hit: use for waves, patterns, height."),
        e("mosaic.waterMask", "Water mask", Category.SURFACE, "1 where the pixel sees water (its surface or through it), else 0."),
        e("mosaic.waterDepth", "Water thickness", Category.SURFACE, "Blocks of water the view ray crosses before it hits something."),
        e("mosaic.underwater", "Camera underwater", Category.SURFACE, "1 while the camera is inside water."),
        e("mosaic.lavaMask", "Lava mask", Category.SURFACE, "1 where the pixel sees lava."),
        e("mosaic.hitMaterial", "Material id", Category.SURFACE, "Material class of the block the pixel shows (-1 = sky)."),
        e("mosaic.materialMask", "Material mask", Category.SURFACE, "1 where the pixel shows the chosen material (leaves, snow, glass...)."),
        e("mosaic.materialDepth", "Material thickness", Category.SURFACE, "Blocks of the chosen material the view ray crosses (water, glass, leaves)."),
        e("mosaic.skyLight", "Sky light", Category.SURFACE, "Sky light at the surface, 0 (cave) .. 1 (open sky)."),
        e("mosaic.blockLight", "Block light", Category.SURFACE, "Light from torches, lamps and lava at the surface, 0..1."),
        e("mosaic.cameraMaterial", "Camera inside", Category.SURFACE, "Material class around the camera (1 water, 2 lava, 9 snow...)."),
        e("mosaic.const", "Constant", Category.VALUE, "A number or a vector of 2..4 numbers."),
        e("mosaic.swizzle", "Swizzle", Category.VALUE, "Pick or reorder components: xyz, w, rrr..."),
        e("mosaic.vec", "Make vector", Category.VALUE, "Builds a vec2/3/4 from numbers; one input fills every component."),
        e("mosaic.math", "Math", Category.CONVERTER, "Any of 32 operations on a and b (add, power, sine, modulo, snap...), per component, optional clamp."),
        e("mosaic.mapRange", "Map Range", Category.CONVERTER, "Remaps a value from one range to another (linear or smooth), optional clamp."),
        e("mosaic.colorRamp", "Color Ramp", Category.CONVERTER, "Turns a 0..1 value into a color along editable stops."),
        e("mosaic.reroute", "Reroute", Category.CONVERTER, "A wire bend point; passes its input through."),
        e("mosaic.mixColor", "Mix Color", Category.COLOR, "Blends b over a by fac: mix, multiply, screen, overlay, soft light, dodge, burn..."),
        e("mosaic.noise", "Noise Texture", Category.TEXTURE, "Smooth fractal noise: scale, detail (octaves), roughness, distortion. Fac and Color."),
        e("mosaic.voronoi", "Voronoi Texture", Category.TEXTURE, "Cell pattern: distance to the nearest cell point, its random color and position."),
        e("mosaic.whiteNoise", "White Noise", Category.TEXTURE, "A random value (and color) per point, no smoothing."),
        e("mosaic.checker", "Checker Texture", Category.TEXTURE, "Alternating two colors in a 3D checkerboard."),
        e("mosaic.wave", "Wave Texture", Category.TEXTURE, "Bands or rings, optionally distorted by noise."),
        e("mosaic.gradient", "Gradient Texture", Category.TEXTURE, "Linear, quadratic, easing, diagonal, spherical or radial gradient."),
        e("mosaic.add", "Add", Category.MATH, "a + b"),
        e("mosaic.sub", "Subtract", Category.MATH, "a - b"),
        e("mosaic.mul", "Multiply", Category.MATH, "a * b"),
        e("mosaic.div", "Divide", Category.MATH, "a / b"),
        e("mosaic.min", "Min", Category.MATH, "Smaller of a and b."),
        e("mosaic.max", "Max", Category.MATH, "Larger of a and b."),
        e("mosaic.mix", "Mix", Category.MATH, "Blend from a to b by t (lerp)."),
        e("mosaic.clamp", "Clamp", Category.MATH, "a limited to lo..hi."),
        e("mosaic.step", "Step", Category.MATH, "0 where b < edge a, else 1."),
        e("mosaic.neg", "Negate", Category.MATH, "-a"),
        e("mosaic.floor", "Floor", Category.MATH, "Round down."),
        e("mosaic.fract", "Fraction", Category.MATH, "a - floor(a)."),
        e("mosaic.sqrt", "Square root", Category.MATH, "sqrt(a)"),
        e("mosaic.exp", "Exp", Category.MATH, "e to the power a."),
        e("mosaic.abs", "Absolute", Category.MATH, "|a|"),
        e("mosaic.saturate", "Saturate", Category.MATH, "Clamp to 0..1."),
        e("mosaic.dot", "Dot", Category.VECTOR, "Dot product of two vectors of the same size."),
        e("mosaic.cross", "Cross", Category.VECTOR, "Cross product of two vec3."),
        e("mosaic.reflect", "Reflect", Category.VECTOR, "Reflect direction a about normal b (vec3)."),
        e("mosaic.normalize", "Normalize", Category.VECTOR, "Same direction, length 1."),
        e("mosaic.length", "Length", Category.VECTOR, "Length of a vector."),
        e("mosaic.lum", "Luminance", Category.VECTOR, "Brightness of an rgb color."),
        e("mosaic.gt", "Greater", Category.LOGIC, "1 when a > b, else 0."),
        e("mosaic.lt", "Less", Category.LOGIC, "1 when a < b, else 0."));

    private static Entry e(String type, String label, Category c, String help) { return new Entry(type, label, c, help); }

    /** Every entry in palette order (grouped by category). */
    public static List<Entry> all() { return ALL; }

    /** The entry of a type; an unknown type gets a neutral placeholder so a broken file still draws. */
    public static Entry of(String type) {
        for (Entry e : ALL) if (e.type().equals(type)) return e;
        String name = type == null ? "?" : type.substring(type.indexOf('.') + 1);
        return new Entry(type, name, Category.VALUE, "Unknown node type " + type + ".");
    }

    /**
     * Entries matching a free-text query, best first: label or short-name prefix, then substring, then a match in the
     * help text. An empty query returns everything in palette order.
     */
    public static List<Entry> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return ALL;
        record Hit(Entry e, int score, int order) {}
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) {
            Entry e = ALL.get(i);
            String label = e.label().toLowerCase(Locale.ROOT), name = e.shortName().toLowerCase(Locale.ROOT);
            int score;
            if (label.equals(q) || name.equals(q)) score = -1;
            else if (label.startsWith(q) || name.startsWith(q)) score = 0;
            else if (label.contains(q) || name.contains(q)) score = 1;
            else if (e.category().label.toLowerCase(Locale.ROOT).startsWith(q)) score = 2;
            else if (e.help().toLowerCase(Locale.ROOT).contains(q)) score = 3;
            else continue;
            hits.add(new Hit(e, score, i));
        }
        hits.sort(Comparator.comparingInt(Hit::score).thenComparingInt(Hit::order));
        return hits.stream().map(Hit::e).toList();
    }
}
