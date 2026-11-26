package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Material;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * The preview world: real blocks the preview renders and the graph lights - the place the player stood when the game
 * opened Studio ({@code bedray-studio/preview-world.bin}), or a built-in landscape (terrain, a lake, trees, a stone house
 * with a glass window, torches and a lava pool) with sky and block light spread like the game's. It feeds the preview's
 * G-buffer pass (scene colour, ray distance) and the world volume (binding 15), so position, normal, material, light,
 * shadow and GI nodes all see a real scene.
 */
final class PreviewWorld {

    private PreviewWorld() {}

    /** Size of the built-in landscape (content, not a limit: the game's capture has the size of its graph settings). */
    static final int SX = 96, SY = 48, SZ = 96;

    /** The game's capture when there is one, else the built-in landscape. */
    static MosaicWorld.Snapshot load(Path gameDir) { return load(gameDir, "", 96, 48, 96); }

    /** Which block every cell of a loaded snapshot is (null: material tiles only). */
    private static final java.util.Map<MosaicWorld.Snapshot, MosaicWorld.BlockTable> BLOCKS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Where a loaded snapshot came from, for the info line. */
    private static final java.util.Map<MosaicWorld.Snapshot, String> ORIGIN =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static MosaicWorld.BlockTable blocksOf(MosaicWorld.Snapshot s) { return s == null ? null : BLOCKS.get(s); }

    /** The biome of every column of a loaded world (names, index per column x fastest), for grass, leaf and water colours. */
    record Biomes(java.util.List<String> names, short[] column) {
        static Biomes of(String[] perColumn) {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.Map<String, Integer> ids = new java.util.HashMap<>();
            short[] col = new short[perColumn.length];
            for (int i = 0; i < perColumn.length; i++) {
                String b = perColumn[i] == null ? "minecraft:plains" : perColumn[i];
                Integer id = ids.get(b);
                if (id == null) {
                    id = names.size();
                    names.add(b);
                    ids.put(b, id);
                }
                col[i] = (short) id.intValue();
            }
            return new Biomes(java.util.List.copyOf(names), col);
        }
    }

    private static final java.util.Map<MosaicWorld.Snapshot, Biomes> BIOMES =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static Biomes biomesOf(MosaicWorld.Snapshot s) { return s == null ? null : BIOMES.get(s); }

    private static final java.util.Map<MosaicWorld.Snapshot, float[]> WEATHER = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static void weather(MosaicWorld.Snapshot s, float rain, float thunder) { WEATHER.put(s, new float[] {rain, thunder}); }

    /** Rain and thunder of a loaded world (0 / 1), none when unknown. */
    static float[] weatherOf(MosaicWorld.Snapshot s) {
        float[] w = s == null ? null : WEATHER.get(s);
        return w == null ? new float[] {0, 0} : w;
    }

    /** An entity of a loaded world: id (minecraft:cow), feet position (absolute blocks), body yaw (Minecraft degrees). */
    record Entity(String id, double x, double y, double z, float yaw) {}

    private static final java.util.Map<MosaicWorld.Snapshot, java.util.List<Entity>> ENTITIES =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static java.util.List<Entity> entitiesOf(MosaicWorld.Snapshot s) {
        java.util.List<Entity> e = s == null ? null : ENTITIES.get(s);
        return e == null ? java.util.List.of() : e;
    }

    static String originOf(MosaicWorld.Snapshot s) {
        String o = s == null ? null : ORIGIN.get(s);
        return o != null ? o : s == DEMO ? "built-in world" : "world from the game";
    }

