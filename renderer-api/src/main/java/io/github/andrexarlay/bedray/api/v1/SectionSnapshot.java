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
    private final SectionTintData tints;
    private final int modelCount;

    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells) {
        this(x, y, z, revision, cells, null);
    }
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials) {
        this(x,y,z,revision,cells,materials,null,null);
    }
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials,
                           SectionModelGeometry models, SectionLightData lighting) {
        this(x,y,z,revision,cells,materials,models,lighting,null);
    }
    /** API minor 5: optional per-cell tint. A cell whose cube face or model quad requests the cell tint
     * must carry a nonzero value; a nonzero value requires cube or model occupancy. */
    public SectionSnapshot(int x, int y, int z, long revision, byte[] cells, CubeSectionMaterials materials,
                           SectionModelGeometry models, SectionLightData lighting, SectionTintData tints) {
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
            int tint = tints == null ? 0 : tints.argb(index);
            if (tint != 0 && cell != OPAQUE_CUBE && cell != MODEL)
                throw new IllegalArgumentException("Cell tint requires cube or model occupancy");
            if (tint == 0 && (cell == OPAQUE_CUBE && materials != null && materials.usesCellTint(materials.reference(index))
                    || cell == MODEL && models.usesCellTint(models.reference(index))))
                throw new IllegalArgumentException("Cell-tinted appearance requires a cell tint");
        }
        opaqueCount=opaque; unsupportedCount=unsupported;
        this.x = x; this.y = y; this.z = z; this.revision = revision;
        this.materials = materials;
        this.models = models;
        this.lighting = lighting;
        this.tints = tints;
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
    /** Null unless the provider opted into API minor 5 cell tint. */
    public SectionTintData tints() { return tints; }
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
            && java.util.Objects.equals(models, other.models) && java.util.Objects.equals(lighting, other.lighting)
            && java.util.Objects.equals(tints, other.tints);
    }
}
