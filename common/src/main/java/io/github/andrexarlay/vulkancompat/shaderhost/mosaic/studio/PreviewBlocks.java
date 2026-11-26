package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * The preview's block atlas: the material-class tiles (for worlds without block names and the far land) followed by the
 * top, side and bottom texture of every block of the loaded world, taken from the block's own model and coloured like
 * the game (grass and foliage from the colour maps at a plains biome, water). Textures come from the resource packs the
 * game has switched on (options.txt, in its order, Mosaic Materials included) before the vanilla jar, so a material you
 * edit shows on the blocks of the preview world.
 */
final class PreviewBlocks {

    private PreviewBlocks() {}

    static final int COLS = 64;

    /**
     * argb rows of w x h (tiles of 16 x 16, each stored bottom-up, then larger textures top-down), per block: top, side,
     * bottom tile, flags (1 textured cube, 2 drawn by its model's quads), and the mesh: quads (5 x vec4 each: corner,
     * edge 1, edge 2 in volume cells with the atlas rect packed in their w; uv origin and edges; rect height, flags) and per
     * cell (start << 8 | count) into the quad id list that follows the cells.
     */
    record Atlas(int[] argb, int w, int h, int[] info, String signature, float[] quads, int[] mesh, int[] biome, int[] clouds, int[] rain, int[] snow) {
        Atlas(int[] argb, int w, int h, int[] info, String signature) { this(argb, w, h, info, signature, null, null, null, null, null, null); }

        Atlas(int[] argb, int w, int h, int[] info, String signature, float[] quads, int[] mesh) { this(argb, w, h, info, signature, quads, mesh, null, null, null, null); }

        /** The rect (x, y, w, h) of the game's clouds.png in the atlas, or null. */
        int[] cloudRect() { return clouds; }

        /** Per column of the volume: grass, foliage and water colour of its biome (null: the textures carry one biome's colour). */
        boolean hasBiomes() { return biome != null && biome.length > 0; }

        boolean hasMesh() { return mesh != null && quads != null && quads.length > 0; }
    }

    /** A place textures are read from: a resource pack folder or zip, or a jar. */
    private record Pack(Path path, boolean zip) {}

    private static final Map<Path, ZipFile> ZIPS = new HashMap<>();

