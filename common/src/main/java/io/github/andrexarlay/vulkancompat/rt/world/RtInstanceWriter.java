package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;

/** Writes the 64-byte VkAccelerationStructureInstanceKHR device layout without allocating a LWJGL wrapper. */
public final class RtInstanceWriter {
    public static final int SIZEOF = 64;
    private static final long CUSTOM_INDEX_AND_MASK = 48L;
    private static final long SBT_OFFSET_AND_FLAGS = 52L;
    private static final long BLAS_ADDRESS = 56L;

    private RtInstanceWriter() {
    }

    public static void write(long address, int customIndex, int mask, long blasAddress, float[] transform) {
        if (transform.length < 12) {
            throw new IllegalArgumentException("transform must contain 12 floats");
        }
        for (int i = 0; i < 12; i++) {
            MemoryUtil.memPutFloat(address + i * 4L, transform[i]);
        }
        MemoryUtil.memPutInt(address + CUSTOM_INDEX_AND_MASK, (customIndex & 0x00FF_FFFF) | ((mask & 0xFF) << 24));
        MemoryUtil.memPutInt(address + SBT_OFFSET_AND_FLAGS,
            KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
        MemoryUtil.memPutLong(address + BLAS_ADDRESS, blasAddress);
    }

    public static void write(long address, int customIndex, long blasAddress, float[] transform) {
        write(address, customIndex, 0x01, blasAddress, transform);
    }
}
