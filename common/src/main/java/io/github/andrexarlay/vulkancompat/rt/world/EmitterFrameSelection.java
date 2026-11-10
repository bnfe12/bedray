package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Arrays;

/** Reusable bounded selection of a completed static snapshot and this frame's camera-relative entity lights. */
final class EmitterFrameSelection {
    record Frame(float[][] records, int count, int originX, int originY, int originZ, int entityInRange) {}

    private float[][] heap = new float[0][];
    private int size;
    private int budget;
    private long[] publishedKeys = new long[0];
    private int publishedCount;

    /** Freeze the membership bonus for one multi-frame static scan. */
    long[] publishedKeysSnapshot() {
        return Arrays.copyOf(publishedKeys, publishedCount);
    }

    /** Called only after the composed records have been published. */
    void recordPublished(Frame frame) {
        if (publishedKeys.length < frame.count()) {
            publishedKeys = new long[heap.length];
        }
        for (int i = 0; i < frame.count(); i++) {
            float[] record = frame.records()[i];
            publishedKeys[i] = RtEmitters.stickKey(record[0], record[1], record[2], record, 0,
                frame.originX(), frame.originY(), frame.originZ());
        }
        publishedCount = frame.count();
        Arrays.sort(publishedKeys, 0, publishedCount);
    }

    Frame compose(float[][] statics, int staticCount, int staticX, int staticY, int staticZ,
                  double cameraX, double cameraY, double cameraZ, float range, int requestedBudget,
                  float[] entities, int entityCount) {
        budget = Math.min(Math.max(requestedBudget, 0), RtEmitters.MAX_EMITTERS);
        ensureCapacity(budget);
        size = 0;
        double range2 = (double) range * range;
        double relativeX = cameraX - staticX, relativeY = cameraY - staticY, relativeZ = cameraZ - staticZ;
        for (int i = 0; i < staticCount; i++) {
            float[] record = statics[i];
            double dx = record[0] + 0.5 * (record[4] + record[8]) - relativeX;
            double dy = record[1] + 0.5 * (record[5] + record[9]) - relativeY;
            double dz = record[2] + 0.5 * (record[6] + record[10]) - relativeZ;
            double distance2 = dx * dx + dy * dy + dz * dz;
            if (distance2 <= range2) {
                offer(record, 0, 0, 0, 0, staticX, staticY, staticZ, importance(record, 0, distance2));
            }
        }

        // Preserve static payload coordinates exactly: a per-frame float rebase would churn otherwise stable IDs.
        // If no static source survives, use a nearby origin so a teleport cannot lose entity sub-block precision.
        int originX = size > 0 ? staticX : (int) Math.floor(cameraX);
        int originY = size > 0 ? staticY : (int) Math.floor(cameraY);
        int originZ = size > 0 ? staticZ : (int) Math.floor(cameraZ);
        double entityX = cameraX - originX, entityY = cameraY - originY, entityZ = cameraZ - originZ;
        int entityInRange = 0;
        for (int i = 0; i < entityCount; i++) {
            int at = i * RtEmitters.FLOATS;
            double dx = entities[at] + 0.5 * (entities[at + 4] + entities[at + 8]);
            double dy = entities[at + 1] + 0.5 * (entities[at + 5] + entities[at + 9]);
            double dz = entities[at + 2] + 0.5 * (entities[at + 6] + entities[at + 10]);
            double distance2 = dx * dx + dy * dy + dz * dz;
            if (distance2 > range2) continue;
            entityInRange++;
            offer(entities, at, entityX, entityY, entityZ, originX, originY, originZ, importance(entities, at, distance2));
        }
        RtEmitters.sortHeapDescending(heap, size);
        return new Frame(heap, size, originX, originY, originZ, entityInRange);
    }

    private static float importance(float[] record, int at, double distance2) {
        return (float) (record[at + 14] * record[at + 7] * record[at + 3] / Math.max(distance2, 1.0));
    }

    private void offer(float[] source, int at, double dx, double dy, double dz,
                       int originX, int originY, int originZ, float importance) {
        if (budget == 0 || (size >= budget
                && RtEmitters.selectionScore(importance, -1L, true) <= heap[0][RtEmitters.FLOATS])) return;
        float x = (float) (source[at] + dx);
        float y = (float) (source[at + 1] + dy);
        float z = (float) (source[at + 2] + dz);
        long key = RtEmitters.stickKey(x, y, z, source, at, originX, originY, originZ);
        boolean sticky = Arrays.binarySearch(publishedKeys, 0, publishedCount, key) >= 0;
        float score = RtEmitters.selectionScore(importance, key, sticky);
        if (size >= budget && score <= heap[0][RtEmitters.FLOATS]) return;
        boolean append = size < budget;
        float[] record = heap[append ? size : 0];
        System.arraycopy(source, at, record, 0, RtEmitters.FLOATS);
        record[0] = x;
        record[1] = y;
        record[2] = z;
        record[RtEmitters.FLOATS] = score;
        if (append) RtEmitters.siftUp(heap, size++);
        else RtEmitters.siftDown(heap, 0, size);
    }

    private void ensureCapacity(int requested) {
        if (heap.length >= requested) return;
        int previous = heap.length;
        heap = Arrays.copyOf(heap, Math.max(requested, Math.max(previous * 2, 64)));
        for (int i = previous; i < heap.length; i++) heap[i] = new float[RtEmitters.FLOATS + 1];
    }
}
