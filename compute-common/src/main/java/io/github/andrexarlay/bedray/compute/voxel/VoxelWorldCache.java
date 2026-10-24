package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import java.util.Map;
import java.util.HashMap;

/** CPU-only bounded scene input for the live-world renderer. No game objects, chunk loads or GPU writes.
 * Occupancy and unsupported cells remain distinct. Coordinates outside the snapshot are UNKNOWN.
 */
public final class VoxelWorldCache {
    public static final int UNKNOWN = -1;
    private record Key(int x, int y, int z) {}
    private Map<Key, SectionSnapshot> sections = Map.of();
    private WorldSnapshot current;
    private CameraSnapshot previousCamera;

    public void clear() { sections = Map.of(); current = null; previousCamera = null; }

    public void accept(WorldSnapshot snapshot) {
        if (current != null && snapshot.session() < current.session())
            throw new IllegalArgumentException("Out-of-order world session");
        if (current != null && snapshot.session() == current.session() && snapshot.revision() < current.revision())
            throw new IllegalArgumentException("Out-of-order world revision");
        if (current != null && snapshot.session() == current.session() && snapshot.revision() == current.revision()) {
            if (!snapshot.sections().equals(current.sections()))
                throw new IllegalArgumentException("World contents changed without a revision");
            previousCamera = current.camera();
            current = snapshot;
            return;
        }
        Map<Key, SectionSnapshot> next = new HashMap<>();
        for (SectionSnapshot section : snapshot.sections()) {
            if (next.put(new Key(section.x(),section.y(),section.z()),section) != null)
                throw new IllegalArgumentException("Duplicate section coordinates");
        }
        previousCamera = current != null && current.session() == snapshot.session() ? current.camera() : null;
        sections = Map.copyOf(next);
        current = snapshot;
    }
    public CameraSnapshot camera() { return current == null ? null : current.camera(); }
    public CameraSnapshot previousCamera() { return previousCamera; }
    public int sectionCount() { return sections.size(); }
    public int occupancy(int blockX, int blockY, int blockZ) {
        SectionSnapshot section = sections.get(new Key(Math.floorDiv(blockX,16),Math.floorDiv(blockY,16),Math.floorDiv(blockZ,16)));
        return section == null ? UNKNOWN : section.cell(Math.floorMod(blockX,16),Math.floorMod(blockY,16),Math.floorMod(blockZ,16));
    }
}
