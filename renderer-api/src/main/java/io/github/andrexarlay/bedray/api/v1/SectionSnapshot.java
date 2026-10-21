package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Occupancy only; deliberately not a material or texture ID. Unsupported geometry stays explicit. */
public final class SectionSnapshot {
    public static final byte EMPTY = 0, OPAQUE_CUBE = 1, UNSUPPORTED = 2;
    public static final int CELL_COUNT = 4096;
    private final int x, y, z;
    private final long revision;
    private final byte[] cells;
    private final int opaqueCount, unsupportedCount;

    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells) {
        if (revision < 0 || cells.length != CELL_COUNT) throw new IllegalArgumentException("Invalid section");
        int opaque=0, unsupported=0;
        for (byte cell : cells) {
            if (cell < EMPTY || cell > UNSUPPORTED) throw new IllegalArgumentException("Invalid occupancy");
            if (cell == OPAQUE_CUBE) opaque++;
            if (cell == UNSUPPORTED) unsupported++;
        }
        opaqueCount=opaque; unsupportedCount=unsupported;
        this.x = x; this.y = y; this.z = z; this.revision = revision;
        this.cells = cells.clone();
    }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public long revision() { return revision; }
    public int opaqueCount() { return opaqueCount; }
    public int unsupportedCount() { return unsupportedCount; }
    public byte cell(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) throw new IndexOutOfBoundsException();
        return cells[x + 16 * (y + 16 * z)];
    }
    public boolean hasSameCells(SectionSnapshot other) { return Arrays.equals(cells, other.cells); }
}
