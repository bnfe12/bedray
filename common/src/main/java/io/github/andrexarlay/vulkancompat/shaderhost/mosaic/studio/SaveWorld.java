package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Material;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Minecraft save (a folder with level.dat and region/*.mca) as a preview world: the blocks around the player (or the
 * spawn) with their saved block and sky light, the time of day, the player's view, and the far land (heightmaps and the
 * top block of every column). Reads the Anvil format of 1.18 and later (block_states palettes, zlib / gzip / raw chunks).
 */
final class SaveWorld {

    private SaveWorld() {}

    /** Size of the far land read around the camera (columns per side). */
    static int farSize = 384;

    /** A loaded save: the snapshot the preview renders, which block every cell is, and the entities in the volume. */
    record Loaded(MosaicWorld.Snapshot snapshot, MosaicWorld.BlockTable blocks, List<PreviewWorld.Entity> entities) {}

    static boolean isSave(Path dir) { return dir != null && Files.isRegularFile(dir.resolve("level.dat")); }

    /** The saves of a game folder, newest first. */
    static List<Path> saves(Path gameDir) {
        List<Path> out = new ArrayList<>();
        if (gameDir == null) return out;
        try (var s = Files.list(gameDir.resolve("saves"))) {
            s.filter(SaveWorld::isSave).forEach(out::add);
        } catch (IOException ignored) {
            // no saves folder
        }
        out.sort((a, b) -> Long.compare(mtime(b.resolve("level.dat")), mtime(a.resolve("level.dat"))));
        return out;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Loads a volume of sx x sy x sz blocks around the player of the save (eye height), plus the far land. */
    static Loaded load(Path save, int sx, int sy, int sz) throws IOException {
        Map<String, Object> level = compound(root(gunzip(Files.readAllBytes(save.resolve("level.dat")))), "Data");
        double x = 0, y = 80, z = 0;
        float yaw = 0, pitch = 0;
        String dim = "minecraft:overworld";
        Map<String, Object> player = compound(level, "Player");
        if (player == null) player = playerFile(save, level); // 26.x keeps the player in players/data/<uuid>.dat
        if (player != null && player.get("Pos") instanceof List<?> pos && pos.size() == 3) {
            x = num(pos.get(0));
            y = num(pos.get(1)) + 1.62;
            z = num(pos.get(2));
            if (player.get("Rotation") instanceof List<?> rot && rot.size() == 2) {
                yaw = (float) num(rot.get(0));
                pitch = (float) num(rot.get(1));
            }
            if (player.get("Dimension") instanceof String d) dim = d;
        } else {
            Map<String, Object> spawn = compound(level, "spawn");
            if (spawn != null && spawn.get("pos") instanceof int[] p && p.length == 3) {
                x = p[0] + 0.5;
                y = p[1] + 1.62;
                z = p[2] + 0.5;
            } else if (level != null && level.get("SpawnX") != null) {
                x = num(level.get("SpawnX")) + 0.5;
                y = num(level.get("SpawnY")) + 1.62;
                z = num(level.get("SpawnZ")) + 0.5;
            }
        }
        long dayTime = level != null && level.get("DayTime") != null ? (long) num(level.get("DayTime")) : clock(save, dim);
        float tod = (float) (Math.floorMod(dayTime - 6000L, 24000L) / 24000.0);
        Path regions = regionDir(save, dim);
        try (Reader r = new Reader(regions)) {
            int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
            int ox = fx - sx / 2, oy = fy - sy / 2, oz = fz - sz / 2;
            short[] cells = new short[sx * sy * sz];
            int[] tops = new int[sx * sz]; // the top of every column (heightmap): sky light starts above it
            short[] index = new short[cells.length];
            Map<String, Integer> ids = new LinkedHashMap<>();
            ids.put("minecraft:air", 0);
            for (int cz = Math.floorDiv(oz, 16); cz <= Math.floorDiv(oz + sz - 1, 16); cz++)
                for (int cx = Math.floorDiv(ox, 16); cx <= Math.floorDiv(ox + sx - 1, 16); cx++) {
                    Chunk c = r.chunk(cx, cz);
                    for (int lz = 0; lz < sz; lz++) {
                        int bz = oz + lz;
                        if (Math.floorDiv(bz, 16) != cz) continue;
                        for (int lx = 0; lx < sx; lx++) {
                            int bx = ox + lx;
                            if (Math.floorDiv(bx, 16) != cx) continue;
                            int top = c == null ? Integer.MIN_VALUE : c.top(bx & 15, bz & 15, "MOTION_BLOCKING");
                            tops[lz * sx + lx] = top;
                            for (int ly = 0; ly < sy; ly++) {
                                int by = oy + ly, i = (ly * sz + lz) * sx + lx;
                                if (c == null) {
                                    cells[i] = (short) MosaicWorld.cell(Material.AIR, 0, 15);
                                    continue;
                                }
                                String name = c.block(bx & 15, by, bz & 15);
                                Material m = classify(name);
                                int sky = c.light(bx & 15, by, bz & 15, true, by > top ? 15 : 0);
                                int blk = c.light(bx & 15, by, bz & 15, false, 0);
                                cells[i] = (short) MosaicWorld.cell(m, blk, sky);
                                if (m != Material.AIR) {
                                    Integer id = ids.get(name);
                                    if (id == null && ids.size() < 65535) ids.put(name, id = ids.size());
                                    index[i] = (short) (id == null ? 0 : id.intValue());
                                }
                            }
                        }
                    }
                }
            if (computeLight) light(cells, tops, sx, sy, sz, oy);
            MosaicWorld.Snapshot snap = new MosaicWorld.Snapshot(sx, sy, sz, ox, oy, oz, cells, x, y, z, yaw, pitch, tod);
            if (farSize > 0) snap = snap.withFar(far(r, fx, fz, farSize));
            List<PreviewWorld.Entity> ents = entities(regions.resolveSibling("entities"), ox, oy, oz, sx, sy, sz);
            return new Loaded(snap, new MosaicWorld.BlockTable(List.copyOf(ids.keySet()), index), ents);
        }
    }

    /** Light the volume like the game (sky light from above every column, block light from light sources) instead of the saved light. */
    static boolean computeLight = true;

    private static boolean opaque(Material m) {
        return switch (m) {
            case AIR, WATER, LEAVES, PLANT, GLASS, ICE, LIGHT, LAVA -> false;
            default -> true;
        };
    }

    /** Sky light 15 above each column's top, spread sideways and down (1 less per block, more through water and leaves); block light from light sources and lava. */
    static void light(short[] cells, int[] tops, int sx, int sy, int sz, int oy) {
        int n = cells.length;
        byte[] sky = new byte[n], blk = new byte[n];
        int[] queue = new int[n * 4];
        for (int pass = 0; pass < 2; pass++) {
            byte[] lv = pass == 0 ? sky : blk;
            int head = 0, tail = 0;
            for (int ly = 0; ly < sy; ly++)
                for (int lz = 0; lz < sz; lz++)
                    for (int lx = 0; lx < sx; lx++) {
                        int i = (ly * sz + lz) * sx + lx;
                        Material m = MosaicWorld.material(cells[i] & 0xFFFF);
                        int start = 0;
                        if (pass == 0 && oy + ly > tops[lz * sx + lx] && !opaque(m)) start = 15;
                        if (pass == 1) start = m == Material.LAVA ? 15 : m == Material.LIGHT ? 14 : 0;
                        if (start > 0) {
                            lv[i] = (byte) start;
                            queue[tail++ % queue.length] = i;
                        }
                    }
            while (head < tail) {
                int i = queue[head++ % queue.length];
                int l = lv[i];
                int lx = i % sx, lz = (i / sx) % sz, ly = i / (sx * sz);
                for (int d = 0; d < 6; d++) {
                    int nx = lx + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = ly + (d == 2 ? 1 : d == 3 ? -1 : 0), nz = lz + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
                    int j = (ny * sz + nz) * sx + nx;
                    Material m = MosaicWorld.material(cells[j] & 0xFFFF);
                    if (opaque(m)) continue;
                    int loss = 1 + (m == Material.WATER || m == Material.LEAVES ? 1 : 0);
                    // sky light falls straight down through air without loss, like the game
                    if (pass == 0 && d == 3 && l == 15 && m == Material.AIR) loss = 0;
                    int nl = l - loss;
                    if (nl <= lv[j]) continue;
                    lv[j] = (byte) nl;
                    if (tail - head < queue.length) queue[tail++ % queue.length] = j; // a ring: the queue never holds more than the volume
                }
            }
        }
        for (int i = 0; i < n; i++) {
            Material m = MosaicWorld.material(cells[i] & 0xFFFF);
            cells[i] = (short) MosaicWorld.cell(m, blk[i], sky[i]);
        }
    }

    /** Heightmaps and the top block of every column around the camera (the far view). */
    private static MosaicWorld.Far far(Reader r, int fx, int fz, int fs) throws IOException {
        int fox = fx - fs / 2, foz = fz - fs / 2;
        short[] hs = new short[fs * fs], gs = new short[fs * fs];
        byte[] ms = new byte[fs * fs];
        java.util.Arrays.fill(hs, Short.MIN_VALUE);
        java.util.Arrays.fill(gs, Short.MIN_VALUE);
        for (int cz = Math.floorDiv(foz, 16); cz <= Math.floorDiv(foz + fs - 1, 16); cz++)
            for (int cx = Math.floorDiv(fox, 16); cx <= Math.floorDiv(fox + fs - 1, 16); cx++) {
                Chunk c = r.chunk(cx, cz);
                if (c == null) continue;
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int i = cx * 16 + x - fox, j = cz * 16 + z - foz;
                        if (i < 0 || j < 0 || i >= fs || j >= fs) continue;
                        int top = c.top(x, z, "MOTION_BLOCKING"), ground = c.top(x, z, "MOTION_BLOCKING_NO_LEAVES");
                        if (top == Integer.MIN_VALUE) continue;
                        String name = c.block(x, top, z);
                        Material m = classify(name);
                        if (m == Material.AIR) continue;
                        int k = j * fs + i;
                        hs[k] = (short) Math.clamp(top, -32767, 32767);
                        gs[k] = (short) Math.clamp(ground == Integer.MIN_VALUE ? top : ground, -32767, 32767);
                        ms[k] = (byte) m.id();
                        if (gs[k] < hs[k] && c.block(x, gs[k], z).matches(".*(_log|_stem|_wood|_hyphae)$")) ms[k] |= (byte) 0x80;
                        else if (gs[k] < hs[k] && classify(c.block(x, gs[k], z)) == Material.WOOD) ms[k] |= (byte) 0x80;
                    }
            }
        return new MosaicWorld.Far(fs, fox, foz, hs, ms, gs);
    }

    /** The single player's data file: players/data/<singleplayer uuid>.dat, else the newest one there. */
    private static Map<String, Object> playerFile(Path save, Map<String, Object> level) {
        try {
            Path dir = save.resolve("players").resolve("data");
            Path f = null;
            if (level != null && level.get("singleplayer_uuid") instanceof int[] u && u.length == 4) {
                long hi = ((long) u[0] << 32) | (u[1] & 0xFFFFFFFFL), lo = ((long) u[2] << 32) | (u[3] & 0xFFFFFFFFL);
                Path p = dir.resolve(new java.util.UUID(hi, lo) + ".dat");
                if (Files.isRegularFile(p)) f = p;
            }
            if (f == null && Files.isDirectory(dir))
                try (var s = Files.list(dir)) {
                    f = s.filter(p -> p.toString().endsWith(".dat")).max(java.util.Comparator.comparingLong(SaveWorld::mtime)).orElse(null);
                }
            return f == null ? null : root(gunzip(Files.readAllBytes(f)));
        } catch (Exception e) {
            return null;
        }
    }

    /** The time of day of a 26.x save (data/minecraft/world_clocks.dat), noon when unknown. */
    private static long clock(Path save, String dim) {
        try {
            Map<String, Object> c = compound(root(gunzip(Files.readAllBytes(save.resolve("data/minecraft/world_clocks.dat")))), "data");
            Map<String, Object> d = compound(c, dim);
            if (d == null) d = compound(c, "minecraft:overworld");
            return d != null && d.get("total_ticks") != null ? (long) num(d.get("total_ticks")) : 6000;
        } catch (Exception e) {
            return 6000;
        }
    }

    /** Entities saved in the volume (entities/r.*.mca: id, position, body yaw), the player left out. */
    @SuppressWarnings("unchecked")
    static List<PreviewWorld.Entity> entities(Path dir, int ox, int oy, int oz, int sx, int sy, int sz) {
        List<PreviewWorld.Entity> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Reader r = new Reader(dir, true)) {
            for (int cz = Math.floorDiv(oz, 16); cz <= Math.floorDiv(oz + sz - 1, 16); cz++)
                for (int cx = Math.floorDiv(ox, 16); cx <= Math.floorDiv(ox + sx - 1, 16); cx++) {
                    Map<String, Object> root = r.raw(cx, cz);
                    if (root == null || !(root.get("Entities") instanceof List<?> list)) continue;
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> e0)) continue;
                        Map<String, Object> e = (Map<String, Object>) e0;
                        if (!(e.get("id") instanceof String id) || !(e.get("Pos") instanceof List<?> pos) || pos.size() != 3) continue;
                        double x = num(pos.get(0)), y = num(pos.get(1)), z = num(pos.get(2));
                        if (x < ox || y < oy || z < oz || x >= ox + sx || y >= oy + sy || z >= oz + sz) continue;
                        float yaw = e.get("Rotation") instanceof List<?> rot && !rot.isEmpty() ? (float) num(rot.get(0)) : 0f;
                        out.add(new PreviewWorld.Entity(id, x, y, z, yaw));
                        if (out.size() >= 256) return out;
                    }
                }
        } catch (Exception ignored) {
            // entities are a bonus
        }
        return out;
    }

    private static Path regionDir(Path save, String dim) {
        String d = dim.replace("minecraft:", "");
        List<Path> tries = switch (d) {
            case "the_nether" -> List.of(save.resolve("dimensions/minecraft/the_nether/region"), save.resolve("DIM-1/region"));
            case "the_end" -> List.of(save.resolve("dimensions/minecraft/the_end/region"), save.resolve("DIM1/region"));
            default -> List.of(save.resolve("dimensions/minecraft/overworld/region"), save.resolve("region"));
        };
        for (Path p : tries) if (Files.isDirectory(p)) return p;
        return tries.get(0);
    }

    // ------------------------------------------------------------------ material classes by block name

    /** The material class of a block for rays and the preview (the game's MosaicWorldFeed does the same from block states). */
    static Material classify(String id) {
        String n = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (n.equals("air") || n.equals("cave_air") || n.equals("void_air") || n.equals("light") || n.equals("structure_void")) return Material.AIR;
        if (n.equals("water") || n.equals("bubble_column") || n.contains("kelp") || n.contains("seagrass")) return Material.WATER;
        if (n.equals("lava") || n.equals("magma_block")) return Material.LAVA;
        if (n.endsWith("leaves")) return Material.LEAVES;
        if (n.contains("glass") || n.equals("beacon")) return Material.GLASS;
        if (n.contains("ice")) return Material.ICE;
        if (n.contains("snow") || n.equals("powder_snow")) return Material.SNOW;
        if (n.contains("torch") || n.contains("lantern") || n.equals("glowstone") || n.equals("shroomlight") || n.contains("lamp")
            || n.contains("campfire") || n.equals("fire") || n.equals("soul_fire") || n.equals("end_rod") || n.contains("froglight")
            || n.equals("jack_o_lantern") || n.contains("candle") || n.equals("glow_lichen") || n.equals("sea_pickle")) return Material.LIGHT;
        if (n.contains("iron") || n.contains("gold") || n.contains("copper") || n.contains("netherite") || n.contains("anvil")
            || n.equals("chain") || n.contains("cauldron") || n.equals("hopper") || n.contains("bars")) return Material.METAL;
        if (n.endsWith("_log") || n.endsWith("_wood") || n.endsWith("_planks") || n.endsWith("_stem") || n.endsWith("_hyphae")
            || n.contains("fence") || n.contains("door") || n.contains("bookshelf") || n.contains("crafting") || n.contains("chest")
            || n.contains("barrel") || n.contains("sign") || n.equals("bamboo_block") || n.contains("_slab") && n.contains("oak")) return Material.WOOD;
        if (n.contains("sand") && !n.contains("sandstone") || n.equals("gravel") || n.contains("suspicious")) return Material.SAND;
        if (n.equals("grass_block") || n.contains("dirt") || n.equals("podzol") || n.equals("mycelium") || n.equals("farmland")
            || n.contains("mud") && !n.contains("brick") || n.equals("clay") || n.equals("dirt_path") || n.contains("moss_block")
            || n.equals("rooted_dirt") || n.contains("nylium")) return Material.DIRT;
        if (n.contains("grass") || n.contains("fern") || n.contains("flower") || n.contains("sapling") || n.contains("bush")
            || n.contains("mushroom") && !n.contains("block") || n.contains("tulip") || n.contains("orchid") || n.equals("dandelion")
            || n.equals("poppy") || n.contains("allium") || n.contains("bluet") || n.contains("daisy") || n.contains("lily")
            || n.equals("sugar_cane") || n.contains("vine") || n.equals("wheat") || n.equals("carrots") || n.equals("potatoes")
            || n.equals("beetroots") || n.contains("roots") || n.equals("cactus") || n.contains("bamboo") || n.contains("petals")
            || n.equals("sunflower") || n.equals("lilac") || n.equals("rose_bush") || n.equals("peony") || n.contains("dripleaf")
            || n.equals("hanging_roots") || n.contains("cornflower") || n.contains("lily_of_the_valley") || n.contains("wither_rose")
            || n.contains("moss_carpet") || n.contains("leaf_litter") || n.contains("cocoa")) return Material.PLANT;
        if (n.contains("stone") || n.contains("ore") || n.contains("deepslate") || n.contains("granite") || n.contains("diorite")
            || n.contains("andesite") || n.contains("tuff") || n.contains("calcite") || n.contains("basalt") || n.contains("blackstone")
            || n.contains("netherrack") || n.contains("brick") || n.equals("bedrock") || n.equals("obsidian") || n.contains("terracotta")
            || n.contains("dripstone") || n.contains("end_stone") || n.contains("prismarine") || n.contains("quartz")) return Material.STONE;
        return Material.OTHER;
    }

    // ------------------------------------------------------------------ Anvil

    /** One chunk: its sections (palette + packed indices + light) and heightmaps. */
    static final class Chunk {
        final int minY;
        final Map<Integer, Map<String, Object>> sections = new HashMap<>();
        final Map<String, Object> heightmaps;
        private final Map<Integer, String[]> palettes = new HashMap<>();

        @SuppressWarnings("unchecked")
        Chunk(Map<String, Object> root) {
            Map<String, Object> lvl = root.get("Level") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;
            int ySec = lvl.get("yPos") != null ? (int) num(lvl.get("yPos")) : -4;
            minY = ySec * 16;
            Object secs = lvl.containsKey("sections") ? lvl.get("sections") : lvl.get("Sections");
            if (secs instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> s && s.get("Y") != null) sections.put((int) num(s.get("Y")), (Map<String, Object>) s);
            heightmaps = lvl.get("Heightmaps") instanceof Map<?, ?> h ? (Map<String, Object>) h : Map.of();
        }

        @SuppressWarnings("unchecked")
        String block(int x, int y, int z) {
            Map<String, Object> s = sections.get(Math.floorDiv(y, 16));
            if (s == null || !(s.get("block_states") instanceof Map<?, ?> bs0)) return "minecraft:air";
            Map<String, Object> bs = (Map<String, Object>) bs0;
            String[] pal = palettes.computeIfAbsent(Math.floorDiv(y, 16), k -> {
                if (!(bs.get("palette") instanceof List<?> p)) return new String[] {"minecraft:air"};
                String[] out = new String[p.size()];
                for (int i = 0; i < out.length; i++)
                    out[i] = p.get(i) instanceof String s0 ? s0 // 26.x: the palette holds block names (state properties dropped)
                        : p.get(i) instanceof Map<?, ?> e && e.get("Name") instanceof String n ? n : "minecraft:air";
                return out;
            });
            if (pal.length == 1 || !(bs.get("data") instanceof long[] data)) return pal[0];
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
            int perLong = 64 / bits, i = ((y & 15) * 16 + z) * 16 + x;
            if (i / perLong >= data.length) return pal[0];
            int v = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
            return v < pal.length ? pal[v] : pal[0];
        }

        /** Saved light (0..15) at a block, or {@code missing} when the section has none. */
        int light(int x, int y, int z, boolean sky, int missing) {
            Map<String, Object> s = sections.get(Math.floorDiv(y, 16));
            if (s == null || !(s.get(sky ? "SkyLight" : "BlockLight") instanceof byte[] a) || a.length < 2048) return missing;
            int i = ((y & 15) * 16 + z) * 16 + x;
            return (a[i >> 1] >> ((i & 1) * 4)) & 15;
        }

        /** The y of the top block of a column in a heightmap, or Integer.MIN_VALUE. */
        int top(int x, int z, String map) {
            if (!(heightmaps.get(map) instanceof long[] data) || data.length == 0) return Integer.MIN_VALUE;
            int bits = bitsFor(data.length);
            int perLong = 64 / bits, i = z * 16 + x;
            if (i / perLong >= data.length) return Integer.MIN_VALUE;
            int v = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
            return v == 0 ? Integer.MIN_VALUE : minY + v - 1;
        }

        /** Bits per heightmap entry from the array length (256 entries, entries never span two longs). */
        private static int bitsFor(int longs) {
            for (int b = 1; b <= 32; b++) if ((256 + (64 / b) - 1) / (64 / b) == longs) return b;
            return 9;
        }
    }

    /** Region files of one dimension, opened once each. */
    static final class Reader implements AutoCloseable {
        private final Path dir;
        private final Map<Long, RandomAccessFile> files = new HashMap<>();
        private final Map<Long, Chunk> chunks = new HashMap<>();

        private final boolean raw;

        Reader(Path dir) { this(dir, false); }

        Reader(Path dir, boolean raw) {
            this.dir = dir;
            this.raw = raw;
        }

        /** A chunk's whole NBT (entity chunks), or null. */
        Map<String, Object> raw(int cx, int cz) {
            try {
                return readRoot(cx, cz);
            } catch (Exception e) {
                return null;
            }
        }

        Chunk chunk(int cx, int cz) throws IOException {
            long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            if (chunks.containsKey(key)) return chunks.get(key);
            Chunk c = null;
            try {
                c = readChunk(cx, cz);
            } catch (IOException | RuntimeException e) {
                // a broken or unsupported chunk is left out
            }
            chunks.put(key, c);
            return c;
        }

        private Chunk readChunk(int cx, int cz) throws IOException {
            Map<String, Object> root = readRoot(cx, cz);
            if (root == null) return null;
            if (root.get("Status") instanceof String st && !st.endsWith("full") && !st.endsWith("spawn") && !st.endsWith("light")
                && !st.endsWith("features") && !st.endsWith("initialize_light")) return null;
            return new Chunk(root);
        }

        private Map<String, Object> readRoot(int cx, int cz) throws IOException {
            int rx = Math.floorDiv(cx, 32), rz = Math.floorDiv(cz, 32);
            long rk = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
            RandomAccessFile f = files.get(rk);
            if (f == null && !files.containsKey(rk)) {
                Path p = dir.resolve("r." + rx + "." + rz + ".mca");
                f = Files.isRegularFile(p) ? new RandomAccessFile(p.toFile(), "r") : null;
                files.put(rk, f);
            }
            if (f == null) return null;
            f.seek(4L * ((cx & 31) + (cz & 31) * 32));
            int loc = f.readInt();
            int sector = loc >>> 8;
            if (sector == 0) return null;
            f.seek(sector * 4096L);
            int len = f.readInt();
            int type = f.readUnsignedByte();
            if (len <= 1 || len > 64 * 1024 * 1024 || (type & 0x80) != 0) return null; // external .mcc chunks are not read
            byte[] data = new byte[len - 1];
            f.readFully(data);
            InputStream in = switch (type) {
                case 1 -> new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data));
                case 2 -> new java.util.zip.InflaterInputStream(new ByteArrayInputStream(data));
                case 3 -> new ByteArrayInputStream(data);
                default -> null; // LZ4 or custom compression: not supported here
            };
            if (in == null) return null;
            return root(in.readAllBytes());
        }

        @Override public void close() {
            for (RandomAccessFile f : files.values()) {
                try {
                    if (f != null) f.close();
                } catch (IOException ignored) {
                    // read only
                }
            }
        }
    }

    // ------------------------------------------------------------------ NBT

    private static byte[] gunzip(byte[] b) throws IOException {
        if (b.length > 2 && (b[0] & 255) == 0x1F && (b[1] & 255) == 0x8B)
            return new java.util.zip.GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes();
        return b;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> root(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("not an NBT compound");
        in.readUTF();
        return (Map<String, Object>) payload(in, 10, 0);
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[checked(in.readInt())];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readUnsignedByte(), n = checked(in.readInt());
                List<Object> l = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) l.add(payload(in, t, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                for (int t = in.readUnsignedByte(); t != 0; t = in.readUnsignedByte()) {
                    String name = in.readUTF();
                    m.put(name, payload(in, t, depth + 1));
                }
                return m;
            }
            case 11: {
                int[] a = new int[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private static int checked(int n) throws IOException {
        if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("bad NBT length " + n);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Map<String, Object> m, String key) {
        return m != null && m.get(key) instanceof Map<?, ?> c ? (Map<String, Object>) c : null;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    static String describe(Path save) { return save.getFileName().toString().toLowerCase(Locale.ROOT); }
}