    private static synchronized byte[] read(Pack p, String entry) {
        try {
            if (p.zip()) {
                ZipFile z = ZIPS.get(p.path());
                if (z == null) ZIPS.put(p.path(), z = new ZipFile(p.path().toFile()));
                var e = z.getEntry(entry);
                return e == null ? null : z.getInputStream(e).readAllBytes();
            }
            Path f = p.path().resolve(entry);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Resource packs switched on in the game (options.txt resourcePacks, last one wins), then the vanilla jar. */
    static List<Pack> packs(Path gameDir) {
        List<Pack> out = new ArrayList<>();
        if (gameDir != null) {
            try {
                for (String line : Files.readAllLines(gameDir.resolve("options.txt"))) {
                    if (!line.startsWith("resourcePacks:")) continue;
                    var arr = JsonParser.parseString(line.substring("resourcePacks:".length())).getAsJsonArray();
                    for (int i = arr.size() - 1; i >= 0; i--) {
                        String id = arr.get(i).getAsString();
                        if (!id.startsWith("file/")) continue;
                        Path p = gameDir.resolve("resourcepacks").resolve(id.substring(5));
                        if (Files.isDirectory(p)) out.add(new Pack(p, false));
                        else if (Files.isRegularFile(p)) out.add(new Pack(p, true));
                    }
                }
            } catch (Exception ignored) {
                // no options.txt: vanilla only
            }
            Path mosaic = gameDir.resolve("resourcepacks").resolve(StudioLibrary.MATERIAL_PACK);
            if (Files.isDirectory(mosaic) && out.stream().noneMatch(p -> p.path().equals(mosaic))) out.add(0, new Pack(mosaic, false));
        }
        try {
            for (StudioLibrary.Source v : StudioLibrary.vanillaJars()) {
                out.add(new Pack(v.path(), Files.isRegularFile(v.path())));
                break;
            }
        } catch (RuntimeException ignored) {
            // no vanilla jar found
        }
        return out;
    }

    /** What the atlas depends on: the packs, their change times (Mosaic Materials included) and options.txt. */
    static String signature(Path gameDir, List<String> blocks) {
        StringBuilder sb = new StringBuilder().append(blocks == null ? 0 : blocks.hashCode()).append('|');
        for (Pack p : packs(gameDir)) sb.append(p.path()).append('@').append(newest(p.path())).append('|');
        if (gameDir != null) sb.append(newest(gameDir.resolve("options.txt")));
        return sb.toString();
    }

    private static long newest(Path p) {
        try {
            if (!Files.isDirectory(p)) return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0;
            try (var s = Files.walk(p, 8)) {
                return s.filter(Files::isRegularFile).mapToLong(f -> {
                    try {
                        return Files.getLastModifiedTime(f).toMillis();
                    } catch (Exception e) {
                        return 0;
                    }
                }).max().orElse(0);
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static final Map<String, Atlas> CACHE = new LinkedHashMap<>();

    /** The atlas for these block names (null: material tiles only) with the packs of this game folder. */
    static synchronized Atlas atlas(List<String> blocks, Path gameDir) {
        String sig = signature(gameDir, blocks) + (perColumnTint ? "|biomes" : "");
        Atlas cached = CACHE.get(sig);
        if (cached != null) return cached;
        List<Pack> packs = packs(gameDir);
        int[] legacy = PreviewWorld.atlas(); // TILES tiles in one row (bottom-up)
        List<int[]> tiles = new ArrayList<>();
        for (int t = 0; t < PreviewWorld.TILES; t++) {
            int[] tile = new int[256];
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) tile[y * 16 + x] = legacy[y * PreviewWorld.TILES * 16 + t * 16 + x];
            tiles.add(tile);
        }
        Map<String, Integer> byKey = new HashMap<>();
        int n = blocks == null ? 0 : blocks.size();
        int[] info = new int[Math.max(1, n) * 4];
        for (int b = 1; b < n; b++) {
            String name = baseName(blocks.get(b));
            String[] faces = faces(packs, blocks.get(b));
            if (faces == null) continue;
            int kind = perColumnTint ? tintKind(name) : 0;
            int tint = kind != 0 ? 0 : tint(packs, name);
            int[] ids = new int[3];
            for (int f = 0; f < 3; f++) {
                boolean grassSide = f == 1 && name.endsWith("grass_block");
                String key = faces[f] + "#" + Integer.toHexString(grassSide ? -1 : tint);
                Integer id = byKey.get(key);
                if (id == null) {
                    // every frame of an animated texture (water, lava, fire...) as consecutive tiles, in the .mcmeta order
                    int[] anim = animation(packs, faces[f]);
                    int base = tiles.size(), count = 0;
                    for (int fr = 0; fr < anim.length - 1; fr++) {
                        int[] tile = tile(packs, faces[f], grassSide ? 0 : tint, grassSide ? tint(packs, "minecraft:grass_block") : 0, anim[fr + 1]);
                        if (tile == null) break;
                        tiles.add(tile);
                        count++;
                    }
                    if (count == 0) {
                        ids = null;
                        break;
                    }
                    // tile | frames << 20 | frame time (ticks) << 28
                    id = base | (count > 1 ? Math.min(255, count) << 20 | Math.min(15, anim[0]) << 28 : 0);
                    byKey.put(key, id);
                }
                ids[f] = id;
            }
            if (ids == null) continue;
            info[b * 4] = ids[0];
            info[b * 4 + 1] = ids[1];
            info[b * 4 + 2] = ids[2];
            // 4 grass, 8 foliage, 16 water colour of the column's biome; 32: the top face only (grass block)
            info[b * 4 + 3] = 1 | (kind == 1 ? 4 : kind == 2 ? 8 : kind == 3 ? 16 : 0) | (name.endsWith("grass_block") ? 32 : 0);
        }
        int rows = Math.max(1, (tiles.size() + COLS - 1) / COLS), w = COLS * 16, h = rows * 16;
        int[] argb = new int[w * h];
        for (int t = 0; t < tiles.size(); t++) {
            int tx = (t % COLS) * 16, ty = (t / COLS) * 16;
            int[] tile = tiles.get(t);
            for (int y = 0; y < 16; y++) System.arraycopy(tile, y * 16, argb, (ty + y) * w + tx, 16);
        }
        Atlas a = new Atlas(argb, w, h, info, sig);
        if (CACHE.size() > 4) CACHE.clear();
        CACHE.put(sig, a);
        return a;
    }

    /** Top, side and bottom texture ids of a block from its blockstate and model, or null (no model: material tile). */
    static String[] faces(List<Pack> packs, String state) {
        String block = baseName(state);
        String ns = block.contains(":") ? block.substring(0, block.indexOf(':')) : "minecraft";
        String name = block.substring(block.indexOf(':') + 1);
        if (name.equals("water")) return new String[] {"minecraft:block/water_still", "minecraft:block/water_still", "minecraft:block/water_still"};
        if (name.equals("lava")) return new String[] {"minecraft:block/lava_still", "minecraft:block/lava_still", "minecraft:block/lava_still"};
        List<String[]> vs = variantsOf(packs, ns, name, props(state));
        String model = vs.isEmpty() ? null : vs.get(0)[0];
        if (model == null) return null;
        Map<String, String> tex = new LinkedHashMap<>();
        Map<String, String> faceTex = new HashMap<>();
        String id = model;
        boolean elements = false;
        for (int depth = 0; depth < 12 && id != null; depth++) {
            String mns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String p = id.substring(id.indexOf(':') + 1);
            if (p.startsWith("builtin/")) break;
            byte[] json = readFirst(packs, "assets/" + mns + "/models/" + p + ".json");
            if (json == null) break;
            JsonObject o;
            try {
                o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                break;
            }
            if (o.has("textures")) for (var e : o.getAsJsonObject("textures").entrySet()) tex.putIfAbsent(e.getKey(), texRef(e.getValue()));
            if (!elements && o.has("elements")) {
                elements = true;
                for (JsonElement el : o.getAsJsonArray("elements")) {
                    if (!el.getAsJsonObject().has("faces")) continue;
                    for (var fe : el.getAsJsonObject().getAsJsonObject("faces").entrySet())
                        faceTex.putIfAbsent(fe.getKey(), fe.getValue().getAsJsonObject().get("texture").getAsString());
                }
            }
            id = o.has("parent") ? o.get("parent").getAsString() : null;
        }
        String up = resolve(faceTex.get("up"), tex), down = resolve(faceTex.get("down"), tex);
        String side = resolve(faceTex.getOrDefault("north", faceTex.getOrDefault("south", faceTex.get("east"))), tex);
        if (side == null) side = resolve(faceTex.get("west"), tex);
        String any = side != null ? side : up != null ? up : down;
        if (any == null) any = resolve("#particle", tex);
        if (any == null) return null;
        return new String[] {norm(up != null ? up : any), norm(side != null ? side : any), norm(down != null ? down : any)};
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom]" -> "minecraft:oak_stairs". */
    static String baseName(String state) { return state.indexOf('[') > 0 ? state.substring(0, state.indexOf('[')) : state; }

    /** The properties of a state name (key -> value), empty without them. */
    static Map<String, String> props(String state) {
        Map<String, String> out = new HashMap<>();
        int a = state.indexOf('['), b = state.lastIndexOf(']');
        if (a < 0 || b < a) return out;
        for (String kv : state.substring(a + 1, b).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) out.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return out;
    }

    /** Whether a blockstate variant key ("facing=east,half=bottom") or multipart "when" holds for these properties. */
    private static boolean matches(String key, Map<String, String> p) {
        if (key.isEmpty() || key.equals("normal")) return true;
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), v = kv.substring(eq + 1);
            if (!p.containsKey(k)) continue; // a property the save does not carry: any value
            boolean any = false;
            for (String alt : v.split("\\|")) any |= alt.equals(p.get(k));
            if (!any) return false;
        }
        return true;
    }

    private static boolean when(JsonObject w, Map<String, String> p) {
        if (w.has("OR")) {
            for (JsonElement e : w.getAsJsonArray("OR")) if (when(e.getAsJsonObject(), p)) return true;
            return false;
        }
        if (w.has("AND")) {
            for (JsonElement e : w.getAsJsonArray("AND")) if (!when(e.getAsJsonObject(), p)) return false;
            return true;
        }
        StringBuilder key = new StringBuilder();
        for (var e : w.entrySet()) key.append(key.length() == 0 ? "" : ",").append(e.getKey()).append('=').append(e.getValue().getAsString());
        if (p.isEmpty()) return false; // without properties only unconditional parts are drawn
        return matches(key.toString(), p);
    }

    /** A model texture entry: a name, or (26.x) an object with its sprite. */
    private static String texRef(JsonElement v) {
        if (v.isJsonPrimitive()) return v.getAsString();
        if (v.isJsonObject() && v.getAsJsonObject().has("sprite")) return v.getAsJsonObject().get("sprite").getAsString();
        return null;
    }

    private static String norm(String t) { return t.contains(":") ? t : "minecraft:" + t; }

    private static String resolve(String ref, Map<String, String> tex) {
        for (int i = 0; i < 10 && ref != null && ref.startsWith("#"); i++) ref = tex.get(ref.substring(1));
        return ref;
    }

    /** The model of the block's default-looking variant (axis=y, facing north, lower half...), or its first multipart model. */
    private static String modelOf(List<Pack> packs, String ns, String name) {
        byte[] json = readFirst(packs, "assets/" + ns + "/blockstates/" + name + ".json");
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("variants")) {
                JsonObject v = o.getAsJsonObject("variants");
                String pick = null;
                for (String k : v.keySet()) {
                    if (pick == null) pick = k;
                    if (k.isEmpty() || k.contains("axis=y") || k.contains("half=bottom") && !k.contains("open=true") || k.contains("snowy=false")) {
                        pick = k;
                        break;
                    }
                }
                return modelIn(v.get(pick));
            }
            if (o.has("multipart"))
                for (JsonElement part : o.getAsJsonArray("multipart")) {
                    String m = modelIn(part.getAsJsonObject().get("apply"));
                    if (m != null) return m;
                }
        } catch (Exception ignored) {
            // unreadable blockstate: material tile
        }
        return null;
    }

