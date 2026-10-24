package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Immutable bounded section-local palette. Reference zero means untextured, 1..N index the palette.
 * References use x + 16*(y + 16*z). Generation identifies the matching host models and live atlas.
 * A reference must not be published for an EMPTY or UNSUPPORTED occupancy cell.
 * GPU palette ABI: each face is 32 little-endian bytes: float u,v,duU,duV,dvU,dvV,
 * uint tintArgb, uint flags (bit0 = cell tint, minor 5; other bits zero). Six faces are consecutive in CubeMaterial order.
 * One-based reference r addresses (r-1)*MATERIAL_BYTES within its fixed section palette slab.
 */
public final class CubeSectionMaterials {
    public static final int MAX_MATERIALS = 128;
    public static final int FACE_BYTES = 32;
    public static final int MATERIAL_BYTES = CubeMaterial.FACE_COUNT * FACE_BYTES;
    public static final int SECTION_BYTES = MAX_MATERIALS * MATERIAL_BYTES;
    private final long generation;
    private final List<CubeMaterial> palette;
    private final short[] references;
    private final boolean[] cellTinted;

    public CubeSectionMaterials(long generation, List<CubeMaterial> palette, short[] references) {
        this.palette = List.copyOf(palette);
        this.references = references.clone();
        if (generation <= 0 || this.palette.size() > MAX_MATERIALS
                || this.references.length != SectionSnapshot.CELL_COUNT)
            throw new IllegalArgumentException("Invalid bounded section materials");
        for (short value : this.references) {
            if (Short.toUnsignedInt(value) > this.palette.size())
                throw new IllegalArgumentException("Cube material reference outside palette");
        }
        this.generation = generation;
        cellTinted = new boolean[this.palette.size()];
        for (int material = 0; material < cellTinted.length; material++)
            for (CubeFaceMaterial face : this.palette.get(material).faces()) cellTinted[material] |= face.cellTint();
    }
    /** True when one-based reference r has a face multiplied by the cell tint. Zero returns false. */
    public boolean usesCellTint(int reference) { return reference > 0 && cellTinted[reference - 1]; }
    public long generation() { return generation; }
    public List<CubeMaterial> palette() { return palette; }
    public int reference(int index) { return Short.toUnsignedInt(references[index]); }
    public int reference(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) throw new IndexOutOfBoundsException();
        return reference(x + 16 * (y + 16 * z));
    }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof CubeSectionMaterials that
            && generation == that.generation && palette.equals(that.palette)
            && Arrays.equals(references, that.references);
    }
    @Override public int hashCode() {
        return 31 * Objects.hash(generation, palette) + Arrays.hashCode(references);
    }
}