    /**
     * The preview world by choice (Studio settings previewWorld): "" the game's capture when there is one, else the
     * built-in landscape; "builtin"; "game" (the capture only); or the folder of a save (its blocks around the player,
     * sx x sy x sz).
     */
    static MosaicWorld.Snapshot load(Path gameDir, String choice, int sx, int sy, int sz) {
        if (choice != null && !choice.isBlank() && !choice.equals("builtin") && !choice.equals("game")) {
            Path save = Path.of(choice);
            if (SaveWorld.isSave(save)) {
                try {
                    SaveWorld.Loaded l = SaveWorld.load(save, sx, sy, sz);
                    BLOCKS.put(l.snapshot(), l.blocks());
                    ENTITIES.put(l.snapshot(), l.entities());
                    BIOMES.put(l.snapshot(), l.biomes());
                    ORIGIN.put(l.snapshot(), "save " + save.getFileName());
                    return l.snapshot();
                } catch (Exception e) {
                    lastError = "could not read the save " + save.getFileName() + ": " + e.getMessage();
                }
            } else lastError = "not a Minecraft save (no level.dat): " + save;
        }
        if (!"builtin".equals(choice) && gameDir != null) {
            try {
                Path f = gameDir.resolve("bedray-studio").resolve("preview-world.bin");
                if (Files.isRegularFile(f)) {
                    byte[] data = Files.readAllBytes(f);
                    MosaicWorld.Snapshot s = MosaicWorld.Snapshot.read(data);
                    if (s != null) {
                        MosaicWorld.BlockTable t = MosaicWorld.BlockTable.read(data);
                        if (t != null && t.index().length == s.cellCount()) BLOCKS.put(s, t);
                        if (t != null && t.biomeNames() != null && t.biomeColumns().length == s.sx() * s.sz())
                            BIOMES.put(s, new Biomes(t.biomeNames(), t.biomeColumns()));
                        ORIGIN.put(s, "world from the game (J)");
                        return s;
                    }
                }
            } catch (Exception ignored) {
                // unreadable capture: the built-in world
            }
        }
        return demo();
    }

    /** Why the chosen world could not be loaded (shown once), or null. */
    static volatile String lastError;

    static boolean fromGame(MosaicWorld.Snapshot s) { return s != DEMO; }

    private static MosaicWorld.Snapshot DEMO;

    // ------------------------------------------------------------------ built-in landscape

