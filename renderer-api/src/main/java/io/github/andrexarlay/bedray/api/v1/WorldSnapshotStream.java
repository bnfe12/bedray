package io.github.andrexarlay.bedray.api.v1;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Render-thread producer: at most two section reads/frame and 27 resident sections (~108 KiB).
 * Polls revisions over successive frames; does not promise same-frame edits. Source never generates chunks.
 */
public final class WorldSnapshotStream {
    public interface Source {
        /** Null means unloaded/outside world. Returns exactly 4096 occupancy bytes otherwise. */
        byte[] readSection(int x, int y, int z);
        /** Cheap loaded check, performed on every resident section to evict unloads immediately. */
        boolean loaded(int x, int y, int z);
    }
    private record Key(int x, int y, int z) {}
    private final Map<Key, SectionSnapshot> resident = new LinkedHashMap<>();
    private Object world;
    private long session, revision;
    private int cursor;

    public void clear() { world = null; resident.clear(); cursor = 0; }

    public WorldSnapshot capture(Object identity, CameraSnapshot camera, Source source) {
        Objects.requireNonNull(identity);
        if (world != identity) {
            clear(); world = identity; session++; revision++;
        }
        int cx = section(camera.x()), cy = section(camera.y()), cz = section(camera.z());
        boolean removed = resident.keySet().removeIf(k -> Math.abs((long) k.x-cx) > 1
            || Math.abs((long) k.y-cy) > 1 || Math.abs((long) k.z-cz) > 1 || !source.loaded(k.x,k.y,k.z));
        if (removed) revision++;
        // Round-robin reads cover the entire window in at most 14 frames, including empty/unloaded slots.
        for (int n = 0; n < 2; n++) {
            int slot = cursor; cursor = (cursor + 1) % 27;
            Key key = new Key(cx + slot % 3 - 1, cy + slot / 3 % 3 - 1, cz + slot / 9 - 1);
            byte[] cells = source.readSection(key.x,key.y,key.z);
            if (cells == null) { if (resident.remove(key) != null) revision++; continue; }
            SectionSnapshot candidate = new SectionSnapshot(key.x,key.y,key.z,revision + 1,cells);
            SectionSnapshot old = resident.get(key);
            if (old == null || !candidate.hasSameCells(old)) { revision++; resident.put(key,candidate); }
        }
        return new WorldSnapshot(session,revision,camera,new ArrayList<>(resident.values()));
    }
    private static int section(double position) {
        double section = Math.floor(position / 16.0);
        if (section < Integer.MIN_VALUE + 1 || section > Integer.MAX_VALUE - 1)
            throw new IllegalArgumentException("Camera outside section coordinate range");
        return (int) section;
    }
}
