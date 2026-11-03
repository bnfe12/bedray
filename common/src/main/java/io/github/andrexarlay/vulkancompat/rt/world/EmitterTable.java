package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Private GPU table: a versioned header, captured payloads with stable IDs, and an ID-to-slot index.
 * IDs are never reused during this owner's lifetime. Only a successful publication commits its identity state. */
final class EmitterTable {
    static final int MAGIC = 0x42454D32;
    static final int HEADER_BYTES = 32;
    static final int RECORD_BYTES = 112;
    private Map<Key, List<Integer>> identities = Map.of();
    private long nextId = 1;
    private long revision;
    private int count;
    private int[] spatialOrder = new int[0];
    private final float globalFraction;

    EmitterTable() { this(!Boolean.getBoolean("bedray.rt.disableLocalLightSampling")); }
    EmitterTable(boolean localSampling) { globalFraction = localSampling ? 0.125f : 1f; }

    static int capacity(int count) {
        if (count < 0 || count > RtEmitters.MAX_EMITTERS) throw new IllegalArgumentException("invalid emitter count: " + count);
        return count == 0 ? 0 : Integer.highestOneBit(count * 2 - 1) << 1;
    }

    static int bytes(int count) {
        return count == 0 ? 0 : HEADER_BYTES + Math.multiplyExact(count, RECORD_BYTES) + capacity(count) * 16
            + Math.multiplyExact(2 * count - 1, EmitterLightBvh.NODE_BYTES);
    }

    record Publication(ByteBuffer data, Map<Key, List<Integer>> identities, long nextId,
                       long revision, int count, int retained, int added, int removed, int maxProbe,
                       int[] spatialOrder, int bvhDepth, boolean topologyReused) {
        float globalFraction() { return count == 0 ? 1 : data.getFloat(24); }
    }

    Publication prepare(float[][] records, int count, int originX, int originY, int originZ) {
        return prepare(records, count, originX, originY, originZ, 0);
    }

    Publication prepare(float[][] records, int count, int originX, int originY, int originZ, int materialRevision) {
        capacity(count);
        if (count > records.length) throw new IllegalArgumentException("emitter count exceeds records");
        var data = ByteBuffer.allocate(bytes(count)).order(ByteOrder.nativeOrder());
        var selected = new HashMap<Key, List<Integer>>();
        long next = nextId;
        int retained = 0, maxProbe = 0;
        int capacity = capacity(count);
        int lookup = HEADER_BYTES + count * RECORD_BYTES;
        if (count > 0) data.putInt(0, MAGIC).putInt(4, count).putInt(8, capacity).putInt(12, RECORD_BYTES);
        for (int i = 0; i < count; i++) {
            float[] record = records[i];
            if (record == null || record.length < RtEmitters.FLOATS) throw new IllegalArgumentException("short emitter record: " + i);
            Key key = new Key(record, originX, originY, originZ, materialRevision);
            var occurrences = selected.computeIfAbsent(key, ignored -> new ArrayList<>(1));
            var previous = identities.get(key);
            int id;
            if (previous != null && occurrences.size() < previous.size()) {
                id = previous.get(occurrences.size());
                retained++;
            } else {
                if (next > 0xFFFF_FFFFL) throw new IllegalStateException("[VKB-EMITTERS] stable ID space exhausted");
                id = (int) next++;
            }
            occurrences.add(id);
            int at = HEADER_BYTES + i * RECORD_BYTES;
            for (int word = 0; word < RtEmitters.FLOATS; word++) data.putFloat(at + word * 4, record[word]);
            data.putInt(at + RtEmitters.BYTES, id);
            int slot = (id * 0x9E3779B9) & (capacity - 1);
            int probes = 1;
            while (data.getInt(lookup + slot * 16) != 0) { slot = (slot + 1) & (capacity - 1); probes++; }
            data.putInt(lookup + slot * 16, id).putInt(lookup + slot * 16 + 4, i);
            maxProbe = Math.max(maxProbe, probes);
        }
        int added = count - retained, removed = this.count - retained;
        boolean reuseTopology = count > 0 && retained == count && this.count == count;
        int[] order = reuseTopology ? new int[count] : null;
        if (reuseTopology) for (int i = 0; i < count; i++) {
            int id = spatialOrder[i], slot = (id * 0x9E3779B9) & (capacity - 1);
            while (data.getInt(lookup + slot * 16) != id) {
                if (data.getInt(lookup + slot * 16) == 0) throw new IllegalStateException("retained light missing from BVH index");
                slot = (slot + 1) & (capacity - 1);
            }
            order[i] = data.getInt(lookup + slot * 16 + 4);
        }
        var tree = EmitterLightBvh.write(records, count, order, data, lookup + capacity * 16);
        int[] orderedIds = new int[count];
        if (count > 0) data.putInt(16, 2 * count - 1).putInt(20, EmitterLightBvh.NODE_BYTES).putFloat(24, globalFraction);
        for (int i = 0; i < count; i++) {
            data.putInt(HEADER_BYTES + i * RECORD_BYTES + RtEmitters.BYTES + 4, tree.leafNodes()[i]);
            orderedIds[i] = data.getInt(HEADER_BYTES + tree.order()[i] * RECORD_BYTES + RtEmitters.BYTES);
        }
        long version = revision + (added != 0 || removed != 0 ? 1 : 0);
        return new Publication(data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), selected, next,
            version, count, retained, added, removed, maxProbe, orderedIds, tree.depth(), reuseTopology);
    }

    void commit(Publication publication) {
        identities = publication.identities();
        nextId = publication.nextId();
        revision = publication.revision();
        count = publication.count();
        spatialOrder = publication.spatialOrder();
    }

    void clear() { identities = Map.of(); count = 0; spatialOrder = new int[0]; }

    /** Exact captured content; world-space double corners avoid losing block identity at large world coordinates. */
    private static final class Key {
        private final int[] words = new int[RtEmitters.FLOATS + 4];
        private final int hash;
        Key(float[] record, int x, int y, int z, int materialRevision) {
            for (int i = 0; i < RtEmitters.FLOATS; i++) {
                float value = record[i];
                if (!Float.isFinite(value)) throw new IllegalArgumentException("[VKB-EMITTERS] non-finite record word " + i);
                if (i < 3) {
                    long bits = Double.doubleToLongBits((double) (i == 0 ? x : i == 1 ? y : z) + value);
                    words[i * 2] = (int) bits;
                    words[i * 2 + 1] = (int) (bits >>> 32);
                } else words[i + 3] = value == 0 ? 0 : Float.floatToIntBits(value);
            }
            words[words.length - 1] = materialRevision;
            hash = Arrays.hashCode(words);
        }
        @Override public boolean equals(Object other) { return other instanceof Key key && Arrays.equals(words, key.words); }
        @Override public int hashCode() { return hash; }
    }
}
