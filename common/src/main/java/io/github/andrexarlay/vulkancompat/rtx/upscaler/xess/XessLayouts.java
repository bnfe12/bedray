package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Hand-written FFM layouts of the XeSS structs (Windows x64, {@code pack(8)} from {@code XESS_PACK_B()}).
 * Written by signature from {@code xess.h} / {@code xess_vk.h} of XeSS SDK 3.0.2; the headers are NOT copied into the repo.
 * Vulkan non-dispatchable handles are 64-bit integers ({@code JAVA_LONG}).
 * <p>
 * Sizes are verified in the static initializer (an explicit check, not {@code assert}, which is off by default):
 * a wrong layout means garbage passed to native code, so class initialization fails loudly.
 */
public final class XessLayouts {
    public static final int SIZE_2D = 8;
    public static final int SIZE_VERSION = 8;
    public static final int SIZE_PROPERTIES = 24;
    public static final int SIZE_VK_IMAGE_VIEW_INFO = 48;
    public static final int SIZE_VK_INIT_PARAMS = 64;
    public static final int SIZE_VK_EXECUTE_PARAMS = 360;

    /** {@code xess_2d_t} / {@code xess_coord_t}. */
    public static final StructLayout XESS_2D = MemoryLayout.structLayout(
        JAVA_INT.withName("x"), JAVA_INT.withName("y"));

    /** {@code xess_version_t}: 4 x uint16. */
    public static final StructLayout XESS_VERSION = MemoryLayout.structLayout(
        JAVA_SHORT.withName("major"), JAVA_SHORT.withName("minor"),
        JAVA_SHORT.withName("patch"), JAVA_SHORT.withName("reserved"));

    /** {@code xess_properties_t}. */
    public static final StructLayout XESS_PROPERTIES = MemoryLayout.structLayout(
        JAVA_INT.withName("requiredDescriptorCount"),
        MemoryLayout.paddingLayout(4),
        JAVA_LONG.withName("tempBufferHeapSize"),
        JAVA_LONG.withName("tempTextureHeapSize"));

