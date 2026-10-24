package io.github.andrexarlay.bedray.compute.voxel;

import io.github.andrexarlay.bedray.api.v1.SectionSnapshot;
import io.github.andrexarlay.bedray.api.v1.WorldSnapshot;
import io.github.andrexarlay.bedray.api.v1.CubeSectionMaterials;
import io.github.andrexarlay.bedray.api.v1.SectionModelGeometry;
import io.github.andrexarlay.bedray.api.v1.ModelQuad;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/** Fixed 27-tile GPU residency. Each changed section uploads 16 KiB of cells and, in the textured
 * pipeline, 24 KiB of face materials through separate bounded command-buffer updates.
 * Unknown and unsupported values are retained for diagnostics, not made into opaque cubes.
 */
public final class VoxelWorldUpload {
    public static final int DIAMETER = 48;
    public static final int TILE_BYTES = 4096 * Integer.BYTES;
    public static final int PALETTE_BYTES = 27 * CubeSectionMaterials.SECTION_BYTES;
    // A single model SSBO keeps grid/palette/models/lighting within four storage bindings.
    public static final int MODEL_BUFFER_HEADER_BYTES = 27 * SectionModelGeometry.SECTION_HEADER_BYTES;
    public static final int MODEL_BUFFER_BYTES = MODEL_BUFFER_HEADER_BYTES + 27 * SectionModelGeometry.SECTION_QUAD_BYTES;
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
        public long materialOffset() { return (long) slot * CubeSectionMaterials.SECTION_BYTES; }
        public long modelHeaderOffset() { return (long) slot * SectionModelGeometry.SECTION_HEADER_BYTES; }
        public long modelQuadOffset() { return MODEL_BUFFER_HEADER_BYTES + (long) slot * SectionModelGeometry.SECTION_QUAD_BYTES; }
        public void writeTo(ByteBuffer target) {
            CubeSectionMaterials materials = section == null ? null : section.materials();
            SectionModelGeometry models = section == null ? null : section.models();
            for (int z=0; z<16; z++) for (int y=0; y<16; y++) for (int x=0; x<16; x++) {
                if (section == null) { target.putInt(UNKNOWN); continue; }
                int index = x + 16 * (y + 16 * z);
                int occupancy = section.cell(x,y,z) & 255;
                int reference = occupancy == SectionSnapshot.MODEL
                    ? (models == null ? 0 : models.reference(index))
                    : (materials == null ? 0 : materials.reference(index));
                int light = section.lighting() == null ? 0 : (section.lighting().packed(index) << 16) | (1 << 28);
                target.putInt(light | (reference << 8) | occupancy);
            }
        }
        /** Zero every unused header, so old quad tails cannot be reached after slot reuse. */
        public void writeModelHeadersTo(ByteBuffer target) {
            var models = section == null ? null : section.models();
            int count = models == null ? 0 : models.palette().size();
            int first = 0;
            for (int model = 0; model < SectionModelGeometry.MAX_MODELS; model++) {
                int quads = model < count ? models.palette().get(model).quads().size() : 0;
                target.putInt(quads == 0 ? 0 : first).putInt(quads).putInt(0).putInt(0);
                first += quads;
            }
        }
        /** Dense used span only; the command recorder splits it into bounded 64 KiB updates. */
        public void writeModelQuadsTo(ByteBuffer target) {
            if (section == null || section.models() == null) return;
            for (var model : section.models().palette()) for (ModelQuad quad : model.quads()) {
                for (int component = 0; component < 20; component++) target.putFloat(quad.component(component));
                target.putInt(quad.tintArgb()).putInt(quad.flags()).putInt(quad.emission()).putInt(0);
            }
        }
        /** Fixed section slab; zero-filled unused entries prevent stale palette reads after slot reuse. */
        public void writeMaterialsTo(ByteBuffer target) {
            CubeSectionMaterials materials = section == null ? null : section.materials();
            int count = materials == null ? 0 : materials.palette().size();
            for (int material = 0; material < CubeSectionMaterials.MAX_MATERIALS; material++) {
                for (int face = 0; face < 6; face++) {
                    if (material < count) {
                        var surface = materials.palette().get(material).face(face);
                        target.putFloat(surface.u()).putFloat(surface.v())
                            .putFloat(surface.duU()).putFloat(surface.duV())
                            .putFloat(surface.dvU()).putFloat(surface.dvV())
                            .putInt(surface.tintArgb()).putInt(0);
                    } else {
                        for (int word = 0; word < CubeSectionMaterials.FACE_BYTES / 4; word++) target.putInt(0);
                    }
                }
            }
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
