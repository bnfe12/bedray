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
        PASSES("Passes", 0xFF3A8FA0),
        /** Ready-made renderers as one node of the frame graph (Bedray render: its stages open in the Bedray stages workspace). */
        READY("Ready-made renderers", 0xFF5A6FD0),
        RENDERER("Bedray renderer", 0xFF5A6FD0),
        /** Stages of your own in the renderer graph: named by you, built from nodes, run in Bedray's frame. */
        YOURS("Your stages", 0xFF2F8F5A);

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
        e("bedray.scene", "Scene", Category.RENDERER, "What the renderer sees: distance, lights, particles, weather, the hand."),
        e("bedray.materials", "Materials", Category.RENDERER, "Surface options of every block: optical glass, automatic normals, self shadow, waving foliage, water."),
        e("bedray.sky", "Sun and sky", Category.RENDERER, "Size and tilt of the sun, how much baked light fills the shadows."),
        e("bedray.pathTracer", "Path tracer", Category.RENDERER, "The Slang path tracer: samples per pixel, bounces, distance, accumulation, caustics."),
        e("bedray.restirDi", "ReSTIR DI", Category.RENDERER, "ReSTIR direct light: torch samples kept over frames (temporal) and shared with neighbours (spatial)."),
        e("bedray.restirPt", "ReSTIR PT", Category.RENDERER, "ReSTIR path reuse: bounced light of the first diffuse bounce kept over frames and pooled with neighbours. About 0.6 GB at 1080p."),
        e("bedray.radianceCascades", "Radiance Cascades", Category.RENDERER, "A radiance-cascade field as the diffuse continuation of the paths (experimental, applies after a restart)."),
        e("bedray.rays", "Hybrid ray effects", Category.RENDERER, "The fast mode: ray traced shadows, reflections, AO, block light (with its own ReSTIR) and one-bounce GI on top of raster."),
        e("bedray.fog", "Volumetric fog", Category.RENDERER, "Light shafts and fog: density, in-scattering, distance."),
        e("bedray.denoiser", "Denoiser", Category.RENDERER, "NRD or DLSS Ray Reconstruction turns the noisy path tracer into a clean image."),
        e("bedray.exposure", "Exposure and tone", Category.RENDERER, "Auto exposure, exposure in EV and the tone curve that maps light to the screen."),
        e("bedray.post", "Post effects", Category.RENDERER, "Vibrant visuals, vignette and film grain of Bedray."),
        e("slang.module", "Slang module", Category.RENDERER, "A module of Bedray's Slang pack (map view): its imports are its wires."),
        e("slang.pack", "Bedray frame (passes)", Category.RENDERER, "Every pass entry point of Bedray's Slang pack (map view)."),
        e("bedray.voxels", "Voxels", Category.RENDERER, "The voxel world the tracers walk: distance, memory, quality profile, bounced light and glass, and which scene renderer runs."),
        e("bedray.raster", "Raster (no ray tracing)", Category.RENDERER, "Minecraft's own lighting with Bedray's post: the mode when nothing traces rays."),
        e("bedray.upscaler", "Upscaler", Category.RENDERER, "Render at a lower resolution and upscale: built-in Bilinear, FSR 1 and NIS on every GPU, FSR 2/3/4, XeSS and DLSS with their libraries."),
        e("user.material", "Your material (path tracer)", Category.YOURS, "Every surface Bedray's path tracer reaches - the camera hit and every bounce - passes through it before it is shaded: build from nodes how its albedo, roughness, metalness and emission change with its world position, normal and material id (wet stone in the rain, polished floors, glowing ores). Runs in the path tracer once you Apply to Bedray."),
        e("user.light", "Your light (path tracer)", Category.YOURS, "Every direct-light sample of Bedray's path tracer - sun, moon, block emitters - passes its radiance through your graph, with the direction, world position and light kind (0 block, 1 sun, 2 moon): a red sun at sunset in all bounced light, a weaker moon, coloured torches. Applies to Bedray's path tracer once you Apply to Bedray."),
        e("user.skyLight", "Your sky light (path tracer)", Category.YOURS, "The sky radiance every path of Bedray's path tracer collects when it escapes, with the direction and bounce number (0 = what the camera sees): the light the sky gives to the world, not just its look."),
        e("user.medium", "Your medium (path tracer)", Category.YOURS, "Every segment a path of Bedray's path tracer flies, in air and in water: add in-scattered light and multiply a transmittance from the segment's start, direction, length and whether it is under water - fog, haze and coloured water inside the light transport."),
        e("user.emission", "Your emission (path tracer)", Category.YOURS, "The emission a path of Bedray's path tracer sees on glowing surfaces (lava, lamps, emissive textures), with the world position and bounce (0 = the camera): brighter lava to the eye, softer lamps in reflections."),
        e("user.bounce", "Your bounce (path tracer)", Category.YOURS, "The weight a path carries on after each BSDF sample (lobe 1 diffuse, 2 specular, 4 transmission) with the new direction, normal and depth: artistic control of bounced light (more or less colour bleeding, stronger reflections)."),
        e("user.roulette", "Your roulette (path tracer)", Category.YOURS, "The Russian roulette survival probability of a path at a depth: lower ends long paths sooner (faster, noisier), higher keeps them (cleaner, slower). Stays unbiased."),
        e("user.pathOutput", "Your path output (path tracer)", Category.YOURS, "The radiance one path brings back, before Bedray's firefly clamp and the denoiser, with its depth and surface hits: debug views (depth, hits), your own firefly control."),
        e("user.post", "Your post stage", Category.YOURS, "A stage of your own, named as you like: double-click it and build its colour change from nodes (Bedray's functions included). It runs in Bedray's frame after the tone curve and Bedray's looks, once you Apply to Bedray."),
        e("user.hdr", "Your HDR stage", Category.YOURS, "A stage of your own on the scene-linear colour after exposure and before the tone curve: grading, your own exposure or colour fog, built from nodes. Runs in Bedray's frame once you Apply to Bedray."),
        e("user.sky", "Your sky look", Category.YOURS, "Your own look of the sky an eye or a glossy reflection sees (Bedray keeps its light dome for lighting), built from nodes over the direction and Bedray's sky colour."),
        e("bedray.stageMap", "Stage inside", Category.RENDERER, "The end of a stage's inside: its settings and the functions it is made of."),
        e("bedray.output", "Bedray frame", Category.RENDERER, "The frame Bedray shows: every stage wired into it is on, the rest are off."),
        e("mosaic.pass", "Buffer (pass)", Category.PASSES, "Writes its input into a named buffer as its own pass, at full, 1/2, 1/4 or 1/8 size (blur, bloom)."),
        e("mosaic.bufferIn", "Read buffer", Category.PASSES, "Reads a buffer written by a Buffer node, at any uv (offsets for blur kernels)."),
        e("mosaic.blur", "Blur buffer", Category.PASSES, "Reads a buffer softened by a 3x3 Gaussian (9 bilinear taps); radius in the buffer's pixels. Chain Buffer nodes at 1/2, 1/4, 1/8 for wide blurs."),
        e("mosaic.kawase", "Kawase blur", Category.PASSES, "Reads a buffer with one Kawase step (4 diagonal taps); radius grows per pass (0, 1, 2, 3...): the cheap wide blur behind bloom chains."),
        e("mosaic.reproject", "Reproject", Category.PASSES, "Where this pixel's surface was on screen last frame (uv): read Previous frame there for temporal anti-aliasing, motion blur and stable trails while the camera moves."),
        e("mosaic.repeat", "Repeat", Category.CONVERTER, "A loop as a node: runs one of your functions vec4 f(vec4 acc, float i) 1 to 64 times, each time on the last result (sums, marches, kernels, iterations)."),
        e("mosaic.taa", "Temporal AA", Category.PASSES, "Blends the previous frame, reprojected and clamped to the 3x3 neighbourhood of this frame, into the scene colour (or a Buffer): smooth edges and calm noise without ghost trails."),
        e("mosaic.bufferPrev", "Buffer (last frame)", Category.PASSES, "Reads a buffer as it was at the end of the last frame: feedback loops (a buffer may read its own last frame), trails, simulations."),
        e("mosaic.accumulate", "Accumulate", Category.PASSES, "Temporal accumulation: blends this frame's value into the buffer's last frame where the same surface was (reprojected), up to 'frames' frames; resets on disocclusion. Wire it straight into the Buffer it names (path tracing, GI, soft shadows)."),
        e("mosaic.average", "Average (buffer)", Category.PASSES, "The average of a whole buffer (8 x 8 taps over its central area, spread 0..1, the centre weighted by centerWeight): the scene brightness for auto exposure, the mean colour of a frame."),
        e("mosaic.atrous", "Denoise (a-trous)", Category.PASSES, "One edge-aware a-trous wavelet step over a buffer: blurs noise but stops at geometry edges and brightness jumps. Chain steps 1, 2, 4, 8 through Buffers for a strong, sharp denoise."),
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
        e("bedray.render", "Bedray render", Category.READY, "The frame Bedray's own renderer made (path tracer, ReSTIR, cascades, denoiser...), as one node: wire your passes after it. Double-click it to open its stages and settings (Bedray stages)."),
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
        e("mosaic.vectorMath", "Vector Math", Category.CONVERTER, "Blender's Vector Math: add, cross, project, reflect, dot, distance, length, scale, normalize, snap and more (pick the operation)."),
        e("mosaic.separate", "Separate XYZW", Category.CONVERTER, "Splits a vector or colour into its components: x (r) on the main output, y, z, w beside it."),
        e("mosaic.smoothstep", "Smoothstep", Category.CONVERTER, "0 below edge 0, 1 above edge 1, a smooth S between: soft masks and fades."),
        e("mosaic.hueSat", "Hue / Saturation / Value", Category.COLOR, "Shifts the hue (0.5 keeps it), scales saturation and value; fac blends it in."),
        e("mosaic.brightContrast", "Bright / Contrast", Category.COLOR, "Blender's Bright/Contrast: brightness adds, contrast spreads around grey."),
        e("mosaic.gamma", "Gamma", Category.COLOR, "Raises the colour to a power (1 keeps it; above 1 darkens the mids)."),
        e("mosaic.invert", "Invert", Category.COLOR, "1 - colour, blended in by fac (alpha kept)."),
        e("mosaic.blackbody", "Blackbody", Category.COLOR, "The colour of a glowing body at a temperature in kelvin: 1900 candle, 3000 lamp, 6500 daylight."),
        e("mosaic.fresnel", "Fresnel", Category.CONVERTER, "How much a surface reflects at this angle (Schlick), from its normal, the view direction and the IOR."),
        e("mosaic.mapping", "Mapping", Category.CONVERTER, "Blender's Mapping (point): scale, rotate (radians, XYZ) and move a vector, for textures and coordinates."),
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
