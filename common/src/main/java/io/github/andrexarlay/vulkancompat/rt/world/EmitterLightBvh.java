package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Balanced spatial light proposals. Bounds cover the complete affine quad; topology never exceeds 16 edges. */
final class EmitterLightBvh {
    static final int NODE_BYTES = 48;
    static final int MAX_DEPTH = 16;
    record Result(int[] order, int[] leafNodes, int depth) {}

    static Result write(float[][] records, int count, int[] reusableOrder, ByteBuffer target, int offset) {
        if (count == 0) return new Result(new int[0], new int[0], 0);
        float[] bounds = new float[count * 7];
        for (int i = 0; i < count; i++) {
            float[] r = records[i];
            for (int axis = 0; axis < 3; axis++) {
                float a = r[axis], b = a + r[axis + 4], c = a + r[axis + 8], d = b + r[axis + 8];
                if (!Float.isFinite(a) || !Float.isFinite(b) || !Float.isFinite(c) || !Float.isFinite(d))
                    throw new IllegalArgumentException("[VKB-EMITTERS] non-finite light BVH bounds at " + i);
                // Conservative rounding margin; the PDF is a proposal, not a hard visibility/range test.
                bounds[i * 7 + axis] = Math.min(Math.min(a, b), Math.min(c, d)) - 0.001f;
                bounds[i * 7 + axis + 3] = Math.max(Math.max(a, b), Math.max(c, d)) + 0.001f;
            }
            double tint = r[15] > 0 ? Math.max(0, r[20] * 0.2126 + r[21] * 0.7152 + r[22] * 0.0722)
                * Math.max(0, r[23]) : 1;
            double power = Math.max(0, (double) r[3]) * Math.max(0, r[7]) * Math.max(0, r[14]) * tint;
            bounds[i * 7 + 6] = (float) Math.clamp(power, 1.0e-8, 1.0e20);
        }
        int[] order = reusableOrder == null ? spatialOrder(bounds, count) : reusableOrder;
        var writer = new Writer(bounds, order, target, offset);
        writer.node(0, count, -1, 0);
        if (writer.next != 2 * count - 1 || writer.depth > MAX_DEPTH)
            throw new IllegalStateException("[VKB-EMITTERS] invalid light BVH topology");
        return new Result(order, writer.leaves, writer.depth);
    }

    private static int[] spatialOrder(float[] bounds, int count) {
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < count; i++) for (int axis = 0; axis < 3; axis++) {
            double centre = ((double) bounds[i * 7 + axis] + bounds[i * 7 + axis + 3]) * 0.5;
            min[axis] = Math.min(min[axis], centre); max[axis] = Math.max(max[axis], centre);
        }
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            int morton = 0;
            for (int axis = 0; axis < 3; axis++) {
                double centre = ((double) bounds[i * 7 + axis] + bounds[i * 7 + axis + 3]) * 0.5;
                double unit = max[axis] > min[axis] ? (centre - min[axis]) / (max[axis] - min[axis]) : 0;
                int quantized = (int) Math.clamp(unit * 1023, 0, 1023);
                for (int bit = 0; bit < 10; bit++) morton |= ((quantized >>> bit) & 1) << (bit * 3 + axis);
            }
            keys[i] = ((long) morton << 32) | Integer.toUnsignedLong(i);
        }
        Arrays.sort(keys);
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = (int) keys[i];
        return order;
    }

    private static final class Writer {
        final float[] bounds;
        final int[] order, leaves;
        final ByteBuffer target;
        final int offset;
        int next, depth;
        Writer(float[] bounds, int[] order, ByteBuffer target, int offset) {
            this.bounds = bounds; this.order = order; this.target = target; this.offset = offset;
            leaves = new int[order.length];
        }
        int node(int lo, int hi, int parent, int level) {
            int index = next++, at = offset + index * NODE_BYTES;
            depth = Math.max(depth, level);
            target.putInt(at + 40, parent);
            if (hi - lo == 1) {
                int record = order[lo];
                leaves[record] = index;
                for (int axis = 0; axis < 3; axis++) {
                    target.putFloat(at + axis * 4, bounds[record * 7 + axis]);
                    target.putFloat(at + 16 + axis * 4, bounds[record * 7 + axis + 3]);
                }
                target.putFloat(at + 12, bounds[record * 7 + 6]);
                target.putInt(at + 32, -1).putInt(at + 36, -1).putInt(at + 44, record);
            } else {
                int mid = (lo + hi) >>> 1;
                int left = node(lo, mid, index, level + 1), right = node(mid, hi, index, level + 1);
                int a = offset + left * NODE_BYTES, b = offset + right * NODE_BYTES;
                for (int axis = 0; axis < 3; axis++) {
                    target.putFloat(at + axis * 4, Math.min(target.getFloat(a + axis * 4), target.getFloat(b + axis * 4)));
                    target.putFloat(at + 16 + axis * 4, Math.max(target.getFloat(a + 16 + axis * 4), target.getFloat(b + 16 + axis * 4)));
                }
                target.putFloat(at + 12, target.getFloat(a + 12) + target.getFloat(b + 12));
                target.putInt(at + 32, left).putInt(at + 36, right).putInt(at + 44, -1);
            }
            return index;
        }
    }
}
