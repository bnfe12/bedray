package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * Views bound for one frame of the Slang RT path.
 *
 * <p>{@code gbuffer} is non-null exactly for the denoiser variant (the five contiguous images plus specular radiance and
 * specular albedo); {@code accum} is non-zero exactly for the accumulation variant. The Slang path passes this record
 * through unchanged.
 */
public record RtViews(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                      long[] gbuffer, long depth, long lightmap) {
    public RtViews(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                   long[] gbuffer) {
        this(tlas, table, output, atlas, material, normal, accum, gbuffer, 0L, 0L);
    }

    public RtViews(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                   long[] gbuffer, long depth) {
        this(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, 0L);
    }

    public boolean sameAs(RtViews o) {
        return o != null && tlas == o.tlas && table.handle == o.table.handle && output == o.output && atlas == o.atlas
            && material == o.material && normal == o.normal && accum == o.accum
            && java.util.Arrays.equals(gbuffer, o.gbuffer) && depth == o.depth && lightmap == o.lightmap;
    }
}
