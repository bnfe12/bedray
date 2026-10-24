package io.github.andrexarlay.bedray.api.v1;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded optional model palette. One-based references use x+16*(y+16*z); zero means absent.
 * Model headers are 16 bytes: firstQuad, quadCount, reserved zero, reserved zero.
 * Quad offsets are relative to this section's fixed MAX_QUADS slab, in palette order.
 * All cells referencing this palette have MODEL occupancy. Generation matches the live atlas.
 */
public final class SectionModelGeometry {
    public static final int MAX_MODELS = 128;
    public static final int MAX_QUADS = MAX_MODELS * BlockModelGeometry.MAX_QUADS;
    public static final int HEADER_BYTES = 16;
    public static final int SECTION_HEADER_BYTES = MAX_MODELS * HEADER_BYTES;
    public static final int SECTION_QUAD_BYTES = MAX_QUADS * ModelQuad.BYTES;
    private final long generation;
    private final List<BlockModelGeometry> palette;
    private final short[] references;
    private final int quadCount;
    private final boolean[] cellTinted;

    public SectionModelGeometry(long generation, List<BlockModelGeometry> palette, short[] references) {
        this.palette = List.copyOf(palette);
        this.references = references.clone();
        if (generation <= 0 || this.palette.size() > MAX_MODELS || this.references.length != SectionSnapshot.CELL_COUNT)
            throw new IllegalArgumentException("Invalid bounded section models");
        for (short reference : this.references)
            if (Short.toUnsignedInt(reference) > this.palette.size())
                throw new IllegalArgumentException("Model reference outside palette");
        this.generation = generation;
        this.quadCount = this.palette.stream().mapToInt(model -> model.quads().size()).sum();
        cellTinted = new boolean[this.palette.size()];
        for (int model = 0; model < cellTinted.length; model++)
            for (ModelQuad quad : this.palette.get(model).quads()) cellTinted[model] |= (quad.flags() & ModelQuad.CELL_TINT) != 0;
    }
    /** True when one-based reference r has a quad multiplied by the cell tint. Zero returns false. */
    public boolean usesCellTint(int reference) { return reference > 0 && cellTinted[reference - 1]; }
    public long generation() { return generation; }
    public List<BlockModelGeometry> palette() { return palette; }
    public int reference(int index) { return Short.toUnsignedInt(references[index]); }
    public int quadCount() { return quadCount; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof SectionModelGeometry that && generation == that.generation
            && palette.equals(that.palette) && Arrays.equals(references, that.references);
    }
    @Override public int hashCode() { return 31 * Objects.hash(generation, palette) + Arrays.hashCode(references); }
}
