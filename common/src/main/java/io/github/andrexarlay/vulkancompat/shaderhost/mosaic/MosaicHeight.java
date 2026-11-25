package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The height field a Mosaic shader can read for distant shadows: for every column of a 256 x 256 square around the camera,
 * the height above the highest block that blocks motion (leaves included), as the game's MOTION_BLOCKING heightmap gives
 * it. The world volume ({@link MosaicWorld}) covers 96 x 48 x 96 blocks in detail; shadow rays that leave it continue over
 * this field ({@code mosaicFarShadow}), so hills and trees far away still cast shadows.
 * <p>
 * Buffer layout (std430, binding {@link #BINDING}): {@code ivec4 originRel} (x: origin x - floor(camera x), y: origin z -
 * floor(camera z), z: floor(camera y), w: 1 when valid), {@code vec4 camFrac} (camera - floor(camera)), then one signed
 * 16-bit absolute height per column, two per uint, x fastest.
 */
public final class MosaicHeight {

    private MosaicHeight() {}

    public static final int BINDING = 18;
    public static final int SIZE = 256;
    public static final int COLUMNS = SIZE * SIZE;
    public static final int HEADER_BYTES = 32;
    public static final int BYTES = HEADER_BYTES + COLUMNS * 2;
    /** Recenter when the camera drifts this many blocks from the field's center. */
    public static final int RECENTER = 32;

    /** Height of one column (absolute y above its top motion-blocking block). */
    @FunctionalInterface
    public interface Sampler {
        int height(int x, int z);
    }

    /** CPU copy of the field, refreshed a slice per frame. Render thread only. */
    public static final class Field {
        private short[] heights = new short[COLUMNS];
        private int ox, oz;
        private boolean placed;
        private int cursor;
        private long version;

        public long version() { return version; }
        public int originX() { return ox; }
        public int originZ() { return oz; }

        public int get(int x, int z) {
            int lx = x - ox, lz = z - oz;
            if (lx < 0 || lz < 0 || lx >= SIZE || lz >= SIZE) return Short.MIN_VALUE;
            return heights[lz * SIZE + lx];
        }

        /** Follows the camera (keeping the overlap on a move) and resamples up to {@code budget} columns. */
        public boolean update(Sampler sampler, double camX, double camZ, int budget) {
            int wantX = (int) Math.floor(camX) - SIZE / 2, wantZ = (int) Math.floor(camZ) - SIZE / 2;
            boolean changed = false;
            if (!placed) {
                java.util.Arrays.fill(heights, Short.MIN_VALUE);
                ox = wantX;
                oz = wantZ;
                placed = true;
                cursor = 0;
                changed = true;
            } else if (Math.abs(wantX - ox) >= RECENTER || Math.abs(wantZ - oz) >= RECENTER) {
                short[] next = new short[COLUMNS];
                java.util.Arrays.fill(next, Short.MIN_VALUE);
                for (int lz = 0; lz < SIZE; lz++)
                    for (int lx = 0; lx < SIZE; lx++) {
                        int x = wantX + lx - ox, z = wantZ + lz - oz;
                        if (x >= 0 && z >= 0 && x < SIZE && z < SIZE) next[lz * SIZE + lx] = heights[z * SIZE + x];
                    }
                heights = next;
                ox = wantX;
                oz = wantZ;
                changed = true;
            }
            for (int n = 0; n < budget; n++) {
                int i = cursor;
                cursor = (cursor + 1) % COLUMNS;
                short h = (short) Math.clamp(sampler.height(ox + i % SIZE, oz + i / SIZE), Short.MIN_VALUE, Short.MAX_VALUE);
                if (heights[i] != h) {
                    heights[i] = h;
                    changed = true;
                }
            }
            if (changed) version++;
            return changed;
        }

        public void writeHeader(ByteBuffer b, double camX, double camY, double camZ) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            int fx = (int) Math.floor(camX), fy = (int) Math.floor(camY), fz = (int) Math.floor(camZ);
            b.putInt(0, ox - fx).putInt(4, oz - fz).putInt(8, fy).putInt(12, placed ? 1 : 0);
            b.putFloat(16, (float) (camX - fx)).putFloat(20, (float) (camY - fy)).putFloat(24, (float) (camZ - fz)).putFloat(28, 0f);
        }

        public void writeColumns(ByteBuffer b, int offset) {
            b.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < COLUMNS; i++) b.putShort(offset + i * 2, heights[i]);
        }
    }

    static final String GLSL = """

            // Height field around the camera (see MosaicHeight): distant shadows beyond the world volume.
            layout(std430, set = 0, binding = 18) readonly buffer BedrayHeight {
                ivec4 originRel;   // x, y: field origin x / z - floor(camera); z: floor(camera y); w: 1 when valid
                vec4 camFrac;
                uint heights[];    // two signed 16-bit absolute heights per uint, x fastest
            } bedrayHeight;
            /** Height above the top motion-blocking block under a camera-relative position, camera-relative; -1e9 unknown. */
            float mosaicHeightAt(vec3 rel) {
                if (bedrayHeight.originRel.w == 0) return -1.0e9;
                ivec2 c = ivec2(floor(rel.xz + bedrayHeight.camFrac.xz)) - bedrayHeight.originRel.xy;
                if (c.x < 0 || c.y < 0 || c.x >= 256 || c.y >= 256) return -1.0e9;
                int i = c.y * 256 + c.x;
                int h = int((bedrayHeight.heights[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu);
                if (h >= 32768) h -= 65536;
                if (h <= -32768) return -1.0e9;
                return float(h - bedrayHeight.originRel.z) - bedrayHeight.camFrac.y;
            }
            /** 1 when a ray from rel toward L clears the height field between tStart and tMax, 0 when terrain blocks it. */
            float mosaicFarShadow(vec3 rel, vec3 L, float tStart, float tMax) {
                float t = tStart;
                for (int i = 0; i < ms_farShadowSteps && t < tMax; i++) {
                    vec3 q = rel + L * t;
                    if (q.y < mosaicHeightAt(q) - ms_farShadowBias) return 0.0;
                    t += ms_farShadowStep + t * ms_farShadowGrowth;
                }
                return 1.0;
            }
            """;

    static final String SLANG = """

            // Height field (same layout as the GLSL block): uints 0-3 originRel, 4-7 camFrac, 8.. heights.
            [[vk::binding(18, 0)]] StructuredBuffer<uint> bedrayHeight;
            float mosaicHeightAt(float3 rel)
            {
                if (asint(bedrayHeight[3]) == 0) return -1.0e9;
                float2 frac = float2(asfloat(bedrayHeight[4]), asfloat(bedrayHeight[6]));
                int2 c = int2(floor(rel.xz + frac)) - int2(asint(bedrayHeight[0]), asint(bedrayHeight[1]));
                if (c.x < 0 || c.y < 0 || c.x >= 256 || c.y >= 256) return -1.0e9;
                int i = c.y * 256 + c.x;
                int h = int((bedrayHeight[8 + (i >> 1)] >> uint((i & 1) * 16)) & 0xFFFFu);
                if (h >= 32768) h -= 65536;
                if (h <= -32768) return -1.0e9;
                return float(h - asint(bedrayHeight[2])) - asfloat(bedrayHeight[5]);
            }
            float mosaicFarShadow(float3 rel, float3 L, float tStart, float tMax)
            {
                float t = tStart;
                for (int i = 0; i < ms_farShadowSteps && t < tMax; i++) {
                    float3 q = rel + L * t;
                    if (q.y < mosaicHeightAt(q) - ms_farShadowBias) return 0.0;
                    t += ms_farShadowStep + t * ms_farShadowGrowth;
                }
                return 1.0;
            }
            """;
}
