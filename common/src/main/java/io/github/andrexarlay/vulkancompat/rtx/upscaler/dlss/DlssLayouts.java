package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM layouts of the POD structs of {@code vkb_dlss.h} (our own wrapper, so the layout is defined by us).
 * The C side asserts the same sizes with {@code static_assert} in {@code vkb_dlss.cpp}; the Java side checks them in
 * {@link #verify()} (explicit check, not {@code assert}) because a wrong layout means garbage handed to native code.
 */
public final class DlssLayouts {
    public static final int SIZE_CAPS = 32;
    public static final int SIZE_OPTIMAL_SETTINGS = 36;
    public static final int SIZE_CREATE_INFO = 32;
    public static final int SIZE_IMAGE = 56;
    public static final int SIZE_EVAL_INFO = 344;

    /** VkbDlssCaps. */
    public static final StructLayout CAPS = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("super_sampling_available"),
        JAVA_INT.withName("needs_updated_driver"),
        JAVA_INT.withName("min_driver_major"),
        JAVA_INT.withName("min_driver_minor"),
        JAVA_INT.withName("feature_init_result"),
        JAVA_INT.withName("ngx_result_init"),
        JAVA_INT.withName("reserved"));

    /** VkbDlssOptimalSettings. */
    public static final StructLayout OPTIMAL_SETTINGS = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("render_width"), JAVA_INT.withName("render_height"),
        JAVA_INT.withName("min_width"), JAVA_INT.withName("min_height"),
        JAVA_INT.withName("max_width"), JAVA_INT.withName("max_height"),
        JAVA_FLOAT.withName("sharpness"),
        JAVA_INT.withName("reserved"));

    /** VkbDlssCreateInfo. */
    public static final StructLayout CREATE_INFO = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("render_width"), JAVA_INT.withName("render_height"),
        JAVA_INT.withName("output_width"), JAVA_INT.withName("output_height"),
        JAVA_INT.withName("quality_mode"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("enable_output_subrects"));

    /** VkbDlssImage. */
    public static final StructLayout IMAGE = MemoryLayout.structLayout(
        JAVA_LONG.withName("image"),
        JAVA_LONG.withName("view"),
        JAVA_INT.withName("format"),
        JAVA_INT.withName("width"), JAVA_INT.withName("height"),
        JAVA_INT.withName("aspect_mask"),
        JAVA_INT.withName("base_mip_level"), JAVA_INT.withName("level_count"),
        JAVA_INT.withName("base_array_layer"), JAVA_INT.withName("layer_count"),
        JAVA_INT.withName("read_write"),
        JAVA_INT.withName("reserved"));

    /** VkbDlssEvalInfo (five VkbDlssImage members, 8-byte aligned at offset 8). */
    public static final StructLayout EVAL_INFO = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("reset"),
        IMAGE.withName("color"),
        IMAGE.withName("output"),
        IMAGE.withName("depth"),
        IMAGE.withName("motion_vectors"),
        IMAGE.withName("exposure"),
        JAVA_FLOAT.withName("jitter_x"), JAVA_FLOAT.withName("jitter_y"),
        JAVA_FLOAT.withName("mv_scale_x"), JAVA_FLOAT.withName("mv_scale_y"),
        JAVA_FLOAT.withName("sharpness"),
        JAVA_FLOAT.withName("pre_exposure"),
        JAVA_FLOAT.withName("exposure_scale"),
        JAVA_INT.withName("render_subrect_width"), JAVA_INT.withName("render_subrect_height"),
        MemoryLayout.sequenceLayout(5, JAVA_INT).withName("reserved"));

    // VKB_DLSS_FLAG_* (vkb_dlss.h)
    public static final int FLAG_MV_LOW_RES = 1;
    public static final int FLAG_IS_HDR = 1 << 1;
    public static final int FLAG_DEPTH_INVERTED = 1 << 2;
    public static final int FLAG_AUTO_EXPOSURE = 1 << 3;
    public static final int FLAG_MV_JITTERED = 1 << 4;

    private DlssLayouts() {}

    /** @throws IllegalStateException when a layout does not have the size the C header static_asserts */
    public static void verify() {
        check("VkbDlssCaps", CAPS.byteSize(), SIZE_CAPS);
        check("VkbDlssOptimalSettings", OPTIMAL_SETTINGS.byteSize(), SIZE_OPTIMAL_SETTINGS);
        check("VkbDlssCreateInfo", CREATE_INFO.byteSize(), SIZE_CREATE_INFO);
        check("VkbDlssImage", IMAGE.byteSize(), SIZE_IMAGE);
        check("VkbDlssEvalInfo", EVAL_INFO.byteSize(), SIZE_EVAL_INFO);
    }

    private static void check(String name, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException(name + " layout is " + actual + " bytes, vkb_dlss.h has " + expected);
        }
    }

    /** Byte offset of a (possibly nested) member. */
    public static long offset(StructLayout layout, String... path) {
        MemoryLayout.PathElement[] elements = new MemoryLayout.PathElement[path.length];
        for (int i = 0; i < path.length; i++) {
            elements[i] = MemoryLayout.PathElement.groupElement(path[i]);
        }
        return layout.byteOffset(elements);
    }
}
