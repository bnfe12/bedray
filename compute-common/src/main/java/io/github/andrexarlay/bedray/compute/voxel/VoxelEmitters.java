package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.ModelQuad;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Bounded list of light-emitting cells inside the resident 48-cubed window, for next-event estimation.
 * Every emitter is a sphere whose surface area equals the emitting geometry's bounding surface:
 * a full cube uses its six unit faces, a baked model uses its quad bounding box. Equal area keeps the
 * orientation-averaged projected area (A/4 for any convex body) and therefore the far-field power.
 * Cells with emission but no captured geometry (unsupported or invisible emitters) use the inscribed
 * cell sphere. The list keeps the emitters nearest to the window center when it overflows.
 */
public final class VoxelEmitters {
    public static final int MAX_EMITTERS = 128;
    /** Two vec4 per emitter: center xyz + radius, then level, cell x, cell y, cell z (as floats). */
    public static final int EMITTER_BYTES = 32;
    public static final int BYTES = MAX_EMITTERS * EMITTER_BYTES;
    static final float CUBE_RADIUS = (float) Math.sqrt(6.0 / (4.0 * Math.PI));
    static final float MIN_RADIUS = 1f / 16f;

    private final float[] data = new float[MAX_EMITTERS * 8];
    private int count, found;

    public int count() { return count; }
    /** Emitting cells in the window before the bounded list kept the nearest MAX_EMITTERS. */
    public int found() { return found; }

    void clear() { count = found = 0; Arrays.fill(data, 0); }

    /** Rebuilds the list from the 27 slot sections (slot = x+3*(y+3*z), null = unknown). */
    void rebuild(SectionSnapshot[] slots) {
        // Candidates are kept as (distance², slot, index) so the nearest MAX_EMITTERS survive.
        long[] keys = new long[64];
        int candidates = 0;
        for (int slot = 0; slot < 27; slot++) {
            SectionSnapshot section = slots[slot];
            if (section == null || section.lighting() == null) continue;
            int tx = slot % 3, ty = slot / 3 % 3, tz = slot / 9;
            for (int index = 0; index < SectionSnapshot.CELL_COUNT; index++) {
                if (((section.lighting().packed(index) >> 8) & 15) == 0) continue;
                int x = tx * 16 + (index & 15), y = ty * 16 + (index >> 4 & 15), z = tz * 16 + (index >> 8);
                long dx = 2L * x + 1 - VoxelWorldUpload.DIAMETER, dy = 2L * y + 1 - VoxelWorldUpload.DIAMETER,
                    dz = 2L * z + 1 - VoxelWorldUpload.DIAMETER;
                long distance = dx * dx + dy * dy + dz * dz;
                if (candidates == keys.length) keys = Arrays.copyOf(keys, keys.length * 2);
                keys[candidates++] = distance << 24 | (long) slot << 12 | index;
            }
        }
        found = candidates;
        Arrays.sort(keys, 0, candidates);
        count = Math.min(candidates, MAX_EMITTERS);
        Arrays.fill(data, 0);
        for (int n = 0; n < count; n++) {
            int slot = (int) (keys[n] >> 12 & 4095), index = (int) (keys[n] & 4095);
            SectionSnapshot section = slots[slot];
            int tx = slot % 3, ty = slot / 3 % 3, tz = slot / 9;
            int x = tx * 16 + (index & 15), y = ty * 16 + (index >> 4 & 15), z = tz * 16 + (index >> 8);
            int level = (section.lighting().packed(index) >> 8) & 15;
            float[] sphere = sphere(section, index);
            int o = n * 8;
            data[o] = x + sphere[0]; data[o + 1] = y + sphere[1]; data[o + 2] = z + sphere[2]; data[o + 3] = sphere[3];
            data[o + 4] = level; data[o + 5] = x; data[o + 6] = y; data[o + 7] = z;
        }
    }

    /** Cell-local center and equal-area radius. */
    static float[] sphere(SectionSnapshot section, int index) {
        int occupancy = section.cell(index & 15, index >> 4 & 15, index >> 8);
        if (occupancy == SectionSnapshot.OPAQUE_CUBE) return new float[]{0.5f, 0.5f, 0.5f, CUBE_RADIUS};
        if (occupancy == SectionSnapshot.MODEL && section.models() != null) {
            int reference = section.models().reference(index);
            if (reference > 0) {
                float minX = 1, minY = 1, minZ = 1, maxX = 0, maxY = 0, maxZ = 0;
                for (ModelQuad quad : section.models().palette().get(reference - 1).quads()) {
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float px = quad.component(vertex * 5), py = quad.component(vertex * 5 + 1), pz = quad.component(vertex * 5 + 2);
                        minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                        minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                        minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
                    }
                }
                float w = maxX - minX, h = maxY - minY, d = maxZ - minZ;
                double area = 2.0 * (w * h + h * d + w * d);
                float radius = Math.max(MIN_RADIUS, (float) Math.sqrt(area / (4.0 * Math.PI)));
                return new float[]{(minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f, radius};
            }
        }
        return new float[]{0.5f, 0.5f, 0.5f, 0.5f};
    }

    public void writeTo(ByteBuffer target) {
        for (float value : data) target.putFloat(value);
    }
}
