package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * The frame's binding contract: which descriptor slot of a render pass holds what, and the bundle of views one frame
 * binds into those slots.
 *
 * <p>The numbers are the ABI between the Slang shaders' {@code contracts/bindings.slang}, the native core's descriptor
 * registration and the denoisers' G-buffer inputs. They outlive the legacy pipeline that used to build the descriptor
 * set from Java: the Slang path binds through {@link PathTraceBackend} and the native core, but a pass still has to be
 * handed the same images, and NRD and DLSS-RR still have to hand back exactly {@link #GBUFFER_VIEWS} of them.
 */
public final class RtFrameBindings {
    /** Push-constant block of a render pass. */
    public static final int PUSH_BYTES = RtFrameParams.BYTES;
    /** Number of G-buffer storage images (bindings 4..8) of the denoiser variant: viewZ, normal+roughness, diffuse, albedo, direct. */
    public static final int GBUFFER_IMAGES = 5;
    /** Specular radiance + hit distance of the denoiser variant (binding 13, after the material/normal/accum/depth slots). */
    public static final int BINDING_GBUFFER_SPECULAR = 13;
    /** Specular albedo (F0) guide of the denoiser variant (binding 14). */
    public static final int BINDING_GBUFFER_SPEC_ALBEDO = 14;
    /** Views the denoiser variant needs: the five contiguous images plus specular radiance and specular albedo. */
    public static final int GBUFFER_VIEWS = GBUFFER_IMAGES + 2;
    public static final int BINDING_MATERIAL = 9;
    public static final int BINDING_NORMAL = 10;
    public static final int BINDING_ACCUM = 11;
    public static final int BINDING_DEPTH = 12;
    public static final int BINDING_LIGHTMAP = 15;

    private RtFrameBindings() {
    }

    /**
     * Views bound for one frame; {@code gbuffer} non-null exactly for the denoiser variant, {@code accum} != 0 exactly
     * when a pass writes the accumulation image. {@code motion} is NRD's {@code IN_MV} buffer (one vec4 per pixel),
     * non-zero only when the NRD layout is selected.
     */
    public record Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                        long[] gbuffer, long depth, long lightmap, long motion) {
        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                     long[] gbuffer) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, 0L, 0L, 0L);
        }

        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                     long[] gbuffer, long depth) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, 0L, 0L);
        }

        public Views(long tlas, RtMemory.Buf table, long output, long atlas, long material, long normal, long accum,
                     long[] gbuffer, long depth, long lightmap) {
            this(tlas, table, output, atlas, material, normal, accum, gbuffer, depth, lightmap, 0L);
        }
    }
}
