package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The world volume a Mosaic shader can read: for every block in a box around the camera, a material class and the
 * sky and block light levels. The game samples it a slice per frame ({@link Volume#update}), the custom RT stage
 * receives it as a storage buffer on binding {@link #BINDING}, and generated code reads it through the
 * {@code mosaic*} helpers (material at a position, along a ray, at the pixel's hit).
 * <p>
 * Buffer layout (std430): {@code ivec4 originRel} (volume origin minus floor(camera), w = 1 when valid),
 * {@code vec4 camFrac} (camera minus floor(camera)), {@code ivec4 size}, then one 16-bit cell per block, two per
 * uint, x fastest, then z, then y. A cell is {@code material | blockLight << 8 | skyLight << 12}.
 */
public final class MosaicWorld {

    private MosaicWorld() {}

    public static final int BINDING = 15;
    /** Defaults of the graph settings worldRadius / worldHeight (MosaicSettings): 96 x 48 x 96 blocks. */
    public static final int DEFAULT_SX = 96, DEFAULT_SY = 48, DEFAULT_SZ = 96;
    public static final int HEADER_BYTES = 48;

    /** Storage-buffer bytes of a volume of this size (header + 16-bit cells). */
    public static int bytes(int sx, int sy, int sz) { return HEADER_BYTES + sx * sy * sz * 2; }

    /** Material classes, by id. The ids are part of the shader contract: never renumber. */
    public enum Material {
        AIR("Air"), WATER("Water"), LAVA("Lava"), STONE("Stone & ore"), DIRT("Dirt & grass"), LEAVES("Leaves"),
        PLANT("Plants & flowers"), WOOD("Wood"), SAND("Sand & gravel"), SNOW("Snow"), ICE("Ice"), GLASS("Glass"),
        METAL("Metal"), LIGHT("Light sources"), OTHER("Other blocks");

        public final String label;

        Material(String label) { this.label = label; }

        public int id() { return ordinal(); }

        public static Material of(int id) { return id >= 0 && id < values().length ? values()[id] : OTHER; }
    }

    public static int cell(Material m, int blockLight, int skyLight) {
        return m.id() | (Math.clamp(blockLight, 0, 15) << 8) | (Math.clamp(skyLight, 0, 15) << 12);
    }

    public static Material material(int cell) { return Material.of(cell & 0xFF); }
    public static int blockLight(int cell) { return (cell >> 8) & 15; }
    public static int skyLight(int cell) { return (cell >> 12) & 15; }

    /** Reads one block of the world: a packed cell ({@link #cell}). */
    @FunctionalInterface
    public interface Sampler {
        int sample(int x, int y, int z);
    }

    /**
     * A frozen volume and the camera that saw it: the game writes one ({@code bedray-studio/preview-world.bin}) when
     * Mosaic Studio opens, and Studio's preview renders and lights it like the game. Positions are absolute blocks;
     * yaw / pitch in Minecraft's degrees; time of day as the shaders take it (0 noon, 0.25 sunset, 0.5 midnight).
     */
    public record Snapshot(int sx, int sy, int sz, int ox, int oy, int oz, short[] cells, double camX, double camY, double camZ,
                           float yaw, float pitch, float timeOfDay, Far far) {
        public static final int MAGIC = 0x3157534D; // "MSW1"
        public static final int FAR_MAGIC = 0x3152414D; // "MAR1"
        public static final int HEADER = 64;

        public Snapshot(int sx, int sy, int sz, int ox, int oy, int oz, short[] cells, double camX, double camY, double camZ,
                        float yaw, float pitch, float timeOfDay) {
            this(sx, sy, sz, ox, oy, oz, cells, camX, camY, camZ, yaw, pitch, timeOfDay, null);
        }

        public Snapshot withFar(Far f) { return new Snapshot(sx, sy, sz, ox, oy, oz, cells, camX, camY, camZ, yaw, pitch, timeOfDay, f); }

        public int cellCount() { return sx * sy * sz; }

        public int bytes() { return MosaicWorld.bytes(sx, sy, sz); }

        public int get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return 0;
            return cells[(ly * sz + lz) * sx + lx] & 0xFFFF;
        }

        public byte[] toBytes() {
            int farBytes = far == null ? 0 : 16 + far.size() * far.size() * 3 + (far.ground() == null ? 0 : 4 + far.size() * far.size() * 2);
            ByteBuffer b = ByteBuffer.allocate(HEADER + cells.length * 2 + farBytes).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(MAGIC).putInt(sx).putInt(sy).putInt(sz).putInt(ox).putInt(oy).putInt(oz);
            b.putDouble(camX).putDouble(camY).putDouble(camZ).putFloat(yaw).putFloat(pitch).putFloat(timeOfDay);
            b.position(HEADER);
            for (short c : cells) b.putShort(c);
            if (far != null) {
                b.putInt(FAR_MAGIC).putInt(far.size()).putInt(far.ox()).putInt(far.oz());
                for (short h : far.heights()) b.putShort(h);
                b.put(far.materials());
                if (far.ground() != null) {
                    b.putInt(Far.GROUND_MAGIC);
                    for (short g : far.ground()) b.putShort(g);
                }
            }
            return b.array();
        }

        /** Null when the bytes are not a snapshot (any volume size from 8 to 512 blocks per side). */
        public static Snapshot read(byte[] data) {
            if (data == null || data.length < HEADER) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != MAGIC) return null;
            int sx = b.getInt(), sy = b.getInt(), sz = b.getInt();
            if (sx < 8 || sy < 8 || sz < 8 || sx > 512 || sy > 512 || sz > 512) return null;
            if (data.length < HEADER + sx * sy * sz * 2) return null;
            int ox = b.getInt(), oy = b.getInt(), oz = b.getInt();
            double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
            float yaw = b.getFloat(), pitch = b.getFloat(), tod = b.getFloat();
            short[] cells = new short[sx * sy * sz];
            b.position(HEADER);
            for (int i = 0; i < cells.length; i++) cells[i] = b.getShort();
            Far far = null;
            if (b.remaining() >= 16 && b.getInt() == FAR_MAGIC) {
                int fs = b.getInt(), fox = b.getInt(), foz = b.getInt();
                if (fs > 0 && fs <= 2048 && b.remaining() >= fs * fs * 3) {
                    short[] hs = new short[fs * fs];
                    for (int i = 0; i < hs.length; i++) hs[i] = b.getShort();
                    byte[] ms = new byte[fs * fs];
                    b.get(ms);
                    short[] gs = null;
                    if (b.remaining() >= 4 + fs * fs * 2 && b.getInt() == Far.GROUND_MAGIC) {
                        gs = new short[fs * fs];
                        for (int i = 0; i < gs.length; i++) gs[i] = b.getShort();
                    }
                    far = new Far(fs, fox, foz, hs, ms, gs);
                }
            }
            return new Snapshot(sx, sy, sz, ox, oy, oz, cells, x, y, z, yaw, pitch, tod, far);
        }

        /** The storage-buffer contents for a camera at (x, y, z): the 48-byte header of {@link Volume#writeHeader}, then cells. */
        public void writeBuffer(ByteBuffer b, double x, double y, double z) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
            b.putInt(0, ox - fx).putInt(4, oy - fy).putInt(8, oz - fz).putInt(12, 1);
            b.putFloat(16, (float) (x - fx)).putFloat(20, (float) (y - fy)).putFloat(24, (float) (z - fz)).putFloat(28, 0f);
            b.putInt(32, sx).putInt(36, sy).putInt(40, sz).putInt(44, 0);
            for (int i = 0; i < cells.length; i++) b.putShort(HEADER_BYTES + i * 2, cells[i]);
        }
    }

    /**
     * Which block every cell of a snapshot is (for the preview's real block textures): a name table (index 0 = air) and
     * one index per cell in the snapshot's order. Appended to {@code preview-world.bin} after everything else, found by
     * its 8-byte trailer (int start of the section, int {@link #TRAILER}), so readers that do not know it skip it.
     */
    public record BlockTable(java.util.List<String> names, short[] index, java.util.List<String> biomeNames, short[] biomeColumns) {
        public static final int MAGIC = 0x314B4C42;   // "BLK1"
        public static final int MAGIC2 = 0x324B4C42;  // "BLK2": also the biome of every column (x fastest, then z)

        public BlockTable(java.util.List<String> names, short[] index) { this(names, index, null, null); }
        public static final int TRAILER = 0x544B4C42; // "BLKT"

        /** The section plus its trailer, to append to a snapshot's bytes that start at {@code start}. */
        public byte[] toBytes(int start) {
            java.io.ByteArrayOutputStream names0 = new java.io.ByteArrayOutputStream();
            for (String n : names) {
                byte[] u = n.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                names0.write(u.length & 255);
                names0.write(u.length >> 8 & 255);
                names0.write(u, 0, u.length);
            }
            boolean bio = biomeNames != null && biomeColumns != null;
            java.io.ByteArrayOutputStream bn = new java.io.ByteArrayOutputStream();
            if (bio) for (String n : biomeNames) {
                byte[] u = n.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bn.write(u.length & 255);
                bn.write(u.length >> 8 & 255);
                bn.write(u, 0, u.length);
            }
            int bioBytes = bio ? 8 + bn.size() + biomeColumns.length * 2 : 0;
            ByteBuffer b = ByteBuffer.allocate(12 + names0.size() + index.length * 2 + bioBytes + 8).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(bio ? MAGIC2 : MAGIC).putInt(names.size()).putInt(index.length);
            b.put(names0.toByteArray());
            for (short s : index) b.putShort(s);
            if (bio) {
                b.putInt(biomeNames.size()).putInt(biomeColumns.length);
                b.put(bn.toByteArray());
                for (short s : biomeColumns) b.putShort(s);
            }
            b.putInt(start).putInt(TRAILER);
            return b.array();
        }

        /** The table of a snapshot file, or null when it has none. */
        public static BlockTable read(byte[] data) {
            if (data == null || data.length < 20) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(data.length - 4) != TRAILER) return null;
            int start = b.getInt(data.length - 8);
            if (start < 0 || start > data.length - 20) return null;
            b.position(start);
            int magic = b.getInt();
            if (magic != MAGIC && magic != MAGIC2) return null;
            int n = b.getInt(), cells = b.getInt();
            if (n < 1 || n > 65535 || cells < 0 || cells > 512 * 512 * 512) return null;
            java.util.List<String> names = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int len = (b.get() & 255) | (b.get() & 255) << 8;
                byte[] u = new byte[len];
                b.get(u);
                names.add(new String(u, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (b.remaining() < cells * 2 + 8) return null;
            short[] idx = new short[cells];
            for (int i = 0; i < cells; i++) idx[i] = b.getShort();
            if (magic == MAGIC2 && b.remaining() >= 16) {
                int bnCount = b.getInt(), cols = b.getInt();
                if (bnCount > 0 && bnCount < 4096 && cols > 0 && cols <= 512 * 512) {
                    java.util.List<String> bns = new java.util.ArrayList<>(bnCount);
                    for (int i = 0; i < bnCount; i++) {
                        int len = (b.get() & 255) | (b.get() & 255) << 8;
                        byte[] u = new byte[len];
                        b.get(u);
                        bns.add(new String(u, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    short[] bc = new short[cols];
                    for (int i = 0; i < cols; i++) bc[i] = b.getShort();
                    return new BlockTable(java.util.List.copyOf(names), idx, java.util.List.copyOf(bns), bc);
                }
            }
            return new BlockTable(java.util.List.copyOf(names), idx);
        }

        /** Snapshot bytes with this table appended. */
        public byte[] appendTo(byte[] snapshot) {
            byte[] tail = toBytes(snapshot.length);
            byte[] out = java.util.Arrays.copyOf(snapshot, snapshot.length + tail.length);
            System.arraycopy(tail, 0, out, snapshot.length, tail.length);
            return out;
        }
    }

    /**
     * The land beyond the volume, for the preview's far view: a size x size grid around the camera (x fastest), the
     * absolute y of the top block of each column (Short.MIN_VALUE unknown) and its material class.
     */
    public record Far(int size, int ox, int oz, short[] heights, byte[] materials, short[] ground) {
        public static final int GROUND_MAGIC = 0x31444E47; // "GND1"

        public Far(int size, int ox, int oz, short[] heights, byte[] materials) { this(size, ox, oz, heights, materials, null); }

        /** Ground height of a column under its leaves (the top when unknown). */
        public short groundAt(int i) { return ground == null ? heights[i] : ground[i]; }

        /**
         * Storage-buffer form: ivec4 (origin x / z - floor(camera), size, 1), ivec4 (floor(camera y), 0, 0, 0), then one
         * uint per column: height + 32768 | material << 16. {@code cells} false writes only the camera-relative header.
         */
        public void writeBuffer(ByteBuffer b, double camX, double camY, double camZ, boolean cells) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(camX), fz = (int) Math.floor(camZ);
            b.putInt(0, ox - fx).putInt(4, oz - fz).putInt(8, size).putInt(12, 1);
            b.putInt(16, (int) Math.floor(camY)).putInt(20, maxTop()).putInt(24, 0).putInt(28, 0);
            // canopy depth: blocks from the top (leaves) down to the ground under them, 0 for bare ground
            if (cells) for (int i = 0; i < size * size; i++) {
                int canopy = heights[i] == Short.MIN_VALUE ? 0 : Math.clamp(heights[i] - groundAt(i), 0, 255);
                b.putInt(32 + i * 4, ((heights[i] + 32768) & 0xFFFF) | ((materials[i] & 0xFF) << 16) | (canopy << 24));
            }
        }

        public int bytes() { return 32 + size * size * 4; }

        /** The highest top of the field (absolute y), so rays above it stop early. */
        public int maxTop() {
            int m = Short.MIN_VALUE;
            for (short h : heights) if (h != Short.MIN_VALUE && h > m) m = h;
            return m;
        }
    }

    /**
     * The CPU copy of the volume, refreshed incrementally. Its size comes from the graph settings worldRadius /
     * worldHeight; it recenters when the camera drifts {@code recenter} blocks (a sixth of the width) from the middle.
     * Not thread-safe: use from the render thread.
     */
    public static final class Volume {
        private final int sx, sy, sz, cellCount, recenter;
        private short[] cells;
        private int ox, oy, oz;             // absolute block coordinates of cell (0, 0, 0)
        private boolean placed;
        private int cursor;                 // next cell to rescan
        private int scannedSincePlace;      // cells scanned since the last recenter
        private long version;

        public Volume() { this(DEFAULT_SX, DEFAULT_SY, DEFAULT_SZ); }

        public Volume(int sx, int sy, int sz) {
            this.sx = Math.clamp(sx, 8, 512);
            this.sy = Math.clamp(sy, 8, 512);
            this.sz = Math.clamp(sz, 8, 512);
            this.cellCount = this.sx * this.sy * this.sz;
            this.recenter = Math.max(2, Math.min(this.sx, this.sz) / 6);
            this.cells = new short[cellCount];
        }

        public int sizeX() { return sx; }
        public int sizeY() { return sy; }
        public int sizeZ() { return sz; }
        public int cellCount() { return cellCount; }
        public int bytes() { return MosaicWorld.bytes(sx, sy, sz); }
        public int recenter() { return recenter; }
        public long version() { return version; }
        public int originX() { return ox; }
        public int originY() { return oy; }
        public int originZ() { return oz; }

        /** Valid once every cell was sampled at least once after the last (re)placement. */
        public boolean complete() { return placed && scannedSincePlace >= cellCount; }

        public int get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) return 0;
            return cells[(ly * sz + lz) * sx + lx] & 0xFFFF;
        }

        /**
         * Follows the camera and rescans up to {@code budget} cells. Moving the volume keeps the overlapping cells, so
         * a walk never blanks the area around the player. Returns true when any cell changed (upload needed).
         */
        public boolean update(Sampler sampler, double camX, double camY, double camZ, int budget) {
            int cx = (int) Math.floor(camX), cy = (int) Math.floor(camY), cz = (int) Math.floor(camZ);
            int wantX = cx - sx / 2, wantY = cy - sy / 2, wantZ = cz - sz / 2;
            boolean changed = false;
            if (!placed) {
                ox = wantX;
                oy = wantY;
                oz = wantZ;
                placed = true;
                cursor = 0;
                scannedSincePlace = 0;
                changed = true;
            } else if (Math.abs(wantX - ox) >= recenter || Math.abs(wantY - oy) >= recenter || Math.abs(wantZ - oz) >= recenter) {
                shift(wantX, wantY, wantZ);
                changed = true;
            }
            for (int n = 0; n < budget; n++) {
                int i = cursor;
                cursor = (cursor + 1) % cellCount;
                int lx = i % sx, lz = (i / sx) % sz, ly = i / (sx * sz);
                short v = (short) sampler.sample(ox + lx, oy + ly, oz + lz);
                if (cells[i] != v) {
                    cells[i] = v;
                    changed = true;
                }
                if (scannedSincePlace < cellCount) scannedSincePlace++;
            }
            if (changed) version++;
            return changed;
        }

        private void shift(int nx, int ny, int nz) {
            short[] next = new short[cellCount];
            for (int ly = 0; ly < sy; ly++)
                for (int lz = 0; lz < sz; lz++)
                    for (int lx = 0; lx < sx; lx++) {
                        int x = nx + lx - ox, y = ny + ly - oy, z = nz + lz - oz;
                        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;
                        next[(ly * sz + lz) * sx + lx] = cells[(y * sz + z) * sx + x];
                    }
            cells = next;
            ox = nx;
            oy = ny;
            oz = nz;
            // the overlap stays valid; the new margin fills as the cursor sweeps
            scannedSincePlace = Math.min(scannedSincePlace, cellCount / 2);
        }

        /** A frozen copy with the camera that saw it (see {@link Snapshot}). */
        public Snapshot snapshot(double camX, double camY, double camZ, float yaw, float pitch, float timeOfDay) {
            return new Snapshot(sx, sy, sz, ox, oy, oz, cells.clone(), camX, camY, camZ, yaw, pitch, timeOfDay);
        }

        /** The 48-byte header for this frame's camera. */
        public void writeHeader(ByteBuffer b, double camX, double camY, double camZ) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(camX), fy = (int) Math.floor(camY), fz = (int) Math.floor(camZ);
            b.putInt(0, ox - fx).putInt(4, oy - fy).putInt(8, oz - fz).putInt(12, placed ? 1 : 0);
            b.putFloat(16, (float) (camX - fx)).putFloat(20, (float) (camY - fy)).putFloat(24, (float) (camZ - fz)).putFloat(28, 0f);
            b.putInt(32, sx).putInt(36, sy).putInt(40, sz).putInt(44, 0);
        }

        /** The cell bytes (after the header): little-endian 16-bit cells. */
        public void writeCells(ByteBuffer b, int offset) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < cellCount; i++) b.putShort(offset + i * 2, cells[i]);
        }
    }
}