    /** {@code xess_vk_image_view_info}. */
    public static final StructLayout XESS_VK_IMAGE_VIEW_INFO = MemoryLayout.structLayout(
        JAVA_LONG.withName("imageView"),
        JAVA_LONG.withName("image"),
        JAVA_INT.withName("aspectMask"),
        JAVA_INT.withName("baseMipLevel"),
        JAVA_INT.withName("levelCount"),
        JAVA_INT.withName("baseArrayLayer"),
        JAVA_INT.withName("layerCount"),
        JAVA_INT.withName("format"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height"));

    /** {@code xess_vk_init_params_t}. */
    public static final StructLayout XESS_VK_INIT_PARAMS = MemoryLayout.structLayout(
        XESS_2D.withName("outputResolution"),
        JAVA_INT.withName("qualitySetting"),
        JAVA_INT.withName("initFlags"),
        JAVA_INT.withName("creationNodeMask"),
        JAVA_INT.withName("visibleNodeMask"),
        JAVA_LONG.withName("tempBufferHeap"),
        JAVA_LONG.withName("bufferHeapOffset"),
        JAVA_LONG.withName("tempTextureHeap"),
        JAVA_LONG.withName("textureHeapOffset"),
        JAVA_LONG.withName("pipelineCache"));

    /** {@code xess_vk_execute_params_t} (used from step X5; layout fixed here so the size is tested early). */
    public static final StructLayout XESS_VK_EXECUTE_PARAMS = MemoryLayout.structLayout(
        XESS_VK_IMAGE_VIEW_INFO.withName("colorTexture"),
        XESS_VK_IMAGE_VIEW_INFO.withName("velocityTexture"),
        XESS_VK_IMAGE_VIEW_INFO.withName("depthTexture"),
        XESS_VK_IMAGE_VIEW_INFO.withName("exposureScaleTexture"),
        XESS_VK_IMAGE_VIEW_INFO.withName("responsivePixelMaskTexture"),
        XESS_VK_IMAGE_VIEW_INFO.withName("outputTexture"),
        JAVA_FLOAT.withName("jitterOffsetX"),
        JAVA_FLOAT.withName("jitterOffsetY"),
        JAVA_FLOAT.withName("exposureScale"),
        JAVA_INT.withName("resetHistory"),
        JAVA_INT.withName("inputWidth"),
        JAVA_INT.withName("inputHeight"),
        XESS_2D.withName("inputColorBase"),
        XESS_2D.withName("inputMotionVectorBase"),
        XESS_2D.withName("inputDepthBase"),
        XESS_2D.withName("inputResponsiveMaskBase"),
        XESS_2D.withName("reserved0"),
        XESS_2D.withName("outputColorBase"));

    // init params field offsets (checked against the header in the static block below)
    public static final long INIT_OFF_OUTPUT = XESS_VK_INIT_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("outputResolution"));
    public static final long INIT_OFF_QUALITY = XESS_VK_INIT_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("qualitySetting"));
    public static final long INIT_OFF_FLAGS = XESS_VK_INIT_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("initFlags"));
    public static final long INIT_OFF_PIPELINE_CACHE = XESS_VK_INIT_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("pipelineCache"));

    /** Offset of {@code jitterOffsetX}; jitterOffsetY, exposureScale, resetHistory, inputWidth, inputHeight follow at +4 each. */
    public static final long EXEC_OFF_JITTER_X = XESS_VK_EXECUTE_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("jitterOffsetX"));

    static {
        check("xess_2d_t", XESS_2D, SIZE_2D);
        check("xess_version_t", XESS_VERSION, SIZE_VERSION);
        check("xess_properties_t", XESS_PROPERTIES, SIZE_PROPERTIES);
        check("xess_vk_image_view_info", XESS_VK_IMAGE_VIEW_INFO, SIZE_VK_IMAGE_VIEW_INFO);
        check("xess_vk_init_params_t", XESS_VK_INIT_PARAMS, SIZE_VK_INIT_PARAMS);
        check("xess_vk_execute_params_t", XESS_VK_EXECUTE_PARAMS, SIZE_VK_EXECUTE_PARAMS);
        checkOffset("xess_vk_init_params_t.qualitySetting", INIT_OFF_QUALITY, 8);
        checkOffset("xess_vk_init_params_t.initFlags", INIT_OFF_FLAGS, 12);
        checkOffset("xess_vk_init_params_t.pipelineCache", INIT_OFF_PIPELINE_CACHE, 56);
        checkOffset("xess_vk_execute_params_t.jitterOffsetX",
            XESS_VK_EXECUTE_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("jitterOffsetX")), 288);
        checkOffset("xess_vk_execute_params_t.inputHeight",
            XESS_VK_EXECUTE_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("inputHeight")), EXEC_OFF_JITTER_X + 20);
        checkOffset("xess_vk_execute_params_t.outputTexture",
            XESS_VK_EXECUTE_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("outputTexture")), 240);
        checkOffset("xess_vk_execute_params_t.outputColorBase",
            XESS_VK_EXECUTE_PARAMS.byteOffset(MemoryLayout.PathElement.groupElement("outputColorBase")), 352);
    }

    private XessLayouts() {}

    private static void check(String name, MemoryLayout layout, long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException("XeSS struct layout " + name + " has size " + layout.byteSize()
                + ", header says " + expected);
        }
    }

    private static void checkOffset(String name, long actual, long expected) {
        if (actual != expected) {
            throw new IllegalStateException("XeSS struct field " + name + " at offset " + actual + ", header says " + expected);
        }
    }

    /** Forces class initialization (and therefore the size checks). */
    public static void verify() {
        // the static initializer does the work
    }

    /** Var handle helper for a primitive field of a struct layout. */
    public static VarHandle field(StructLayout layout, String name) {
        return layout.varHandle(MemoryLayout.PathElement.groupElement(name));
    }
}
