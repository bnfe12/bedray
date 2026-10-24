package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;

/** Occupancy stays independent of optional materials. Unsupported geometry remains explicit. */
public final class SectionSnapshot {
    public static final byte EMPTY = 0, OPAQUE_CUBE = 1, UNSUPPORTED = 2, MODEL = 3;
    public static final int CELL_COUNT = 4096;
    private final int x, y, z;
    private final long revision;
    private final byte[] cells;
    private final int opaqueCount, unsupportedCount;
    private final CubeSectionMaterials materials;
    private final SectionModelGeometry models;
    private final SectionLightData lighting;
    private final int modelCount;

    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells) {
        this(x, y, z, revision, cells, null);
    }
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials) {
        this(x,y,z,revision,cells,materials,null,null);
    }
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials,
                           SectionModelGeometry models, SectionLightData lighting) {
        if (revision < 0 || cells.length != CELL_COUNT) throw new IllegalArgumentException("Invalid section");
        this.cells = cells.clone();
        int opaque=0, unsupported=0, model=0;
        for (int index = 0; index < this.cells.length; index++) {
            byte cell = this.cells[index];
            if (cell < EMPTY || cell > MODEL) throw new IllegalArgumentException("Invalid occupancy");
            if (cell == OPAQUE_CUBE) opaque++;
            if (cell == UNSUPPORTED) unsupported++;
            if (cell == MODEL) {
                model++;
                if (models == null || models.reference(index) == 0)
                    throw new IllegalArgumentException("Model cell requires geometry");
            }
            if (models != null && models.reference(index) != 0 && cell != MODEL)
                throw new IllegalArgumentException("Model reference requires model occupancy");
            if (materials != null && materials.reference(index) != 0 && cell != OPAQUE_CUBE)
                throw new IllegalArgumentException("Material reference requires an opaque cube");
        }
        opaqueCount=opaque; unsupportedCount=unsupported;
        this.x = x; this.y = y; this.z = z; this.revision = revision;
        this.materials = materials;
        this.models = models;
        this.lighting = lighting;
        this.modelCount = model;
        if (materials != null && models != null && materials.generation() != models.generation())
            throw new IllegalArgumentException("Mixed section material generations");
    }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public long revision() { return revision; }
    public int opaqueCount() { return opaqueCount; }
    public int unsupportedCount() { return unsupportedCount; }
    public CubeSectionMaterials materials() { return materials; }
    public SectionModelGeometry models() { return models; }
    public SectionLightData lighting() { return lighting; }
    public int modelCount() { return modelCount; }
    public long materialGeneration() {
        return materials != null ? materials.generation() : models != null ? models.generation() : 0;
    }
    public byte cell(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) throw new IndexOutOfBoundsException();
        return cells[x + 16 * (y + 16 * z)];
    }
    public boolean hasSameCells(SectionSnapshot other) { return Arrays.equals(cells, other.cells); }
    public boolean hasSameContent(SectionSnapshot other) {
        return hasSameCells(other) && java.util.Objects.equals(materials, other.materials)
            && java.util.Objects.equals(models, other.models) && java.util.Objects.equals(lighting, other.lighting);
    }
}
