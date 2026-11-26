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

    /** argb rows of {@code w x h} (tiles of 16 x 16, each stored bottom-up), and per block: top, side, bottom tile, flags. */
    record Atlas(int[] argb, int w, int h, int[] info, String signature) {}

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
        String sig = signature(gameDir, blocks);
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
            String name = blocks.get(b);
            String[] faces = faces(packs, name);
            if (faces == null) continue;
            int tint = tint(packs, name);
            int[] ids = new int[3];
            for (int f = 0; f < 3; f++) {
                String key = faces[f] + "#" + Integer.toHexString(f == 1 && name.endsWith("grass_block") ? -1 : tint);
                Integer id = byKey.get(key);
                if (id == null) {
                    int[] tile = tile(packs, faces[f], f == 1 && name.endsWith("grass_block") ? 0 : tint,
                        f == 1 && name.endsWith("grass_block") ? tint(packs, "minecraft:grass_block") : 0);
                    if (tile == null) {
                        ids = null;
                        break;
                    }
                    id = tiles.size();
                    tiles.add(tile);
                    byKey.put(key, id);
                }
                ids[f] = id;
            }
            if (ids == null) continue;
            info[b * 4] = ids[0];
            info[b * 4 + 1] = ids[1];
            info[b * 4 + 2] = ids[2];
            info[b * 4 + 3] = 1;
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
    static String[] faces(List<Pack> packs, String block) {
        String ns = block.contains(":") ? block.substring(0, block.indexOf(':')) : "minecraft";
        String name = block.substring(block.indexOf(':') + 1);
        if (name.equals("water")) return new String[] {"minecraft:block/water_still", "minecraft:block/water_still", "minecraft:block/water_still"};
        if (name.equals("lava")) return new String[] {"minecraft:block/lava_still", "minecraft:block/lava_still", "minecraft:block/lava_still"};
        String model = modelOf(packs, ns, name);
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
            if (o.has("textures")) for (var e : o.getAsJsonObject("textures").entrySet()) tex.putIfAbsent(e.getKey(), e.getValue().getAsString());
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

    /** One 16 x 16 tile (bottom-up), tinted; for the grass side the tinted overlay over the dirt side. */
    private static int[] tile(List<Pack> packs, String textureId, int tint, int overlayTint) {
        BufferedImage img = image(packs, textureId);
        if (img == null) return null;
        BufferedImage over = overlayTint != 0 ? image(packs, "minecraft:block/grass_block_side_overlay") : null;
        int size = Math.min(img.getWidth(), img.getHeight()); // animated textures: the first frame
        int[] out = new int[256];
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int argb = img.getRGB(x * img.getWidth() / 16, y * size / 16);
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

    /** Plains: temperature 0.8, downfall 0.4. */
    static double biomeTemperature = 0.8, biomeDownfall = 0.4;

    /** The game's tint of a block at the preview's biome (0: none). */
    static int tint(List<Pack> packs, String block) {
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

    /** Material class fallback for blocks of a world without names (unused here, kept for tools). */
    static MosaicWorld.Material classOf(String block) { return SaveWorld.classify(block); }
}