    static synchronized MosaicWorld.Snapshot demo() {
        if (DEMO != null) return DEMO;
        int ox = 0, oy = 48, oz = 0;
        Material[] m = new Material[SX * SY * SZ];
        java.util.Arrays.fill(m, Material.AIR);
        int water = 62 - oy;
        int[][] height = new int[SX][SZ];
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++) {
                height[x][z] = (int) Math.round(demoHeight(x, z)) - oy;
            }
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++) {
                int top = height[x][z];
                for (int y = 0; y < SY; y++) {
                    Material v = Material.AIR;
                    if (y < top - 3) v = Material.STONE;
                    else if (y < top) v = top <= water + 1 ? Material.SAND : Material.DIRT;
                    else if (y == top) v = top <= water + 1 ? Material.SAND : Material.DIRT;
                    else if (y <= water) v = Material.WATER;
                    m[idx(x, y, z)] = v;
                }
            }
        // trees
        java.util.List<int[]> trees = new java.util.ArrayList<>();
        for (int gx = 4; gx < SX - 4; gx += 9)
            for (int gz = 4; gz < SZ - 4; gz += 9) {
                int hsh = (gx * 73856093) ^ (gz * 19349663);
                if (Math.floorMod(hsh, 5) > 1) continue;
                trees.add(new int[] {gx + Math.floorMod(hsh >> 4, 5) - 2, gz + Math.floorMod(hsh >> 8, 5) - 2});
            }
        for (int[] t : trees) {
            int x = t[0], z = t[1], base = height[x][z] + 1;
            if (Math.abs(x - 59) < 7 && Math.abs(z - 33) < 7 || Math.hypot(x - 44, z - 92) < 10) continue; // the house, the view
            if (base <= water + 1) continue;
            for (int y = 0; y < 5; y++) set(m, x, base + y, z, Material.WOOD);
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int dy = 3; dy <= 6; dy++) {
                        int r = dy >= 5 ? 1 : 2;
                        if (Math.abs(dx) > r || Math.abs(dz) > r || (Math.abs(dx) == 2 && Math.abs(dz) == 2)) continue;
                        if (get(m, x + dx, base + dy, z + dz) == Material.AIR) set(m, x + dx, base + dy, z + dz, Material.LEAVES);
                    }
        }
        // a stone house with a glass window, a torch inside and one at the door
        int hx = 56, hz = 30, hb = 0;
        for (int x = hx; x < hx + 7; x++) for (int z = hz; z < hz + 6; z++) hb = Math.max(hb, height[x][z] + 1);
        for (int x = hx; x < hx + 7; x++)
            for (int z = hz; z < hz + 6; z++) {
                for (int y = height[x][z] + 1; y < hb; y++) set(m, x, y, z, Material.STONE);
                for (int y = hb; y < hb + 5; y++) {
                    boolean wall = x == hx || x == hx + 6 || z == hz || z == hz + 5;
                    boolean roof = y == hb + 4;
                    set(m, x, y, z, roof || wall ? Material.OTHER : Material.AIR);
                }
            }
        set(m, hx + 3, hb, hz + 5, Material.AIR);
        set(m, hx + 3, hb + 1, hz + 5, Material.AIR);
        set(m, hx + 1, hb + 2, hz + 5, Material.GLASS);
        set(m, hx + 5, hb + 2, hz + 5, Material.GLASS);
        set(m, hx + 6, hb + 2, hz + 2, Material.GLASS);
        set(m, hx + 1, hb + 1, hz + 1, Material.LIGHT);
        set(m, hx + 4, hb, hz + 6, Material.WOOD);      // a chest by the door (named below)
        set(m, hx + 2, hb, hz + 1, Material.WOOD);      // and one inside
        set(m, hx + 2, hb + 1, hz + 6, Material.LIGHT);
        // lava pool, an iron block and a snowy stone pillar
        for (int x = 64; x < 67; x++) for (int z = 56; z < 59; z++) set(m, x, height[x][z], z, Material.LAVA);
        set(m, 50, height[50][44] + 1, 44, Material.METAL);
        for (int y = 1; y < 6; y++) set(m, 18, height[18][30] + y, 30, Material.STONE);
        set(m, 18, height[18][30] + 6, 30, Material.SNOW);
        // grass and flowers on the open ground
        java.util.Map<Long, String> plants = new java.util.HashMap<>();
        String[] flora = {"minecraft:short_grass", "minecraft:short_grass", "minecraft:short_grass", "minecraft:poppy", "minecraft:dandelion",
            "minecraft:fern", "minecraft:oxeye_daisy", "minecraft:cornflower"};
        for (int x = 1; x < SX - 1; x++)
            for (int z = 1; z < SZ - 1; z++) {
                int top = height[x][z], hsh = (x * 73428767) ^ (z * 912931);
                if (top + 1 >= SY || get(m, x, top, z) != Material.DIRT || get(m, x, top + 1, z) != Material.AIR || Math.floorMod(hsh, 7) != 0) continue;
                set(m, x, top + 1, z, Material.PLANT);
                plants.put((long) idx(x, top + 1, z), flora[Math.floorMod(hsh >> 5, flora.length)]);
            }
        short[] cells = light(m);
        DEMO = demoFar(new MosaicWorld.Snapshot(SX, SY, SZ, ox, oy, oz, cells, ox + 44.5, oy + height[44][92] + 9.6, oz + 92.5, 160f, 20f, 0.06f));
        // real block names, so the built-in world is drawn with each block's own textures and models
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of("minecraft:air"));
        java.util.Map<String, Integer> ids = new java.util.HashMap<>(java.util.Map.of("minecraft:air", 0));
        short[] index = new short[m.length];
        for (int y = 0; y < SY; y++)
            for (int z = 0; z < SZ; z++)
                for (int x = 0; x < SX; x++) {
                    int i = idx(x, y, z);
                    String n = switch (m[i]) {
                        case AIR -> null;
                        case DIRT -> y == height[x][z] && (y + 1 >= SY || m[idx(x, y + 1, z)] == Material.AIR || m[idx(x, y + 1, z)] == Material.PLANT)
                            ? "minecraft:grass_block" : "minecraft:dirt";
                        case STONE -> "minecraft:stone";
                        case OTHER -> "minecraft:stone_bricks";
                        case SAND -> "minecraft:sand";
                        case WATER -> "minecraft:water";
                        case LAVA -> "minecraft:lava";
                        case WOOD -> "minecraft:oak_log";
                        case LEAVES -> "minecraft:oak_leaves";
                        case GLASS -> "minecraft:glass";
                        case LIGHT -> "minecraft:torch";
                        case METAL -> "minecraft:iron_block";
                        case SNOW -> "minecraft:snow_block";
                        case ICE -> "minecraft:ice";
                        case PLANT -> plants.getOrDefault((long) i, "minecraft:short_grass");
                    };
                    if (m[i] == Material.WOOD && y == hb && ((x == hx + 4 && z == hz + 6) || (x == hx + 2 && z == hz + 1)))
                        n = z == hz + 6 ? "minecraft:chest[facing=south]" : "minecraft:chest[facing=east]";
                    if (n == null) continue;
                    Integer id = ids.get(n);
                    if (id == null) {
                        id = names.size();
                        names.add(n);
                        ids.put(n, id);
                    }
                    index[i] = (short) id.intValue();
                }
        BLOCKS.put(DEMO, new MosaicWorld.BlockTable(java.util.List.copyOf(names), index));
        // a few animals on the meadow in front of the camera (drawn by their exported models)
        java.util.List<Entity> animals = new java.util.ArrayList<>();
        int[][] spots = {{40, 82, 30}, {47, 80, 200}, {36, 76, 120}, {52, 74, 300}};
        String[] kinds = {"minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:cow"};
        for (int k = 0; k < spots.length; k++) {
            int x = spots[k][0], z = spots[k][1];
            int top = height[x][z];
            if (top + 1 <= 62 - oy + 1 || get(m, x, top + 1, z) == Material.WOOD) continue;
            animals.add(new Entity(kinds[k], ox + x + 0.5, oy + top + 1, oz + z + 0.5, spots[k][2]));
        }
        ENTITIES.put(DEMO, animals);
        String[] plainsEverywhere = new String[SX * SZ];
        java.util.Arrays.fill(plainsEverywhere, "minecraft:plains");
        BIOMES.put(DEMO, Biomes.of(plainsEverywhere));
        return DEMO;
    }

    /** The built-in landscape's ground height at an absolute column (hills, a ridge, a lake), also beyond the volume. */
    static double demoHeight(int x, int z) {
        double h = 64 + 4.5 * Math.sin(x * 0.09) * Math.cos(z * 0.07) + 2.5 * Math.sin((x + z) * 0.05 + 1.3)
            + 1.2 * Math.cos(x * 0.21 - z * 0.17) + Math.max(0, 9 - Math.hypot(x - 78, z - 20) * 0.45) * 1.6
            + 9.0 * Math.sin(x * 0.013 + 0.7) * Math.cos(z * 0.011) * Math.min(1.0, Math.hypot(x - 48, z - 48) / 120.0);
        double lake = Math.hypot(x - 30, z - 58);
        if (lake < 15) h -= (15 - lake) * 0.6;
        double sea = Math.hypot(x + 60, z - 150);
        if (sea < 90) h -= (90 - sea) * 0.12;
        return h;
    }

    /** The land around the volume (384 x 384 columns): ground, sand by the water, water, scattered tree tops. */
    private static MosaicWorld.Snapshot demoFar(MosaicWorld.Snapshot s) {
        int fs = 384, fox = s.ox() + SX / 2 - fs / 2, foz = s.oz() + SZ / 2 - fs / 2, water = 62;
        short[] hs = new short[fs * fs], gs = new short[fs * fs];
        byte[] ms = new byte[fs * fs];
        for (int j = 0; j < fs; j++)
            for (int i = 0; i < fs; i++) {
                int x = fox + i, z = foz + j, h = (int) Math.round(demoHeight(x, z));
                Material m = h < water ? Material.WATER : h <= water + 1 ? Material.SAND : Material.DIRT;
                if (h < water) h = water;
                gs[j * fs + i] = (short) h;
                // tree crowns: 3 x 3 blocks around a point of a 9-block grid, a little higher in the middle
                int gx = Math.floorDiv(x, 9), gz = Math.floorDiv(z, 9), hash = (gx * 73856093) ^ (gz * 19349663);
                int cx = gx * 9 + 3 + Math.floorMod(hash >> 3, 3), cz = gz * 9 + 3 + Math.floorMod(hash >> 6, 3);
                if (m == Material.DIRT && Math.floorMod(hash, 3) == 0 && Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 2
                    && !(Math.abs(x - cx) == 2 && Math.abs(z - cz) == 2)) {
                    m = Material.LEAVES;
                    h += (x == cx && z == cz) ? 6 : 5;
                }
                hs[j * fs + i] = (short) h;
                ms[j * fs + i] = (byte) (m.id() | (m == Material.LEAVES && x == cx && z == cz ? 0x80 : 0)); // the trunk under the crown
            }
        return s.withFar(new MosaicWorld.Far(fs, fox, foz, hs, ms, gs));
    }

    private static int idx(int x, int y, int z) { return (y * SZ + z) * SX + x; }

    private static void set(Material[] m, int x, int y, int z, Material v) {
        if (x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ) m[idx(x, y, z)] = v;
    }

    private static Material get(Material[] m, int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ ? m[idx(x, y, z)] : Material.AIR;
    }

    /** Light passes through air, plants, glass; water and leaves dim it by one more per block; solids stop it. */
    static boolean opaque(Material v) {
        return switch (v) {
            case AIR, PLANT, GLASS, WATER, LEAVES, ICE -> false;
            default -> true;
        };
    }

    private static int extraLoss(Material v) { return v == Material.WATER || v == Material.LEAVES ? 1 : 0; }

    /** Sky light falls straight down at 15 and spreads sideways losing one per block; block light spreads from emitters. */
    static short[] light(Material[] m) {
        int n = m.length;
        int[] sky = new int[n], block = new int[n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < SX; x++)
            for (int z = 0; z < SZ; z++) {
                int l = 15;
                for (int y = SY - 1; y >= 0; y--) {
                    Material v = m[idx(x, y, z)];
                    if (opaque(v)) break;
                    l = Math.max(0, l - extraLoss(v));
                    sky[idx(x, y, z)] = l;
                    q.add(idx(x, y, z));
                }
            }
        spread(m, sky, q);
        for (int i = 0; i < n; i++)
            if (m[i] == Material.LIGHT || m[i] == Material.LAVA) {
                block[i] = m[i] == Material.LIGHT ? 14 : 15;
                q.add(i);
            }
        spread(m, block, q);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            // solid blocks report the light of the air next to them the way shaders read it (the cell in front of a face)
            out[i] = (short) MosaicWorld.cell(m[i], block[i], sky[i]);
        }
        return out;
    }

    private static void spread(Material[] m, int[] level, ArrayDeque<Integer> q) {
        int[] d = {1, -1, SX, -SX, SX * SZ, -SX * SZ};
        while (!q.isEmpty()) {
            int i = q.poll();
            int l = level[i];
            if (l <= 1) continue;
            int x = i % SX, z = (i / SX) % SZ, y = i / (SX * SZ);
            for (int k = 0; k < 6; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0), ny = y + (k == 4 ? 1 : k == 5 ? -1 : 0);
                if (nx < 0 || ny < 0 || nz < 0 || nx >= SX || ny >= SY || nz >= SZ) continue;
                int j = i + d[k];
                if (opaque(m[j])) continue;
                int nl = l - 1 - extraLoss(m[j]);
                if (nl > level[j]) {
                    level[j] = nl;
                    q.add(j);
                }
            }
        }
    }

    // ------------------------------------------------------------------ block textures

    /** Atlas tiles: material classes 0..14 (side faces), then grass top, dirt, log top. */
    static final int TILES = 18;
    private static int[] ATLAS;

    /** 16 x 16 tiles in one row (RGBA, alpha kept for cutouts), from the newest Minecraft jar; flat colours without one. */
    static synchronized int[] atlas() {
        if (ATLAS != null) return ATLAS;
        String[] names = {null, "water_still", "lava_still", "stone", "grass_block_side", "oak_leaves", "short_grass", "oak_log", "sand",
            "snow", "ice", "glass", "iron_block", "glowstone", "stone_bricks", "grass_block_top", "dirt", "oak_log_top"};
        int[] tints = {0, 0x3F76E4, 0, 0, 0, 0x59AE30, 0x7CBD6B, 0, 0, 0, 0, 0, 0, 0, 0, 0x7CBD6B, 0, 0};
        int[] flat = {0, 0x2F5FA8, 0xFF7A1A, 0x7F7F80, 0x6A8F3E, 0x3F7A26, 0x5D9B3E, 0x6B5130, 0xDBCFA0, 0xF4F8FA, 0x9ABCF4,
            0xD8E6EC, 0xC8C8CC, 0xF2D27A, 0x7A7A7C, 0x6FA544, 0x86603E, 0xA8885A};
        int[] out = new int[TILES * 16 * 16];
        StudioLibrary.Source jar;
        try {
            jar = StudioLibrary.vanillaJars().stream().findFirst().orElse(null);
        } catch (RuntimeException e) { // no Studio settings yet (tests, tools): flat colours
            jar = null;
        }
        for (int t = 0; t < TILES; t++) {
            BufferedImage img = jar == null || names[t] == null ? null
                : StudioLibrary.image(jar, "assets/minecraft/textures/block/" + names[t] + ".png");
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int argb;
                    if (img != null) {
                        int sx = x * img.getWidth() / 16, sy = y * Math.min(img.getWidth(), img.getHeight()) / 16; // animated: first frame
                        argb = img.getRGB(sx, sy);
                        if (t == 4) { // grass side: grey overlay band tinted green over the dirt
                            int a = argb >>> 24;
                            argb = a == 0 ? 0 : argb;
                        }
                        if (tints[t] != 0) argb = tint(argb, tints[t]);
                    } else argb = 0xFF000000 | flat[t];
                    if (t == 4 && img != null) {
                        BufferedImage over = StudioLibrary.image(jar, "assets/minecraft/textures/block/grass_block_side_overlay.png");
                        if (over != null) {
                            int o = over.getRGB(x * over.getWidth() / 16, y * over.getHeight() / 16);
                            if ((o >>> 24) > 128) argb = tint(o, 0x7CBD6B);
                        }
                    }
                    // stored bottom-up per tile row so v = 0 is the bottom of the texture
                    out[(15 - y) * TILES * 16 + t * 16 + x] = argb;
                }
        }
        ATLAS = out;
        return out;
    }

    private static int tint(int argb, int rgb) {
        int r = ((argb >> 16) & 255) * ((rgb >> 16) & 255) / 255, g = ((argb >> 8) & 255) * ((rgb >> 8) & 255) / 255,
            b = (argb & 255) * (rgb & 255) / 255;
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ the look of the preview world (Studio settings)

    /**
     * How the preview world draws the game's image (the scene colour the graph starts from): the game's light map, face
     * shading, translucency. Edited in the Preview's "World look" dialog, kept in Studio settings, emitted as constants.
     */
    static final java.util.List<io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting> LOOK = java.util.List.of(
        look("maxDistance", "View distance (blocks)", "Blocks the preview camera sees in full detail (the volume).", 256),
        look("farDistance", "Far view distance", "Blocks the far land (height field beyond the volume) is drawn to.", 1500),
        look("canopyDepth", "Far canopy depth", "Blocks of leaves drawn at the top of a far tree column (the rest down to the ground is open).", 4),
        look("trunkWidth", "Far trunk width", "Width of a far tree's trunk (fraction of a block).", 0.7),
        look("farStep", "Far march step", "Growth of the far land's march steps with distance (smaller = finer, slower).", 0.01),
        look("lightCurve", "Light curve", "Shape of the light map: level / ((1 + k) - k x level); 3 is Minecraft's.", 3),
        look("dayRise", "Daylight rise", "How fast sky light brightens with the sun's height.", 2.5),
        look("dayOffset", "Daylight offset", "Sky light when the sun is at the horizon.", 0.55),
        look("nightLevel", "Night level", "Sky light at night (0..1).", 0.12),
        new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting("blockColor", "Block light colour", "Preview",
            "Colour of torch and lava light.", 3, new double[] {1.0, 0.86, 0.66}, false),
        look("blockBoost", "Block light strength", "Strength of torch and lava light.", 1.1),
        look("ambient", "Ambient floor", "Light everywhere, even in caves.", 0.03),
        look("faceTop", "Top face shade", "Brightness of faces looking up.", 1.0),
        look("faceBottom", "Bottom face shade", "Brightness of faces looking down.", 0.5),
        look("faceX", "East / west face shade", "Brightness of faces along x.", 0.6),
        look("faceZ", "North / south face shade", "Brightness of faces along z.", 0.8),
        look("waterOpacity", "Water opacity", "How much of the water surface covers what lies under it.", 0.55),
        look("waterReflection", "Water sky reflection", "How much sky the water surface reflects.", 0.25),
        look("glassOpacity", "Glass and ice opacity", "How much glass and ice cover what lies behind.", 0.35),
        look("cutout", "Cutout threshold", "Texture alpha below which leaves, plants and glass are see-through.", 0.5),
        look("moonSharpness", "Moon size", "Higher = smaller moon disc.", 900),
        look("moonBrightness", "Moon brightness", "Brightness of the moon disc.", 2),
        look("smoothLighting", "Smooth lighting", "1: light blended across faces with ambient occlusion in corners, like the game's Smooth Lighting; 0: flat.", 1),
        look("aoStrength", "Ambient occlusion", "How much a corner darkens per block touching it (the game: about 0.6).", 0.6),
        look("fog", "Fog", "1: the game's distance fog towards the sky colour; 0: none.", 1),
        look("fogStart", "Fog start", "Fraction of the view distance where the fog begins (the game: about 0.9 of the render distance).", 0.75),
        look("clouds", "Clouds", "1: the game's cloud layer (clouds.png) drawn over the world; 0: none.", 1),
        look("cloudHeight", "Cloud height", "Absolute y of the cloud layer (the game: 192.33).", 192.33),
        look("cloudSpeed", "Cloud speed", "Blocks per second the clouds drift along x (the game: about 0.6).", 0.6),
        look("cloudOpacity", "Cloud opacity", "How much the clouds cover the sky.", 0.8),
        look("rain", "Rain", "0: never; 1: when the world is raining (saves keep their weather); 2: always.", 1),
        look("rainOpacity", "Rain opacity", "How strongly the falling rain shows.", 0.55),
        look("rainSpeed", "Rain speed", "How fast the rain falls (texture lengths per second).", 1.6),
        look("rainDarken", "Rain darkening", "How much rain darkens the sky and the daylight (the game: about 0.75 of the light).", 0.25),
        look("brightness", "Brightness", "The game's brightness option: 0 Moody, 0.5 the default, 1 Bright (lifts the dark end of the light map).", 0.5),
        new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting("nightSkyColor", "Night sky light colour", "Preview",
            "Colour sky light takes as the day fades (the game's night is bluish).", 3, new double[] {0.62, 0.66, 1.0}, false),
        look("vanillaSky", "The game's sky", "1: the biome's sky and fog colours, the sun and moon textures, stars, like the game; 0: the graph's own sky model.", 1),
        look("sunsetGlow", "Sunrise / sunset glow", "Strength of the orange glow towards the sun at dawn and dusk.", 0.8),
        look("stars", "Stars", "Brightness of the stars at night.", 1),
        look("snowTemperature", "Snow below temperature", "Biome temperature under which it snows instead of raining (the game: 0.15).", 0.15),
        look("snowSpeed", "Snow speed", "How fast snow falls compared to rain.", 0.15),
        look("animate", "Animated textures", "1: water, lava, fire, sea lanterns... play their animations like the game; 0: the first frame.", 1),
        look("models", "Block models and entities", "1: stairs, slabs, torches, flowers, fences... and the world's entities drawn by their models; 0: cubes.", 1));

    private static io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting look(String key, String label, String help, double def) {
        return new io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.Setting(key, label, "Preview", help, 1, new double[] {def}, false);
    }

    /** Constants pw_<key> of the look for the G-buffer shader. */
    static String lookConstants(java.util.Map<String, double[]> values) {
        StringBuilder sb = new StringBuilder();
        for (var s : LOOK) {
            double[] v = io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicSettings.value(values, s);
            if (s.size() == 1) sb.append("const float pw_").append(s.key()).append(" = ").append(String.format(java.util.Locale.ROOT, "%.6f", v[0])).append(";\n");
            else sb.append("const vec3 pw_").append(s.key()).append(" = vec3(").append(String.format(java.util.Locale.ROOT, "%.6f, %.6f, %.6f", v[0], v[1], v[2])).append(");\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the preview camera

    /** Where the preview looks from: absolute position, Minecraft yaw / pitch in degrees, time of day (0 noon). */
    record View(MosaicWorld.Snapshot world, double x, double y, double z, float yaw, float pitch, float timeOfDay, PreviewBlocks.Atlas atlas,
                java.util.Map<String, double[]> graphSettings, java.util.Map<String, double[]> look) {
        View(MosaicWorld.Snapshot world, double x, double y, double z, float yaw, float pitch, float timeOfDay, PreviewBlocks.Atlas atlas) {
            this(world, x, y, z, yaw, pitch, timeOfDay, atlas, java.util.Map.of(), java.util.Map.of());
        }

        static View of(MosaicWorld.Snapshot s) { return of(s, null); }

        /** The view of a snapshot with the atlas of its blocks and the game folder's resource packs. */
        static View of(MosaicWorld.Snapshot s, Path gameDir) {
            return new View(s, s.camX(), s.camY(), s.camZ(), s.yaw(), s.pitch(), s.timeOfDay(), PreviewBlocks.atlasFor(s, gameDir));
        }

        View withAtlas(PreviewBlocks.Atlas a) { return new View(world, x, y, z, yaw, pitch, timeOfDay, a, graphSettings, look); }

        MosaicWorld.BlockTable blocks() { return blocksOf(world); }

        /** The graph's settings (sky model, material colours) and the preview look the G-buffer draws with. */
        View with(java.util.Map<String, double[]> graph, java.util.Map<String, double[]> lookValues) {
            return new View(world, x, y, z, yaw, pitch, timeOfDay, atlas, java.util.Map.copyOf(graph), java.util.Map.copyOf(lookValues));
        }

        View look(float dYaw, float dPitch) {
            return new View(world, x, y, z, yaw + dYaw, Math.max(-89f, Math.min(89f, pitch + dPitch)), timeOfDay, atlas, graphSettings, look);
        }

        /** Moves along the view direction (forward > 0), staying inside the volume. */
        View move(double forward, double right, double up) {
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            double fx = -Math.sin(ry) * Math.cos(rp), fy = -Math.sin(rp), fz = Math.cos(ry) * Math.cos(rp);
            double rx = -Math.cos(ry), rz = -Math.sin(ry);
            double nx = x + fx * forward + rx * right, ny = y + fy * forward + up, nz = z + fz * forward + rz * right;
            nx = Math.max(world.ox() + 0.5, Math.min(world.ox() + world.sx() - 0.5, nx));
            ny = Math.max(world.oy() + 0.5, Math.min(world.oy() + world.sy() + 16, ny));
            nz = Math.max(world.oz() + 0.5, Math.min(world.oz() + world.sz() - 0.5, nz));
            return new View(world, nx, ny, nz, yaw, pitch, timeOfDay, atlas, graphSettings, look);
        }

        View at(float tod) { return new View(world, x, y, z, yaw, pitch, tod, atlas, graphSettings, look); }
    }
}
