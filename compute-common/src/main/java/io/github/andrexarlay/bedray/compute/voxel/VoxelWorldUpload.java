package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** Fixed 27-tile GPU residency. Each changed section is one 16 KiB command-buffer update.
 * Unknown and unsupported values are retained for diagnostics, not made into opaque cubes.
 */
public final class VoxelWorldUpload {
    public static final int DIAMETER = 48;
    public static final int TILE_BYTES = 4096 * Integer.BYTES;
    public static final int UNKNOWN = -1;
    private final SectionSnapshot[] previous = new SectionSnapshot[27];
    private final SectionSnapshot[] slots = new SectionSnapshot[27];
    private List<SectionSnapshot> previousSections = List.of();
    private long session = -1;
    private long revision = -1;
    private int originX, originY, originZ;
    private boolean initialized;
    /** Invalidates residency without allocating new arrays on repeated idle/close requests. */
    public void clear() {
        Arrays.fill(previous, null);
        Arrays.fill(slots, null);
        previousSections = List.of();
        session = revision = -1;
        initialized = false;
    }
    public record Tile(int slot, SectionSnapshot section) {
        public Tile { if (slot < 0 || slot >= 27) throw new IllegalArgumentException("Invalid voxel tile slot"); }
        public long offset() { return VoxelGrid.HEADER_BYTES + (long) slot * TILE_BYTES; }
        public void writeTo(ByteBuffer target) {
            for (int z=0; z<16; z++) for (int y=0; y<16; y++) for (int x=0; x<16; x++)
                target.putInt(section == null ? UNKNOWN : section.cell(x,y,z));
        }
    }
    public List<Tile> update(WorldSnapshot snapshot) {
        int sx = (int)Math.floor(snapshot.camera().x()/16.0)-1;
        int sy = (int)Math.floor(snapshot.camera().y()/16.0)-1;
        int sz = (int)Math.floor(snapshot.camera().z()/16.0)-1;
        int nextX = Math.multiplyExact(sx,16), nextY = Math.multiplyExact(sy,16), nextZ = Math.multiplyExact(sz,16);
        if (initialized && session == snapshot.session() && revision == snapshot.revision()
                && nextX == originX && nextY == originY && nextZ == originZ
                && previousSections.equals(snapshot.sections())) return List.of();
        originX = nextX; originY = nextY; originZ = nextZ;
        Arrays.fill(slots, null);
        for (SectionSnapshot section : snapshot.sections()) {
            long x=(long)section.x()-sx, y=(long)section.y()-sy, z=(long)section.z()-sz;
            if (x>=0 && x<3 && y>=0 && y<3 && z>=0 && z<3) slots[(int)(x+3*(y+3*z))]=section;
        }
        List<Tile> updates = new ArrayList<>();
        for (int i=0; i<27; i++) {
            SectionSnapshot a=previous[i], b=slots[i];
            boolean same = a==b || a!=null && b!=null && a.x()==b.x() && a.y()==b.y() && a.z()==b.z() && a.revision()==b.revision();
            if (!initialized || session!=snapshot.session() || !same) updates.add(new Tile(i,b));
            previous[i]=b;
        }
        session=snapshot.session(); revision=snapshot.revision(); previousSections=snapshot.sections(); initialized=true;
        return List.copyOf(updates);
    }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
}
