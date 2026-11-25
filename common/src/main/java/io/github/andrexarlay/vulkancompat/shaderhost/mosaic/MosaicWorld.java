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
    public static final int SX = 96, SY = 48, SZ = 96;
    public static final int CELLS = SX * SY * SZ;
    public static final int HEADER_BYTES = 48;
    public static final int BYTES = HEADER_BYTES + CELLS * 2;
    /** Recenter when the camera drifts this many blocks from the volume center on any axis. */
    public static final int RECENTER = 16;

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
    public record Snapshot(int ox, int oy, int oz, short[] cells, double camX, double camY, double camZ, float yaw, float pitch,
                           float timeOfDay) {
        public static final int MAGIC = 0x3157534D; // "MSW1"
        public static final int HEADER = 64;

        public int get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= SX || ly >= SY || lz >= SZ) return 0;
            return cells[(ly * SZ + lz) * SX + lx] & 0xFFFF;
        }

        public byte[] toBytes() {
            ByteBuffer b = ByteBuffer.allocate(HEADER + CELLS * 2).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(MAGIC).putInt(SX).putInt(SY).putInt(SZ).putInt(ox).putInt(oy).putInt(oz);
            b.putDouble(camX).putDouble(camY).putDouble(camZ).putFloat(yaw).putFloat(pitch).putFloat(timeOfDay);
            b.position(HEADER);
            for (short c : cells) b.putShort(c);
            return b.array();
        }

        /** Null when the bytes are not a snapshot of this volume size. */
        public static Snapshot read(byte[] data) {
            if (data == null || data.length != HEADER + CELLS * 2) return null;
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != MAGIC || b.getInt() != SX || b.getInt() != SY || b.getInt() != SZ) return null;
            int ox = b.getInt(), oy = b.getInt(), oz = b.getInt();
            double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
            float yaw = b.getFloat(), pitch = b.getFloat(), tod = b.getFloat();
            short[] cells = new short[CELLS];
            b.position(HEADER);
            for (int i = 0; i < CELLS; i++) cells[i] = b.getShort();
            return new Snapshot(ox, oy, oz, cells, x, y, z, yaw, pitch, tod);
        }

        /** The storage-buffer contents for a camera at (x, y, z): the 48-byte header of {@link Volume#writeHeader}, then cells. */
        public void writeBuffer(ByteBuffer b, double x, double y, double z) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
            b.putInt(0, ox - fx).putInt(4, oy - fy).putInt(8, oz - fz).putInt(12, 1);
            b.putFloat(16, (float) (x - fx)).putFloat(20, (float) (y - fy)).putFloat(24, (float) (z - fz)).putFloat(28, 0f);
            b.putInt(32, SX).putInt(36, SY).putInt(40, SZ).putInt(44, 0);
            for (int i = 0; i < CELLS; i++) b.putShort(HEADER_BYTES + i * 2, cells[i]);
        }
    }

    /** The CPU copy of the volume, refreshed incrementally. Not thread-safe: use from the render thread. */
    public static final class Volume {
        private short[] cells = new short[CELLS];
        private int ox, oy, oz;             // absolute block coordinates of cell (0, 0, 0)
        private boolean placed;
        private int cursor;                 // next cell to rescan
        private int scannedSincePlace;      // cells scanned since the last recenter
        private long version;

        public long version() { return version; }
        public int originX() { return ox; }
        public int originY() { return oy; }
        public int originZ() { return oz; }

        /** Valid once every cell was sampled at least once after the last (re)placement. */
        public boolean complete() { return placed && scannedSincePlace >= CELLS; }

        public int get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= SX || ly >= SY || lz >= SZ) return 0;
            return cells[(ly * SZ + lz) * SX + lx] & 0xFFFF;
        }

        /**
         * Follows the camera and rescans up to {@code budget} cells. Moving the volume keeps the overlapping cells, so
         * a walk never blanks the area around the player. Returns true when any cell changed (upload needed).
         */
        public boolean update(Sampler sampler, double camX, double camY, double camZ, int budget) {
            int cx = (int) Math.floor(camX), cy = (int) Math.floor(camY), cz = (int) Math.floor(camZ);
            int wantX = cx - SX / 2, wantY = cy - SY / 2, wantZ = cz - SZ / 2;
            boolean changed = false;
            if (!placed) {
                ox = wantX;
                oy = wantY;
                oz = wantZ;
                placed = true;
                cursor = 0;
                scannedSincePlace = 0;
                changed = true;
            } else if (Math.abs(wantX - ox) >= RECENTER || Math.abs(wantY - oy) >= RECENTER || Math.abs(wantZ - oz) >= RECENTER) {
                shift(wantX, wantY, wantZ);
                changed = true;
            }
            for (int n = 0; n < budget; n++) {
                int i = cursor;
                cursor = (cursor + 1) % CELLS;
                int lx = i % SX, lz = (i / SX) % SZ, ly = i / (SX * SZ);
                short v = (short) sampler.sample(ox + lx, oy + ly, oz + lz);
                if (cells[i] != v) {
                    cells[i] = v;
                    changed = true;
                }
                if (scannedSincePlace < CELLS) scannedSincePlace++;
            }
            if (changed) version++;
            return changed;
        }

        private void shift(int nx, int ny, int nz) {
            short[] next = new short[CELLS];
            for (int ly = 0; ly < SY; ly++)
                for (int lz = 0; lz < SZ; lz++)
                    for (int lx = 0; lx < SX; lx++) {
                        int x = nx + lx - ox, y = ny + ly - oy, z = nz + lz - oz;
                        if (x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ) continue;
                        next[(ly * SZ + lz) * SX + lx] = cells[(y * SZ + z) * SX + x];
                    }
            cells = next;
            ox = nx;
            oy = ny;
            oz = nz;
            // the overlap stays valid; the new margin fills as the cursor sweeps
            scannedSincePlace = Math.min(scannedSincePlace, CELLS / 2);
        }

        /** A frozen copy with the camera that saw it (see {@link Snapshot}). */
        public Snapshot snapshot(double camX, double camY, double camZ, float yaw, float pitch, float timeOfDay) {
            return new Snapshot(ox, oy, oz, cells.clone(), camX, camY, camZ, yaw, pitch, timeOfDay);
        }

        /** The 48-byte header for this frame's camera. */
        public void writeHeader(ByteBuffer b, double camX, double camY, double camZ) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(camX), fy = (int) Math.floor(camY), fz = (int) Math.floor(camZ);
            b.putInt(0, ox - fx).putInt(4, oy - fy).putInt(8, oz - fz).putInt(12, placed ? 1 : 0);
            b.putFloat(16, (float) (camX - fx)).putFloat(20, (float) (camY - fy)).putFloat(24, (float) (camZ - fz)).putFloat(28, 0f);
            b.putInt(32, SX).putInt(36, SY).putInt(40, SZ).putInt(44, 0);
        }

        /** The cell bytes (after the header): little-endian 16-bit cells. */
        public void writeCells(ByteBuffer b, int offset) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < CELLS; i++) b.putShort(offset + i * 2, cells[i]);
        }
    }
}