    private static String modelIn(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonArray()) e = e.getAsJsonArray().size() == 0 ? null : e.getAsJsonArray().get(0);
        return e != null && e.isJsonObject() && e.getAsJsonObject().has("model") ? e.getAsJsonObject().get("model").getAsString() : null;
    }

    private static byte[] readFirst(List<Pack> packs, String entry) {
        for (Pack p : packs) {
            byte[] b = read(p, entry);
            if (b != null) return b;
        }
        return null;
    }

    private static BufferedImage image(List<Pack> packs, String textureId) {
        String ns = textureId.substring(0, textureId.indexOf(':')), p = textureId.substring(textureId.indexOf(':') + 1);
        byte[] b = readFirst(packs, "assets/" + ns + "/textures/" + p + ".png");
        try {
            return b == null ? null : ImageIO.read(new ByteArrayInputStream(b));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The animation of a texture: {frame time in ticks, frame index...} from its .mcmeta (frames list, or every frame of
     * the strip in order); {1, 0} for a still texture.
     */
    static int[] animation(List<Pack> packs, String textureId) {
        String ns = textureId.substring(0, textureId.indexOf(':')), p = textureId.substring(textureId.indexOf(':') + 1);
        BufferedImage img = image(packs, textureId);
        int count = img == null || img.getHeight() <= img.getWidth() ? 1 : img.getHeight() / img.getWidth();
        if (count <= 1) return new int[] {1, 0};
        int frametime = 1;
        List<Integer> order = new ArrayList<>();
        byte[] meta = readFirst(packs, "assets/" + ns + "/textures/" + p + ".png.mcmeta");
        if (meta != null) {
            try {
                JsonObject a = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animation");
                if (a.has("frametime")) frametime = Math.max(1, a.get("frametime").getAsInt());
                if (a.has("frames"))
                    for (JsonElement e : a.getAsJsonArray("frames"))
                        order.add(e.isJsonObject() ? e.getAsJsonObject().get("index").getAsInt() : e.getAsInt());
            } catch (Exception ignored) {
                // every frame in order
            }
        }
        if (order.isEmpty()) for (int i = 0; i < count; i++) order.add(i);
        int n = Math.min(255, order.size());
        int[] out = new int[n + 1];
        out[0] = frametime;
        for (int i = 0; i < n; i++) out[i + 1] = Math.clamp(order.get(i), 0, count - 1);
        return out;
    }

    private static int[] tile(List<Pack> packs, String textureId, int tint, int overlayTint) { return tile(packs, textureId, tint, overlayTint, 0); }

    /** One 16 x 16 tile (bottom-up) of an animation frame, tinted; for the grass side the tinted overlay over the dirt side. */
    private static int[] tile(List<Pack> packs, String textureId, int tint, int overlayTint, int frame) {
        BufferedImage img = image(packs, textureId);
        if (img == null) return null;
        BufferedImage over = overlayTint != 0 ? image(packs, "minecraft:block/grass_block_side_overlay") : null;
        int size = Math.min(img.getWidth(), img.getHeight()), y0 = Math.min(frame * size, Math.max(0, img.getHeight() - size));
        int[] out = new int[256];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int argb = img.getRGB(x * img.getWidth() / 16, y0 + y * size / 16);
                if (tint != 0) argb = mul(argb, tint);
                if (over != null) {
                    int o = over.getRGB(x * over.getWidth() / 16, y * Math.min(over.getWidth(), over.getHeight()) / 16);
                    if ((o >>> 24) > 128) argb = mul(o, overlayTint);
                }
                out[(15 - y) * 16 + x] = argb;
            }
        return out;
    }

    private static int mul(int argb, int rgb) {
        int r = ((argb >> 16) & 255) * ((rgb >> 16) & 255) / 255, g = ((argb >> 8) & 255) * ((rgb >> 8) & 255) / 255, b = (argb & 255) * (rgb & 255) / 255;
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** How a block takes its biome's colour: 0 none, 1 grass, 2 foliage, 3 water (fixed-colour leaves are 0: tint() bakes them). */
    static int tintKind(String block) {
        block = baseName(block);
        String n = block.substring(block.indexOf(':') + 1);
        if (n.equals("water")) return 3;
        if (n.equals("grass_block") || n.equals("short_grass") || n.equals("tall_grass") || n.contains("fern") || n.equals("sugar_cane") || n.equals("bush"))
            return 1;
        if (n.equals("oak_leaves") || n.equals("jungle_leaves") || n.equals("acacia_leaves") || n.equals("dark_oak_leaves") || n.equals("mangrove_leaves")
            || n.equals("vine")) return 2;
        return 0;
    }

    /** Set while building an atlas for a world with biomes: tinted blocks keep grey textures and the shader colours them per column. */
    private static boolean perColumnTint;

    private static final Map<String, int[]> BIOME_COLOURS = new HashMap<>();

    /** Grass, foliage and water colour of a biome from its worldgen JSON (temperature, downfall, effects), like the game. */
    static int[] biomeColours(List<Pack> packs, String biome) {
        return BIOME_COLOURS.computeIfAbsent(biome, b -> {
            double temp = biomeTemperature, down = biomeDownfall;
            Integer grass = null, foliage = null, water = null;
            String modifier = "";
            String ns = b.contains(":") ? b.substring(0, b.indexOf(':')) : "minecraft", name = b.substring(b.indexOf(':') + 1);
            byte[] json = readFirst(packs, "data/" + ns + "/worldgen/biome/" + name + ".json");
            if (json != null) {
                try {
                    JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("temperature")) temp = o.get("temperature").getAsDouble();
                    if (o.has("downfall")) down = o.get("downfall").getAsDouble();
                    JsonObject fx = o.has("effects") ? o.getAsJsonObject("effects") : new JsonObject();
                    if (fx.has("grass_color")) grass = colour(fx.get("grass_color"));
                    if (fx.has("foliage_color")) foliage = colour(fx.get("foliage_color"));
                    if (fx.has("water_color")) water = colour(fx.get("water_color"));
                    if (fx.has("grass_color_modifier")) modifier = fx.get("grass_color_modifier").getAsString();
                } catch (Exception ignored) {
                    // defaults
                }
            }
            int g = grass != null ? grass : colormap(packs, "minecraft:colormap/grass", temp, down, 0x91BD59);
            if (modifier.equals("swamp")) g = 0x6A7039;
            else if (modifier.equals("dark_forest")) g = ((g & 0xFEFEFE) + 0x28340A) >> 1;
            int f = foliage != null ? foliage : colormap(packs, "minecraft:colormap/foliage", temp, down, 0x77AB2F);
            return new int[] {g, f, water != null ? water : 0x3F76E4, (int) Math.round(temp * 1000) + 10000};
        });
    }

    private static Integer colour(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsInt() & 0xFFFFFF;
        String s = e.getAsString().trim();
        return s.startsWith("#") ? Integer.parseInt(s.substring(1), 16) : Integer.parseInt(s);
    }

    private static int colormap(List<Pack> packs, String map, double temp, double down, int fallback) {
        BufferedImage img = image(packs, map);
        if (img == null) return fallback;
        double t = Math.clamp(temp, 0, 1), d = Math.clamp(down, 0, 1) * t;
        return img.getRGB((int) ((1 - t) * (img.getWidth() - 1)), (int) ((1 - d) * (img.getHeight() - 1))) & 0xFFFFFF;
    }

    /** Plains: temperature 0.8, downfall 0.4. */
    static double biomeTemperature = 0.8, biomeDownfall = 0.4;

    /** The game's tint of a block at the preview's biome (0: none). */
    static int tint(List<Pack> packs, String block) {
        block = baseName(block);
        String n = block.substring(block.indexOf(':') + 1);
        if (n.equals("water")) return 0x3F76E4;
        if (n.equals("spruce_leaves")) return 0x619961;
        if (n.equals("birch_leaves")) return 0x80A755;
        if (n.equals("lily_pad")) return 0x208030;
        boolean grass = n.equals("grass_block") || n.equals("short_grass") || n.equals("tall_grass") || n.contains("fern") || n.equals("sugar_cane")
            || n.equals("bush");
        boolean foliage = n.equals("oak_leaves") || n.equals("jungle_leaves") || n.equals("acacia_leaves") || n.equals("dark_oak_leaves")
            || n.equals("mangrove_leaves") || n.equals("vine");
        if (!grass && !foliage) return 0;
        BufferedImage map = image(packs, grass ? "minecraft:colormap/grass" : "minecraft:colormap/foliage");
        if (map == null) return grass ? 0x91BD59 : 0x77AB2F;
        double t = Math.clamp(biomeTemperature, 0, 1), d = Math.clamp(biomeDownfall, 0, 1) * t;
        int x = (int) ((1 - t) * (map.getWidth() - 1)), y = (int) ((1 - d) * (map.getHeight() - 1));
        return map.getRGB(x, y) & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ models and entities as quads

    /** One textured quad of a model in 0..1 block units: corner p0, corners p1 (s = 1) and p3 (t = 1), uv at p0 and its steps. */
    record Q(double[] p0, double[] e1, double[] e2, double[] uv0, double[] du, double[] dv, String texture, int tint) {}

    /** A block's model as quads (null when it is a plain full cube, drawn by the cube path). */
    static List<Q> modelQuads(List<Pack> packs, String state) {
        String block = baseName(state);
        String ns = block.contains(":") ? block.substring(0, block.indexOf(':')) : "minecraft";
        String name = block.substring(block.indexOf(':') + 1);
        if (name.equals("water") || name.equals("lava")) return null;
        List<String[]> variants = variantsOf(packs, ns, name, props(state));
        if (variants.isEmpty()) return null;
        List<Q> all = new ArrayList<>();
        boolean allCubes = true;
        for (String[] variant : variants) {
            List<Q> part = variantQuads(packs, block, variant);
            if (part == null) continue; // a plain unrotated cube
            if (part.isEmpty()) return null;
            allCubes = false;
            all.addAll(part);
        }
        if (allCubes && variants.size() == 1) return null;
        if (allCubes) return null;
        return all.isEmpty() ? null : all;
    }

    /** The quads of one model variant; null when it is a plain, unrotated full cube (the cube path draws it). */
    private static List<Q> variantQuads(List<Pack> packs, String block, String[] variant) {
        Map<String, String> tex = new LinkedHashMap<>();
        com.google.gson.JsonArray elements = null;
        String id = variant[0];
        for (int depth = 0; depth < 12 && id != null; depth++) {
            String mns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String p = id.substring(id.indexOf(':') + 1);
            if (p.startsWith("builtin/")) break;
            byte[] json = readFirst(packs, "assets/" + mns + "/models/" + p + ".json");
            if (json == null) break;
            JsonObject o;
            try {
                o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                break;
            }
            if (o.has("textures")) for (var e : o.getAsJsonObject("textures").entrySet()) tex.putIfAbsent(e.getKey(), texRef(e.getValue()));
            if (elements == null && o.has("elements")) elements = o.getAsJsonArray("elements");
            id = o.has("parent") ? o.get("parent").getAsString() : null;
        }
        if (elements == null || elements.isEmpty()) return null;
        boolean cube = true; // every element a full cube (grass block: the dirt and its overlay): the cube path draws it
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            double[] f = vec(e.getAsJsonArray("from")), t = vec(e.getAsJsonArray("to"));
            if (e.has("rotation") || f[0] != 0 || f[1] != 0 || f[2] != 0 || t[0] != 16 || t[1] != 16 || t[2] != 16) cube = false;
        }
        int rx = Integer.parseInt(variant[1]), ry = Integer.parseInt(variant[2]);
        if (cube && rx == 0 && ry == 0) return null; // a rotated cube (a log lying along x) is drawn by its quads
        int kind = perColumnTint ? tintKind(block) : 0;
        int tint = kind != 0 ? -kind : tint(packs, block); // < 0: coloured per column by the shader (grass, foliage)
        List<Q> out = new ArrayList<>();
        java.util.Set<String> planes = new java.util.HashSet<>(); // a face and its back on the same rectangle: once (both sides are drawn)
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            if (!e.has("faces")) continue;
            double[] f = vec(e.getAsJsonArray("from")), t = vec(e.getAsJsonArray("to"));
            JsonObject rot = e.has("rotation") ? e.getAsJsonObject("rotation") : null;
            for (var fe : e.getAsJsonObject("faces").entrySet()) {
                JsonObject face = fe.getValue().getAsJsonObject();
                String texture = resolve(face.get("texture").getAsString(), tex);
                double[][] p = BlockModels.corners(fe.getKey(), f, t);
                if (texture == null || p == null) continue;
                if (rot != null) rotate(p, vec(rot.getAsJsonArray("origin")), rot.get("angle").getAsDouble(), rot.get("axis").getAsString());
                if (rx != 0) rotate(p, new double[] {8, 8, 8}, -rx, "x");
                if (ry != 0) rotate(p, new double[] {8, 8, 8}, -ry, "y");
                double[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : BlockModels.defaultUv(fe.getKey(), f, t);
                var q = new BlockModels.Quad(p, uv[0], uv[1], uv[2], uv[3], face.has("rotation") ? face.get("rotation").getAsInt() : 0,
                    texture, fe.getKey(), null);
                double[] a = BlockModels.uvAt(q, 0, 0), b = BlockModels.uvAt(q, 1, 0), c = BlockModels.uvAt(q, 0, 1);
                double[] p0 = scale(p[0]), p1 = scale(p[1]), p3 = scale(p[3]);
                boolean tinted = face.has("tintindex") && tint != 0;
                double[][] cs = {p0, p1, p3, add(p1, sub(p3, p0))};
                java.util.List<String> pts = new java.util.ArrayList<>();
                for (double[] cc : cs) pts.add(Math.round(cc[0] * 1000) + "," + Math.round(cc[1] * 1000) + "," + Math.round(cc[2] * 1000));
                java.util.Collections.sort(pts);
                if (!planes.add(String.join(";", pts) + "|" + texture)) continue;
                out.add(new Q(p0, sub(p1, p0), sub(p3, p0), new double[] {a[0] / 16, a[1] / 16}, new double[] {(b[0] - a[0]) / 16, (b[1] - a[1]) / 16},
                    new double[] {(c[0] - a[0]) / 16, (c[1] - a[1]) / 16}, norm(texture), tinted ? tint : 0));
            }
        }
        return out;
    }

    private static double[] scale(double[] p) { return new double[] {p[0] / 16, p[1] / 16, p[2] / 16}; }

    private static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    private static double[] vec(com.google.gson.JsonArray a) { return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()}; }

    private static double[] vec4(com.google.gson.JsonArray a) {
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()};
    }

    private static void rotate(double[][] p, double[] o, double deg, String axis) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        for (double[] q : p) {
            double x = q[0] - o[0], y = q[1] - o[1], z = q[2] - o[2];
            switch (axis) {
                case "x" -> { q[1] = o[1] + y * c - z * s; q[2] = o[2] + y * s + z * c; }
                case "y" -> { q[0] = o[0] + x * c + z * s; q[2] = o[2] - x * s + z * c; }
                default -> { q[0] = o[0] + x * c - y * s; q[1] = o[1] + x * s + y * c; }
            }
        }
    }

    /**
     * The models of a block state: the variant matching its properties (or the default-looking one when the state has none),
     * or every multipart part whose condition holds; each {model, x rotation, y rotation}.
     */
    static List<String[]> variantsOf(List<Pack> packs, String ns, String name, Map<String, String> p) {
        List<String[]> out = new ArrayList<>();
        byte[] json = readFirst(packs, "assets/" + ns + "/blockstates/" + name + ".json");
        if (json == null) return out;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("variants") && !p.isEmpty()) {
                JsonObject vs = o.getAsJsonObject("variants");
                for (String k : vs.keySet())
                    if (matches(k, p)) {
                        String[] v = variantIn(vs.get(k));
                        if (v != null) out.add(v);
                        return out;
                    }
            }
            if (o.has("multipart") && !p.isEmpty()) {
                for (JsonElement part : o.getAsJsonArray("multipart")) {
                    JsonObject po = part.getAsJsonObject();
                    if (po.has("when") && !when(po.getAsJsonObject("when"), p)) continue;
                    String[] v = variantIn(po.get("apply"));
                    if (v != null) out.add(v);
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Exception ignored) {
            // fall back to the default-looking variant
        }
        String[] v = variantOf(packs, ns, name);
        if (v != null) out.add(v);
        return out;
    }

    private static String[] variantIn(JsonElement v) {
        if (v != null && v.isJsonArray()) v = v.getAsJsonArray().isEmpty() ? null : v.getAsJsonArray().get(0);
        if (v == null || !v.isJsonObject() || !v.getAsJsonObject().has("model")) return null;
        JsonObject m = v.getAsJsonObject();
        return new String[] {m.get("model").getAsString(), String.valueOf(m.has("x") ? m.get("x").getAsInt() : 0),
            String.valueOf(m.has("y") ? m.get("y").getAsInt() : 0)};
    }

    /** The default-looking variant of a block: {model, x rotation, y rotation}, or null. */
    private static String[] variantOf(List<Pack> packs, String ns, String name) {
        byte[] json = readFirst(packs, "assets/" + ns + "/blockstates/" + name + ".json");
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement v = null;
            if (o.has("variants")) {
                JsonObject vs = o.getAsJsonObject("variants");
                String pick = null;
                for (String k : vs.keySet()) {
                    if (pick == null) pick = k;
                    if (k.isEmpty() || k.contains("axis=y") || k.contains("facing=north") || k.contains("half=bottom") && !k.contains("open=true")
                        || k.contains("snowy=false") || k.contains("type=bottom")) {
                        pick = k;
                        break;
                    }
                }
                v = vs.get(pick);
            } else if (o.has("multipart")) {
                for (JsonElement part : o.getAsJsonArray("multipart"))
                    if (!part.getAsJsonObject().has("when")) {
                        v = part.getAsJsonObject().get("apply");
                        break;
                    }
                if (v == null && !o.getAsJsonArray("multipart").isEmpty()) v = o.getAsJsonArray("multipart").get(0).getAsJsonObject().get("apply");
            }
            if (v != null && v.isJsonArray()) v = v.getAsJsonArray().isEmpty() ? null : v.getAsJsonArray().get(0);
            if (v == null || !v.isJsonObject() || !v.getAsJsonObject().has("model")) return null;
            JsonObject m = v.getAsJsonObject();
            return new String[] {m.get("model").getAsString(), String.valueOf(m.has("x") ? m.get("x").getAsInt() : 0),
                String.valueOf(m.has("y") ? m.get("y").getAsInt() : 0)};
        } catch (Exception e) {
            return null;
        }
    }

    /** The texture of an entity type (entity/cow/cow.png, entity/pig.png, the first texture of its folder...). */
    static String entityTexture(List<Pack> packs, String entityId) {
        String n = entityId.substring(entityId.indexOf(':') + 1);
        for (String c : new String[] {"entity/" + n + "/" + n, "entity/" + n, "entity/" + n + "/" + n + "_temperate", "entity/" + n + "/temperate_" + n,
            "entity/" + n + "/" + n + "_base"})
            if (readFirst(packs, "assets/minecraft/textures/" + c + ".png") != null) return "minecraft:" + c;
        for (Pack p : packs) {
            if (!p.zip()) continue;
            try {
                ZipFile z;
                synchronized (PreviewBlocks.class) {
                    z = ZIPS.get(p.path());
                    if (z == null) ZIPS.put(p.path(), z = new ZipFile(p.path().toFile()));
                }
                String prefix = "assets/minecraft/textures/entity/" + n + "/";
                var e = z.stream().filter(x -> x.getName().startsWith(prefix) && x.getName().endsWith(".png")).findFirst().orElse(null);
                if (e != null) return "minecraft:" + e.getName().substring("assets/minecraft/textures/".length(), e.getName().length() - 4);
            } catch (Exception ignored) {
                // next pack
            }
        }
        return null;
    }

    /** An entity's quads in world space relative to the volume origin (its exported model posed like the game draws it). */
    static List<Q> entityQuads(PreviewWorld.Entity e, Map<String, double[]> layers, List<Pack> packs, MosaicWorld.Snapshot s) {
        String tex = entityTexture(packs, e.id());
        double[] v = layers.get(e.id() + "#main");
        List<Q> out = new ArrayList<>();
        double yr = Math.toRadians(180 - e.yaw()), cy = Math.cos(yr), sy = Math.sin(yr);
        double bx = e.x() - s.ox(), by = e.y() - s.oy(), bz = e.z() - s.oz();
        if (v == null || tex == null) {
            // no exported model yet: a box of a mob's size in the colour of its texture
            double w = 0.3, h = 1.8;
            double[][] faces = {{-w, 0, -w, 2 * w, 0, 0, 0, h, 0}, {w, 0, w, -2 * w, 0, 0, 0, h, 0}, {-w, 0, w, 0, 0, -2 * w, 0, h, 0},
                {w, 0, -w, 0, 0, 2 * w, 0, h, 0}, {-w, h, -w, 2 * w, 0, 0, 0, 0, 2 * w}};
            for (double[] f : faces)
                out.add(new Q(new double[] {bx + f[0], by + f[1], bz + f[2]}, new double[] {f[3], f[4], f[5]}, new double[] {f[6], f[7], f[8]},
                    new double[] {0.25, 0.25}, new double[] {0.5, 0}, new double[] {0, 0.5}, tex == null ? "minecraft:block/white_wool" : tex, 0));
            return out;
        }
        for (int i = 0; i + 19 < v.length; i += 20) {
            double[][] p = new double[4][];
            double[][] uv = new double[4][];
            for (int c = 0; c < 4; c++) {
                int o = i + c * 5;
                // the export is posed and in blocks, in model space (y down): the game draws it scaled by (-1, -1, 1) and lifted by 1.501
                double x = -v[o], y = 1.501 - v[o + 1], z = v[o + 2];
                p[c] = new double[] {bx + x * cy + z * sy, by + y, bz - x * sy + z * cy};
                uv[c] = new double[] {v[o + 3], v[o + 4]};
            }
            out.add(new Q(p[0], sub(p[1], p[0]), sub(p[3], p[0]), uv[0], new double[] {uv[1][0] - uv[0][0], uv[1][1] - uv[0][1]},
                new double[] {uv[3][0] - uv[0][0], uv[3][1] - uv[0][1]}, tex, 0));
        }
        return out;
    }

    private static boolean exportAsked;

    /** Blocks the game draws with an entity model (chests, bells): layer and texture. */
    static final Map<String, String[]> BLOCK_ENTITIES = Map.of(
        "minecraft:chest", new String[] {"minecraft:chest#main", "minecraft:entity/chest/normal"},
        "minecraft:trapped_chest", new String[] {"minecraft:chest#main", "minecraft:entity/chest/trapped"},
        "minecraft:ender_chest", new String[] {"minecraft:chest#main", "minecraft:entity/chest/ender"},
        "minecraft:copper_chest", new String[] {"minecraft:chest#main", "minecraft:entity/chest/copper"},
        "minecraft:bell", new String[] {"minecraft:bell#main", "minecraft:entity/bell/bell_body"});

    /** A chest or bell of the world drawn by its entity model, turned to its facing (south: as exported). */
    static List<Q> blockEntityQuads(String state, Map<String, double[]> layers, List<Pack> packs) {
        String[] be = BLOCK_ENTITIES.get(baseName(state));
        if (be == null) return null;
        double[] v = layers.get(be[0]);
        if (v == null || image(packs, be[1]) == null) return null;
        String facing = props(state).getOrDefault("facing", "south");
        double deg = switch (facing) { case "west" -> 90; case "north" -> 180; case "east" -> 270; default -> 0; };
        double r = Math.toRadians(-deg), c = Math.cos(r), s = Math.sin(r);
        List<Q> out = new ArrayList<>();
        for (int i = 0; i + 19 < v.length; i += 20) {
            double[][] p = new double[4][], uv = new double[4][];
            for (int k = 0; k < 4; k++) {
                int o = i + k * 5;
                double x = v[o] - 0.5, y = v[o + 1], z = v[o + 2] - 0.5;
                p[k] = new double[] {0.5 + x * c + z * s, y, 0.5 - x * s + z * c};
                uv[k] = new double[] {v[o + 3], v[o + 4]};
            }
            out.add(new Q(p[0], sub(p[1], p[0]), sub(p[3], p[0]), uv[0], new double[] {uv[1][0] - uv[0][0], uv[1][1] - uv[0][1]},
                new double[] {uv[3][0] - uv[0][0], uv[3][1] - uv[0][1]}, be[1], 0));
        }
        return out;
    }

    /**
     * The atlas of a loaded world with its mesh: block textures, the quads of every block drawn by its model (stairs,
     * slabs, torches, flowers, fences...) and of the world's entities (their exported models), listed per cell.
     */
    static synchronized Atlas atlasFor(MosaicWorld.Snapshot s, Path gameDir) {
        MosaicWorld.BlockTable t = PreviewWorld.blocksOf(s);
        List<PreviewWorld.Entity> ents = PreviewWorld.entitiesOf(s);
        Path export = BlockModels.offlineFile();
        String sig = signature(gameDir, t == null ? null : t.names()) + "|" + System.identityHashCode(s) + "|"
            + (export == null ? 0 : newest(export)) + "|" + ents.size();
        Atlas cached = CACHE.get(sig);
        if (cached != null) return cached;
        PreviewWorld.Biomes biomes = PreviewWorld.biomesOf(s);
        perColumnTint = biomes != null;
        Atlas base;
        try {
            base = atlas(t == null ? null : t.names(), gameDir);
        } finally {
            if (t == null && ents.isEmpty()) perColumnTint = false;
        }
        if (t == null && ents.isEmpty()) return base;
        List<Pack> packs = packs(gameDir);
        int[] biomeCols = null;
        if (biomes != null) {
            biomeCols = new int[biomes.column().length * 4];
            List<int[]> byBiome = new ArrayList<>();
            for (String name : biomes.names()) byBiome.add(biomeColours(packs, name));
            for (int i = 0; i < biomes.column().length; i++) {
                int[] c = byBiome.get(biomes.column()[i]);
                biomeCols[i * 4] = c[0];
                biomeCols[i * 4 + 1] = c[1];
                biomeCols[i * 4 + 2] = c[2];
                biomeCols[i * 4 + 3] = c[3]; // temperature x 1000 + 10000: snow instead of rain below 0.15
            }
        }
        boolean blockEntities = t != null && t.names().stream().anyMatch(n -> BLOCK_ENTITIES.containsKey(baseName(n)));
        Map<String, double[]> layers = ents.isEmpty() && !blockEntities ? Map.of() : BlockModels.entityLayers(gameDir);
        if ((!ents.isEmpty() || blockEntities) && layers.isEmpty() && !exportAsked) {
            exportAsked = true;
            BlockModels.exportOffline(() -> { }); // entity models from the client jar, once; the preview picks them up when ready
        }
        int n = t == null ? 0 : t.names().size();
        List<List<Q>> models = new ArrayList<>();
        int[] info = base.info().clone();
        for (int b = 0; b < n; b++) {
            List<Q> q = b == 0 ? null : blockEntityQuads(t.names().get(b), layers, packs);
            if (q == null && b != 0) q = modelQuads(packs, t.names().get(b));
            models.add(q);
            if (q != null && b * 4 + 3 < info.length) info[b * 4 + 3] |= 2;
        }
        // textures: the base atlas tiles stay; model and entity textures get their own rects after them
        Map<String, int[]> rects = new HashMap<>();
        List<int[]> pixels = new ArrayList<>();
        List<int[]> placed = new ArrayList<>();
        int w = base.w(), y0 = base.h(), cx = 0, cy = y0, rowH = 0;
        java.util.function.Function<String, int[]> rectOf = key -> rects.computeIfAbsent(key, k -> {
            String texture = k.substring(0, k.indexOf('#'));
            int tint = Math.max(0, Integer.parseInt(k.substring(k.indexOf('#') + 1)));
            BufferedImage img = image(packs, texture);
            if (img == null) return null;
            boolean entity = texture.contains(":entity/");
            int tw = entity ? Math.min(128, img.getWidth()) : 16, th = entity ? Math.max(1, img.getHeight() * tw / img.getWidth()) : 16;
            int frameH = entity ? img.getHeight() : Math.min(img.getWidth(), img.getHeight());
            int[] px = new int[tw * th];
            for (int yy = 0; yy < th; yy++)
                for (int xx = 0; xx < tw; xx++) {
                    int argb = img.getRGB(xx * img.getWidth() / tw, yy * frameH / th);
                    px[yy * tw + xx] = tint != 0 ? mul(argb, tint) : argb;
                }
            pixels.add(px);
            int[] r = {0, 0, tw, th};
            placed.add(r);
            return r;
        });
        int[] cloudRect = null;
        BufferedImage cloudImg = image(packs, "minecraft:environment/clouds");
        if (cloudImg != null) {
            int cw = Math.min(256, cloudImg.getWidth()), ch = Math.min(256, cloudImg.getHeight());
            int[] px = new int[cw * ch];
            for (int yy = 0; yy < ch; yy++) for (int xx = 0; xx < cw; xx++) px[yy * cw + xx] = cloudImg.getRGB(xx * cloudImg.getWidth() / cw, yy * cloudImg.getHeight() / ch);
            pixels.add(px);
            cloudRect = new int[] {0, 0, cw, ch};
            placed.add(cloudRect);
        }
        int[] snowRect = null;
        BufferedImage snowImg = image(packs, "minecraft:environment/snow");
        if (snowImg != null) {
            int sw = Math.min(64, snowImg.getWidth()), sh = Math.min(256, snowImg.getHeight());
            int[] px = new int[sw * sh];
            for (int yy = 0; yy < sh; yy++) for (int xx = 0; xx < sw; xx++) px[yy * sw + xx] = snowImg.getRGB(xx * snowImg.getWidth() / sw, yy * snowImg.getHeight() / sh);
            pixels.add(px);
            snowRect = new int[] {0, 0, sw, sh};
            placed.add(snowRect);
        }
        int[] rainRect = null;
        BufferedImage rainImg = image(packs, "minecraft:environment/rain");
        if (rainImg != null) {
            int rw = Math.min(64, rainImg.getWidth()), rh = Math.min(256, rainImg.getHeight());
            int[] px = new int[rw * rh];
            for (int yy = 0; yy < rh; yy++) for (int xx = 0; xx < rw; xx++) px[yy * rw + xx] = rainImg.getRGB(xx * rainImg.getWidth() / rw, yy * rainImg.getHeight() / rh);
            pixels.add(px);
            rainRect = new int[] {0, 0, rw, rh};
            placed.add(rainRect);
        }
        List<int[]> cellQuads = new ArrayList<>(); // {cell index, quad index}
        List<float[]> quads = new ArrayList<>();
        int sx = s.sx(), sy = s.sy(), sz = s.sz();
        for (int ly = 0, i = 0; ly < sy; ly++)
            for (int lz = 0; lz < sz; lz++)
                for (int lx = 0; lx < sx; lx++, i++) {
                    int b = t == null ? 0 : t.index()[i] & 0xFFFF;
                    if (b == 0 || b >= models.size() || models.get(b) == null) continue;
                    for (Q q : models.get(b)) {
                        int[] r = rectOf.apply(q.texture() + "#" + Math.max(0, q.tint()));
                        if (r == null) continue;
                        float[] packed = pack(q, lx, ly, lz, r, false);
                        if (q.tint() < 0) packed[19] = q.tint() == -1 ? 2 : 4; // grass / foliage colour of the column
                        quads.add(packed);
                        cellQuads.add(new int[] {i, quads.size() - 1});
                    }
                }
        for (PreviewWorld.Entity e : ents)
            for (Q q : entityQuads(e, layers, packs, s)) {
                int[] r = rectOf.apply(q.texture() + "#0");
                if (r == null) continue;
                quads.add(pack(q, 0, 0, 0, r, true));
                int qi = quads.size() - 1;
                double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (double[] c : new double[][] {q.p0(), add(q.p0(), q.e1()), add(q.p0(), q.e2()), add(add(q.p0(), q.e1()), q.e2())})
                    for (int k = 0; k < 3; k++) {
                        lo[k] = Math.min(lo[k], c[k]);
                        hi[k] = Math.max(hi[k], c[k]);
                    }
                for (int yy = (int) Math.floor(lo[1]); yy <= (int) Math.floor(hi[1]); yy++)
                    for (int zz = (int) Math.floor(lo[2]); zz <= (int) Math.floor(hi[2]); zz++)
                        for (int xx = (int) Math.floor(lo[0]); xx <= (int) Math.floor(hi[0]); xx++)
                            if (xx >= 0 && yy >= 0 && zz >= 0 && xx < sx && yy < sy && zz < sz) cellQuads.add(new int[] {(yy * sz + zz) * sx + xx, qi});
            }
        // shelf-pack the extra textures under the tiles
        for (int[] r : placed) {
            if (cx + r[2] > w) {
                cx = 0;
                cy += rowH;
                rowH = 0;
            }
            r[0] = cx;
            r[1] = cy;
            cx += r[2];
            rowH = Math.max(rowH, r[3]);
        }
        int h = Math.max(base.h(), cy + rowH);
        int[] argb = java.util.Arrays.copyOf(base.argb(), w * h);
        for (int k = 0; k < placed.size(); k++) {
            int[] r = placed.get(k), px = pixels.get(k);
            for (int yy = 0; yy < r[3]; yy++) System.arraycopy(px, yy * r[2], argb, (r[1] + yy) * w + r[0], r[2]);
        }
        // the rects were only known now: write them into the quads
        float[] qa = new float[quads.size() * 20];
        for (int k = 0; k < quads.size(); k++) {
            float[] f = quads.get(k);
            int[] r = RECTS.get(f);
            f[3] = r[0];
            f[7] = r[1];
            f[11] = r[2];
            f[18] = r[3];
            System.arraycopy(f, 0, qa, k * 20, 20);
        }
        RECTS.clear();
        cellQuads.sort((a, b) -> Integer.compare(a[0], b[0]));
        int cells = sx * sy * sz;
        int[] mesh = new int[cells + cellQuads.size()];
        for (int k = 0; k < cellQuads.size(); ) {
            int cell = cellQuads.get(k)[0], start = k, count = 0;
            while (k < cellQuads.size() && cellQuads.get(k)[0] == cell) {
                k++;
                count++;
            }
            mesh[cell] = (start << 8) | Math.min(255, count);
        }
        for (int k = 0; k < cellQuads.size(); k++) mesh[cells + k] = cellQuads.get(k)[1];
        perColumnTint = false;
        Atlas a = new Atlas(argb, w, h, info, sig, qa, mesh, biomeCols, cloudRect, rainRect, snowRect);
        if (CACHE.size() > 4) CACHE.clear();
        CACHE.put(sig, a);
        return a;
    }

    private static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    /**
     * A quad for the GPU (5 x vec4): (p0, rect x), (edge 1, rect y), (edge 2, rect w), (uv0, du), (dv, rect h, flags). Tiles are
     * its own rect (top-down); the rect is filled in after shelf packing.
     */
    private static float[] pack(Q q, int lx, int ly, int lz, int[] rect, boolean entity) {
        float[] f = new float[20];
        double[] uv0 = q.uv0().clone(), du = q.du().clone(), dv = q.dv().clone();
        f[0] = (float) (q.p0()[0] + lx);
        f[1] = (float) (q.p0()[1] + ly);
        f[2] = (float) (q.p0()[2] + lz);
        f[4] = (float) q.e1()[0];
        f[5] = (float) q.e1()[1];
        f[6] = (float) q.e1()[2];
        f[8] = (float) q.e2()[0];
        f[9] = (float) q.e2()[1];
        f[10] = (float) q.e2()[2];
        f[12] = (float) uv0[0];
        f[13] = (float) uv0[1];
        f[14] = (float) du[0];
        f[15] = (float) du[1];
        f[16] = (float) dv[0];
        f[17] = (float) dv[1];
        f[19] = entity ? 1 : 0;
        RECTS.put(f, rect);
        return f;
    }

    /** Quad -> its rect until the atlas is laid out (identity keys). */
    private static final Map<float[], int[]> RECTS = new java.util.IdentityHashMap<>();

    /** Material class fallback for blocks of a world without names (unused here, kept for tools). */
    static MosaicWorld.Material classOf(String block) { return SaveWorld.classify(block); }
}
