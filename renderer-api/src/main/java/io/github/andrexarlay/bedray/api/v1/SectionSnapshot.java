package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Occupancy stays independent of optional materials. Unsupported geometry remains explicit. */
public final class SectionSnapshot {
    public static final byte EMPTY = 0, OPAQUE_CUBE = 1, UNSUPPORTED = 2;
    public static final int CELL_COUNT = 4096;
    private final int x, y, z;
    private final long revision;
    private final byte[] cells;
    private final int opaqueCount, unsupportedCount;
    private final CubeSectionMaterials materials;

    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells) {
        this(x, y, z, revision, cells, null);
    }
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials) {
        if (revision < 0 || cells.length != CELL_COUNT) throw new IllegalArgumentException("Invalid section");
        this.cells = cells.clone();
        int opaque=0, unsupported=0;
        for (int index = 0; index < this.cells.length; index++) {
            byte cell = this.cells[index];
            if (cell < EMPTY || cell > UNSUPPORTED) throw new IllegalArgumentException("Invalid occupancy");
            if (cell == OPAQUE_CUBE) opaque++;
            if (cell == UNSUPPORTED) unsupported++;
            if (materials != null && materials.reference(index) != 0 && cell != OPAQUE_CUBE)
                throw new IllegalArgumentException("Material reference requires an opaque cube");
        }
        opaqueCount=opaque; unsupportedCount=unsupported;
        this.x = x; this.y = y; this.z = z; this.revision = revision;
        this.materials = materials;
    }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public long revision() { return revision; }
    public int opaqueCount() { return opaqueCount; }
    public int unsupportedCount() { return unsupportedCount; }
    public CubeSectionMaterials materials() { return materials; }
    public byte cell(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) throw new IndexOutOfBoundsException();
        return cells[x + 16 * (y + 16 * z)];
    }
    public boolean hasSameCells(SectionSnapshot other) { return Arrays.equals(cells, other.cells); }
    public boolean hasSameContent(SectionSnapshot other) {
        return hasSameCells(other) && java.util.Objects.equals(materials, other.materials);
    }
}
