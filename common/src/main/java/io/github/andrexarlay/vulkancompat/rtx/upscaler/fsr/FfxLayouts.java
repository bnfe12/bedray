package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Hand-written x64 (MSVC) layouts of the ffx-api structures used for FSR upscaling on Vulkan. Source of every field
 * (project rules): the headers of the official FidelityFX SDK tag v1.1.4
 * ({@code local reference}):
 * <ul>
 * <li>{@code ffx_api.h}: ffxApiHeader (l.51), ffxQueryDescGetVersions (l.85), ffxOverrideVersion (l.96),
 *     ffxQueryGetProviderVersion (l.103);</li>
 * <li>{@code ffx_api_types.h}: FfxApiDimensions2D (l.134), FfxApiFloatCoords2D (l.141), FfxApiResourceDescription (l.159),
 *     FfxApiResource (l.183);</li>
 * <li>{@code ffx_upscale.h}: ffxCreateContextDescUpscale (l.71), ffxDispatchDescUpscale (l.81), the query descs l.108-144;</li>
 * <li>{@code vk/ffx_api_vk.h}: ffxCreateBackendVKDesc (l.40).</li>
 * </ul>
 * C {@code bool} is one byte (stdbool, MSVC). Padding is written out explicitly; {@link #verify()} checks the offsets that
 * are hard-coded in {@link FfxNative} against the C rules (natural alignment), so a layout mistake fails before any native call.
 */
public final class FfxLayouts {
    private FfxLayouts() {}

    public static final StructLayout HEADER = MemoryLayout.structLayout(
        JAVA_LONG.withName("type"), ADDRESS.withName("pNext"));

    public static final StructLayout DIMENSIONS_2D = MemoryLayout.structLayout(
        JAVA_INT.withName("width"), JAVA_INT.withName("height"));

    public static final StructLayout FLOAT_COORDS_2D = MemoryLayout.structLayout(
        JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"));

    public static final StructLayout RESOURCE_DESCRIPTION = MemoryLayout.structLayout(
        JAVA_INT.withName("type"), JAVA_INT.withName("format"), JAVA_INT.withName("width"), JAVA_INT.withName("height"),
        JAVA_INT.withName("depth"), JAVA_INT.withName("mipCount"), JAVA_INT.withName("flags"), JAVA_INT.withName("usage"));

    /** void* resource; FfxApiResourceDescription description (32); uint32 state; 4 bytes tail padding (alignment 8). */
    public static final StructLayout RESOURCE = MemoryLayout.structLayout(
        ADDRESS.withName("resource"), RESOURCE_DESCRIPTION.withName("description"), JAVA_INT.withName("state"),
        MemoryLayout.paddingLayout(4));

    public static final StructLayout CREATE_BACKEND_VK = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_LONG.withName("vkDevice"), JAVA_LONG.withName("vkPhysicalDevice"),
        ADDRESS.withName("vkDeviceProcAddr"));

    public static final StructLayout CREATE_UPSCALE = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_INT.withName("flags"), DIMENSIONS_2D.withName("maxRenderSize"),
        DIMENSIONS_2D.withName("maxUpscaleSize"), MemoryLayout.paddingLayout(4), ADDRESS.withName("fpMessage"));

    public static final StructLayout OVERRIDE_VERSION = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_LONG.withName("versionId"));

    public static final StructLayout QUERY_GET_VERSIONS = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_LONG.withName("createDescType"), ADDRESS.withName("device"),
        ADDRESS.withName("outputCount"), ADDRESS.withName("versionIds"), ADDRESS.withName("versionNames"));

    public static final StructLayout QUERY_PROVIDER_VERSION = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_LONG.withName("versionId"), ADDRESS.withName("versionName"));

    public static final StructLayout QUERY_RENDER_RESOLUTION = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_INT.withName("displayWidth"), JAVA_INT.withName("displayHeight"),
        JAVA_INT.withName("qualityMode"), MemoryLayout.paddingLayout(4),
        ADDRESS.withName("pOutRenderWidth"), ADDRESS.withName("pOutRenderHeight"));

    public static final StructLayout QUERY_UPSCALE_RATIO = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_INT.withName("qualityMode"), MemoryLayout.paddingLayout(4),
        ADDRESS.withName("pOutUpscaleRatio"));

    public static final StructLayout QUERY_JITTER_PHASE_COUNT = MemoryLayout.structLayout(
        HEADER.withName("header"), JAVA_INT.withName("renderWidth"), JAVA_INT.withName("displayWidth"),
        ADDRESS.withName("pOutPhaseCount"));

    public static final StructLayout DISPATCH_UPSCALE = MemoryLayout.structLayout(
        HEADER.withName("header"),
        ADDRESS.withName("commandList"),
        RESOURCE.withName("color"),
        RESOURCE.withName("depth"),
        RESOURCE.withName("motionVectors"),
        RESOURCE.withName("exposure"),
        RESOURCE.withName("reactive"),
        RESOURCE.withName("transparencyAndComposition"),
        RESOURCE.withName("output"),
        FLOAT_COORDS_2D.withName("jitterOffset"),
        FLOAT_COORDS_2D.withName("motionVectorScale"),
        DIMENSIONS_2D.withName("renderSize"),
        DIMENSIONS_2D.withName("upscaleSize"),
        JAVA_BYTE.withName("enableSharpening"), MemoryLayout.paddingLayout(3),
        JAVA_FLOAT.withName("sharpness"),
        JAVA_FLOAT.withName("frameTimeDelta"),
        JAVA_FLOAT.withName("preExposure"),
        JAVA_BYTE.withName("reset"), MemoryLayout.paddingLayout(3),
        JAVA_FLOAT.withName("cameraNear"),
        JAVA_FLOAT.withName("cameraFar"),
        JAVA_FLOAT.withName("cameraFovAngleVertical"),
        JAVA_FLOAT.withName("viewSpaceToMetersFactor"),
        JAVA_INT.withName("flags"));

    public static long offset(StructLayout layout, String... path) {
        MemoryLayout.PathElement[] elements = new MemoryLayout.PathElement[path.length];
        for (int i = 0; i < path.length; i++) {
            elements[i] = groupElement(path[i]);
        }
        return layout.byteOffset(elements);
    }

    /**
     * Offsets and sizes computed by hand from the C rules (x64: pointers and uint64 aligned to 8, uint32/float to 4,
     * bool 1, struct size rounded up to its alignment). Throws when the Java layout differs.
     */
    public static void verify() {
        expect(RESOURCE_DESCRIPTION.byteSize(), 32, "sizeof(FfxApiResourceDescription)");
        expect(RESOURCE.byteSize(), 48, "sizeof(FfxApiResource)");
        expect(offset(RESOURCE, "state"), 40, "FfxApiResource.state");
        expect(CREATE_BACKEND_VK.byteSize(), 40, "sizeof(ffxCreateBackendVKDesc)");
        expect(offset(CREATE_UPSCALE, "fpMessage"), 40, "ffxCreateContextDescUpscale.fpMessage");
        expect(CREATE_UPSCALE.byteSize(), 48, "sizeof(ffxCreateContextDescUpscale)");
        expect(OVERRIDE_VERSION.byteSize(), 24, "sizeof(ffxOverrideVersion)");
        expect(QUERY_GET_VERSIONS.byteSize(), 56, "sizeof(ffxQueryDescGetVersions)");
        expect(QUERY_PROVIDER_VERSION.byteSize(), 32, "sizeof(ffxQueryGetProviderVersion)");
        expect(offset(QUERY_RENDER_RESOLUTION, "pOutRenderWidth"), 32, "GetRenderResolution.pOutRenderWidth");
        expect(offset(QUERY_UPSCALE_RATIO, "pOutUpscaleRatio"), 24, "GetUpscaleRatio.pOutUpscaleRatio");
        expect(offset(QUERY_JITTER_PHASE_COUNT, "pOutPhaseCount"), 24, "GetJitterPhaseCount.pOutPhaseCount");
        expect(offset(DISPATCH_UPSCALE, "commandList"), 16, "dispatch.commandList");
        expect(offset(DISPATCH_UPSCALE, "color"), 24, "dispatch.color");
        expect(offset(DISPATCH_UPSCALE, "output"), 312, "dispatch.output");
        expect(offset(DISPATCH_UPSCALE, "jitterOffset"), 360, "dispatch.jitterOffset");
        expect(offset(DISPATCH_UPSCALE, "enableSharpening"), 392, "dispatch.enableSharpening");
        expect(offset(DISPATCH_UPSCALE, "sharpness"), 396, "dispatch.sharpness");
        expect(offset(DISPATCH_UPSCALE, "reset"), 408, "dispatch.reset");
        expect(offset(DISPATCH_UPSCALE, "cameraNear"), 412, "dispatch.cameraNear");
        expect(offset(DISPATCH_UPSCALE, "flags"), 428, "dispatch.flags");
        expect(DISPATCH_UPSCALE.byteSize(), 432, "sizeof(ffxDispatchDescUpscale)");
    }

    private static void expect(long actual, long expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException("ffx-api layout mismatch: " + what + " is " + actual + ", C layout says " + expected);
        }
    }
}
